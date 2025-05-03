package main.java.com.p2pchat.node;

// Import common classes INCLUDING the new CryptoUtils
import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.common.CryptoUtils; // <-- NEW
import main.java.com.p2pchat.common.CryptoUtils.EncryptedPayload; // <-- NEW Container Class

import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.SecretKey; // <-- NEW Crypto imports
import java.security.*;
import java.io.*; // <-- NEW File IO imports
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files; // <-- NEW NIO File imports
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class P2PNode {

    // --- Configuration ---
    private static String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 19999;
    private static final int BUFFER_SIZE = 4096;
    private static final int LOCAL_UDP_PORT = 0;
    private static final long KEEPALIVE_SERVER_INTERVAL_MS = 20 * 1000;
    private static final long KEEPALIVE_PEER_INTERVAL_MS = 5 * 1000;
    private static final long PING_INTERVAL_MS = 400;
    private static final int MAX_PING_ATTEMPTS = 15;
    private static final long WAIT_MATCH_TIMEOUT_MS = 60 * 1000;
    private static final long STATE_PRINT_INTERVAL_MS = 5 * 1000;

    // --- NEW File Transfer Configuration ---
    private static final String DOWNLOAD_DIR = "downloads";
    private static final long MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024; // 100 MB
    private static final int FILE_CHUNK_SIZE_BYTES = 2048; // Raw data size
    private static final long FILE_OFFER_TIMEOUT_MS = 30 * 1000; // Timeout for user to accept/reject offer
    private static final long FILE_ACK_TIMEOUT_MS = 4 * 1000; // Timeout for waiting for a chunk ACK
    private static final int FILE_MAX_RETRIES = 8; // Max retries per chunk

    // --- State ---
    private static String myNodeId = null;
    private static String myUsername = "User" + (int)(Math.random() * 1000);
    private static List<Endpoint> myLocalEndpoints = new ArrayList<>();
    private static Endpoint myPublicEndpointSeen = null;
    private static final AtomicReference<String> targetPeerId = new AtomicReference<>(null);
    private static final AtomicReference<String> connectedPeerId = new AtomicReference<>(null);
    private static final AtomicReference<String> connectedPeerUsername = new AtomicReference<>(null);
    private static final List<Endpoint> peerCandidates = new CopyOnWriteArrayList<>();
    private static final AtomicReference<InetSocketAddress> peerAddrConfirmed = new AtomicReference<>(null);
    private static ChatHistoryManager chatHistoryManager = null;

    // --- Cryptography State ---
    private static KeyPair myKeyPair = null;
    private static final ConcurrentHashMap<String, SecretKey> peerSymmetricKeys = new ConcurrentHashMap<>();

    // --- File Transfer State ---
    private static final ConcurrentHashMap<String, TransferState> outgoingTransfers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, TransferState> incomingTransfers = new ConcurrentHashMap<>();
    private static final AtomicReference<String> pendingFileOfferId = new AtomicReference<>(null); // ID of offer waiting for user yes/no

    private static DatagramSocket udpSocket;
    private static InetSocketAddress serverAddress;
    private static volatile boolean running = true;

    // --- Threading & Sync ---
    private static final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "UDPListenerThread"));
    private static final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ConnectionManagerThread");
            t.setDaemon(true);
            return t;
        });
    private static final AtomicBoolean connectionInfoReceived = new AtomicBoolean(false); // Server sent peer info
    private static final AtomicBoolean p2pUdpPathConfirmed = new AtomicBoolean(false);   // Ping/Pong worked
    private static final AtomicBoolean sharedKeyEstablished = new AtomicBoolean(false); // Symmetric key derived

    private static final Object stateLock = new Object(); // General state lock
    private static final Object transferLock = new Object(); // Separate lock for transfer state manipulation

    // --- State Machine ---
    private enum NodeState { DISCONNECTED, WAITING_MATCH, ATTEMPTING_UDP, EXCHANGING_KEYS, CONNECTED_SECURE }
    private static volatile NodeState currentState = NodeState.DISCONNECTED;
    private static long waitingSince = 0;
    private static int pingAttempts = 0;
    private static long lastStatePrintTime = 0;

    // --- Inner Class for File Transfer State ---
    private static class TransferState {
        final String transferId;
        final String peerNodeId;
        final String filename;
        final long filesize;
        final int totalChunks;
        final boolean isSender; // True if we are sending, false if receiving
        final Path filePath; // Path for saving (receiver) or reading (sender)

        // Volatile or Atomic needed? Access synchronized by transferLock mostly
        volatile int currentChunkIndex = 0; // Next chunk TO SEND (sender) or EXPECTING (receiver)
        volatile int ackedChunkIndex = -1; // Last chunk ACKED BY PEER (sender) or ACKED BY US (receiver)
        volatile long lastActivityTime; // Timestamp of last relevant packet sent/received
        volatile int retryCount = 0; // Retries for the current chunk (sender)
        volatile boolean accepted = false; // Has the offer been accepted?
        volatile boolean completed = false;
        volatile boolean failed = false;

        // File handles - manage carefully!
        InputStream fileInputStream = null; // For sender
        OutputStream fileOutputStream = null; // For receiver (consider RandomAccessFile for out-of-order writes if not enforcing strict ACK order)

        TransferState(String transferId, String peerNodeId, String filename, long filesize, boolean isSender, Path filePath) {
            this.transferId = transferId;
            this.peerNodeId = peerNodeId;
            this.filename = filename;
            this.filesize = filesize;
            this.isSender = isSender;
            this.filePath = filePath;
            this.totalChunks = (int) Math.ceil((double) filesize / FILE_CHUNK_SIZE_BYTES);
            this.lastActivityTime = System.currentTimeMillis();
        }

        // Utility to safely close streams
        void closeStreams() {
             if (fileInputStream != null) {
                try { fileInputStream.close(); } catch (IOException e) { /* ignore */ }
                fileInputStream = null;
             }
             if (fileOutputStream != null) {
                 try { fileOutputStream.close(); } catch (IOException e) { /* ignore */ }
                 fileOutputStream = null;
             }
        }

        // Simplified toString for logging
        @Override
        public String toString() {
            return String.format("Transfer{id=%s, file=%s, size=%d, sender=%b, state=%s, chunk=%d/%d}",
                transferId.substring(0, 8), filename, filesize, isSender,
                failed ? "FAILED" : completed ? "DONE" : accepted ? (isSender ? "SENDING" : "RECEIVING") : "PENDING",
                currentChunkIndex, totalChunks);
        }
    }


    // --- Main Method and Startup ---
    public static void main(String[] args) {
        if (args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            SERVER_IP = args[0].trim();
            System.out.println("[*] Using Server IP from command line: " + SERVER_IP);
        } else {
            if (SERVER_IP.equals("127.0.0.1")) {
                System.out.println("[!] WARNING: Using default Server IP '127.0.0.1'. Pass the actual server IP as an argument if nodes are on different machines!");
            } else {
                 System.out.println("[*] Using configured Server IP: " + SERVER_IP);
            }
        }

        Scanner inputScanner = new Scanner(System.in);

        try {
            // Create downloads directory
            Path downloadsPath = Paths.get(DOWNLOAD_DIR);
            if (!Files.exists(downloadsPath)) {
                try {
                    Files.createDirectories(downloadsPath);
                    System.out.println("[*] Created downloads directory: " + downloadsPath.toAbsolutePath());
                } catch (IOException e) {
                     System.err.println("[!] Failed to create downloads directory: " + e.getMessage());
                     // Continue anyway, maybe permissions will allow file creation later
                }
            }


            System.out.println("[*] Generating Elliptic Curve key pair...");
            try {
                myKeyPair = CryptoUtils.generateECKeyPair();
                System.out.println("[+] Key pair generated successfully.");
            } catch (NoSuchAlgorithmException e) {
                System.err.println("[!!!] FATAL: Failed to generate key pair. Cryptography provider not supported: " + e.getMessage());
                return;
            }

            System.out.println("[*] Resolving server address: " + SERVER_IP + ":" + SERVER_PORT + "...");
            serverAddress = new InetSocketAddress(InetAddress.getByName(SERVER_IP), SERVER_PORT);
            System.out.println("[*] Server address resolved to: " + serverAddress);

            udpSocket = new DatagramSocket(LOCAL_UDP_PORT);
            udpSocket.setReuseAddress(true);
            int actualLocalPort = udpSocket.getLocalPort();
            System.out.println("[*] UDP Socket bound to local address: " + udpSocket.getLocalAddress().getHostAddress() + ":" + actualLocalPort);

            System.out.print("[?] Enter your desired username: ");
            String inputName = "";
            while(inputName == null || inputName.trim().isEmpty()) {
                if (inputScanner.hasNextLine()) {
                    inputName = inputScanner.nextLine().trim();
                    if (inputName.isEmpty()) {
                        System.out.print("[!] Username cannot be empty. Please enter a username: ");
                    }
                } else {
                    System.out.println("\n[*] Input stream closed during username entry. Using default.");
                    inputName = "User" + (int)(Math.random() * 1000);
                    break;
                }
            }
            myUsername = inputName;
            System.out.println("[*] Username set to: " + myUsername);

            myLocalEndpoints = getLocalNetworkEndpoints(actualLocalPort);
            System.out.println("[*] Discovered Local Endpoints:");
            if(myLocalEndpoints.isEmpty()) System.out.println("    <None found>");
            else myLocalEndpoints.forEach(ep -> System.out.println("    - " + ep));

            Runtime.getRuntime().addShutdownHook(new Thread(P2PNode::shutdown));
            listenerExecutor.submit(P2PNode::udpListenerLoop);

            if (!registerWithServer()) {
                System.err.println("[!!!] Failed to register with server. Exiting.");
                shutdown();
                return;
            }

            System.out.println("[+] Successfully registered with server. Node ID: " + myNodeId);

            startConnectionManager();
            userInteractionLoop(inputScanner);

        } catch (UnknownHostException e) {
            System.err.println("[!!!] FATAL: Could not resolve server hostname: " + SERVER_IP + " - " + e.getMessage());
        } catch (SocketException e) {
            System.err.println("[!!!] FATAL: Failed to create or bind UDP socket: " + e.getMessage());
            System.err.println("    Check if another application is using the port or if permissions are sufficient.");
        } catch (SecurityException e) {
            System.err.println("[!!!] FATAL: Security Manager prevented network operations: " + e.getMessage());
        }
        catch (Exception e) {
            System.err.println("[!!!] FATAL: Unexpected error during startup: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
            System.out.println("[*] Node main method finished.");
        }
    }

    // --- Network Setup & Discovery (Unchanged) ---
     private static List<Endpoint> getLocalNetworkEndpoints(int udpPort) {
        List<Endpoint> endpoints = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                try {
                    if (ni.isLoopback() || !ni.isUp() || ni.isVirtual() || ni.isPointToPoint()) continue;
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr.isLinkLocalAddress() || addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()) continue;
                        String ip = addr.getHostAddress();
                        String type;
                        if (addr instanceof Inet6Address) {
                            int scopeIndex = ip.indexOf('%');
                            if (scopeIndex > 0) ip = ip.substring(0, scopeIndex);
                            if (ip.startsWith("::ffff:")) continue;
                            type = "local_v6";
                        } else if (addr instanceof Inet4Address) {
                            type = "local_v4";
                        } else continue;
                        endpoints.add(new Endpoint(ip, udpPort, type));
                    }
                } catch (SocketException se) { /* Ignore interfaces that error */ }
            }
        } catch (SocketException e) {
            System.err.println("[!] Error getting network interfaces: " + e.getMessage());
        }
        if (endpoints.isEmpty()) System.out.println("[!] No suitable local non-loopback IPs found. Will rely on public IP seen by server.");
        return new ArrayList<>(new HashSet<>(endpoints));
    }

    // --- Registration (Unchanged) ---
    private static boolean registerWithServer() {
        if (myKeyPair == null) {
             System.err.println("[!] Cannot register: KeyPair not generated.");
             return false;
        }
        JSONObject registerMsg = new JSONObject();
        registerMsg.put("action", "register");
        registerMsg.put("username", myUsername);
        registerMsg.put("public_key", CryptoUtils.encodePublicKey(myKeyPair.getPublic()));
        JSONArray localEndpointsJson = new JSONArray();
        myLocalEndpoints.forEach(ep -> localEndpointsJson.put(ep.toJson()));
        registerMsg.put("local_endpoints", localEndpointsJson);

        System.out.println("[*] Sending registration (with public key) to server " + serverAddress + "...");
        boolean sent = sendUdp(registerMsg, serverAddress);
        if (!sent) {
            System.err.println("[!] Failed to send registration message.");
            return false;
        }

        long startTime = System.currentTimeMillis();
        long timeout = 5000;
        while (myNodeId == null && System.currentTimeMillis() - startTime < timeout) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }

        if(myNodeId == null) {
            System.err.println("[!] No registration confirmation received from server within " + timeout/1000 + " seconds.");
            return false;
        }
        return true;
    }


    // --- Main Loops (Listener, Manager, User Interaction) ---

    private static void udpListenerLoop() {
        System.out.println("[Listener Thread] Started. Listening for UDP packets...");
        byte[] buffer = new byte[BUFFER_SIZE];
        while (running && udpSocket != null && !udpSocket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                udpSocket.receive(packet);
                if (!running) break;

                InetSocketAddress senderAddr = (InetSocketAddress) packet.getSocketAddress();
                String messageStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                 if (messageStr.isEmpty()) continue;

                JSONObject data;
                 try {
                     data = new JSONObject(messageStr);
                 } catch (org.json.JSONException e) {
                     System.err.println("[Listener] Invalid JSON received from " + senderAddr + ". Content snippet: " + messageStr.substring(0, Math.min(100, messageStr.length())) + "...");
                     continue;
                 }

                boolean fromServer = serverAddress != null && serverAddress.equals(senderAddr);

                if (fromServer) {
                    handleServerMessage(data);
                } else {
                    handlePeerMessage(data, senderAddr); // Might handle chat OR file messages now
                }

            } catch (SocketException e) {
                if (running) System.err.println("[Listener Thread] Socket closed or error: " + e.getMessage());
            }
            catch (IOException e) {
                if (running) System.err.println("[Listener Thread] I/O error receiving UDP packet: " + e.getMessage());
            } catch (Exception e) {
                if (running) {
                    System.err.println("[Listener Thread] Error processing UDP packet from " + ((packet!=null && packet.getSocketAddress()!=null)?packet.getSocketAddress():"unknown") + ": " + e.getClass().getName() + " - " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        System.out.println("[*] UDP Listener thread finished.");
    }

    // Modified to include File Transfer state management
    private static void startConnectionManager() {
        scheduledExecutor.scheduleWithFixedDelay(() -> {
            if (!running || udpSocket == null || udpSocket.isClosed()) return;

            try {
                long now = System.currentTimeMillis();

                // --- Server Keep-Alive ---
                if (myNodeId != null) {
                    JSONObject keepAliveMsg = new JSONObject();
                    keepAliveMsg.put("action", "keep_alive");
                    keepAliveMsg.put("node_id", myNodeId);
                    sendUdp(keepAliveMsg, serverAddress);
                }

                String currentTarget = targetPeerId.get();
                InetSocketAddress confirmedPeer = peerAddrConfirmed.get();
                NodeState stateNow = currentState;

                // --- P2P Pinging (Only during ATTEMPTING_UDP) ---
                if (stateNow == NodeState.ATTEMPTING_UDP && currentTarget != null && !peerCandidates.isEmpty() && !p2pUdpPathConfirmed.get()) {
                    if (pingAttempts < MAX_PING_ATTEMPTS) {
                        JSONObject pingMsg = new JSONObject();
                        pingMsg.put("action", "ping");
                        pingMsg.put("node_id", myNodeId);
                        boolean sentPingThisRound = false;
                        // Optimized slightly: Send only to candidates not yet confirmed unreachable maybe? For now, ping all.
                        List<Endpoint> currentCandidates = new ArrayList<>(peerCandidates);
                        currentCandidates.sort(Comparator.comparing(ep -> (ep.type != null && ep.type.contains("public")) ? 0 : 1));
                        for (Endpoint candidate : currentCandidates) {
                            try {
                                InetSocketAddress candidateAddr = new InetSocketAddress(InetAddress.getByName(candidate.ip), candidate.port);
                                if(sendUdp(pingMsg, candidateAddr)) sentPingThisRound = true;
                            } catch (Exception e){ /* Ignore send errors during ping */ }
                        }
                        if(sentPingThisRound) {
                             synchronized(stateLock) { if(currentState == NodeState.ATTEMPTING_UDP) pingAttempts++; }
                        }
                    }
                }
                // --- Peer Keep-Alive (if securely connected) ---
                else if (stateNow == NodeState.CONNECTED_SECURE && confirmedPeer != null) {
                    JSONObject keepAliveMsg = new JSONObject();
                    keepAliveMsg.put("action", "keepalive");
                    keepAliveMsg.put("node_id", myNodeId);
                    sendUdp(keepAliveMsg, confirmedPeer);
                }

                // --- *** NEW: File Transfer Management *** ---
                synchronized (transferLock) {
                    // Check timeouts for outgoing transfers (waiting for ACK)
                    checkOutgoingTransferTimeouts(now);

                    // Check timeouts for incoming transfers (stalled?)
                    checkIncomingTransferTimeouts(now);

                    // Check timeout for pending user acceptance of file offer
                    checkFileOfferAcceptanceTimeout(now);
                }

            } catch (Exception e) {
                System.err.println("[!!!] Error in Connection Manager task: " + e.getMessage());
                e.printStackTrace();
            }

        }, 1000, // Initial delay
           Math.min(Math.min(KEEPALIVE_PEER_INTERVAL_MS, PING_INTERVAL_MS), 1000L), // Run roughly every second for file checks
           TimeUnit.MILLISECONDS);

        System.out.println("[*] Started Connection Manager task.");
    }

    // Modified User Interaction Loop
    private static void userInteractionLoop(Scanner scanner) {
        try {
            System.out.println("\n--- P2P Node Ready (Username: " + myUsername + ", Node ID: " + myNodeId + ") ---");
            System.out.println("--- Enter commands ('connect', 'chat', 'sendfile', 'status', 'id', 'quit') ---");

            while (running) {
                try {
                    checkStateTransitions(); // Check async events first

                    long now = Instant.now().toEpochMilli();
                    String prompt = getPrompt(); // Get current prompt including transfer status
                    NodeState stateNow = currentState;
                    boolean offerPending = pendingFileOfferId.get() != null;

                    // Print status periodically or if an offer is pending
                    if (offerPending || (stateNow != NodeState.DISCONNECTED && stateNow != NodeState.CONNECTED_SECURE)) {
                        if (offerPending || now - lastStatePrintTime > STATE_PRINT_INTERVAL_MS || lastStatePrintTime == 0) {
                            System.out.println(getStateDescription()); // Print node connection status
                            // Print file transfer status if any active
                            printTransferStatus();
                            if (offerPending) {
                                TransferState offer = incomingTransfers.get(pendingFileOfferId.get());
                                if (offer != null) {
                                     System.out.print("\r[!] Incoming file offer for '" + offer.filename + "' (" + formatFilesize(offer.filesize) + "). Accept? (yes/no): ");
                                } else {
                                    pendingFileOfferId.set(null); // Clean up inconsistent state
                                }
                            } else {
                                System.out.print(prompt); // Print normal prompt
                            }
                            lastStatePrintTime = now;
                        }
                        // Connection state timeouts (unchanged logic)
                        if (stateNow == NodeState.WAITING_MATCH && (now - waitingSince > WAIT_MATCH_TIMEOUT_MS)) { /*...*/ }
                        if (stateNow == NodeState.ATTEMPTING_UDP && (pingAttempts >= MAX_PING_ATTEMPTS) && !p2pUdpPathConfirmed.get()) { /*...*/ }

                         // Brief sleep if no input
                         if (System.in.available() <= 0) {
                           try { Thread.sleep(200); } catch (InterruptedException e) { running = false; Thread.currentThread().interrupt(); break; }
                            continue;
                        }
                    } else {
                         // Print status only if changed or periodically
                        if (now - lastStatePrintTime > STATE_PRINT_INTERVAL_MS || lastStatePrintTime == 0) {
                            printTransferStatus(); // Show transfer status even when connected/idle
                            lastStatePrintTime = now;
                        }
                        System.out.print(prompt); // Print prompt normally for DISCONNECTED or CONNECTED_SECURE
                    }


                    // Handle User Input
                    if (!scanner.hasNextLine()) {
                        System.out.println("\n[*] Input stream closed. Exiting.");
                        running = false;
                        break;
                     }
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty()) continue;

                    checkStateTransitions(); // Check state again *after* input, before command processing
                    NodeState stateBeforeCommand = currentState;
                    offerPending = pendingFileOfferId.get() != null; // Re-check offer pending status


                    // --- Handle yes/no for file offer ---
                    if (offerPending) {
                        String offerId = pendingFileOfferId.get();
                        TransferState offer = incomingTransfers.get(offerId);
                        if (offer != null) {
                            if (line.equalsIgnoreCase("yes") || line.equalsIgnoreCase("y")) {
                                acceptFileOffer(offerId);
                                pendingFileOfferId.set(null);
                            } else if (line.equalsIgnoreCase("no") || line.equalsIgnoreCase("n")) {
                                rejectFileOffer(offerId, "User rejected");
                                pendingFileOfferId.set(null);
                            } else {
                                System.out.println("[!] Please answer 'yes' or 'no'.");
                            }
                        } else {
                            System.out.println("[!] Offer no longer valid."); // Race condition?
                            pendingFileOfferId.set(null);
                        }
                        continue; // Handled yes/no, get next command
                    }

                    // Check if state changed non-interactively
                    if(stateBeforeCommand != currentState) {
                        System.out.println("\n[*] State changed during input, please check status and retry.");
                        continue;
                    }

                    // Process regular commands
                    String[] parts = line.split(" ", 2);
                    String command = parts[0].toLowerCase();

                    switch (command) {
                        case "quit":
                        case "exit":
                            System.out.println("[*] Quit command received. Initiating shutdown...");
                            shutdown();
                            System.exit(0); // Force exit after shutdown attempt
                            break;
                        case "connect":
                            if (stateBeforeCommand == NodeState.DISCONNECTED) {
                                if (parts.length > 1 && !parts[1].isEmpty()) {
                                    String peerToConnect = parts[1].trim();
                                    if(peerToConnect.equals(myNodeId)) System.out.println("[!] Cannot connect to yourself.");
                                    else if (peerToConnect.length() < 10) System.out.println("[!] Invalid Peer ID format."); // Basic check
                                    else startConnectionAttempt(peerToConnect);
                                } else System.out.println("[!] Usage: connect <peer_node_id>");
                            } else System.out.println("[!] Already connecting or connected. Disconnect first ('disconnect').");
                            break;
                        case "disconnect":
                        case "cancel": // Also cancels connection attempt
                            if (stateBeforeCommand != NodeState.DISCONNECTED) {
                                System.out.println("[*] Disconnecting/Cancelling connection with " + getPeerDisplayName() + "...");
                                resetConnectionState(); // This now also cancels transfers with the peer
                                currentState = NodeState.DISCONNECTED;
                            } else System.out.println("[!] Not currently connected or attempting connection.");
                            break;
                        case "chat":
                        case "c":
                            if (stateBeforeCommand == NodeState.CONNECTED_SECURE) {
                                if (parts.length > 1 && !parts[1].isEmpty()) sendMessageToPeer(parts[1]);
                                else System.out.println("[!] Usage: chat <message>");
                            } else if (stateBeforeCommand == NodeState.EXCHANGING_KEYS || stateBeforeCommand == NodeState.ATTEMPTING_UDP) {
                                 System.out.println("[!] Waiting for secure connection to be established...");
                            } else System.out.println("[!] Not connected to a peer. Use 'connect <peer_id>' first.");
                            break;
                        case "status":
                        case "s":
                            System.out.println(getStateDescription());
                            printTransferStatus(); // Show transfer status too
                            break;
                        case "id":
                            System.out.println("[*] Your Username: " + myUsername);
                            System.out.println("[*] Your Node ID: " + myNodeId);
                            break;
                        // --- NEW: sendfile command ---
                        case "sendfile":
                        case "sf":
                             if (stateBeforeCommand == NodeState.CONNECTED_SECURE) {
                                if (parts.length > 1 && !parts[1].isEmpty()) {
                                    initiateFileSend(parts[1]);
                                } else System.out.println("[!] Usage: sendfile <path_to_local_file>");
                             } else System.out.println("[!] Not securely connected to a peer. Use 'connect' first.");
                            break;
                        // --- (Optional) NEW: canceltransfer command ---
                        /*
                        case "canceltransfer":
                        case "ct":
                             if (parts.length > 1 && !parts[1].isEmpty()) {
                                 cancelTransferByUser(parts[1].trim()); // Need method to find by ID prefix maybe
                             } else System.out.println("[!] Usage: canceltransfer <transfer_id_prefix>");
                             break;
                        */
                        default:
                             if(stateBeforeCommand == NodeState.CONNECTED_SECURE) System.out.println("[!] Unknown command. Available: chat, sendfile, disconnect, status, quit");
                             else System.out.println("[!] Unknown command. Available: connect, status, id, quit");
                            break;
                    }
                }
                catch (NoSuchElementException e) {
                    System.out.println("\n[*] Input stream ended unexpectedly. Shutting down.");
                    running = false;
                } catch (Exception e) {
                    System.err.println("\n[!!!] Error in user interaction loop: " + e.getMessage());
                    e.printStackTrace();
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            } // End while(running)
        } finally {
             // Avoid closing System.in scanner
        }
        System.out.println("[*] User Interaction loop finished.");
    }

    // --- State Management & Transitions (Largely Unchanged, resetConnectionState modified) ---

    private static void checkStateTransitions() {
         // ... (Existing connection state transition logic remains the same) ...
         // No file transfer logic needed here, it's managed separately
    }

     // Gets a description of the current node state. (Unchanged)
    private static String getStateDescription() {
        String fullPeerId = connectedPeerId.get(); // Try connected first
        if (fullPeerId == null) fullPeerId = targetPeerId.get(); // Fallback to target
        String peerDisplay = getPeerDisplayName(); // Use helper for username/short ID

        switch (currentState) {
            case DISCONNECTED: return "[Status] Disconnected. Your Node ID: " + myNodeId + " | Username: " + myUsername;
            case WAITING_MATCH:
                long elapsed = (System.currentTimeMillis() - waitingSince) / 1000;
                return "[Status] Request sent. Waiting for peer " + (fullPeerId != null ? fullPeerId : peerDisplay) + " (" + elapsed + "s / " + WAIT_MATCH_TIMEOUT_MS/1000 + "s)";
            case ATTEMPTING_UDP:
                return "[Status] Attempting UDP P2P connection to " + (fullPeerId != null ? fullPeerId : peerDisplay) + " (Ping cycle: " + pingAttempts + "/" + MAX_PING_ATTEMPTS + ")";
            case EXCHANGING_KEYS:
                 return "[Status] UDP path OK. Establishing secure E2EE session with " + peerDisplay + "...";
            case CONNECTED_SECURE:
                InetSocketAddress addr = peerAddrConfirmed.get();
                return "[Status] 🔒 E2EE Connected to " + peerDisplay + " (" + (addr != null ? addr : "???") + ")";
            default: return "[Status] Unknown State";
        }
    }

    // Gets the appropriate command prompt based on the current state. (Unchanged)
    private static String getPrompt() {
        // If a file offer is pending, the prompt is handled differently in the loop
        if (pendingFileOfferId.get() != null) return ""; // Handled specially

        String peerName = getPeerDisplayName(); // Use helper (Username > Short ID)
        switch (currentState) {
            case DISCONNECTED: return "[?] Enter command: ";
            case WAITING_MATCH: return "[Waiting:" + peerName + " ('cancel')] ";
            case ATTEMPTING_UDP: return "[Pinging:" + peerName + " ('cancel')] ";
            case EXCHANGING_KEYS: return "[Securing:" + peerName + " ('cancel')] ";
            case CONNECTED_SECURE: return "[Chat 🔒 " + peerName + "] Enter command: ";
            default: return "> ";
        }
    }

    // Gets the display name for the peer (Username or ID prefix). (Unchanged)
    private static String getPeerDisplayName() {
        String peerId = connectedPeerId.get();
        String username = connectedPeerUsername.get();
        if (peerId != null) {
            if (username != null && !username.isEmpty()) return username;
            return peerId.substring(0, Math.min(8, peerId.length())) + "...";
        }
        String targetId = targetPeerId.get();
        if (targetId != null) return targetId.substring(0, Math.min(8, targetId.length())) + "...";
        return "???";
    }

    // Initiates a connection attempt to the specified peer ID. (Unchanged)
    private static void startConnectionAttempt(String peerToConnect) {
        synchronized (stateLock) {
            if (currentState != NodeState.DISCONNECTED) {
                System.out.println("[!] Cannot start new connection attempt while in state: " + currentState); return;
            }
             if (myKeyPair == null) {
                System.err.println("[!] Cannot start connection: KeyPair missing."); return;
            }
            System.out.println("[*] Requesting connection info for peer: " + peerToConnect);
            resetConnectionState(); // Clears crypto state too

            targetPeerId.set(peerToConnect);

            JSONObject requestMsg = new JSONObject();
            requestMsg.put("action", "request_connection");
            requestMsg.put("node_id", myNodeId);
            requestMsg.put("target_id", peerToConnect);
            boolean sent = sendUdp(requestMsg, serverAddress);

            if (sent) {
                currentState = NodeState.WAITING_MATCH;
                waitingSince = System.currentTimeMillis();
                lastStatePrintTime = 0;
            } else {
                 System.err.println("[!] Failed to send connection request to server.");
                 targetPeerId.set(null);
            }
        }
    }

    // MODIFIED: Resets connection variables AND cancels associated file transfers
    private static void resetConnectionState() {
        synchronized (stateLock) { // Lock general state first
            String peerId = connectedPeerId.get();
            if(peerId == null) peerId = targetPeerId.get(); // Check target too

            if(peerId != null) {
                 peerSymmetricKeys.remove(peerId);
                 System.out.println("[*] Cleared session key for peer " + peerId);

                 // --- NEW: Cancel transfers associated with this peer ---
                 final String finalPeerId = peerId;
                 synchronized(transferLock) {
                     outgoingTransfers.values().removeIf(t -> {
                         if (t.peerNodeId.equals(finalPeerId)) {
                             System.out.println("[*] Cancelling outgoing transfer " + t.transferId.substring(0,8) + " due to disconnect.");
                             t.failed = true; // Mark as failed
                             t.closeStreams();
                             // No need to send cancel message, peer is disconnecting
                             return true;
                         }
                         return false;
                     });
                     incomingTransfers.values().removeIf(t -> {
                         if (t.peerNodeId.equals(finalPeerId)) {
                             System.out.println("[*] Cancelling incoming transfer " + t.transferId.substring(0,8) + " due to disconnect.");
                             t.failed = true; // Mark as failed
                             t.closeStreams();
                             try { Files.deleteIfExists(t.filePath); } catch (IOException e) {/* ignore */}
                             // Clear pending offer if it matches
                             if (t.transferId.equals(pendingFileOfferId.get())) {
                                 pendingFileOfferId.set(null);
                             }
                             return true;
                         }
                         return false;
                     });
                 }
                 // --- End Transfer Cancellation ---
            }

            // Reset connection state variables (unchanged)
            targetPeerId.set(null);
            connectedPeerId.set(null);
            connectedPeerUsername.set(null);
            peerCandidates.clear();
            peerAddrConfirmed.set(null);
            connectionInfoReceived.set(false);
            p2pUdpPathConfirmed.set(false);
            sharedKeyEstablished.set(false);
            pingAttempts = 0;
            waitingSince = 0;

            if (chatHistoryManager != null) {
                System.out.println("[*] Chat history logging stopped.");
                chatHistoryManager = null;
            }
            System.out.println("[*] Reset connection, cryptographic state, and associated transfers.");

            // Ensure state is DISCONNECTED if called explicitly
             if (currentState != NodeState.DISCONNECTED) {
                 currentState = NodeState.DISCONNECTED;
                 System.out.println("[State Change] -> " + currentState);
             }
        }
    }


    // --- Message Handling ---

    // Handles messages received from the Coordination Server. (Unchanged)
    private static void handleServerMessage(JSONObject data) {
        String status = data.optString("status", null);
        String action = data.optString("action", null);

        if ("registered".equals(status) && myNodeId == null) {
            // ... (same registration handling) ...
             String receivedNodeId = data.optString("node_id");
             if (receivedNodeId != null && !receivedNodeId.isEmpty()) {
                 myNodeId = receivedNodeId; // Store full ID
                 JSONObject publicEpJson = data.optJSONObject("your_public_endpoint");
                 if(publicEpJson != null) {
                     myPublicEndpointSeen = Endpoint.fromJson(publicEpJson);
                      System.out.println("[*] Server recorded public endpoint: " + myPublicEndpointSeen);
                 }
             } else System.err.println("[!] Received 'registered' status from server but missing 'node_id'.");

        } else if ("connection_info".equals(action) && "match_found".equals(status)) {
             // ... (same connection_info handling, generates shared key) ...
             String receivedPeerId = data.optString("peer_id");
             String currentTarget = targetPeerId.get();

             if (currentTarget != null && currentTarget.equals(receivedPeerId)) {
                 JSONArray candidatesJson = data.optJSONArray("peer_endpoints");
                 String peerUsername = data.optString("peer_username", null);
                 String peerPublicKeyBase64 = data.optString("peer_public_key", null);

                  if (candidatesJson == null || peerPublicKeyBase64 == null || peerPublicKeyBase64.isEmpty()) { /* ... error handling ... */ return; }

                 synchronized(stateLock) {
                     if(currentState != NodeState.WAITING_MATCH) { /* ... ignore ... */ return; }

                     // Generate Shared Key FIRST
                     boolean keyGenSuccess = generateSharedKeyAndStore(receivedPeerId, peerPublicKeyBase64);
                     if (!keyGenSuccess) { /* ... error handling ... */ return; }

                     // Store peer username
                     if (peerUsername != null && !peerUsername.isEmpty()) connectedPeerUsername.set(peerUsername);
                     else { connectedPeerUsername.set(null); System.out.println("[!] Peer username not provided by server."); }
                     System.out.println("[*] Received Peer Username: " + connectedPeerUsername.get());

                     // Store peer candidates
                     peerCandidates.clear();
                     System.out.println("[*] Received Peer Candidates from server for " + receivedPeerId + ":");
                     int validCandidates = 0;
                     for (int i = 0; i < candidatesJson.length(); i++) { /* ... store candidates ... */ }

                     if (validCandidates > 0) connectionInfoReceived.set(true); // Signal OK
                     else { /* ... error handling ... */ }
                 } // End synchronized block
             } else System.out.println("[?] Received connection_info for unexpected peer " + receivedPeerId + ". Ignoring.");

        } else if ("error".equals(status)) {
            // ... (same server error handling) ...
             String message = data.optString("message", "Unknown error");
             System.err.println("\n[!] Server Error: " + message);
             synchronized(stateLock) {
                 if (currentState == NodeState.WAITING_MATCH || currentState == NodeState.ATTEMPTING_UDP || currentState == NodeState.EXCHANGING_KEYS) {
                     System.out.println("    Cancelling connection attempt due to server error.");
                     resetConnectionState(); // Will set state to DISCONNECTED
                 }
             }

         } else if ("connection_request_received".equals(status) && "ack".equals(action)) {
             // ... (same ack handling) ...
         }
    }

    // MODIFIED: Handles messages received directly from other Peers (Chat or File Transfer)
    private static void handlePeerMessage(JSONObject data, InetSocketAddress peerAddr) {
        String action = data.optString("action", null);
        String senderId = data.optString("node_id", null);

        if (senderId == null || action == null) return; // Ignore invalid

        String currentTarget = targetPeerId.get();
        String currentPeer = connectedPeerId.get();
        NodeState stateNow = currentState;

        // --- Processing During UDP Connection Attempt (Unchanged) ---
        if (stateNow == NodeState.ATTEMPTING_UDP && currentTarget != null && currentTarget.equals(senderId)) {
             // ... (same ping/pong handling to confirm path) ...
             boolean senderIsCandidate = peerCandidates.stream().anyMatch(cand -> {
                try {
                    // Create an InetSocketAddress from the candidate Endpoint info
                    InetSocketAddress candidateAddr = new InetSocketAddress(InetAddress.getByName(cand.ip), cand.port);
                    // Compare it with the actual sender address of the received packet
                    return candidateAddr.equals(peerAddr);
                } catch (UnknownHostException e) {
                    // Handle cases where the candidate IP might be an unresolvable hostname (less likely here)
                    // Or other potential errors during address creation
                     // System.err.println("[!] Error checking candidate address " + cand.ip + ":" + cand.port + " - " + e.getMessage()); // Less verbose
                    return false;
                } catch (IllegalArgumentException e) {
                    // Handle cases like invalid port number in candidate data
                     System.err.println("[!] Invalid argument checking candidate address " + cand.ip + ":" + cand.port + " - " + e.getMessage());
                    return false;
                }
            });
             if (senderIsCandidate && (action.equals("ping") || action.equals("pong"))) { /* ... confirm path, set flags, send pong ... */ }
        }
        // --- Processing During Key Exchange (Unchanged) ---
        else if (stateNow == NodeState.EXCHANGING_KEYS && currentPeer != null && currentPeer.equals(senderId)) {
             // ... (still respond to ping/pong) ...
        }
        // --- Processing After Secure Connection Established ---
        else if (stateNow == NodeState.CONNECTED_SECURE && currentPeer != null && currentPeer.equals(senderId)) {
            InetSocketAddress confirmedAddr = peerAddrConfirmed.get();
            SecretKey sharedKey = peerSymmetricKeys.get(currentPeer);

            // Basic validation (correct peer, correct address, have key)
            if (confirmedAddr == null || !peerAddr.equals(confirmedAddr)) { /* ... warning, ignore ... */ return; }
            if (sharedKey == null) { /* ... critical error, disconnect ... */ resetConnectionState(); return; }

            // --- Handle different actions from connected peer ---
            switch (action) {
                // --- Chat Message Handling (Unchanged) ---
                case "e_chat": {
                    EncryptedPayload payload = EncryptedPayload.fromJson(data);
                    if (payload == null) { /* ... error handling ... */ break; }
                    try {
                        String decryptedMessage = CryptoUtils.decrypt(payload, sharedKey);
                        // Use \r to overwrite prompt potentially
                        System.out.print("\r[" + getPeerDisplayName() + "]: " + decryptedMessage + "\n" + getPrompt()); // Reprint prompt after message

                        if (chatHistoryManager != null) {
                            long timestamp = System.currentTimeMillis();
                            chatHistoryManager.addMessage(timestamp, senderId, connectedPeerUsername.get(), "RECEIVED", decryptedMessage);
                        }
                    } catch (GeneralSecurityException e) { /* ... decryption error ... */
                    } catch (Exception e) { /* ... other error ... */ }
                    break;
                } // end case "e_chat"

                // --- Keepalive/Ping/Pong Handling (Unchanged) ---
                case "keepalive": break; // Ignore received keepalives
                case "ping":
                    JSONObject pongMsg = new JSONObject();
                    pongMsg.put("action", "pong");
                    pongMsg.put("node_id", myNodeId);
                    sendUdp(pongMsg, peerAddr);
                    break;
                case "pong": break; // Ignore pongs when connected

                // --- *** NEW: File Transfer Message Handling *** ---
                case "file_offer":
                    handleFileOffer(data, senderId, sharedKey);
                    break;
                case "file_accept":
                    handleFileAccept(data, senderId, sharedKey);
                    break;
                case "file_reject":
                    handleFileReject(data, senderId);
                    break;
                case "file_chunk":
                    handleFileChunk(data, senderId, sharedKey);
                    break;
                case "file_ack":
                    handleFileAck(data, senderId);
                    break;
                case "file_cancel":
                    handleFileCancel(data, senderId);
                    break;
                // --- End File Transfer Handling ---

                default:
                    System.out.println("[?] Received unknown action '" + action + "' from connected peer " + getPeerDisplayName());
                    break;
            }
        } // End if CONNECTED_SECURE
         else {
             // Message from unexpected peer or in wrong state
             // System.out.println("[?] Ignoring message with action '"+action+"' from " + senderId + " @ " + peerAddr + " while in state " + stateNow);
         }
    }


    // --- Sending Messages ---

    // sendUdp remains unchanged
    private static boolean sendUdp(JSONObject jsonObject, InetSocketAddress destination) {
        if (udpSocket == null || udpSocket.isClosed()) return false;
        if (destination == null) { System.err.println("[!] Cannot send UDP: Destination address is null."); return false; }
        try {
            byte[] data = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > BUFFER_SIZE) { System.err.println("[!] Cannot send UDP: Message size (" + data.length + ") exceeds buffer size (" + BUFFER_SIZE + ")."); return false; }
            DatagramPacket packet = new DatagramPacket(data, data.length, destination);
            udpSocket.send(packet);
            return true;
        } catch (PortUnreachableException e) {
            System.err.println("[!] Error sending UDP to " + destination + ": Port Unreachable.");
            // Consider triggering disconnect if port becomes unreachable for connected peer
            if (currentState == NodeState.CONNECTED_SECURE && destination.equals(peerAddrConfirmed.get())) {
                 System.out.println("[!] Connection possibly lost with " + getPeerDisplayName() + " (Port unreachable). Disconnecting.");
                 resetConnectionState(); // Disconnect if peer port is gone
            }
            return false;
        }
        catch (IOException e) { System.err.println("[!] IO Error sending UDP to " + destination + ": " + e.getMessage()); return false;
        } catch (Exception e) { System.err.println("[!] Unexpected Error sending UDP to " + destination + ": " + e.getClass().getName() + " - " + e.getMessage()); return false; }
    }

    // sendMessageToPeer remains unchanged
    private static void sendMessageToPeer(String message) {
        InetSocketAddress peerAddr = peerAddrConfirmed.get();
        String peerId = connectedPeerId.get();
        SecretKey sharedKey = (peerId != null) ? peerSymmetricKeys.get(peerId) : null;

        if (currentState == NodeState.CONNECTED_SECURE && peerAddr != null && peerId != null && sharedKey != null) {
            try {
                EncryptedPayload payload = CryptoUtils.encrypt(message, sharedKey);

                JSONObject chatMsg = new JSONObject();
                chatMsg.put("action", "e_chat");
                chatMsg.put("node_id", myNodeId);
                chatMsg.put("iv", payload.ivBase64);
                chatMsg.put("e_payload", payload.ciphertextBase64); // Use generic key here

                boolean sent = sendUdp(chatMsg, peerAddr);

                if (sent && chatHistoryManager != null) {
                    long timestamp = System.currentTimeMillis();
                    chatHistoryManager.addMessage(timestamp, peerId, connectedPeerUsername.get(), "SENT", message);
                }
                if (!sent) System.out.println("[!] Failed to send chat message. Connection might be lost.");

            } catch (GeneralSecurityException e) { System.err.println("[!] Failed to encrypt message: " + e.getMessage());
            } catch (Exception e) { System.err.println("[!] Unexpected error sending chat message: " + e.getMessage()); }
        } else System.out.println("[!] Cannot send chat message, not securely connected or missing key.");
    }


    // --- Cryptography Helpers (Unchanged) ---
    private static boolean generateSharedKeyAndStore(String peerId, String peerPublicKeyBase64) {
        if (myKeyPair == null) { /* ... error ... */ return false; }
        if (peerId == null || peerPublicKeyBase64 == null) { /* ... error ... */ return false; }
        try {
            PublicKey peerPublicKey = CryptoUtils.decodePublicKey(peerPublicKeyBase64);
            System.out.println("[*] Performing ECDH key agreement with peer " + peerId + "...");
            byte[] sharedSecretBytes = CryptoUtils.generateSharedSecret(myKeyPair.getPrivate(), peerPublicKey);
            System.out.println("[*] Deriving AES symmetric key from shared secret...");
            SecretKey derivedKey = CryptoUtils.deriveSymmetricKey(sharedSecretBytes);
            peerSymmetricKeys.put(peerId, derivedKey);
            sharedKeyEstablished.set(true);
            System.out.println("[+] Shared symmetric key established successfully for peer " + peerId + "!");
            return true;
        } catch (NoSuchAlgorithmException | InvalidKeyException | java.security.spec.InvalidKeySpecException e) { // Added InvalidKeySpecException
             System.err.println("[!] Cryptographic error during key exchange: " + e.getMessage());
        } catch (Exception e) { /* ... other error ... */ }
        sharedKeyEstablished.set(false);
        return false;
    }

    // --- *** NEW: File Transfer Logic Methods *** ---

    /** Initiates sending a file */
    private static void initiateFileSend(String filePathStr) {
        String peerId = connectedPeerId.get();
        InetSocketAddress peerAddr = peerAddrConfirmed.get();
        if (peerId == null || peerAddr == null || currentState != NodeState.CONNECTED_SECURE) {
            System.out.println("[!] Not securely connected to a peer.");
            return;
        }

        try {
            Path filePath = Paths.get(filePathStr);
            if (!Files.exists(filePath) || !Files.isReadable(filePath) || Files.isDirectory(filePath)) {
                System.out.println("[!] File not found, is not readable, or is a directory: " + filePathStr);
                return;
            }

            long filesize = Files.size(filePath);
            if (filesize > MAX_FILE_SIZE_BYTES) {
                System.out.println("[!] File exceeds the size limit of " + formatFilesize(MAX_FILE_SIZE_BYTES) + ". File size: " + formatFilesize(filesize));
                return;
            }
             if (filesize == 0) {
                 System.out.println("[!] Sending zero-byte files is currently not supported (or meaningful)."); // Or allow? Let's forbid for now.
                 return;
             }

            String filename = filePath.getFileName().toString();
            String transferId = UUID.randomUUID().toString();

            synchronized(transferLock) {
                 // Check for existing transfers (optional, maybe allow multiple?)
                 // For simplicity, let's restrict to one outgoing at a time for now.
                 if (!outgoingTransfers.isEmpty()) {
                     System.out.println("[!] Another file transfer is already in progress. Please wait.");
                     return;
                 }

                 TransferState state = new TransferState(transferId, peerId, filename, filesize, true, filePath);
                 outgoingTransfers.put(transferId, state);

                 JSONObject offerMsg = new JSONObject();
                 offerMsg.put("action", "file_offer");
                 offerMsg.put("node_id", myNodeId);
                 offerMsg.put("transfer_id", transferId);
                 offerMsg.put("filename", filename);
                 offerMsg.put("filesize", filesize);

                 System.out.println("[*] Offering file '" + filename + "' (" + formatFilesize(filesize) + ") to " + getPeerDisplayName() + ". Waiting for acceptance...");
                 System.out.print(getPrompt()); // Show prompt again
                 if (!sendUdp(offerMsg, peerAddr)) {
                     System.err.println("[!] Failed to send file offer.");
                     outgoingTransfers.remove(transferId); // Clean up state
                 } else {
                     state.lastActivityTime = System.currentTimeMillis(); // Mark time offer was sent
                 }
            }

        } catch (IOException e) {
            System.err.println("[!] Error accessing file: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[!] Unexpected error initiating file send: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Handles an incoming file offer */
    private static void handleFileOffer(JSONObject data, String senderId, SecretKey sharedKey) {
         String transferId = data.optString("transfer_id", null);
         String filename = data.optString("filename", null);
         long filesize = data.optLong("filesize", -1);

         if (transferId == null || filename == null || filesize < 0) {
             System.err.println("[!] Received invalid file offer from " + getPeerDisplayName() + ".");
             return;
         }
          if (filesize == 0) {
              System.out.println("[!] Peer " + getPeerDisplayName() + " offered a zero-byte file. Rejecting.");
              // Send reject? For now, just ignore.
              return;
          }
         if (filesize > MAX_FILE_SIZE_BYTES) {
             System.out.println("[!] Peer " + getPeerDisplayName() + " offered file '" + filename + "' which exceeds size limit (" + formatFilesize(filesize) + "). Rejecting.");
             sendSimpleTransferResponse(senderId, transferId, "file_reject", "File exceeds size limit");
             return;
         }

         synchronized(transferLock) {
             // Only allow one pending offer at a time for simplicity
             if (pendingFileOfferId.get() != null) {
                 System.out.println("[!] Received new file offer while another is pending. Rejecting new offer.");
                 sendSimpleTransferResponse(senderId, transferId, "file_reject", "Another offer pending");
                 return;
             }
             // Check if already receiving this transfer (shouldn't happen normally)
             if (incomingTransfers.containsKey(transferId)) {
                 System.out.println("[?] Received duplicate file offer for " + transferId.substring(0,8) + ". Ignoring.");
                 return;
             }

             Path downloadPath = Paths.get(DOWNLOAD_DIR, filename); // TODO: Handle filename collisions? Add counter?
             if (Files.exists(downloadPath)) {
                  System.out.println("[!] File '" + filename + "' already exists in downloads. Rejecting offer.");
                  sendSimpleTransferResponse(senderId, transferId, "file_reject", "File already exists");
                  return;
             }

             TransferState state = new TransferState(transferId, senderId, filename, filesize, false, downloadPath);
             incomingTransfers.put(transferId, state);
             pendingFileOfferId.set(transferId);
             state.lastActivityTime = System.currentTimeMillis(); // Record offer time

             // Prompt user in the main loop - just print initial notification here
             System.out.print("\r"); // Clear current prompt line
             System.out.println("[!] Incoming file offer from " + getPeerDisplayName() + ": '" + filename + "' (" + formatFilesize(filesize) + ")");
             System.out.print("[!] Accept? (yes/no): "); // Prompt will be repeated by main loop
         }
    }

    /** Handles the user typing 'yes' to an offer */
    private static void acceptFileOffer(String transferId) {
        synchronized (transferLock) {
            TransferState state = incomingTransfers.get(transferId);
            String peerId = connectedPeerId.get();
            InetSocketAddress peerAddr = peerAddrConfirmed.get();

            if (state == null || state.isSender || state.accepted || peerId == null || peerAddr == null) {
                System.out.println("[!] Cannot accept offer: Invalid state or not connected.");
                if(state != null) incomingTransfers.remove(transferId); // Clean up invalid state
                return;
            }
             if (!state.peerNodeId.equals(peerId)) {
                 System.out.println("[!] Offer peer ("+state.peerNodeId.substring(0,8)+") does not match connected peer ("+peerId.substring(0,8)+"). Cannot accept.");
                 incomingTransfers.remove(transferId);
                 return;
             }

            // Prepare for receiving
            try {
                 // Ensure downloads directory exists
                 Files.createDirectories(Paths.get(DOWNLOAD_DIR));
                 // Open file stream - Use RandomAccessFile for potential out-of-order writes, though our simple ACK enforces order now
                 state.fileOutputStream = new FileOutputStream(state.filePath.toFile()); // Or RandomAccessFile(state.filePath.toFile(), "rw");
                 System.out.println("[*] Accepting file offer for '" + state.filename + "'. Preparing to receive...");

                 state.accepted = true;
                 state.lastActivityTime = System.currentTimeMillis();

                 // Send accept message
                 if (!sendSimpleTransferResponse(peerId, transferId, "file_accept", null)) {
                     System.err.println("[!] Failed to send file acceptance message.");
                     state.failed = true;
                     state.closeStreams();
                     try { Files.deleteIfExists(state.filePath); } catch (IOException e) {}
                     incomingTransfers.remove(transferId);
                 }

            } catch (IOException e) {
                System.err.println("[!] Error preparing to receive file: " + e.getMessage());
                sendSimpleTransferResponse(peerId, transferId, "file_reject", "Receiver I/O error");
                incomingTransfers.remove(transferId); // Clean up
            }
        }
        System.out.print(getPrompt()); // Reprint prompt
    }

    /** Handles the user typing 'no' to an offer */
     private static void rejectFileOffer(String transferId, String reason) {
         synchronized (transferLock) {
            TransferState state = incomingTransfers.get(transferId);
            String peerId = connectedPeerId.get(); // Get current peer
            InetSocketAddress peerAddr = peerAddrConfirmed.get();

            if (state == null || state.isSender || state.accepted || peerId == null || peerAddr == null) {
                // Offer might have timed out or state is inconsistent
                System.out.println("[*] Offer " + transferId.substring(0,8) + " is no longer valid or already handled.");
                if(state != null) incomingTransfers.remove(transferId); // Clean up if exists
                return;
            }
            if (!state.peerNodeId.equals(peerId)) {
                 System.out.println("[!] Offer peer ("+state.peerNodeId.substring(0,8)+") does not match connected peer ("+peerId.substring(0,8)+").");
                 incomingTransfers.remove(transferId);
                 return;
             }

            System.out.println("[*] Rejecting file offer for '" + state.filename + "'.");
            sendSimpleTransferResponse(state.peerNodeId, transferId, "file_reject", reason);
            incomingTransfers.remove(transferId); // Remove state
         }
         System.out.print(getPrompt()); // Reprint prompt
     }


    /** Handles receiving acceptance from peer */
    private static void handleFileAccept(JSONObject data, String senderId, SecretKey sharedKey) {
        String transferId = data.optString("transfer_id", null);
        if (transferId == null) return;

        synchronized(transferLock) {
            TransferState state = outgoingTransfers.get(transferId);
            if (state == null || !state.isSender || state.accepted || !state.peerNodeId.equals(senderId)) {
                // System.out.println("[?] Received unexpected file_accept for " + transferId.substring(0,8) + ". Ignoring.");
                return;
            }

            System.out.println("\r[*] Peer " + getPeerDisplayName() + " accepted the file offer for '" + state.filename + "'. Starting transfer...");
            System.out.print(getPrompt());

            state.accepted = true;
            state.lastActivityTime = System.currentTimeMillis();
            state.currentChunkIndex = 0; // Ensure we start from chunk 0

            // Open input stream
            try {
                state.fileInputStream = new FileInputStream(state.filePath.toFile());
                // Send the first chunk
                sendNextChunk(state);
            } catch (FileNotFoundException e) {
                 System.err.println("[!] Error opening file to send: " + e.getMessage());
                 failTransfer(state, "Sender file error", true);
            }
        }
    }

    /** Handles receiving rejection from peer */
     private static void handleFileReject(JSONObject data, String senderId) {
         String transferId = data.optString("transfer_id", null);
         String reason = data.optString("reason", "Peer rejected");
         if (transferId == null) return;

         synchronized(transferLock) {
             TransferState state = outgoingTransfers.get(transferId);
             if (state == null || !state.isSender || !state.peerNodeId.equals(senderId)) {
                 return; // Ignore if not relevant
             }
             if (state.completed || state.failed) return; // Already done/failed

             System.out.println("\r[!] Peer " + getPeerDisplayName() + " rejected the file offer for '" + state.filename + "'. Reason: " + reason);
             System.out.print(getPrompt());
             failTransfer(state, reason, false); // Mark as failed, clean up, don't send cancel
         }
     }

    /** Handles receiving a file chunk */
     private static void handleFileChunk(JSONObject data, String senderId, SecretKey sharedKey) {
         String transferId = data.optString("transfer_id", null);
         int chunkIndex = data.optInt("chunk_index", -1);
         // iv and e_chunk extracted inside decryptBytes via EncryptedPayload.fromJson

         if (transferId == null || chunkIndex < 0) {
              System.err.println("[!] Received invalid file chunk data from " + getPeerDisplayName() + ".");
              return;
         }

         synchronized(transferLock) {
             TransferState state = incomingTransfers.get(transferId);
             if (state == null || state.isSender || state.failed || state.completed || !state.accepted || !state.peerNodeId.equals(senderId)) {
                 // System.out.println("[?] Received file chunk for inactive/unknown transfer " + transferId.substring(0,8) + ". Ignoring.");
                 // Could send cancel if state exists but not accepted? Low priority.
                 return;
             }

             // --- ACK Logic: Only process expected chunk ---
             if (chunkIndex != state.currentChunkIndex) {
                  // Received out-of-order chunk or duplicate of old chunk
                  // System.out.println("[?] Received chunk " + chunkIndex + ", expected " + state.currentChunkIndex + ". For " + transferId.substring(0,8));
                  // If it's an OLD chunk we already acked, re-send the ACK to help sender move on
                  if (chunkIndex < state.currentChunkIndex && chunkIndex == state.ackedChunkIndex) {
                      System.out.println("[*] Resending ACK for chunk " + chunkIndex);
                      sendChunkAck(state.peerNodeId, transferId, chunkIndex);
                  }
                  // Otherwise, ignore out-of-order chunks for now (simple stop-and-wait)
                  return;
             }

             // --- Decrypt and Write ---
             EncryptedPayload payload = EncryptedPayload.fromJson(data);
             if (payload == null) {
                 System.err.println("[!] Failed to parse encrypted payload for chunk " + chunkIndex + " from " + getPeerDisplayName());
                 // Don't send ACK, let sender time out and resend
                 return;
             }

             try {
                 byte[] decryptedBytes = CryptoUtils.decryptBytes(payload, sharedKey);

                 if (state.fileOutputStream == null) {
                     throw new IOException("File output stream is not open.");
                 }

                 // Write the chunk (assuming simple sequential writing)
                 state.fileOutputStream.write(decryptedBytes);
                 state.lastActivityTime = System.currentTimeMillis();
                 state.ackedChunkIndex = chunkIndex; // Record that we processed this chunk
                 state.currentChunkIndex++; // Expect the next chunk now

                 // Send ACK for the received chunk
                 sendChunkAck(senderId, transferId, chunkIndex);

                 // --- Check for Completion ---
                 if (state.currentChunkIndex >= state.totalChunks) {
                     state.completed = true;
                     state.closeStreams();
                     incomingTransfers.remove(transferId); // Remove from active transfers
                     System.out.print("\r"); // Clear potentially partial prompt line
                     System.out.println("[+] File '" + state.filename + "' received successfully! (" + formatFilesize(state.filesize) + ")");
                     System.out.print(getPrompt());
                 } else {
                      // Print progress occasionally
                      if (chunkIndex % 20 == 0 || chunkIndex == state.totalChunks - 1) { // Print every 20 chunks or on last
                           System.out.printf("\r[*] Receiving '%s': Chunk %d / %d (%.1f%%)",
                               state.filename, state.currentChunkIndex, state.totalChunks,
                               (100.0 * state.currentChunkIndex / state.totalChunks));
                      }
                 }

             } catch (GeneralSecurityException e) {
                 System.err.println("\n[!] Failed to decrypt chunk " + chunkIndex + " for file '" + state.filename + "': " + e.getMessage());
                 // Don't ACK, let sender resend
             } catch (IOException e) {
                 System.err.println("\n[!] I/O error writing chunk " + chunkIndex + " for file '" + state.filename + "': " + e.getMessage());
                 failTransfer(state, "Receiver I/O error", true); // Fail transfer and notify sender
             } catch (Exception e) {
                 System.err.println("\n[!] Unexpected error handling chunk " + chunkIndex + ": " + e.getMessage());
                 e.printStackTrace();
                 failTransfer(state, "Unexpected receiver error", true);
             }
         } // End synchronized block
     }

    /** Handles receiving an ACK for a sent chunk */
    private static void handleFileAck(JSONObject data, String senderId) {
         String transferId = data.optString("transfer_id", null);
         int ackedIndex = data.optInt("acked_chunk_index", -1);

         if (transferId == null || ackedIndex < 0) return; // Invalid ACK

         synchronized(transferLock) {
            TransferState state = outgoingTransfers.get(transferId);
            // Validate state: Must exist, be sender, accepted, not done/failed, and from correct peer
            if (state == null || !state.isSender || !state.accepted || state.completed || state.failed || !state.peerNodeId.equals(senderId)) {
                return;
            }

            // Check if this ACK is for the chunk we are waiting for
            if (ackedIndex == state.currentChunkIndex) {
                state.lastActivityTime = System.currentTimeMillis();
                state.ackedChunkIndex = ackedIndex; // Record successful ACK
                state.currentChunkIndex++; // Move to next chunk
                state.retryCount = 0; // Reset retry count for the NEW chunk

                // Check for completion
                if (state.currentChunkIndex >= state.totalChunks) {
                    state.completed = true;
                    state.closeStreams();
                    outgoingTransfers.remove(transferId);
                    System.out.print("\r"); // Clear progress line
                    System.out.println("[+] File '" + state.filename + "' sent successfully!");
                    System.out.print(getPrompt());
                } else {
                    // Print progress occasionally
                    if (state.currentChunkIndex % 20 == 0 || state.currentChunkIndex == state.totalChunks - 1) {
                       System.out.printf("\r[*] Sending '%s': Chunk %d / %d (%.1f%%)",
                           state.filename, state.currentChunkIndex + 1, state.totalChunks, // Show next chunk to send
                           (100.0 * state.currentChunkIndex / state.totalChunks));
                    }
                    // Send the next chunk
                    sendNextChunk(state);
                }
            } else if (ackedIndex < state.currentChunkIndex) {
                // Ignore duplicate ACKs for old chunks
                // System.out.println("[?] Received duplicate ACK for chunk " + ackedIndex + ", expected " + state.currentChunkIndex);
            } else {
                 // Received ACK for a future chunk? Protocol error.
                 System.err.println("[!] Received unexpected future ACK for chunk " + ackedIndex + ", expected " + state.currentChunkIndex + ". Transfer " + transferId.substring(0,8));
                 // Maybe fail the transfer? For now, ignore and hope for correct ACK.
            }
         } // End synchronized block
     }

     /** Handles receiving a cancellation message from peer */
     private static void handleFileCancel(JSONObject data, String senderId) {
         String transferId = data.optString("transfer_id", null);
         String reason = data.optString("reason", "Peer cancelled");
         if (transferId == null) return;

         synchronized(transferLock) {
             TransferState state = outgoingTransfers.get(transferId);
             if (state != null && state.peerNodeId.equals(senderId)) { // It's an outgoing transfer cancelled by peer
                 if (!state.completed && !state.failed) {
                    System.out.println("\r[!] Peer " + getPeerDisplayName() + " cancelled transfer for '" + state.filename + "'. Reason: " + reason);
                    System.out.print(getPrompt());
                    failTransfer(state, reason, false); // Mark failed, clean up, don't send cancel back
                 }
             } else {
                 state = incomingTransfers.get(transferId);
                 if (state != null && state.peerNodeId.equals(senderId)) { // It's an incoming transfer cancelled by peer
                     if (!state.completed && !state.failed) {
                        System.out.println("\r[!] Peer " + getPeerDisplayName() + " cancelled transfer for '" + state.filename + "'. Reason: " + reason);
                        System.out.print(getPrompt());
                        failTransfer(state, reason, false); // Mark failed, clean up, don't send cancel back
                         if (transferId.equals(pendingFileOfferId.get())) pendingFileOfferId.set(null); // Clear pending offer
                     }
                 }
             }
         }
     }

    /** Sends the next required chunk for an outgoing transfer */
    private static void sendNextChunk(TransferState state) {
         if (state == null || !state.isSender || !state.accepted || state.completed || state.failed) return;

         SecretKey sharedKey = peerSymmetricKeys.get(state.peerNodeId);
         InetSocketAddress peerAddr = peerAddrConfirmed.get(); // Assuming this is correct for the peer

         if (sharedKey == null || peerAddr == null || !state.peerNodeId.equals(connectedPeerId.get())) {
             failTransfer(state, "Connection state invalid for sending chunk", false);
             return;
         }
         if (state.fileInputStream == null) {
              failTransfer(state, "Sender file stream closed unexpectedly", true);
              return;
         }

         try {
             byte[] buffer = new byte[FILE_CHUNK_SIZE_BYTES];
             int bytesRead = state.fileInputStream.read(buffer);

             if (bytesRead == -1) { // Should not happen if filesize calculation was correct, but handle EOF
                 System.err.println("[!] Premature EOF reached while reading file " + state.filename + " at chunk " + state.currentChunkIndex);
                 failTransfer(state, "Unexpected EOF reading file", true);
                 return;
             }

             // Trim buffer if last chunk is smaller
             byte[] chunkData = (bytesRead == FILE_CHUNK_SIZE_BYTES) ? buffer : Arrays.copyOf(buffer, bytesRead);

             EncryptedPayload payload = CryptoUtils.encryptBytes(chunkData, sharedKey);

             JSONObject chunkMsg = new JSONObject();
             chunkMsg.put("action", "file_chunk");
             chunkMsg.put("node_id", myNodeId);
             chunkMsg.put("transfer_id", state.transferId);
             chunkMsg.put("chunk_index", state.currentChunkIndex);
             chunkMsg.put("iv", payload.ivBase64);
             chunkMsg.put("e_chunk", payload.ciphertextBase64); // Use specific key

             if (sendUdp(chunkMsg, peerAddr)) {
                 state.lastActivityTime = System.currentTimeMillis(); // Record send time to track ACK timeout
                 // Retry count is reset when ACK is received for *previous* chunk
             } else {
                 System.err.println("[!] Failed to send chunk " + state.currentChunkIndex + " for transfer " + state.transferId.substring(0,8) + ". Will retry.");
                 // Let timeout handler manage retries
             }

         } catch (IOException e) {
              System.err.println("\n[!] I/O error reading file chunk " + state.currentChunkIndex + ": " + e.getMessage());
              failTransfer(state, "Sender I/O error reading file", true);
         } catch (GeneralSecurityException e) {
              System.err.println("\n[!] Encryption error for chunk " + state.currentChunkIndex + ": " + e.getMessage());
              failTransfer(state, "Encryption error", true); // Maybe retry? Let's fail for crypto errors.
         } catch (Exception e) {
              System.err.println("\n[!] Unexpected error sending chunk " + state.currentChunkIndex + ": " + e.getMessage());
              e.printStackTrace();
              failTransfer(state, "Unexpected sender error", true);
         }
    }

    /** Helper to send ACK for a received chunk */
    private static boolean sendChunkAck(String peerId, String transferId, int ackedIndex) {
        InetSocketAddress peerAddr = peerAddrConfirmed.get();
        if (peerAddr == null) return false;

        JSONObject ackMsg = new JSONObject();
        ackMsg.put("action", "file_ack");
        ackMsg.put("node_id", myNodeId);
        ackMsg.put("transfer_id", transferId);
        ackMsg.put("acked_chunk_index", ackedIndex);
        return sendUdp(ackMsg, peerAddr);
    }

    /** Helper to send simple accept/reject messages */
    private static boolean sendSimpleTransferResponse(String peerId, String transferId, String action, String reason) {
         InetSocketAddress peerAddr = peerAddrConfirmed.get();
         if (peerAddr == null || !peerId.equals(connectedPeerId.get())) return false; // Ensure connected to the right peer

         JSONObject responseMsg = new JSONObject();
         responseMsg.put("action", action); // "file_accept" or "file_reject" or "file_cancel"
         responseMsg.put("node_id", myNodeId);
         responseMsg.put("transfer_id", transferId);
         if (reason != null) {
             responseMsg.put("reason", reason);
         }
         return sendUdp(responseMsg, peerAddr);
     }

    /** Marks a transfer as failed, cleans up, and optionally notifies the peer */
    private static void failTransfer(TransferState state, String reason, boolean notifyPeer) {
        synchronized (transferLock) {
            if (state == null || state.failed || state.completed) return; // Already handled

            state.failed = true;
            System.out.print("\r"); // Clear prompt line potentially
            System.err.println("[!] File transfer '" + state.filename + "' (" + state.transferId.substring(0,8) + ") FAILED. Reason: " + reason);

            state.closeStreams();

            // Remove from active transfers
            if (state.isSender) {
                outgoingTransfers.remove(state.transferId);
            } else {
                incomingTransfers.remove(state.transferId);
                // Delete partial download
                try {
                    if (state.filePath != null && Files.exists(state.filePath)) {
                        Files.delete(state.filePath);
                        System.out.println("[*] Deleted partial download: " + state.filePath.getFileName());
                    }
                } catch (IOException e) {
                    System.err.println("[!] Error deleting partial file: " + e.getMessage());
                }
                 // Clear pending offer if it matches
                if (state.transferId.equals(pendingFileOfferId.get())) {
                    pendingFileOfferId.set(null);
                }
            }

             // Notify peer if requested
            if (notifyPeer) {
                 sendSimpleTransferResponse(state.peerNodeId, state.transferId, "file_cancel", reason);
            }
             System.out.print(getPrompt()); // Reprint prompt
        }
    }

    // --- NEW: Timeout Checking Logic (Called by ConnectionManager) ---

    private static void checkOutgoingTransferTimeouts(long now) {
        // Check outgoing transfers (waiting for ACK)
        List<TransferState> toFail = new ArrayList<>();
        for (TransferState state : outgoingTransfers.values()) {
            if (!state.accepted || state.completed || state.failed) continue;

            // Check offer acceptance timeout (if peer hasn't accepted yet)
            if (!state.accepted && (now - state.lastActivityTime > FILE_OFFER_TIMEOUT_MS)) {
                 System.out.println("\r[!] Timeout waiting for peer to accept file offer for '" + state.filename + "'.");
                 toFail.add(state);
                 continue;
            }

            // Check ACK timeout for the current chunk
            if (state.accepted && state.currentChunkIndex <= state.ackedChunkIndex + 1) { // Only timeout if waiting for ACK
                if (now - state.lastActivityTime > FILE_ACK_TIMEOUT_MS) {
                    state.retryCount++;
                    if (state.retryCount > FILE_MAX_RETRIES) {
                         System.err.println("\n[!] Max retries exceeded waiting for ACK on chunk " + state.currentChunkIndex + " for file '" + state.filename + "'.");
                         toFail.add(state);
                    } else {
                         System.out.printf("\r[*] Timeout waiting for ACK (Chunk %d, Retry %d/%d). Resending...\n", state.currentChunkIndex, state.retryCount, FILE_MAX_RETRIES);
                         System.out.print(getPrompt());
                         state.lastActivityTime = now; // Update time to avoid immediate re-retry
                         // Resend the *current* chunk (the one not acked yet)
                         sendNextChunk(state); // sendNextChunk sends based on state.currentChunkIndex
                    }
                }
            }
        }
        // Fail transfers outside the loop to avoid ConcurrentModificationException
        for (TransferState state : toFail) {
            failTransfer(state, "Timeout or max retries exceeded", true);
        }
    }

    private static void checkIncomingTransferTimeouts(long now) {
        // Check incoming transfers for general stall
        List<TransferState> toFail = new ArrayList<>();
        for (TransferState state : incomingTransfers.values()) {
             if (!state.accepted || state.completed || state.failed) continue; // Only check active, accepted transfers

            // If no chunk received for a long time
            if (now - state.lastActivityTime > FILE_ACK_TIMEOUT_MS * (FILE_MAX_RETRIES + 2)) { // More generous timeout for receiver stall
                 System.err.println("\n[!] Transfer for '" + state.filename + "' stalled. No chunk received for a long time.");
                 toFail.add(state);
            }
        }
        for (TransferState state : toFail) {
            failTransfer(state, "Transfer stalled (timeout)", true);
        }
    }

     private static void checkFileOfferAcceptanceTimeout(long now) {
         String offerId = pendingFileOfferId.get();
         if (offerId != null) {
             TransferState state = incomingTransfers.get(offerId);
             if (state != null && !state.accepted) { // Check if still pending user input
                 if (now - state.lastActivityTime > FILE_OFFER_TIMEOUT_MS) {
                     System.out.print("\r"); // Clear prompt line
                     System.out.println("[!] Timed out waiting for user response to file offer for '" + state.filename + "'. Rejecting.");
                     rejectFileOffer(offerId, "Timeout"); // This cleans up state and notifies sender
                     pendingFileOfferId.set(null); // Ensure cleared
                     System.out.print(getPrompt()); // Reprint standard prompt
                 }
             } else {
                 // State mismatch, clear pending offer ID
                 pendingFileOfferId.set(null);
             }
         }
     }

     /** Prints status of ongoing file transfers */
     private static void printTransferStatus() {
         synchronized(transferLock) {
            if (outgoingTransfers.isEmpty() && incomingTransfers.isEmpty()) {
                return; // No transfers active
            }
            System.out.println("--- File Transfers ---");
             if (outgoingTransfers.isEmpty()) System.out.println("  (No outgoing transfers)");
             else outgoingTransfers.values().forEach(t -> System.out.println("  [OUT] " + t));

             if (incomingTransfers.isEmpty()) System.out.println("  (No incoming transfers)");
             else incomingTransfers.values().forEach(t -> {
                 if (!t.accepted && t.transferId.equals(pendingFileOfferId.get())) {
                     System.out.println("  [IN ] Transfer{id=" + t.transferId.substring(0,8) + ", file=" + t.filename + ", size=" + formatFilesize(t.filesize)+", state=WAITING_ACCEPT}");
                 } else {
                    System.out.println("  [IN ] " + t);
                 }
             });
            System.out.println("----------------------");
         }
     }

     /** Utility to format filesize */
     private static String formatFilesize(long size) {
         if (size < 1024) return size + " B";
         int exp = (int) (Math.log(size) / Math.log(1024));
         String pre = "KMGTPE".charAt(exp-1) + "i";
         return String.format("%.1f %sB", size / Math.pow(1024, exp), pre);
     }

    // --- Shutdown ---
    // MODIFIED: Clean up transfers on shutdown
    private static synchronized void shutdown() {
        if (!running) return;
        System.out.println("\n[*] Shutting down node...");
        running = false;

        // --- NEW: Cancel active transfers ---
        System.out.println("[*] Cancelling active file transfers...");
        synchronized(transferLock) {
             List<TransferState> toCancel = new ArrayList<>();
             toCancel.addAll(outgoingTransfers.values());
             toCancel.addAll(incomingTransfers.values());

             for(TransferState state : toCancel) {
                 // Don't notify peer during shutdown, just clean up locally
                 failTransfer(state, "Node shutting down", false);
             }
             outgoingTransfers.clear();
             incomingTransfers.clear();
             pendingFileOfferId.set(null);
        }
        // --- End Transfer Cancellation ---


        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdownNow();
            System.out.println("[*] Scheduled executor shutdown requested.");
             try { if (!scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS)) System.err.println("[!] Scheduled executor did not terminate cleanly."); else System.out.println("[*] Scheduled executor terminated."); }
             catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close(); System.out.println("[*] UDP Socket closed.");
        }
        if (listenerExecutor != null && !listenerExecutor.isShutdown()) {
            listenerExecutor.shutdown(); System.out.println("[*] Listener executor shutdown requested.");
             try { if (!listenerExecutor.awaitTermination(1, TimeUnit.SECONDS)) { System.err.println("[!] Listener thread did not terminate cleanly, forcing..."); listenerExecutor.shutdownNow(); } else System.out.println("[*] Listener executor terminated."); }
             catch (InterruptedException e) { listenerExecutor.shutdownNow(); Thread.currentThread().interrupt(); }
        }
        peerSymmetricKeys.clear(); myKeyPair = null;
        System.out.println("[*] Node shutdown sequence complete.");
    }
} // End of P2PNode class