package main.java.com.p2pchat.node;

import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.node.ChatHistoryManager; // Handles CSV History
import main.java.com.p2pchat.node.CryptoManager; // Handles Encryption

import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.SecretKey; // For AES key storage
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException; // For crypto errors
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
// Removed unused import: import java.util.stream.Collectors;


public class P2PNode {

    // --- Configuration ---
    private static String SERVER_IP = "127.0.0.1"; // <--- CHANGE THIS OR PASS VIA ARGS
    private static final int SERVER_PORT = 19999;
    private static final int BUFFER_SIZE = 4096; // Consider if encrypted messages might exceed this
    private static final int LOCAL_UDP_PORT = 0; // 0 = Bind to any available port
    private static final long KEEPALIVE_SERVER_INTERVAL_MS = 20 * 1000; // Send keepalive to server every 20s
    private static final long KEEPALIVE_PEER_INTERVAL_MS = 5 * 1000;    // Send keepalive to peer every 5s when connected
    private static final long PING_INTERVAL_MS = 400;     // Send pings to candidates every 400ms when attempting connection
    private static final int MAX_PING_ATTEMPTS = 15;      // Max pings per candidate set (~6 seconds of pinging)
    private static final long WAIT_MATCH_TIMEOUT_MS = 60 * 1000; // Timeout if peer doesn't send request within 60s
    private static final long STATE_PRINT_INTERVAL_MS = 5 * 1000; // How often to print status when waiting/attempting

    // --- State ---
    private static String myNodeId = null;
    private static String myUsername = "User" + (int)(Math.random() * 1000); // Default username, updated during startup
    private static List<Endpoint> myLocalEndpoints = new ArrayList<>();
    private static Endpoint myPublicEndpointSeen = null;
    // Connection Target State
    private static final AtomicReference<String> targetPeerId = new AtomicReference<>(null);
    // Active Connection State
    private static final AtomicReference<String> connectedPeerId = new AtomicReference<>(null);
    private static final AtomicReference<String> connectedPeerUsername = new AtomicReference<>(null);
    private static final AtomicReference<InetSocketAddress> peerAddrConfirmed = new AtomicReference<>(null);
    // Connection Attempt State
    private static final List<Endpoint> peerCandidates = new CopyOnWriteArrayList<>();
    // Cryptography State
    private static CryptoManager cryptoManager = null; // <-- E2EE: Crypto helper
    private static volatile boolean secureSessionEstablished = false; // <-- E2EE: Track E2EE state
    private static SecretKey sharedAesKey = null; // <-- E2EE: Shared symmetric key
    // History Manager instance
    private static ChatHistoryManager chatHistoryManager = null;

    private static DatagramSocket udpSocket;
    private static InetSocketAddress serverAddress; // Resolved server address
    private static volatile boolean running = true; // Flag to control main loops

    // --- Threading & Sync ---
    private static final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "UDPListenerThread"));
    private static final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ConnectionManagerThread");
            t.setDaemon(true);
            return t;
        });
    private static final AtomicBoolean connectionInfoReceived = new AtomicBoolean(false);
    private static final AtomicBoolean p2pConnectionEstablished = new AtomicBoolean(false);
    private static final Object stateLock = new Object();

    // --- State Machine ---
    private enum NodeState { DISCONNECTED, WAITING_MATCH, ATTEMPTING, CONNECTED_IDLE }
    private static volatile NodeState currentState = NodeState.DISCONNECTED;
    private static long waitingSince = 0;
    private static int pingAttempts = 0;
    private static long lastStatePrintTime = 0;

    public static void main(String[] args) {
        // Handle Server IP Arg
        if (args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            SERVER_IP = args[0].trim();
            System.out.println("[*] Using Server IP from command line: " + SERVER_IP);
        } else {
            if (SERVER_IP.equals("127.0.0.1")) System.out.println("[!] WARNING: Using default Server IP '127.0.0.1'. Pass the actual server IP as an argument!");
            else System.out.println("[*] Using configured Server IP: " + SERVER_IP);
        }

        Scanner inputScanner = new Scanner(System.in);

        try {
            // Resolve Server Address
            System.out.println("[*] Resolving server address: " + SERVER_IP + ":" + SERVER_PORT + "...");
            serverAddress = new InetSocketAddress(InetAddress.getByName(SERVER_IP), SERVER_PORT);
            System.out.println("[*] Server address resolved to: " + serverAddress);

            // Setup UDP Socket
            udpSocket = new DatagramSocket(LOCAL_UDP_PORT);
            udpSocket.setReuseAddress(true);
            int actualLocalPort = udpSocket.getLocalPort();
            System.out.println("[*] UDP Socket bound to local address: " + udpSocket.getLocalAddress().getHostAddress() + ":" + actualLocalPort);

            // Prompt for Username
            System.out.print("[?] Enter your desired username: ");
            String inputName = "";
            while(inputName == null || inputName.trim().isEmpty()) {
                if (inputScanner.hasNextLine()) {
                    inputName = inputScanner.nextLine().trim();
                    if (inputName.isEmpty()) System.out.print("[!] Username cannot be empty. Please enter a username: ");
                } else {
                    System.out.println("\n[*] Input stream closed during username entry. Using default.");
                    inputName = "User" + (int)(Math.random() * 1000);
                    break;
                }
            }
            myUsername = inputName;
            System.out.println("[*] Username set to: " + myUsername);

            // Discover Local Endpoints
            myLocalEndpoints = getLocalNetworkEndpoints(actualLocalPort);
            System.out.println("[*] Discovered Local Endpoints:");
            if(myLocalEndpoints.isEmpty()) System.out.println("    <None found>");
            else myLocalEndpoints.forEach(ep -> System.out.println("    - " + ep));

            // Add Shutdown Hook
            Runtime.getRuntime().addShutdownHook(new Thread(P2PNode::shutdown));

            // Start Listener Thread
            listenerExecutor.submit(P2PNode::udpListenerLoop);

            // Register With Server
            if (!registerWithServer()) {
                System.err.println("[!!!] Failed to register with server. Exiting.");
                shutdown();
                return;
            }
            System.out.println("[+] Successfully registered with server. Node ID: " + myNodeId);

            // Start Connection Manager Thread
            startConnectionManager();

            // Start User Interaction Loop (passes scanner)
            userInteractionLoop(inputScanner);

        } catch (UnknownHostException e) {
            System.err.println("[!!!] FATAL: Could not resolve server hostname: " + SERVER_IP + " - " + e.getMessage());
        } catch (SocketException e) {
            System.err.println("[!!!] FATAL: Failed to create or bind UDP socket: " + e.getMessage());
        } catch (SecurityException e) {
            System.err.println("[!!!] FATAL: Security Manager prevented network operations: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[!!!] FATAL: Unexpected error during startup: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
            System.out.println("[*] Node main method finished.");
        }
    }

    // --- Network Setup & Discovery ---

    private static List<Endpoint> getLocalNetworkEndpoints(int udpPort) {
        List<Endpoint> endpoints = new ArrayList<>();
        System.out.println("[*] Discovering network interfaces...");
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
                        } else { continue; }
                        endpoints.add(new Endpoint(ip, udpPort, type));
                    }
                } catch (SocketException se) { /* Ignore interface specific errors */ }
            }
        } catch (SocketException e) { System.err.println("[!] Error getting network interfaces: " + e.getMessage()); }
        if (endpoints.isEmpty()) System.out.println("[!] No suitable local non-loopback IPs found.");
        return new ArrayList<>(new HashSet<>(endpoints));
    }

    private static boolean registerWithServer() {
        JSONObject registerMsg = new JSONObject();
        registerMsg.put("action", "register");
        registerMsg.put("username", myUsername); // Send username
        JSONArray localEndpointsJson = new JSONArray();
        myLocalEndpoints.forEach(ep -> localEndpointsJson.put(ep.toJson()));
        registerMsg.put("local_endpoints", localEndpointsJson);

        System.out.println("[*] Sending registration to server " + serverAddress + "...");
        boolean sent = sendUdp(registerMsg, serverAddress);
        if (!sent) { System.err.println("[!] Failed to send registration message."); return false; }

        long startTime = System.currentTimeMillis();
        long timeout = 5000;
        while (myNodeId == null && System.currentTimeMillis() - startTime < timeout) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        if(myNodeId == null) { System.err.println("[!] No registration confirmation received."); return false; }
        return true;
    }

    // --- Main Loops ---

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
                JSONObject data = new JSONObject(messageStr);
                boolean fromServer = serverAddress != null && serverAddress.equals(senderAddr);
                if (fromServer) handleServerMessage(data);
                else handlePeerMessage(data, senderAddr);
            } catch (SocketException e) { if (running) System.err.println("[Listener Thread] Socket error: " + e.getMessage()); }
            catch (IOException e) { if (running) System.err.println("[Listener Thread] I/O error receiving: " + e.getMessage()); }
            catch (org.json.JSONException e) { System.err.println("[Listener Thread] Invalid JSON received: " + e.getMessage()); }
            catch (Exception e) { if (running) System.err.println("[Listener Thread] Error processing packet: " + e.getMessage()); }
        }
        System.out.println("[*] UDP Listener thread finished.");
    }

    private static void startConnectionManager() {
        scheduledExecutor.scheduleWithFixedDelay(() -> {
            if (!running || udpSocket == null || udpSocket.isClosed()) return;
            try {
                // Server Keep-Alive
                if (myNodeId != null) {
                    JSONObject keepAliveMsg = new JSONObject().put("action", "keep_alive").put("node_id", myNodeId);
                    sendUdp(keepAliveMsg, serverAddress);
                }
                String currentTarget = targetPeerId.get();
                InetSocketAddress confirmedPeer = peerAddrConfirmed.get();
                // P2P Pinging
                if (currentState == NodeState.ATTEMPTING && currentTarget != null && !peerCandidates.isEmpty() && !p2pConnectionEstablished.get()) {
                    if (pingAttempts < MAX_PING_ATTEMPTS) {
                        JSONObject pingMsg = new JSONObject().put("action", "ping").put("node_id", myNodeId);
                        List<Endpoint> currentCandidates = new ArrayList<>(peerCandidates);
                        currentCandidates.sort(Comparator.comparing(ep -> (ep.type != null && ep.type.contains("public")) ? 0 : 1));
                        boolean sentPingThisRound = false;
                        for (Endpoint candidate : currentCandidates) {
                            try { sendUdp(pingMsg, new InetSocketAddress(InetAddress.getByName(candidate.ip), candidate.port)); sentPingThisRound = true; }
                            catch (Exception e) { /* Ignore send errors during ping */ }
                        }
                        if(sentPingThisRound) synchronized(stateLock) { if(currentState == NodeState.ATTEMPTING) pingAttempts++; }
                    }
                }
                // Peer Keep-Alive
                else if (currentState == NodeState.CONNECTED_IDLE && confirmedPeer != null) {
                    JSONObject keepAliveMsg = new JSONObject().put("action", "keepalive").put("node_id", myNodeId);
                    sendUdp(keepAliveMsg, confirmedPeer); // Ignore failure for keepalive? Could check connection.
                }
            } catch (Exception e) { System.err.println("[!!!] Error in Connection Manager task: " + e.getMessage()); }
        }, 1000, Math.min(KEEPALIVE_PEER_INTERVAL_MS, PING_INTERVAL_MS), TimeUnit.MILLISECONDS);
        System.out.println("[*] Started Connection Manager task.");
    }

    private static void userInteractionLoop(Scanner scanner) {
        try {
            System.out.println("\n--- P2P Node Ready (Username: " + myUsername + ", Node ID: " + myNodeId + ") ---");
            System.out.println("--- Enter 'connect <peer_id>', 'status', or 'quit' ---");
            while (running) {
                try {
                    checkStateTransitions();
                    long now = Instant.now().toEpochMilli();
                    String prompt = getPrompt();
                    if (currentState == NodeState.WAITING_MATCH || currentState == NodeState.ATTEMPTING) {
                        if (now - lastStatePrintTime > STATE_PRINT_INTERVAL_MS) { System.out.println(getStateDescription()); System.out.print(prompt); lastStatePrintTime = now; }
                        if (currentState == NodeState.WAITING_MATCH && (now - waitingSince > WAIT_MATCH_TIMEOUT_MS)) { System.out.println("\n[!] Timed out waiting for peer match."); resetConnectionState(); currentState = NodeState.DISCONNECTED; System.out.print(getPrompt()); continue; }
                        if (currentState == NodeState.ATTEMPTING && (pingAttempts >= MAX_PING_ATTEMPTS) && !p2pConnectionEstablished.get()) { System.out.println("\n[!] Failed to establish P2P connection after ping attempts."); resetConnectionState(); currentState = NodeState.DISCONNECTED; System.out.print(getPrompt()); continue; }
                        if (System.in.available() <= 0) { Thread.sleep(200); continue; }
                    } else { System.out.print(prompt); }

                    if (!scanner.hasNextLine()) { System.out.println("\n[*] Input stream closed. Exiting."); running = false; break; }
                    String line = scanner.nextLine().trim();
                    checkStateTransitions(); NodeState stateBeforeCommand = currentState; // Recheck state
                    if (line.isEmpty()) continue;
                    String[] parts = line.split(" ", 2); String command = parts[0].toLowerCase();
                    if(currentState != stateBeforeCommand) { System.out.println("\n[*] State changed during input, please retry."); continue; }

                    switch (command) {
                        case "quit": case "exit":
                            System.out.println("[*] Quit command received. Initiating shutdown...");
                            shutdown(); System.exit(0); break;
                        case "connect":
                            if (stateBeforeCommand == NodeState.DISCONNECTED) { if (parts.length > 1 && !parts[1].isEmpty()) { String peerToConnect = parts[1].trim(); if(peerToConnect.equals(myNodeId)) System.out.println("[!] Cannot connect to yourself."); else if (peerToConnect.length() < 5) System.out.println("[!] Invalid Peer ID format."); else startConnectionAttempt(peerToConnect); } else System.out.println("[!] Usage: connect <peer_node_id>"); }
                            else System.out.println("[!] Already connecting or connected."); break;
                        case "disconnect": case "cancel":
                            if (stateBeforeCommand == NodeState.CONNECTED_IDLE) { System.out.println("[*] Disconnecting from peer " + getPeerDisplayName() + "..."); resetConnectionState(); currentState = NodeState.DISCONNECTED; }
                            else if (stateBeforeCommand == NodeState.WAITING_MATCH || stateBeforeCommand == NodeState.ATTEMPTING) { System.out.println("[*] Cancelling connection attempt..."); resetConnectionState(); currentState = NodeState.DISCONNECTED; }
                            else System.out.println("[!] Not currently connected or attempting."); break;
                        case "chat": case "c":
                            if (stateBeforeCommand == NodeState.CONNECTED_IDLE) { if (parts.length > 1 && !parts[1].isEmpty()) sendMessageToPeer(parts[1]); else System.out.println("[!] Usage: chat <message>"); }
                            else System.out.println("[!] Not connected to a peer."); break;
                        case "status": case "s": System.out.println(getStateDescription()); break;
                        case "id": System.out.println("[*] Your Username: " + myUsername + "\n[*] Your Node ID: " + myNodeId); break;
                        default: System.out.println("[!] Unknown command."); break;
                    }
                } catch (NoSuchElementException e) { System.out.println("\n[*] Input stream ended. Shutting down."); running = false; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); System.out.println("\n[*] Interrupted. Shutting down."); running = false; }
                catch (Exception e) { System.err.println("\n[!!!] Error in user loop: " + e.getMessage()); try { Thread.sleep(500); } catch (InterruptedException ignored) {} }
            }
        } finally { /* Scanner close handling potentially here or in main */ }
        System.out.println("[*] User Interaction loop finished.");
    }

    // --- State Management & Transitions ---

    /** Checks atomic flags set by other threads and updates the main state machine. */
    private static void checkStateTransitions() {
        NodeState previousState = currentState;
        synchronized (stateLock) {
            // Info received -> Attempting
            if (currentState == NodeState.WAITING_MATCH && connectionInfoReceived.compareAndSet(true, false)) {
                if (!peerCandidates.isEmpty()) { currentState = NodeState.ATTEMPTING; pingAttempts = 0; System.out.println("\n[*] Received peer info for " + getPeerDisplayName() +". Attempting UDP P2P..."); }
                else { System.out.println("\n[!] Received empty candidate list. Cancelling."); resetConnectionState(); currentState = NodeState.DISCONNECTED; }
            }
            // P2P Established -> Connected Idle (Initiate E2EE)
            else if ((currentState == NodeState.WAITING_MATCH || currentState == NodeState.ATTEMPTING) && p2pConnectionEstablished.compareAndSet(false, true)) { // Use compareAndSet here!
                currentState = NodeState.CONNECTED_IDLE;
                InetSocketAddress confirmedAddr = peerAddrConfirmed.get(); String peerId = connectedPeerId.get(); String peerUsername = connectedPeerUsername.get();
                System.out.println("\n-----------------------------------------------------");
                System.out.println("[+] UDP P2P CONNECTION ESTABLISHED with " + getPeerDisplayName() + "!");
                System.out.println("    Using path: YOU -> " + (confirmedAddr != null ? confirmedAddr : "???"));
                System.out.println("-----------------------------------------------------");
                System.out.println("[*] Initializing secure session..."); // New status message
                // Reset crypto state before starting new session
                secureSessionEstablished = false; sharedAesKey = null; cryptoManager = null;
                // Init History Manager
                if (peerId != null) { chatHistoryManager = new ChatHistoryManager(); chatHistoryManager.initialize(myNodeId, peerId, peerUsername); }
                else { System.err.println("[!] Cannot init history: Peer Node ID null!"); chatHistoryManager = null; }
                // Initiate Key Exchange
                try {
                    cryptoManager = new CryptoManager(); byte[] pubKeyBytes = cryptoManager.getDhPublicKeyEncoded(); String pubKeyB64 = CryptoManager.encodeBase64(pubKeyBytes);
                    JSONObject dhKeyMsg = new JSONObject().put("action", "dh_public_key").put("node_id", myNodeId).put("key_data", pubKeyB64);
                    if (sendUdp(dhKeyMsg, confirmedAddr)) System.out.println("[*] Sent DH public key to " + getPeerDisplayName());
                    else { System.err.println("[!] Failed to send DH public key. Cannot establish secure session."); resetConnectionState(); currentState = NodeState.DISCONNECTED; }
                } catch (GeneralSecurityException e) { System.err.println("[!!!] CRITICAL: Crypto init failed: " + e.getMessage()); resetConnectionState(); currentState = NodeState.DISCONNECTED; }
                // Clear connection attempt state variables
                targetPeerId.set(null); connectionInfoReceived.set(false); peerCandidates.clear(); pingAttempts = 0;
            }
            // Connection Lost -> Disconnected
            else if (currentState == NodeState.CONNECTED_IDLE && !p2pConnectionEstablished.get()) { // This flag might need external trigger on send failure etc.
                System.out.println("\n[*] UDP P2P connection with " + getPeerDisplayName() + " lost or disconnected.");
                resetConnectionState(); currentState = NodeState.DISCONNECTED;
            }
        } // End synchronized block
        if (currentState != previousState) { System.out.println("[State Change] " + previousState + " -> " + currentState); System.out.print(getPrompt()); }
    }

    /** Gets a description of the current node state. */
    private static String getStateDescription() {
        switch (currentState) {
            case DISCONNECTED: return "[Status] Disconnected. ID: " + myNodeId + " | User: " + myUsername;
            case WAITING_MATCH: long elapsed = (System.currentTimeMillis() - waitingSince) / 1000; return "[Status] Waiting for peer " + targetPeerId.get().substring(0,8) + "... (" + elapsed + "s / " + WAIT_MATCH_TIMEOUT_MS/1000 + "s)";
            case ATTEMPTING: return "[Status] Attempting P2P to " + targetPeerId.get().substring(0,8) + "... (Ping: " + pingAttempts + "/" + MAX_PING_ATTEMPTS + ")";
            case CONNECTED_IDLE: InetSocketAddress addr = peerAddrConfirmed.get(); return "[Status] Connected to " + getPeerDisplayName() + " (" + (addr != null ? addr : "???") + (secureSessionEstablished ? " - Secure" : " - Negotiating Security") + ")"; // <-- Updated status
            default: return "[Status] Unknown State";
        }
    }

    /** Gets the appropriate command prompt based on the current state. */
    private static String getPrompt() {
        switch (currentState) {
            case DISCONNECTED: return "[?] Cmds: connect, status, id, quit: ";
            case WAITING_MATCH: return "[Waiting for " + targetPeerId.get().substring(0,8) + "... (cancel/disconnect)]: ";
            case ATTEMPTING: return "[Attempting " + targetPeerId.get().substring(0,8) + "... (cancel/disconnect)]: ";
            case CONNECTED_IDLE: return "[Chat (" + getPeerDisplayName() + (secureSessionEstablished ? " - S" : " - !S") + ")] ('chat', 'disconnect', 'status', 'quit'): "; // <-- Indicate Secure/Not Secure
            default: return "> ";
        }
    }

    /** Gets the display name for the connected peer (Username or ID prefix). */
    private static String getPeerDisplayName() {
        String username = connectedPeerUsername.get(); if (username != null && !username.isEmpty()) return username;
        String peerId = connectedPeerId.get(); if (peerId != null) return peerId.substring(0, Math.min(8, peerId.length())) + "...";
        String targetId = targetPeerId.get(); if(targetId != null) return targetId.substring(0, Math.min(8, targetId.length())) + "...";
        return "???";
    }

    /** Initiates a connection attempt to the specified peer ID. */
    private static void startConnectionAttempt(String peerToConnect) {
        synchronized (stateLock) {
            if (currentState != NodeState.DISCONNECTED) { System.out.println("[!] Already connecting or connected."); return; }
            System.out.println("[*] Requesting connection info for peer: " + peerToConnect.substring(0, Math.min(8, peerToConnect.length())) + "...");
            resetConnectionState();
            targetPeerId.set(peerToConnect);
            JSONObject requestMsg = new JSONObject().put("action", "request_connection").put("node_id", myNodeId).put("target_id", peerToConnect);
            if (sendUdp(requestMsg, serverAddress)) { currentState = NodeState.WAITING_MATCH; waitingSince = System.currentTimeMillis(); lastStatePrintTime = 0; }
            else { System.err.println("[!] Failed to send connection request."); targetPeerId.set(null); }
        }
    }

    /** Resets all variables related to an active or pending connection attempt. */
    private static void resetConnectionState() {
        synchronized (stateLock) {
            targetPeerId.set(null); connectedPeerId.set(null); connectedPeerUsername.set(null);
            peerCandidates.clear(); peerAddrConfirmed.set(null);
            connectionInfoReceived.set(false); p2pConnectionEstablished.set(false); // Reset P2P flag too!
            pingAttempts = 0; waitingSince = 0;
            // Reset Crypto & History
            secureSessionEstablished = false; sharedAesKey = null; cryptoManager = null;
            chatHistoryManager = null;
            System.out.println("[*] Reset connection state variables.");
        }
    }

    // --- Message Handling ---

    /** Handles messages received from the Coordination Server. */
    private static void handleServerMessage(JSONObject data) {
        String status = data.optString("status", null); String action = data.optString("action", null);
        // Registration Reply
        if ("registered".equals(status) && myNodeId == null) {
            String rid = data.optString("node_id"); if (rid != null && !rid.isEmpty()) myNodeId = rid;
            JSONObject pep = data.optJSONObject("your_public_endpoint"); if(pep != null) myPublicEndpointSeen = Endpoint.fromJson(pep);
        }
        // Connection Info Reply
        else if ("connection_info".equals(action) && "match_found".equals(status)) {
            String rid = data.optString("peer_id"); String cT = targetPeerId.get();
            if (cT != null && cT.equals(rid)) {
                JSONArray cands = data.optJSONArray("peer_endpoints"); String pUser = data.optString("peer_username", null);
                if (cands != null) {
                    synchronized(stateLock) {
                        peerCandidates.clear(); int vCands = 0;
                        for (int i = 0; i < cands.length(); i++) { JSONObject epJ = cands.optJSONObject(i); if (epJ != null) { Endpoint ep = Endpoint.fromJson(epJ); if (ep != null) { peerCandidates.add(ep); System.out.println("    - " + ep); vCands++; } } }
                        if (vCands > 0) { connectionInfoReceived.set(true); if (pUser != null && !pUser.isEmpty()) { connectedPeerUsername.set(pUser); System.out.println("[*] Received Peer Username: " + pUser); } else { connectedPeerUsername.set(null); System.out.println("[!] Peer username not received."); } }
                        else { System.out.println("[!] Received empty/invalid candidate list."); connectedPeerUsername.set(null); }
                    }
                } else { System.out.println("[!] Missing 'peer_endpoints'."); connectedPeerUsername.set(null); }
            } else { /* Ignore info for unexpected peer */ }
        }
        // Server Error
        else if ("error".equals(status)) {
            System.err.println("\n[!] Server Error: " + data.optString("message", "Unknown"));
            synchronized(stateLock) { if (currentState == NodeState.WAITING_MATCH || currentState == NodeState.ATTEMPTING) { resetConnectionState(); currentState = NodeState.DISCONNECTED; System.out.print(getPrompt()); } } // Print prompt after error reset
        }
        // Connection Request Ack
        else if ("connection_request_received".equals(status) && "ack".equals(action)) {
            String wF = data.optString("waiting_for", "?"); if (currentState == NodeState.WAITING_MATCH && wF.equals(targetPeerId.get())) System.out.println("[*] Server ack received. Waiting for peer...");
        }
    }

    /** Handles messages received directly from other Peers. */
    private static void handlePeerMessage(JSONObject data, InetSocketAddress peerAddr) {
        String action = data.optString("action", null); String senderId = data.optString("node_id", null);
        if (senderId == null || action == null) return;
        String currentTarget = targetPeerId.get(); String currentPeer = connectedPeerId.get();

        // Processing During Connection Attempt (Establish UDP Path)
        // Use p2pConnectionEstablished flag correctly - only set it once path is confirmed.
        if (currentState == NodeState.ATTEMPTING && currentTarget != null && currentTarget.equals(senderId)) {
            boolean senderIsCand = false; synchronized(stateLock){ senderIsCand = peerCandidates.stream().anyMatch(c -> { try { return new InetSocketAddress(InetAddress.getByName(c.ip), c.port).equals(peerAddr); } catch (Exception e) { return false; } }); }
            if (senderIsCand || action.equals("ping") || action.equals("pong")) {
                // Confirm address and ID first
                synchronized(stateLock) { if (peerAddrConfirmed.compareAndSet(null, peerAddr)) { System.out.println("\n[*] CONFIRMED receiving from Peer " + senderId.substring(0,8) + "... at " + peerAddr); connectedPeerId.set(senderId); } }
                // Now check for PING/PONG to confirm bidirectional path and set flag
                if ("ping".equals(action)) { JSONObject pong = new JSONObject().put("action", "pong").put("node_id", myNodeId); sendUdp(pong, peerAddr); if (p2pConnectionEstablished.compareAndSet(false, true)) System.out.println("[+] P2P Path Confirmed (Got PING)"); } // Set flag only once
                else if ("pong".equals(action)) { if (p2pConnectionEstablished.compareAndSet(false, true)) System.out.println("[+] P2P Path Confirmed (Got PONG)"); } // Set flag only once
            }
        }
        // Processing After Connection Established (CONNECTED_IDLE State)
        else if (currentState == NodeState.CONNECTED_IDLE && currentPeer != null && currentPeer.equals(senderId)) {
            InetSocketAddress confAddr = peerAddrConfirmed.get();
            // Security check: Only accept from the confirmed address
            if (confAddr == null || !peerAddr.equals(confAddr)) { System.out.println("[?] WARNING: Ignoring msg from peer " + getPeerDisplayName() + " from wrong address " + peerAddr); return; }

            switch (action) {
                case "dh_public_key": { // Handle Key Exchange
                    if (!secureSessionEstablished && cryptoManager != null) {
                        String keyB64 = data.optString("key_data", null); if (keyB64 != null) { System.out.println("[*] Received DH public key from " + getPeerDisplayName()); try { byte[] peerKeyBytes = CryptoManager.decodeBase64(keyB64); byte[] secret = cryptoManager.computeSharedSecret(peerKeyBytes); sharedAesKey = cryptoManager.deriveAesKeyFromSharedSecret(secret); if (sharedAesKey == null) throw new GeneralSecurityException("Derived AES key null"); secureSessionEstablished = true; System.out.println("[+] Secure session established with " + getPeerDisplayName() + "!"); System.out.print(getPrompt()); } catch (Exception e) { System.err.println("[!] Crypto error during key agreement: " + e.getMessage()); resetConnectionState(); currentState = NodeState.DISCONNECTED; System.out.print(getPrompt()); } }
                        else System.err.println("[!] Received dh_public_key with missing key_data.");
                    } else if (secureSessionEstablished) System.out.println("[?] Ignoring duplicate DH key from " + getPeerDisplayName());
                    else System.out.println("[?] Ignoring DH key in wrong state/init."); break;
                }
                case "chat": { // Handle Encrypted Chat
                    if (!secureSessionEstablished) { System.out.println("\n[!] Ignoring chat from " + getPeerDisplayName() + ": Secure session not ready."); System.out.print(getPrompt()); break; }
                    String securePayloadB64 = data.optString("secure_payload", null); String usernameFromPayload = data.optString("username", null); // Can be removed from send/receive if desired
                    if (securePayloadB64 == null) { System.out.println("\n[!] Ignoring chat with missing secure_payload."); System.out.print(getPrompt()); break; }
                    String displayUser = getPeerDisplayName();
                    String decryptedMsg = "[Decryption Error]";
                    try { byte[] ivCtTag = CryptoManager.decodeBase64(securePayloadB64); byte[] decBytes = cryptoManager.decrypt(ivCtTag, sharedAesKey); decryptedMsg = new String(decBytes, StandardCharsets.UTF_8);
                          // Store ENCRYPTED history
                          if (chatHistoryManager != null) { long ts = System.currentTimeMillis(); String peerUserHist = connectedPeerUsername.get() != null ? connectedPeerUsername.get() : usernameFromPayload; chatHistoryManager.addMessage(ts, senderId, peerUserHist, "RECEIVED", securePayloadB64); }
                    } catch (IllegalArgumentException e) { decryptedMsg = "[Bad Base64 Data]"; System.err.println("\n[!] Failed to decode Base64 payload: " + e.getMessage()); }
                    catch (GeneralSecurityException e) { decryptedMsg = "[Decryption Failed - Tampered?]"; System.err.println("\n[!] Decryption/Auth failed: " + e.getMessage()); }
                    catch (Exception e) { decryptedMsg = "[Error Processing Message]"; System.err.println("\n[!] Error processing chat: " + e.getMessage()); }
                    System.out.print("\r[" + displayUser + "]: " + decryptedMsg + "\n" + getPrompt()); break;
                }
                case "keepalive": /* Ignore received peer keepalive for now */ break;
                case "ping": JSONObject pong = new JSONObject().put("action", "pong").put("node_id", myNodeId); sendUdp(pong, peerAddr); break;
                case "pong": /* Ignore received pong */ break;
                default: System.out.println("[?] Unknown action '" + action + "' from " + getPeerDisplayName()); break;
            }
        }
    }

    // --- Sending Messages ---

    /** Safely sends a UDP datagram with JSON payload. Returns true on success, false on failure. */
    private static boolean sendUdp(JSONObject jsonObject, InetSocketAddress destination) {
        if (udpSocket == null || udpSocket.isClosed() || destination == null) return false;
        try { byte[] data = jsonObject.toString().getBytes(StandardCharsets.UTF_8); if (data.length > BUFFER_SIZE) { System.err.println("[!] UDP Send Error: Msg too large ("+data.length+")"); return false; } DatagramPacket p = new DatagramPacket(data, data.length, destination); udpSocket.send(p); return true; }
        catch (IOException e) { System.err.println("[!] UDP Send IO Error to " + destination + ": " + e.getMessage()); return false; }
        catch (Exception e) { System.err.println("[!] UDP Send Error to " + destination + ": " + e.getMessage()); return false; }
    }

    /** Sends an encrypted chat message. */
    private static void sendMessageToPeer(String message) {
        InetSocketAddress peerAddr = peerAddrConfirmed.get(); String peerId = connectedPeerId.get();
        if (!secureSessionEstablished || cryptoManager == null || sharedAesKey == null) { System.out.println("[!] Cannot send chat: Secure session not ready."); return; }
        if (peerAddr == null || peerId == null) { System.out.println("[!] Cannot send chat: Not connected."); return; }
        try {
            byte[] plainBytes = message.getBytes(StandardCharsets.UTF_8); byte[] encBytes = cryptoManager.encrypt(plainBytes, sharedAesKey); String securePayloadB64 = CryptoManager.encodeBase64(encBytes);
            JSONObject chatMsg = new JSONObject().put("action", "chat").put("node_id", myNodeId).put("secure_payload", securePayloadB64);
            // Optionally add username: chatMsg.put("username", myUsername); // Not strictly needed if receiver uses connectedPeerUsername
            boolean sent = sendUdp(chatMsg, peerAddr);
            // Store ENCRYPTED history
            if (sent && chatHistoryManager != null) { long ts = System.currentTimeMillis(); String peerUser = connectedPeerUsername.get(); chatHistoryManager.addMessage(ts, peerId, peerUser, "SENT", securePayloadB64); }
            if (!sent) System.out.println("[!] Failed to send chat message.");
        } catch (GeneralSecurityException e) { System.err.println("[!] Failed to encrypt message: " + e.getMessage()); }
        catch (Exception e) { System.err.println("[!] Error sending chat: " + e.getMessage()); }
    }

    // --- Shutdown ---

    /** Gracefully shuts down the node, closing sockets and stopping threads. */
    private static synchronized void shutdown() {
        if (!running) return;
        System.out.println("\n[*] Shutting down node..."); running = false;
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) { scheduledExecutor.shutdownNow(); try { if (!scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS)) System.err.println("[!] Scheduled executor did not terminate cleanly."); else System.out.println("[*] Scheduled executor terminated."); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        if (udpSocket != null && !udpSocket.isClosed()) { udpSocket.close(); System.out.println("[*] UDP Socket closed."); }
        if (listenerExecutor != null && !listenerExecutor.isShutdown()) { listenerExecutor.shutdown(); try { if (!listenerExecutor.awaitTermination(2, TimeUnit.SECONDS)) { System.err.println("[!] Listener thread did not terminate cleanly, forcing..."); listenerExecutor.shutdownNow(); } else System.out.println("[*] Listener executor terminated."); } catch (InterruptedException e) { listenerExecutor.shutdownNow(); Thread.currentThread().interrupt(); } }
        System.out.println("[*] Node shutdown sequence complete.");
    }
} // End of P2PNode class