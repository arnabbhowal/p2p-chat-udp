package main.java.com.p2pchat.node;

// Import common classes INCLUDING the new CryptoUtils
import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.common.CryptoUtils; // <-- NEW
import main.java.com.p2pchat.common.CryptoUtils.EncryptedPayload; // <-- NEW Container Class

import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.SecretKey; // <-- NEW Crypto imports
import java.security.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    private static final Object stateLock = new Object();

    // --- State Machine ---
    private enum NodeState { DISCONNECTED, WAITING_MATCH, ATTEMPTING_UDP, EXCHANGING_KEYS, CONNECTED_SECURE }
    private static volatile NodeState currentState = NodeState.DISCONNECTED;
    private static long waitingSince = 0;
    private static int pingAttempts = 0;
    private static long lastStatePrintTime = 0;

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
            // Show full ID on successful registration
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

    // --- Network Setup & Discovery (getLocalNetworkEndpoints remains unchanged) ---
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
                    handlePeerMessage(data, senderAddr);
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

    private static void startConnectionManager() {
        scheduledExecutor.scheduleWithFixedDelay(() -> {
            if (!running || udpSocket == null || udpSocket.isClosed()) return;

            try {
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
                if (stateNow == NodeState.ATTEMPTING_UDP && currentTarget != null && !peerCandidates.isEmpty() && !p2pUdpPathConfirmed.get()) { // Check UDP path flag
                    if (pingAttempts < MAX_PING_ATTEMPTS) {
                        JSONObject pingMsg = new JSONObject();
                        pingMsg.put("action", "ping");
                        pingMsg.put("node_id", myNodeId);

                        List<Endpoint> currentCandidates = new ArrayList<>(peerCandidates);
                        currentCandidates.sort(Comparator.comparing(ep -> (ep.type != null && ep.type.contains("public")) ? 0 : 1));

                        boolean sentPingThisRound = false;
                        for (Endpoint candidate : currentCandidates) {
                            try {
                                InetSocketAddress candidateAddr = new InetSocketAddress(InetAddress.getByName(candidate.ip), candidate.port);
                                if(sendUdp(pingMsg, candidateAddr)) sentPingThisRound = true;
                            } catch (Exception e){ /* Ignore send errors during ping */ }
                        }
                        if(sentPingThisRound) {
                            synchronized(stateLock) {
                                if(currentState == NodeState.ATTEMPTING_UDP) pingAttempts++;
                            }
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

            } catch (Exception e) {
                System.err.println("[!!!] Error in Connection Manager task: " + e.getMessage());
                e.printStackTrace();
            }

        }, 1000, Math.min(KEEPALIVE_PEER_INTERVAL_MS, PING_INTERVAL_MS), TimeUnit.MILLISECONDS);

        System.out.println("[*] Started Connection Manager task.");
    }

     // Use the scanner passed from main
    private static void userInteractionLoop(Scanner scanner) {
        try {
            // Show full ID in initial ready message
            System.out.println("\n--- P2P Node Ready (Username: " + myUsername + ", Node ID: " + myNodeId + ") ---");
            System.out.println("--- Enter 'connect <peer_id>', 'status', or 'quit' ---");

            while (running) {
                try {
                    checkStateTransitions(); // Check async events first

                    long now = Instant.now().toEpochMilli();
                    String prompt = getPrompt();
                    NodeState stateNow = currentState;

                    // Periodic status/timeout checks for intermediate states
                    if (stateNow == NodeState.WAITING_MATCH || stateNow == NodeState.ATTEMPTING_UDP || stateNow == NodeState.EXCHANGING_KEYS) {
                        if (now - lastStatePrintTime > STATE_PRINT_INTERVAL_MS || lastStatePrintTime == 0) {
                            System.out.println(getStateDescription());
                            System.out.print(prompt);
                            lastStatePrintTime = now;
                        }

                        // Check timeouts
                        if (stateNow == NodeState.WAITING_MATCH && (now - waitingSince > WAIT_MATCH_TIMEOUT_MS)) {
                            System.out.println("\n[!] Timed out waiting for peer match (" + WAIT_MATCH_TIMEOUT_MS / 1000 + "s). Cancelling.");
                            resetConnectionState();
                            currentState = NodeState.DISCONNECTED;
                            System.out.print(getPrompt());
                            continue;
                        }
                        // Check PING timeout
                        if (stateNow == NodeState.ATTEMPTING_UDP && (pingAttempts >= MAX_PING_ATTEMPTS) && !p2pUdpPathConfirmed.get()) {
                            System.out.println("\n[!] Failed to establish UDP connection with peer " + getPeerDisplayName() + " after " + MAX_PING_ATTEMPTS + " ping cycles.");
                            resetConnectionState();
                            currentState = NodeState.DISCONNECTED;
                             System.out.print(getPrompt());
                            continue;
                         }
                         // Timeout for key exchange (implicitly if state doesn't advance)

                        // Sleep briefly if no input pending
                        if (System.in.available() <= 0) {
                           try { Thread.sleep(200); } catch (InterruptedException e) { running = false; Thread.currentThread().interrupt(); break; }
                            continue;
                        }
                    } else {
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

                    String[] parts = line.split(" ", 2);
                    String command = parts[0].toLowerCase();

                    if(stateBeforeCommand != currentState) {
                        System.out.println("\n[*] State changed during input, please check status and retry.");
                        continue;
                    }

                    // Process commands
                    switch (command) {
                        case "quit":
                        case "exit":
                            System.out.println("[*] Quit command received. Initiating shutdown...");
                            shutdown();
                            System.exit(0);
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
                        case "cancel":
                            if (stateBeforeCommand != NodeState.DISCONNECTED) {
                                System.out.println("[*] Disconnecting/Cancelling connection with " + getPeerDisplayName() + "...");
                                resetConnectionState();
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
                            break;
                        case "id":
                            System.out.println("[*] Your Username: " + myUsername);
                            System.out.println("[*] Your Node ID: " + myNodeId); // Always show full ID here
                            break;
                        default:
                             if(stateBeforeCommand == NodeState.CONNECTED_SECURE) System.out.println("[!] Unknown command. Available: chat, disconnect, status, quit");
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


    // --- State Management & Transitions ---

    private static void checkStateTransitions() {
        NodeState previousState = currentState;
        String peerId = connectedPeerId.get();

        synchronized (stateLock) {
            // Event: Received connection info from server (triggers key gen + sets flag)
            if (currentState == NodeState.WAITING_MATCH && connectionInfoReceived.compareAndSet(true, false)) {
                 // We generated the key in handleServerMessage. Now check if successful.
                 if (sharedKeyEstablished.get() && !peerCandidates.isEmpty()) { // Check key AND candidates
                     currentState = NodeState.ATTEMPTING_UDP;
                     pingAttempts = 0; // Reset ping counter
                     // Show full target ID here
                     System.out.println("\n[*] Received peer info for " + targetPeerId.get() +". Attempting UDP P2P connection...");
                 } else {
                     // Key gen failed or candidates missing
                     System.out.println("\n[!] Failed to process peer info (missing candidates or key error). Cancelling.");
                     resetConnectionState();
                     currentState = NodeState.DISCONNECTED;
                 }
            }
            // Event: UDP path established (ping/pong worked)
            // This flag (p2pUdpPathConfirmed) is set by handlePeerMessage upon receiving ping/pong
            else if (currentState == NodeState.ATTEMPTING_UDP && p2pUdpPathConfirmed.get()) {
                 // We already generated the key upon receiving server info.
                 // If UDP path is now confirmed AND key is established, move to secure state.
                 if (sharedKeyEstablished.get()) {
                    currentState = NodeState.CONNECTED_SECURE; // Directly to secure state
                    InetSocketAddress confirmedAddr = peerAddrConfirmed.get();
                    System.out.println("\n-----------------------------------------------------");
                    System.out.println("[+] SECURE E2EE CONNECTION ESTABLISHED with " + getPeerDisplayName() + "!");
                    System.out.println("    Using path: YOU -> " + (confirmedAddr != null ? confirmedAddr : "???"));
                    System.out.println("-----------------------------------------------------");

                    if (peerId != null) {
                        chatHistoryManager = new ChatHistoryManager();
                        chatHistoryManager.initialize(myNodeId, peerId, connectedPeerUsername.get());
                    } else {
                        System.err.println("[!] Cannot initialize history: Peer Node ID is null upon secure connection!");
                        chatHistoryManager = null;
                    }
                    targetPeerId.set(null);
                    connectionInfoReceived.set(false); // Reset this flag too
                    peerCandidates.clear();
                    pingAttempts = 0;
                 } else {
                     // UDP path ok, but key exchange failed earlier or is pending? Unlikely state.
                     // Move to intermediate state just in case.
                     currentState = NodeState.EXCHANGING_KEYS;
                     System.out.println("\n[*] UDP Path established, but waiting for or failed key exchange with " + getPeerDisplayName() + "...");
                 }
            }
             // Event: If somehow UDP path confirmed AFTER key established (maybe pong received late)
            else if (currentState == NodeState.EXCHANGING_KEYS && p2pUdpPathConfirmed.get() && sharedKeyEstablished.get()){
                 // This path might handle the race condition observed before
                 currentState = NodeState.CONNECTED_SECURE; // Move to secure state
                 InetSocketAddress confirmedAddr = peerAddrConfirmed.get();
                 System.out.println("\n-----------------------------------------------------");
                 System.out.println("[+] SECURE E2EE CONNECTION ESTABLISHED with " + getPeerDisplayName() + "!");
                 System.out.println("    Using path: YOU -> " + (confirmedAddr != null ? confirmedAddr : "???"));
                 System.out.println("-----------------------------------------------------");
                 if (peerId != null) {
                     chatHistoryManager = new ChatHistoryManager();
                     chatHistoryManager.initialize(myNodeId, peerId, connectedPeerUsername.get());
                 } else {
                     System.err.println("[!] Cannot initialize history: Peer Node ID is null upon secure connection!");
                     chatHistoryManager = null;
                 }
                 targetPeerId.set(null);
                 connectionInfoReceived.set(false);
                 peerCandidates.clear();
                 pingAttempts = 0;
            }

            // Event: Connection lost (UDP path lost OR shared key lost AFTER connection)
            // Check UDP path loss primarily for intermediate states, key loss for secure state
            else if ( (currentState == NodeState.ATTEMPTING_UDP || currentState == NodeState.EXCHANGING_KEYS) && !p2pUdpPathConfirmed.get() && peerAddrConfirmed.get() != null) {
                 // If we had confirmed an address but lost UDP path during setup
                 System.out.println("\n[*] UDP connection with " + getPeerDisplayName() + " lost during setup.");
                 resetConnectionState();
                 currentState = NodeState.DISCONNECTED;
            } else if ( currentState == NodeState.CONNECTED_SECURE && (!p2pUdpPathConfirmed.get() || !sharedKeyEstablished.get()) ) {
                 // If secure connection established, but UDP path flag resets OR key flag resets (shouldn't happen unless explicit disconnect)
                 // OR if we receive an error indicating loss (e.g. Port Unreachable - TODO: Signal this)
                 System.out.println("\n[*] Secure connection with " + getPeerDisplayName() + " lost or became insecure.");
                 resetConnectionState();
                 currentState = NodeState.DISCONNECTED;
            }
             // Add cleanup for EXCHANGING_KEYS if UDP path confirmed but key is NOT established after a while? Low priority.

        } // End synchronized block

        if (currentState != previousState) {
            System.out.println("[State Change] " + previousState + " -> " + currentState);
            lastStatePrintTime = 0;
            // Avoid printing prompt if state changed non-interactively to prevent duplicates
            // System.out.print(getPrompt()); // REMOVE this, let main loop handle prompt
        }
    }

     /** Gets a description of the current node state. */
    private static String getStateDescription() {
        String fullPeerId = connectedPeerId.get(); // Try connected first
        if (fullPeerId == null) fullPeerId = targetPeerId.get(); // Fallback to target
        String peerDisplay = getPeerDisplayName(); // Use helper for username/short ID

        switch (currentState) {
            // Show full Node ID in disconnected state description
            case DISCONNECTED: return "[Status] Disconnected. Your Node ID: " + myNodeId + " | Username: " + myUsername;
            case WAITING_MATCH:
                long elapsed = (System.currentTimeMillis() - waitingSince) / 1000;
                // Show full Peer ID in status message
                return "[Status] Request sent. Waiting for peer " + (fullPeerId != null ? fullPeerId : peerDisplay) + " (" + elapsed + "s / " + WAIT_MATCH_TIMEOUT_MS/1000 + "s)";
            case ATTEMPTING_UDP:
                // Show full Peer ID in status message
                return "[Status] Attempting UDP P2P connection to " + (fullPeerId != null ? fullPeerId : peerDisplay) + " (Ping cycle: " + pingAttempts + "/" + MAX_PING_ATTEMPTS + ")";
            case EXCHANGING_KEYS:
                 // Show peer username/short ID is fine here
                 return "[Status] UDP path OK. Establishing secure E2EE session with " + peerDisplay + "...";
            case CONNECTED_SECURE:
                InetSocketAddress addr = peerAddrConfirmed.get();
                 // Show peer username/short ID is fine here
                return "[Status] 🔒 E2EE Connected to " + peerDisplay + " (" + (addr != null ? addr : "???") + ")";
            default: return "[Status] Unknown State";
        }
    }

    /** Gets the appropriate command prompt based on the current state. */
    private static String getPrompt() {
        String peerName = getPeerDisplayName(); // Use helper (Username > Short ID)
        switch (currentState) {
            case DISCONNECTED: return "[?] Enter 'connect <peer_id>', 'status', 'id', 'quit': ";
            // Use Username/Short ID for prompt brevity
            case WAITING_MATCH: return "[Waiting:" + peerName + " ('cancel'/'disconnect')] ";
            case ATTEMPTING_UDP: return "[Pinging:" + peerName + " ('cancel'/'disconnect')] ";
            case EXCHANGING_KEYS: return "[Securing:" + peerName + " ('cancel'/'disconnect')] ";
            case CONNECTED_SECURE: return "[Chat 🔒 " + peerName + "] ('chat <msg>', 'disconnect', 'status', 'quit'): ";
            default: return "> ";
        }
    }

    /** Gets the display name for the peer (Username or ID prefix). Stays the same. */
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


    /** Initiates a connection attempt to the specified peer ID. */
    private static void startConnectionAttempt(String peerToConnect) {
        synchronized (stateLock) {
            if (currentState != NodeState.DISCONNECTED) {
                System.out.println("[!] Cannot start new connection attempt while in state: " + currentState); return;
            }
             if (myKeyPair == null) {
                System.err.println("[!] Cannot start connection: KeyPair missing."); return;
            }
            // Show full peer ID when starting connection
            System.out.println("[*] Requesting connection info for peer: " + peerToConnect);
            resetConnectionState();

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

    /** Resets all variables related to an active or pending connection attempt. */
    private static void resetConnectionState() {
        synchronized (stateLock) {
            String peerId = connectedPeerId.get();
            if(peerId == null) peerId = targetPeerId.get(); // Check target too

            if(peerId != null) {
                 peerSymmetricKeys.remove(peerId);
                 // Show full ID when clearing key
                 System.out.println("[*] Cleared session key for peer " + peerId);
            }

            targetPeerId.set(null);
            connectedPeerId.set(null);
            connectedPeerUsername.set(null);
            peerCandidates.clear();
            peerAddrConfirmed.set(null);
            connectionInfoReceived.set(false);
            p2pUdpPathConfirmed.set(false); // Reset UDP flag
            sharedKeyEstablished.set(false);
            pingAttempts = 0;
            waitingSince = 0;

            if (chatHistoryManager != null) {
                System.out.println("[*] Chat history logging stopped.");
                chatHistoryManager = null;
            }
            System.out.println("[*] Reset connection and cryptographic state variables.");
        }
    }


    // --- Message Handling ---

    /** Handles messages received from the Coordination Server. */
    private static void handleServerMessage(JSONObject data) {
        String status = data.optString("status", null);
        String action = data.optString("action", null);

        if ("registered".equals(status) && myNodeId == null) {
            String receivedNodeId = data.optString("node_id");
            if (receivedNodeId != null && !receivedNodeId.isEmpty()) {
                myNodeId = receivedNodeId; // Store full ID
                JSONObject publicEpJson = data.optJSONObject("your_public_endpoint");
                if(publicEpJson != null) {
                    myPublicEndpointSeen = Endpoint.fromJson(publicEpJson);
                     System.out.println("[*] Server recorded public endpoint: " + myPublicEndpointSeen);
                }
            } else System.err.println("[!] Received 'registered' status from server but missing 'node_id'.");
        }
        else if ("connection_info".equals(action) && "match_found".equals(status)) {
            String receivedPeerId = data.optString("peer_id");
            String currentTarget = targetPeerId.get();

            if (currentTarget != null && currentTarget.equals(receivedPeerId)) {
                JSONArray candidatesJson = data.optJSONArray("peer_endpoints");
                String peerUsername = data.optString("peer_username", null);
                String peerPublicKeyBase64 = data.optString("peer_public_key", null);

                 if (candidatesJson == null || peerPublicKeyBase64 == null || peerPublicKeyBase64.isEmpty()) {
                     System.err.println("[!] Received incomplete 'connection_info' from server (missing endpoints or public key). Cancelling.");
                     resetConnectionState();
                     currentState = NodeState.DISCONNECTED;
                     return;
                 }

                synchronized(stateLock) {
                    if(currentState != NodeState.WAITING_MATCH) {
                         System.out.println("[?] Received connection_info while not in WAITING_MATCH state. Ignoring.");
                         return;
                    }

                    // Generate Shared Key FIRST
                    boolean keyGenSuccess = generateSharedKeyAndStore(receivedPeerId, peerPublicKeyBase64);
                    if (!keyGenSuccess) {
                         // Show full peer ID on failure
                         System.err.println("[!] Failed to establish shared secret with peer " + receivedPeerId + ". Cancelling connection.");
                         resetConnectionState();
                         currentState = NodeState.DISCONNECTED;
                         return;
                    }

                    // Store peer username
                    if (peerUsername != null && !peerUsername.isEmpty()) connectedPeerUsername.set(peerUsername);
                    else { connectedPeerUsername.set(null); System.out.println("[!] Peer username not provided by server."); }
                    System.out.println("[*] Received Peer Username: " + connectedPeerUsername.get());

                    // Store peer candidates
                    peerCandidates.clear();
                    // Show full peer ID here
                    System.out.println("[*] Received Peer Candidates from server for " + receivedPeerId + ":");
                    int validCandidates = 0;
                    for (int i = 0; i < candidatesJson.length(); i++) {
                        JSONObject epJson = candidatesJson.optJSONObject(i);
                        if (epJson != null) {
                            Endpoint ep = Endpoint.fromJson(epJson);
                            if (ep != null) { peerCandidates.add(ep); System.out.println("    - " + ep); validCandidates++; }
                        }
                    }
                    if (validCandidates > 0) connectionInfoReceived.set(true); // Signal OK
                    else {
                        // Show full peer ID here
                        System.out.println("[!] Received empty or invalid candidate list for peer " + receivedPeerId + "...");
                        resetConnectionState();
                        currentState = NodeState.DISCONNECTED;
                    }
                } // End synchronized block
            } else System.out.println("[?] Received connection_info for unexpected peer " + receivedPeerId + ". Ignoring.");
        }
         else if ("error".equals(status)) {
            String message = data.optString("message", "Unknown error");
            System.err.println("\n[!] Server Error: " + message);
            synchronized(stateLock) {
                if (currentState == NodeState.WAITING_MATCH || currentState == NodeState.ATTEMPTING_UDP || currentState == NodeState.EXCHANGING_KEYS) {
                    System.out.println("    Cancelling connection attempt due to server error.");
                    resetConnectionState();
                    currentState = NodeState.DISCONNECTED;
                }
            }
         }
         else if ("connection_request_received".equals(status) && "ack".equals(action)) {
             String waitingFor = data.optString("waiting_for", "?");
             if (currentState == NodeState.WAITING_MATCH && waitingFor.equals(targetPeerId.get())) {
                 // Show username/short ID is fine here
                 System.out.println("[*] Server acknowledged request. Waiting for peer " + getPeerDisplayName() + " to connect.");
             }
         }
    }

    /** Handles messages received directly from other Peers. */
    private static void handlePeerMessage(JSONObject data, InetSocketAddress peerAddr) {
        String action = data.optString("action", null);
        String senderId = data.optString("node_id", null);

        if (senderId == null || action == null) return; // Ignore invalid

        String currentTarget = targetPeerId.get();
        String currentPeer = connectedPeerId.get();
        NodeState stateNow = currentState;

        // --- Processing During UDP Connection Attempt ---
        if (stateNow == NodeState.ATTEMPTING_UDP && currentTarget != null && currentTarget.equals(senderId)) {
             boolean senderIsCandidate = peerCandidates.stream().anyMatch(cand -> {
                 try { return new InetSocketAddress(InetAddress.getByName(cand.ip), cand.port).equals(peerAddr); }
                 catch (UnknownHostException e) { return false; }
             });

            if (senderIsCandidate && (action.equals("ping") || action.equals("pong"))) {
                 synchronized(stateLock) {
                     if (currentState != NodeState.ATTEMPTING_UDP) return; // Re-check

                     if (peerAddrConfirmed.compareAndSet(null, peerAddr)) {
                         // Show full sender ID on confirmation
                         System.out.println("\n[*] CONFIRMED receiving directly from Peer " + senderId + " at " + peerAddr + "!");
                         connectedPeerId.set(senderId);
                     }

                     if (p2pUdpPathConfirmed.compareAndSet(false, true)) { // Set UDP path flag
                          System.out.println("[+] P2P UDP Path Confirmed (Received " + action.toUpperCase() + ")!");
                          // State transition happens in main loop check
                     }

                     if ("ping".equals(action)) {
                         JSONObject pongMsg = new JSONObject();
                         pongMsg.put("action", "pong");
                         pongMsg.put("node_id", myNodeId);
                         sendUdp(pongMsg, peerAddr);
                     }
                 } // End synchronized block
            }
        }
        // --- Processing During Key Exchange ---
        else if (stateNow == NodeState.EXCHANGING_KEYS && currentPeer != null && currentPeer.equals(senderId)) {
             InetSocketAddress confirmedAddr = peerAddrConfirmed.get();
             if (confirmedAddr != null && peerAddr.equals(confirmedAddr)) {
                 // Still respond to pings/pongs to keep path alive
                 if ("ping".equals(action)) {
                    JSONObject pongMsg = new JSONObject();
                    pongMsg.put("action", "pong");
                    pongMsg.put("node_id", myNodeId);
                    sendUdp(pongMsg, peerAddr);
                 }
                 if ("pong".equals(action)) {
                     // Receiving pong reinforces path is good, set flag if not already set
                     if (p2pUdpPathConfirmed.compareAndSet(false, true)) {
                         System.out.println("[+] P2P UDP Path Confirmed (Received PONG during key exchange)!");
                     }
                 }
             }
        }
        // --- Processing After Secure Connection Established ---
        else if (stateNow == NodeState.CONNECTED_SECURE && currentPeer != null && currentPeer.equals(senderId)) {
            InetSocketAddress confirmedAddr = peerAddrConfirmed.get();
            SecretKey sharedKey = peerSymmetricKeys.get(currentPeer);

            if (confirmedAddr == null || !peerAddr.equals(confirmedAddr)) {
                System.out.println("[?] WARNING: Received message from connected peer " + getPeerDisplayName() + " but from wrong address " + peerAddr + ". IGNORING.");
                return;
            }
            if (sharedKey == null) {
                 System.err.println("[!] CRITICAL: No shared key found for connected peer " + getPeerDisplayName() + ". Disconnecting.");
                 resetConnectionState();
                 currentState = NodeState.DISCONNECTED;
                 return;
            }

            switch (action) {
                case "e_chat": {
                    EncryptedPayload payload = EncryptedPayload.fromJson(data);
                    if (payload == null) {
                        System.err.println("[!] Received invalid encrypted chat payload from " + getPeerDisplayName());
                        break;
                    }
                    try {
                        String decryptedMessage = CryptoUtils.decrypt(payload, sharedKey);

                        // FIX: Print message cleanly without reprinting prompt from listener
                        System.out.print("\r[" + getPeerDisplayName() + "]: " + decryptedMessage + "\n");

                        if (chatHistoryManager != null) {
                            long timestamp = System.currentTimeMillis();
                            chatHistoryManager.addMessage(timestamp, senderId, connectedPeerUsername.get(), "RECEIVED", decryptedMessage);
                        }

                    } catch (GeneralSecurityException e) {
                         System.err.println("[!] Failed to decrypt message from " + getPeerDisplayName() + ". " + e.getMessage());
                    } catch (Exception e) {
                        System.err.println("[!] Error handling decrypted message from " + getPeerDisplayName() + ": " + e.getMessage());
                    }
                    break;
                } // end case "e_chat"

                case "keepalive": break; // Ignore received keepalives for now
                case "ping": // Still respond to pings
                    JSONObject pongMsg = new JSONObject();
                    pongMsg.put("action", "pong");
                    pongMsg.put("node_id", myNodeId);
                    sendUdp(pongMsg, peerAddr);
                    break;
                case "pong": break; // Ignore pongs when connected
                default:
                    System.out.println("[?] Received unknown action '" + action + "' from connected peer " + getPeerDisplayName());
                    break;
            }
        }
    }


    // --- Sending Messages (sendUdp remains unchanged) ---
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
            if (currentState == NodeState.CONNECTED_SECURE && destination.equals(peerAddrConfirmed.get())) {
                 System.out.println("[!] Connection possibly lost with " + getPeerDisplayName() + " (Port unreachable).");
                 // Trigger disconnect more explicitly maybe? Set UDP path confirmed to false?
                 // p2pUdpPathConfirmed.set(false); // This might trigger the disconnect in checkStateTransitions
            }
            return false;
        }
        catch (IOException e) { System.err.println("[!] IO Error sending UDP to " + destination + ": " + e.getMessage()); return false;
        } catch (Exception e) { System.err.println("[!] Unexpected Error sending UDP to " + destination + ": " + e.getClass().getName() + " - " + e.getMessage()); return false; }
    }

    /** Encrypts and sends a chat message to the currently connected peer. */
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
                chatMsg.put("e_payload", payload.ciphertextBase64);

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

    // --- Cryptography Helpers ---
    /** Generates shared secret and derives/stores symmetric key */
    private static boolean generateSharedKeyAndStore(String peerId, String peerPublicKeyBase64) {
        if (myKeyPair == null) { System.err.println("[!] Cannot generate shared key: Own keypair is missing."); return false; }
        if (peerId == null || peerPublicKeyBase64 == null) { System.err.println("[!] Cannot generate shared key: Peer info missing."); return false; }

        try {
            PublicKey peerPublicKey = CryptoUtils.decodePublicKey(peerPublicKeyBase64);
            // Show full peer ID here
            System.out.println("[*] Performing ECDH key agreement with peer " + peerId + "...");
            byte[] sharedSecretBytes = CryptoUtils.generateSharedSecret(myKeyPair.getPrivate(), peerPublicKey);

            System.out.println("[*] Deriving AES symmetric key from shared secret...");
            SecretKey derivedKey = CryptoUtils.deriveSymmetricKey(sharedSecretBytes);

            peerSymmetricKeys.put(peerId, derivedKey);
            sharedKeyEstablished.set(true);
            // Show full peer ID here
            System.out.println("[+] Shared symmetric key established successfully for peer " + peerId + "!");
            return true;

        } catch (NoSuchAlgorithmException  | InvalidKeyException e) {
             System.err.println("[!] Cryptographic error during key exchange: " + e.getMessage());
        } catch (Exception e) {
             System.err.println("[!] Unexpected error during shared key generation: " + e.getMessage());
             e.printStackTrace();
        }
        sharedKeyEstablished.set(false);
        return false;
    }


    // --- Shutdown (Remains unchanged) ---
    private static synchronized void shutdown() {
        if (!running) return;
        System.out.println("\n[*] Shutting down node...");
        running = false;

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