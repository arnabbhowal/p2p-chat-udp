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
// Removed unused import: import java.util.stream.Collectors;


public class P2PNode {

    // --- Configuration ---
    private static String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 19999;
    private static final int BUFFER_SIZE = 4096; // Ensure buffer can hold encrypted data + overhead
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

    // --- Cryptography State --- NEW
    private static KeyPair myKeyPair = null; // Our own ECDH key pair
    private static final ConcurrentHashMap<String, SecretKey> peerSymmetricKeys = new ConcurrentHashMap<>(); // Map PeerID -> Shared AES Key

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
    private static final AtomicBoolean connectionInfoReceived = new AtomicBoolean(false);
    private static final AtomicBoolean p2pConnectionEstablished = new AtomicBoolean(false);
    private static final AtomicBoolean sharedKeyEstablished = new AtomicBoolean(false); // <-- NEW flag for E2EE setup
    private static final Object stateLock = new Object();

    // --- State Machine ---
    // Added state for key exchange phase
    private enum NodeState { DISCONNECTED, WAITING_MATCH, ATTEMPTING_UDP, EXCHANGING_KEYS, CONNECTED_SECURE } // <-- UPDATED States
    private static volatile NodeState currentState = NodeState.DISCONNECTED;
    private static long waitingSince = 0;
    private static int pingAttempts = 0;
    private static long lastStatePrintTime = 0;

    public static void main(String[] args) {
        // Override Server IP if provided as command line argument
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
            // --- Generate Key Pair --- NEW
            System.out.println("[*] Generating Elliptic Curve key pair...");
            try {
                myKeyPair = CryptoUtils.generateECKeyPair();
                System.out.println("[+] Key pair generated successfully.");
                 // Optional: Print public key for debugging (remove in production)
                 // System.out.println("    Public Key (Base64): " + CryptoUtils.encodePublicKey(myKeyPair.getPublic()));
            } catch (NoSuchAlgorithmException e) {
                System.err.println("[!!!] FATAL: Failed to generate key pair. Cryptography provider not supported: " + e.getMessage());
                return; // Cannot proceed without keys
            }
            // --- End Key Pair Generation ---


            System.out.println("[*] Resolving server address: " + SERVER_IP + ":" + SERVER_PORT + "...");
            serverAddress = new InetSocketAddress(InetAddress.getByName(SERVER_IP), SERVER_PORT);
            System.out.println("[*] Server address resolved to: " + serverAddress);

            udpSocket = new DatagramSocket(LOCAL_UDP_PORT);
            udpSocket.setReuseAddress(true);
            int actualLocalPort = udpSocket.getLocalPort();
            System.out.println("[*] UDP Socket bound to local address: " + udpSocket.getLocalAddress().getHostAddress() + ":" + actualLocalPort);

            // --- Prompt for Username ---
            System.out.print("[?] Enter your desired username: ");
            String inputName = "";
             // Use loop similar to original code for robustness
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

            // Discover local endpoints
            myLocalEndpoints = getLocalNetworkEndpoints(actualLocalPort);
            System.out.println("[*] Discovered Local Endpoints:");
            if(myLocalEndpoints.isEmpty()) System.out.println("    <None found>");
            else myLocalEndpoints.forEach(ep -> System.out.println("    - " + ep));

            Runtime.getRuntime().addShutdownHook(new Thread(P2PNode::shutdown));
            listenerExecutor.submit(P2PNode::udpListenerLoop);

            // Register with Server - includes Public Key now
            if (!registerWithServer()) {
                System.err.println("[!!!] Failed to register with server. Exiting.");
                shutdown();
                return;
            }
            System.out.println("[+] Successfully registered with server. Node ID: " + myNodeId.substring(0,8) + "...");

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
        // System.out.println("[*] Discovering network interfaces..."); // Less noisy
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                try {
                    // Skip loopback, virtual, P2P, and down interfaces.
                    if (ni.isLoopback() || !ni.isUp() || ni.isVirtual() || ni.isPointToPoint()) {
                        continue;
                    }

                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        // Skip link-local, multicast, wildcard, loopback any local address
                        if (addr.isLinkLocalAddress() || addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                            continue;
                        }

                        String ip = addr.getHostAddress();
                        String type;
                        if (addr instanceof Inet6Address) {
                            int scopeIndex = ip.indexOf('%');
                            if (scopeIndex > 0) {
                                ip = ip.substring(0, scopeIndex);
                            }
                            if (ip.startsWith("::ffff:")) continue; // Skip IPv4 mapped
                            type = "local_v6";
                        } else if (addr instanceof Inet4Address) {
                            type = "local_v4";
                        } else {
                            continue;
                        }
                        endpoints.add(new Endpoint(ip, udpPort, type));
                    }
                } catch (SocketException se) { /* Ignore interfaces that error */ }
            }
        } catch (SocketException e) {
            System.err.println("[!] Error getting network interfaces: " + e.getMessage());
        }

        if (endpoints.isEmpty()) {
            System.out.println("[!] No suitable local non-loopback IPs found. Will rely on public IP seen by server.");
        }
        // Deduplicate
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
        registerMsg.put("public_key", CryptoUtils.encodePublicKey(myKeyPair.getPublic())); // <-- Send public key
        JSONArray localEndpointsJson = new JSONArray();
        myLocalEndpoints.forEach(ep -> localEndpointsJson.put(ep.toJson()));
        registerMsg.put("local_endpoints", localEndpointsJson);

        System.out.println("[*] Sending registration (with public key) to server " + serverAddress + "...");
        boolean sent = sendUdp(registerMsg, serverAddress);
        if (!sent) {
            System.err.println("[!] Failed to send registration message.");
            return false;
        }

        // Simple wait for registration confirmation (handled in listener)
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
                 if (messageStr.isEmpty()) continue; // Ignore empty

                JSONObject data;
                 try {
                     data = new JSONObject(messageStr);
                 } catch (org.json.JSONException e) {
                     System.err.println("[Listener] Invalid JSON received from " + senderAddr + ". Content: " + messageStr.substring(0, Math.min(100, messageStr.length())) + "..."); // Log snippet
                     continue; // Ignore malformed JSON
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
            } catch (Exception e) { // Catch broader exceptions during handling
                if (running) {
                    System.err.println("[Listener Thread] Error processing UDP packet from " + ((packet!=null && packet.getSocketAddress()!=null)?packet.getSocketAddress():"unknown") + ": " + e.getClass().getName() + " - " + e.getMessage());
                    e.printStackTrace(); // Log stack trace for unexpected errors
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
                NodeState stateNow = currentState; // Capture current state

                // --- P2P Pinging / Hole Punching (Only during ATTEMPTING_UDP) ---
                if (stateNow == NodeState.ATTEMPTING_UDP && currentTarget != null && !peerCandidates.isEmpty() && !p2pConnectionEstablished.get()) {
                    if (pingAttempts < MAX_PING_ATTEMPTS) {
                        JSONObject pingMsg = new JSONObject();
                        pingMsg.put("action", "ping");
                        pingMsg.put("node_id", myNodeId);

                        List<Endpoint> currentCandidates = new ArrayList<>(peerCandidates);
                        // Prioritize public endpoints
                        currentCandidates.sort(Comparator.comparing(ep -> (ep.type != null && ep.type.contains("public")) ? 0 : 1));

                        boolean sentPingThisRound = false;
                        for (Endpoint candidate : currentCandidates) {
                            try {
                                InetSocketAddress candidateAddr = new InetSocketAddress(InetAddress.getByName(candidate.ip), candidate.port);
                                if(sendUdp(pingMsg, candidateAddr)) sentPingThisRound = true;
                            } catch (UnknownHostException e) {
                                // System.err.println("[Manager] Failed to resolve candidate address: " + candidate.ip); // Less noisy
                            } catch(Exception e){ /* Ignore send errors during ping */ }
                        }
                        if(sentPingThisRound) {
                            synchronized(stateLock) {
                                if(currentState == NodeState.ATTEMPTING_UDP) pingAttempts++;
                            }
                        }
                    }
                }
                // --- Peer Keep-Alive (if connected AND key established) ---
                else if (stateNow == NodeState.CONNECTED_SECURE && confirmedPeer != null) {
                    // Send simple unencrypted keepalive for now. Could encrypt later if needed.
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
            System.out.println("\n--- P2P Node Ready (Username: " + myUsername + ", Node ID: " + myNodeId.substring(0,8) + "...) ---");
            System.out.println("--- Enter 'connect <peer_id>', 'status', or 'quit' ---");

            while (running) {
                try {
                    // --- Check State Transitions ---
                    checkStateTransitions();

                    // --- Print Current State Periodically or Handle Input ---
                    long now = Instant.now().toEpochMilli();
                    String prompt = getPrompt();
                    NodeState stateNow = currentState; // Capture state for this iteration

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
                            resetConnectionState(); // Resets crypto state too
                            currentState = NodeState.DISCONNECTED;
                            System.out.print(getPrompt()); // Reprint final prompt
                            continue;
                        }
                        if (stateNow == NodeState.ATTEMPTING_UDP && (pingAttempts >= MAX_PING_ATTEMPTS) && !p2pConnectionEstablished.get()) {
                            System.out.println("\n[!] Failed to establish UDP connection with peer " + getPeerDisplayName() + " after " + MAX_PING_ATTEMPTS + " ping cycles.");
                            resetConnectionState();
                            currentState = NodeState.DISCONNECTED;
                             System.out.print(getPrompt());
                            continue;
                         }
                          // Add timeout for key exchange? (e.g., if peer never sends PONG or message after UDP established)
                          // This is implicitly handled now by lack of transition to CONNECTED_SECURE

                        // Sleep briefly if no input pending
                        if (System.in.available() <= 0) {
                           try { Thread.sleep(200); } catch (InterruptedException e) { running = false; break; }
                            continue; // Re-check state/timeouts
                        }
                    } else {
                        // Not in an intermediate state, just print prompt
                        System.out.print(prompt);
                    }

                    // --- Handle User Input ---
                    if (!scanner.hasNextLine()) {
                        System.out.println("\n[*] Input stream closed. Exiting.");
                        running = false;
                        break;
                     }
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty()) continue;

                    // Re-check state *after* getting input, before processing command
                    checkStateTransitions();
                    NodeState stateBeforeCommand = currentState;

                    String[] parts = line.split(" ", 2);
                    String command = parts[0].toLowerCase();

                    // If state changed while user was typing, make them retry
                    if(stateBeforeCommand != currentState) {
                        System.out.println("\n[*] State changed during input, please check status and retry.");
                        continue;
                    }

                    switch (command) {
                        case "quit":
                        case "exit":
                            System.out.println("[*] Quit command received. Initiating shutdown...");
                            shutdown();
                            System.exit(0);
                            break; // Unreachable
                        case "connect":
                            if (stateBeforeCommand == NodeState.DISCONNECTED) {
                                if (parts.length > 1 && !parts[1].isEmpty()) {
                                    String peerToConnect = parts[1].trim();
                                    if(peerToConnect.equals(myNodeId)){
                                        System.out.println("[!] Cannot connect to yourself.");
                                    } else if (peerToConnect.length() < 5) {
                                        System.out.println("[!] Invalid Peer ID format.");
                                    } else {
                                        startConnectionAttempt(peerToConnect);
                                    }
                                } else {
                                    System.out.println("[!] Usage: connect <peer_node_id>");
                                }
                            } else {
                                System.out.println("[!] Already connecting or connected. Disconnect first ('disconnect').");
                            }
                            break;
                        case "disconnect":
                        case "cancel":
                            if (stateBeforeCommand != NodeState.DISCONNECTED) {
                                System.out.println("[*] Disconnecting/Cancelling connection with " + getPeerDisplayName() + "...");
                                resetConnectionState();
                                currentState = NodeState.DISCONNECTED;
                            } else {
                                System.out.println("[!] Not currently connected or attempting connection.");
                            }
                            break;
                        case "chat":
                        case "c": // Alias for chat
                            if (stateBeforeCommand == NodeState.CONNECTED_SECURE) { // Only allow chat when securely connected
                                if (parts.length > 1 && !parts[1].isEmpty()) {
                                    sendMessageToPeer(parts[1]); // Sends encrypted message now
                                } else {
                                    System.out.println("[!] Usage: chat <message>");
                                }
                            } else if (stateBeforeCommand == NodeState.EXCHANGING_KEYS || stateBeforeCommand == NodeState.ATTEMPTING_UDP) {
                                 System.out.println("[!] Waiting for secure connection to be established...");
                            }
                            else {
                                System.out.println("[!] Not connected to a peer. Use 'connect <peer_id>' first.");
                            }
                            break;
                        case "status":
                        case "s": // Alias
                            System.out.println(getStateDescription());
                            break;
                        case "id":
                            System.out.println("[*] Your Username: " + myUsername);
                            System.out.println("[*] Your Node ID: " + myNodeId);
                            break;
                        default:
                             // Provide specific help when connected securely
                             if(stateBeforeCommand == NodeState.CONNECTED_SECURE) {
                                 System.out.println("[!] Unknown command. Available: chat, disconnect, status, quit");
                             } else {
                                 System.out.println("[!] Unknown command. Available: connect, status, id, quit");
                             }
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
            }
        } finally {
             // Avoid closing System.in scanner
        }
        System.out.println("[*] User Interaction loop finished.");
    }


    // --- State Management & Transitions ---

    private static void checkStateTransitions() {
        NodeState previousState = currentState;
        String peerId = connectedPeerId.get(); // Get potentially connected peer

        synchronized (stateLock) {
            // Event: Received connection info from server
            if (currentState == NodeState.WAITING_MATCH && connectionInfoReceived.compareAndSet(true, false)) {
                if (!peerCandidates.isEmpty()) {
                    currentState = NodeState.ATTEMPTING_UDP;
                    pingAttempts = 0; // Reset ping counter
                    System.out.println("\n[*] Received peer info for " + getPeerDisplayName() +". Attempting UDP P2P connection...");
                } else {
                    System.out.println("\n[!] Received connection info response, but peer candidate list was empty. Cancelling.");
                    resetConnectionState();
                    currentState = NodeState.DISCONNECTED;
                }
            }
            // Event: UDP path established (ping/pong worked)
            else if (currentState == NodeState.ATTEMPTING_UDP && p2pConnectionEstablished.get()) {
                 // Now we need to establish the shared key
                 currentState = NodeState.EXCHANGING_KEYS; // Move to key exchange state
                 System.out.println("\n[*] UDP Path established. Attempting secure key exchange with " + getPeerDisplayName() + "...");
                 // Key exchange initiated by handleServerMessage storing peer public key
                 // and then generateSharedKeyAndStore being called.
                 // We might need to trigger sending a PONG again here to ensure peer has our address if they PINGed first.
                 InetSocketAddress confirmedAddr = peerAddrConfirmed.get();
                 if(confirmedAddr != null) {
                    JSONObject pongMsg = new JSONObject();
                    pongMsg.put("action", "pong");
                    pongMsg.put("node_id", myNodeId);
                    sendUdp(pongMsg, confirmedAddr); // Send initial PONG to confirm path both ways maybe
                 }
            }
            // Event: Shared symmetric key established
            else if (currentState == NodeState.EXCHANGING_KEYS && sharedKeyEstablished.get()) {
                 currentState = NodeState.CONNECTED_SECURE;
                 InetSocketAddress confirmedAddr = peerAddrConfirmed.get();
                 System.out.println("\n-----------------------------------------------------");
                 System.out.println("[+] SECURE E2EE CONNECTION ESTABLISHED with " + getPeerDisplayName() + "!");
                 System.out.println("    Using path: YOU -> " + (confirmedAddr != null ? confirmedAddr : "???"));
                 System.out.println("-----------------------------------------------------");

                 // Initialize History Manager (only once fully connected)
                 if (peerId != null) {
                     chatHistoryManager = new ChatHistoryManager();
                     chatHistoryManager.initialize(myNodeId, peerId, connectedPeerUsername.get()); // Pass peer details
                 } else {
                     System.err.println("[!] Cannot initialize history: Peer Node ID is null upon connection!");
                     chatHistoryManager = null;
                 }
                 targetPeerId.set(null); // Clear the target
                 connectionInfoReceived.set(false);
                 peerCandidates.clear();
                 pingAttempts = 0;
            }
            // Event: Connection lost (UDP or crypto state becomes invalid)
            else if ((currentState == NodeState.CONNECTED_SECURE || currentState == NodeState.EXCHANGING_KEYS || currentState == NodeState.ATTEMPTING_UDP) && (!p2pConnectionEstablished.get() || (currentState == NodeState.CONNECTED_SECURE && !sharedKeyEstablished.get())) ) {
                 System.out.println("\n[*] Connection with " + getPeerDisplayName() + " lost or became insecure.");
                 resetConnectionState();
                 currentState = NodeState.DISCONNECTED;
            }
        } // End synchronized block

        if (currentState != previousState) {
            System.out.println("[State Change] " + previousState + " -> " + currentState);
            lastStatePrintTime = 0; // Reset print timer on state change
            // Print prompt again if state changed non-interactively
            System.out.print(getPrompt());
        }
    }

     /** Gets a description of the current node state. */
    private static String getStateDescription() {
        switch (currentState) {
            case DISCONNECTED: return "[Status] Disconnected. Your Node ID: " + (myNodeId != null ? myNodeId.substring(0,8) + "..." : "N/A") + " | Username: " + myUsername;
            case WAITING_MATCH:
                long elapsed = (System.currentTimeMillis() - waitingSince) / 1000;
                return "[Status] Request sent. Waiting for peer " + getPeerDisplayName() + " (" + elapsed + "s / " + WAIT_MATCH_TIMEOUT_MS/1000 + "s)";
            case ATTEMPTING_UDP:
                return "[Status] Attempting UDP P2P connection to " + getPeerDisplayName() + " (Ping cycle: " + pingAttempts + "/" + MAX_PING_ATTEMPTS + ")";
            case EXCHANGING_KEYS:
                 return "[Status] UDP path OK. Establishing secure E2EE session with " + getPeerDisplayName() + "...";
            case CONNECTED_SECURE:
                InetSocketAddress addr = peerAddrConfirmed.get();
                 // Add lock icon for secure state
                return "[Status] 🔒 E2EE Connected to " + getPeerDisplayName() + " (" + (addr != null ? addr : "???") + ")";
            default: return "[Status] Unknown State";
        }
    }

    /** Gets the appropriate command prompt based on the current state. */
    private static String getPrompt() {
        String peerName = getPeerDisplayName();
        switch (currentState) {
            case DISCONNECTED: return "[?] Enter 'connect <peer_id>', 'status', 'id', 'quit': ";
            case WAITING_MATCH: return "[Waiting:" + peerName + " ('cancel'/'disconnect')] ";
            case ATTEMPTING_UDP: return "[Pinging:" + peerName + " ('cancel'/'disconnect')] ";
            case EXCHANGING_KEYS: return "[Securing:" + peerName + " ('cancel'/'disconnect')] ";
            case CONNECTED_SECURE: return "[Chat 🔒 " + peerName + "] ('chat <msg>', 'disconnect', 'status', 'quit'): "; // Add lock icon
            default: return "> ";
        }
    }

    /** Gets the display name for the peer (Username or ID prefix). */
    private static String getPeerDisplayName() {
        // Prioritize connected peer info
        String peerId = connectedPeerId.get();
        String username = connectedPeerUsername.get();
        if (peerId != null) { // If we have a confirmed connected peer
            if (username != null && !username.isEmpty()) {
                return username; // Use username if available
            }
            // Fallback to ID prefix if username not set for connected peer
            return peerId.substring(0, Math.min(8, peerId.length())) + "...";
        }

        // If not connected, use target peer info if available
        String targetId = targetPeerId.get();
        if (targetId != null) {
            return targetId.substring(0, Math.min(8, targetId.length())) + "...";
        }

        // Default fallback
        return "???";
    }


    /** Initiates a connection attempt to the specified peer ID. */
    private static void startConnectionAttempt(String peerToConnect) {
        synchronized (stateLock) {
            if (currentState != NodeState.DISCONNECTED) {
                System.out.println("[!] Cannot start new connection attempt while in state: " + currentState);
                return;
            }
             if (myKeyPair == null) {
                System.err.println("[!] Cannot start connection: KeyPair missing.");
                return;
            }
            System.out.println("[*] Requesting connection info for peer: " + peerToConnect.substring(0, Math.min(8, peerToConnect.length())) + "...");
            resetConnectionState(); // Ensure clean state

            targetPeerId.set(peerToConnect); // Set who we want to connect to

            JSONObject requestMsg = new JSONObject();
            requestMsg.put("action", "request_connection");
            requestMsg.put("node_id", myNodeId);
            requestMsg.put("target_id", peerToConnect);
            // No need to send public key here, server already has it from registration
            boolean sent = sendUdp(requestMsg, serverAddress);

            if (sent) {
                currentState = NodeState.WAITING_MATCH;
                waitingSince = System.currentTimeMillis();
                lastStatePrintTime = 0;
            } else {
                 System.err.println("[!] Failed to send connection request to server.");
                 targetPeerId.set(null); // Reset target if send failed
            }
        }
    }

    /** Resets all variables related to an active or pending connection attempt. */
    private static void resetConnectionState() {
        synchronized (stateLock) {
            String peerId = connectedPeerId.get();
            if(peerId != null) {
                 peerSymmetricKeys.remove(peerId); // <-- Remove session key
                 System.out.println("[*] Cleared session key for peer " + peerId.substring(0,8) + "...");
            }

            targetPeerId.set(null);
            connectedPeerId.set(null);
            connectedPeerUsername.set(null);
            peerCandidates.clear();
            peerAddrConfirmed.set(null);
            connectionInfoReceived.set(false);
            p2pConnectionEstablished.set(false);
            sharedKeyEstablished.set(false); // <-- Reset crypto flag
            pingAttempts = 0;
            waitingSince = 0;

            // Reset history manager only if it was initialized
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

        // --- Registration Reply ---
        if ("registered".equals(status) && myNodeId == null) {
            String receivedNodeId = data.optString("node_id");
            if (receivedNodeId != null && !receivedNodeId.isEmpty()) {
                myNodeId = receivedNodeId;
                JSONObject publicEpJson = data.optJSONObject("your_public_endpoint");
                if(publicEpJson != null) {
                    myPublicEndpointSeen = Endpoint.fromJson(publicEpJson);
                     System.out.println("[*] Server recorded public endpoint: " + myPublicEndpointSeen);
                }
            } else {
                 System.err.println("[!] Received 'registered' status from server but missing 'node_id'.");
            }
        }
        // --- Connection Info Reply ---
        else if ("connection_info".equals(action) && "match_found".equals(status)) {
            String receivedPeerId = data.optString("peer_id");
            String currentTarget = targetPeerId.get();

            if (currentTarget != null && currentTarget.equals(receivedPeerId)) {
                JSONArray candidatesJson = data.optJSONArray("peer_endpoints");
                String peerUsername = data.optString("peer_username", null);
                String peerPublicKeyBase64 = data.optString("peer_public_key", null); // <-- Get peer public key

                 // Validate essential info
                 if (candidatesJson == null || peerPublicKeyBase64 == null || peerPublicKeyBase64.isEmpty()) {
                     System.err.println("[!] Received incomplete 'connection_info' from server (missing endpoints or public key). Cancelling.");
                     resetConnectionState();
                     currentState = NodeState.DISCONNECTED;
                     return;
                 }


                synchronized(stateLock) { // Protect access to shared state
                    if(currentState != NodeState.WAITING_MATCH) {
                         System.out.println("[?] Received connection_info while not in WAITING_MATCH state. Ignoring.");
                         return; // Avoid processing if state already advanced or changed
                    }

                    // --- Generate Shared Key --- NEW ---
                    boolean keyGenSuccess = generateSharedKeyAndStore(receivedPeerId, peerPublicKeyBase64);
                    if (!keyGenSuccess) {
                         System.err.println("[!] Failed to establish shared secret with peer " + receivedPeerId.substring(0,8) + ". Cancelling connection.");
                         resetConnectionState();
                         currentState = NodeState.DISCONNECTED;
                         return; // Cannot proceed without shared key
                    }
                     // --- End Generate Shared Key ---

                    // Store peer username
                    if (peerUsername != null && !peerUsername.isEmpty()) {
                        connectedPeerUsername.set(peerUsername);
                        System.out.println("[*] Received Peer Username: " + peerUsername);
                    } else {
                        connectedPeerUsername.set(null); // Clear if not received
                        System.out.println("[!] Peer username not provided by server.");
                    }

                    // Store peer candidates
                    peerCandidates.clear();
                    System.out.println("[*] Received Peer Candidates from server for " + receivedPeerId.substring(0,8) + "...:");
                    int validCandidates = 0;
                    for (int i = 0; i < candidatesJson.length(); i++) {
                        JSONObject epJson = candidatesJson.optJSONObject(i);
                        if (epJson != null) {
                            Endpoint ep = Endpoint.fromJson(epJson);
                            if (ep != null) {
                                peerCandidates.add(ep);
                                System.out.println("    - " + ep);
                                validCandidates++;
                            }
                        }
                    }
                    if (validCandidates > 0) {
                        connectionInfoReceived.set(true); // Signal that valid info WAS received
                    } else {
                        System.out.println("[!] Received empty or invalid candidate list for peer " + receivedPeerId.substring(0,8) + "...");
                        resetConnectionState(); // Cannot proceed without candidates
                        currentState = NodeState.DISCONNECTED;
                    }
                } // End synchronized block
            } else {
                System.out.println("[?] Received connection_info for unexpected peer " + receivedPeerId + " while targeting " + currentTarget + ". Ignoring.");
            }
        }
         // --- Server Error Messages ---
         else if ("error".equals(status)) {
            String message = data.optString("message", "Unknown error");
            System.err.println("\n[!] Server Error: " + message);
            synchronized(stateLock) {
                 // Reset connection attempt if error occurs during sensitive phases
                if (currentState == NodeState.WAITING_MATCH || currentState == NodeState.ATTEMPTING_UDP || currentState == NodeState.EXCHANGING_KEYS) {
                    System.out.println("    Cancelling connection attempt due to server error.");
                    resetConnectionState();
                    currentState = NodeState.DISCONNECTED;
                }
            }
         }
         // --- Acknowledgment of Connection Request ---
         else if ("connection_request_received".equals(status) && "ack".equals(action)) {
             String waitingFor = data.optString("waiting_for", "?");
             if (currentState == NodeState.WAITING_MATCH && waitingFor.equals(targetPeerId.get())) {
                 System.out.println("[*] Server acknowledged request. Waiting for peer " + getPeerDisplayName() + " to connect.");
             }
         }
    }

    /** Handles messages received directly from other Peers. */
    private static void handlePeerMessage(JSONObject data, InetSocketAddress peerAddr) {
        String action = data.optString("action", null);
        String senderId = data.optString("node_id", null);

        if (senderId == null || action == null) {
            System.out.println("[?] Received invalid message (no action/node_id) from " + peerAddr);
            return;
        }

        String currentTarget = targetPeerId.get();
        String currentPeer = connectedPeerId.get();
        NodeState stateNow = currentState; // Capture current state

        // --- Processing During UDP Connection Attempt (ATTEMPTING_UDP state) ---
        if (stateNow == NodeState.ATTEMPTING_UDP && currentTarget != null && currentTarget.equals(senderId)) {
             boolean senderIsCandidate = peerCandidates.stream().anyMatch(cand -> {
                 try { return new InetSocketAddress(InetAddress.getByName(cand.ip), cand.port).equals(peerAddr); }
                 catch (UnknownHostException e) { return false; }
             });

            // Only process ping/pong from candidate addresses during this phase
            if (senderIsCandidate && (action.equals("ping") || action.equals("pong"))) {
                 synchronized(stateLock) {
                     if (currentState != NodeState.ATTEMPTING_UDP) return; // Re-check state inside lock

                     if (peerAddrConfirmed.compareAndSet(null, peerAddr)) {
                         System.out.println("\n[*] CONFIRMED receiving directly from Peer " + senderId.substring(0,8) + "... at " + peerAddr + "!");
                         connectedPeerId.set(senderId); // Set the connected peer ID now
                     }

                     // If we get a PING or PONG, it means UDP path is likely working
                     if (p2pConnectionEstablished.compareAndSet(false, true)) {
                          System.out.println("[+] P2P UDP Path Confirmed (Received " + action.toUpperCase() + ")!");
                          // State transition to EXCHANGING_KEYS happens in main loop check
                     }

                     // Respond to PING with PONG
                     if ("ping".equals(action)) {
                         JSONObject pongMsg = new JSONObject();
                         pongMsg.put("action", "pong");
                         pongMsg.put("node_id", myNodeId);
                         sendUdp(pongMsg, peerAddr);
                     }
                 } // End synchronized block
            } // else: Ignore non-ping/pong or messages from non-candidate addresses during UDP attempt
        }
        // --- Processing During Key Exchange (EXCHANGING_KEYS state) ---
        else if (stateNow == NodeState.EXCHANGING_KEYS && currentPeer != null && currentPeer.equals(senderId)) {
            // We might receive PING/PONG here too as paths stabilize
            InetSocketAddress confirmedAddr = peerAddrConfirmed.get();
            if (confirmedAddr != null && peerAddr.equals(confirmedAddr)) {
                 if ("ping".equals(action)) {
                    JSONObject pongMsg = new JSONObject();
                    pongMsg.put("action", "pong");
                    pongMsg.put("node_id", myNodeId);
                    sendUdp(pongMsg, peerAddr);
                 }
                 // If shared key is already established (e.g. race condition), ignore further pongs
                 if ("pong".equals(action) && !sharedKeyEstablished.get()) {
                     // Receiving pong confirms bidirectional UDP path - now fully ready for secure state
                     // The sharedKeyEstablished flag is set when *we* compute the key after receiving server info.
                     // So receiving pong here just reinforces the path is good.
                     System.out.println("[*] Received PONG during key exchange phase from " + getPeerDisplayName() + ".");
                 }
                 // Ignore chat messages during key exchange
            }
        }
        // --- Processing After Secure Connection Established (CONNECTED_SECURE state) ---
        else if (stateNow == NodeState.CONNECTED_SECURE && currentPeer != null && currentPeer.equals(senderId)) {
            InetSocketAddress confirmedAddr = peerAddrConfirmed.get();
            SecretKey sharedKey = peerSymmetricKeys.get(currentPeer); // Get the key for this peer

            // SECURITY: Only accept messages from the confirmed peer address
            if (confirmedAddr == null || !peerAddr.equals(confirmedAddr)) {
                System.out.println("[?] WARNING: Received message from connected peer " + getPeerDisplayName() + " but from wrong address " + peerAddr + " (expected " + confirmedAddr + "). IGNORING.");
                return;
            }
             // SECURITY: Ensure we have a shared key for this peer
            if (sharedKey == null) {
                 System.err.println("[!] CRITICAL: No shared key found for connected peer " + getPeerDisplayName() + ". Disconnecting.");
                 resetConnectionState();
                 currentState = NodeState.DISCONNECTED;
                 return;
            }


            switch (action) {
                // --- Encrypted Chat Handling --- NEW ---
                case "e_chat": { // Use a distinct action for encrypted messages
                    EncryptedPayload payload = EncryptedPayload.fromJson(data);
                    if (payload == null) {
                        System.err.println("[!] Received invalid encrypted chat payload from " + getPeerDisplayName());
                        break;
                    }
                    try {
                        String decryptedMessage = CryptoUtils.decrypt(payload, sharedKey);

                        // Display and Log the DECRYPTED message
                        System.out.print("\r[" + getPeerDisplayName() + "]: " + decryptedMessage + "\n" + getPrompt());

                        if (chatHistoryManager != null) {
                            long timestamp = System.currentTimeMillis();
                            chatHistoryManager.addMessage(timestamp, senderId, connectedPeerUsername.get(), "RECEIVED", decryptedMessage); // Log plaintext
                        }

                    } catch (GeneralSecurityException e) {
                         System.err.println("[!] Failed to decrypt message from " + getPeerDisplayName() + ". Possible tampering or key mismatch. " + e.getMessage());
                         // Optionally: Disconnect on decryption failure? Could be a transient issue too.
                    } catch (Exception e) {
                        System.err.println("[!] Error handling decrypted message from " + getPeerDisplayName() + ": " + e.getMessage());
                    }
                    break;
                } // end case "e_chat"

                case "keepalive":
                    // Received keepalive. Good. No action needed currently.
                    break;
                case "ping":
                    JSONObject pongMsg = new JSONObject();
                    pongMsg.put("action", "pong");
                    pongMsg.put("node_id", myNodeId);
                    sendUdp(pongMsg, peerAddr);
                    break;
                case "pong":
                    // Ignore pongs when connected.
                    break;
                default:
                    System.out.println("[?] Received unknown action '" + action + "' from connected peer " + getPeerDisplayName());
                    break;
            }
        } else {
             // Message received from unexpected peer or in unexpected state
             // System.out.println("[?] Ignoring message from " + senderId.substring(0,8) + " @ " + peerAddr + " while in state " + stateNow); // Can be noisy
        }
    }


    // --- Sending Messages ---

    /** Safely sends a UDP datagram with JSON payload. Returns true on success, false on failure. */
    private static boolean sendUdp(JSONObject jsonObject, InetSocketAddress destination) {
        if (udpSocket == null || udpSocket.isClosed()) {
            // System.err.println("[!] Cannot send UDP: Socket is null or closed."); // Too noisy potentially
            return false;
        }
         if (destination == null) {
            System.err.println("[!] Cannot send UDP: Destination address is null.");
            return false;
        }
        try {
            byte[] data = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > BUFFER_SIZE) {
                System.err.println("[!] Cannot send UDP: Message size (" + data.length + ") exceeds buffer size (" + BUFFER_SIZE + ").");
                return false;
            }
            DatagramPacket packet = new DatagramPacket(data, data.length, destination);
            udpSocket.send(packet);
            return true;
        } catch (PortUnreachableException e) {
            // This often indicates the peer is offline or firewall blocking
            System.err.println("[!] Error sending UDP to " + destination + ": Port Unreachable.");
            // If connected securely, this might indicate connection loss
            if (currentState == NodeState.CONNECTED_SECURE && destination.equals(peerAddrConfirmed.get())) {
                 System.out.println("[!] Connection possibly lost with " + getPeerDisplayName() + " (Port unreachable).");
                 // Consider triggering resetConnectionState() here or letting keepalive timeout handle it.
            }
            return false;
        }
        catch (IOException e) {
            System.err.println("[!] IO Error sending UDP to " + destination + ": " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("[!] Unexpected Error sending UDP to " + destination + ": " + e.getClass().getName() + " - " + e.getMessage());
             return false;
        }
    }

    /** Encrypts and sends a chat message to the currently connected peer. */
    private static void sendMessageToPeer(String message) {
        InetSocketAddress peerAddr = peerAddrConfirmed.get();
        String peerId = connectedPeerId.get();
        SecretKey sharedKey = (peerId != null) ? peerSymmetricKeys.get(peerId) : null;

        // Ensure we are fully connected and have the key
        if (currentState == NodeState.CONNECTED_SECURE && peerAddr != null && peerId != null && sharedKey != null) {
            try {
                EncryptedPayload payload = CryptoUtils.encrypt(message, sharedKey);

                JSONObject chatMsg = new JSONObject();
                chatMsg.put("action", "e_chat"); // Use new action for encrypted chat
                chatMsg.put("node_id", myNodeId);
                // No need to send username in encrypted message, receiver knows who we are.
                // Add the encrypted payload parts
                chatMsg.put("iv", payload.ivBase64);
                chatMsg.put("e_payload", payload.ciphertextBase64);

                boolean sent = sendUdp(chatMsg, peerAddr);

                // --- ADD SENT MESSAGE TO HISTORY (Plaintext) ---
                if (sent && chatHistoryManager != null) {
                    long timestamp = System.currentTimeMillis();
                    chatHistoryManager.addMessage(timestamp, peerId, connectedPeerUsername.get(), "SENT", message); // Log plaintext
                }
                // --- END ADD SENT MESSAGE TO HISTORY ---

                if (!sent) {
                    System.out.println("[!] Failed to send chat message. Connection might be lost.");
                }
            } catch (GeneralSecurityException e) {
                 System.err.println("[!] Failed to encrypt message: " + e.getMessage());
                 // Should not happen with valid key unless crypto provider issue
            } catch (Exception e) {
                 System.err.println("[!] Unexpected error sending chat message: " + e.getMessage());
            }
        } else {
            System.out.println("[!] Cannot send chat message, not securely connected or missing key.");
             System.out.println("    Current State: " + currentState + ", Peer Address: " + peerAddr + ", Peer ID: " + peerId + ", Key Available: " + (sharedKey != null));
        }
    }

    // --- Cryptography Helpers --- NEW

    /** Generates shared secret and derives/stores symmetric key */
    private static boolean generateSharedKeyAndStore(String peerId, String peerPublicKeyBase64) {
        if (myKeyPair == null) {
            System.err.println("[!] Cannot generate shared key: Own keypair is missing.");
            return false;
        }
        if (peerId == null || peerPublicKeyBase64 == null) {
            System.err.println("[!] Cannot generate shared key: Peer info missing.");
            return false;
        }

        try {
            PublicKey peerPublicKey = CryptoUtils.decodePublicKey(peerPublicKeyBase64);

            System.out.println("[*] Performing ECDH key agreement with peer " + peerId.substring(0,8) + "...");
            byte[] sharedSecretBytes = CryptoUtils.generateSharedSecret(myKeyPair.getPrivate(), peerPublicKey);

            System.out.println("[*] Deriving AES symmetric key from shared secret...");
            SecretKey derivedKey = CryptoUtils.deriveSymmetricKey(sharedSecretBytes);

            // Store the key mapped to the peer ID
            peerSymmetricKeys.put(peerId, derivedKey);
            sharedKeyEstablished.set(true); // Signal successful key generation
            System.out.println("[+] Shared symmetric key established successfully for peer " + peerId.substring(0,8) + "!");
            return true;

        } catch (NoSuchAlgorithmException e) {
            System.err.println("[!] Crypto algorithm not supported: " + e.getMessage());
        } catch (InvalidKeySpecException e) {
            System.err.println("[!] Invalid peer public key received: " + e.getMessage());
        } catch (InvalidKeyException e) {
            System.err.println("[!] Invalid key during key agreement: " + e.getMessage());
        } catch (Exception e) {
             System.err.println("[!] Unexpected error during shared key generation: " + e.getMessage());
             e.printStackTrace();
        }
        sharedKeyEstablished.set(false); // Ensure flag is false on failure
        return false;
    }


    // --- Shutdown ---

    /** Gracefully shuts down the node, closing sockets and stopping threads. */
    private static synchronized void shutdown() {
        if (!running) return;
        System.out.println("\n[*] Shutting down node...");
        running = false;

        // 1. Shutdown scheduled tasks forcefully
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdownNow(); // Stop keepalives etc. immediately
            System.out.println("[*] Scheduled executor shutdown requested.");
             try {
                if (!scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                     System.err.println("[!] Scheduled executor did not terminate cleanly.");
                } else { System.out.println("[*] Scheduled executor terminated."); }
             } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // 2. Close UDP Socket
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            System.out.println("[*] UDP Socket closed.");
        }

        // 3. Shutdown listener executor gracefully (allow processing final packets)
        if (listenerExecutor != null && !listenerExecutor.isShutdown()) {
            listenerExecutor.shutdown();
            System.out.println("[*] Listener executor shutdown requested.");
             try {
                if (!listenerExecutor.awaitTermination(1, TimeUnit.SECONDS)) { // Shorter wait
                    System.err.println("[!] Listener thread did not terminate cleanly, forcing...");
                    listenerExecutor.shutdownNow();
                } else { System.out.println("[*] Listener executor terminated."); }
             } catch (InterruptedException e) {
                listenerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
             }
        }

        // Clear sensitive data
        peerSymmetricKeys.clear();
        myKeyPair = null; // Clear our keypair from memory

        System.out.println("[*] Node shutdown sequence complete.");
    }
} // End of P2PNode class