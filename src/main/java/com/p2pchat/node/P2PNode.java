package main.java.com.p2pchat.node;

import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.node.ChatHistoryManager; // <-- Import History Manager

import org.json.JSONArray;
import org.json.JSONObject;

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
    // IMPORTANT: Replace 127.0.0.1 with the ACTUAL PUBLIC IP of your server machine
    //            OR pass it as a command-line argument.
    private static String SERVER_IP = "127.0.0.1"; // <--- CHANGE THIS OR PASS VIA ARGS
    private static final int SERVER_PORT = 19999;
    private static final int BUFFER_SIZE = 4096;
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
    // Who we WANT to connect to (set when user types 'connect')
    private static final AtomicReference<String> targetPeerId = new AtomicReference<>(null);
    // Who we ARE connected to (set when P2P connection established)
    private static final AtomicReference<String> connectedPeerId = new AtomicReference<>(null);
    private static final AtomicReference<String> connectedPeerUsername = new AtomicReference<>(null); // Store peer's username
    // Potential endpoints received from server for the targetPeerId
    private static final List<Endpoint> peerCandidates = new CopyOnWriteArrayList<>(); // Thread-safe for read/write
    // The specific address of the peer that we successfully communicated with
    private static final AtomicReference<InetSocketAddress> peerAddrConfirmed = new AtomicReference<>(null);
    // History Manager instance
    private static ChatHistoryManager chatHistoryManager = null; // <-- History Manager instance

    private static DatagramSocket udpSocket;
    private static InetSocketAddress serverAddress; // Resolved server address
    private static volatile boolean running = true; // Flag to control main loops

    // --- Threading & Sync ---
    // Executor for the main listener loop
    private static final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "UDPListenerThread"));
    // Scheduled executor for keep-alives and pinging tasks
    private static final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ConnectionManagerThread");
            t.setDaemon(true); // Allow JVM exit even if this thread is active
            return t;
        });
    // Flags to signal state changes between threads
    private static final AtomicBoolean connectionInfoReceived = new AtomicBoolean(false);
    private static final AtomicBoolean p2pConnectionEstablished = new AtomicBoolean(false);
    // Lock for synchronizing complex state transitions if needed (currently using atomics primarily)
    private static final Object stateLock = new Object();

    // --- State Machine ---
    private enum NodeState { DISCONNECTED, WAITING_MATCH, ATTEMPTING, CONNECTED_IDLE }
    private static volatile NodeState currentState = NodeState.DISCONNECTED;
    private static long waitingSince = 0; // Timestamp when WAITING_MATCH started
    private static int pingAttempts = 0; // Counter for PINGs sent during ATTEMPTING
    private static long lastStatePrintTime = 0; // Timestamp for periodic status prints

    public static void main(String[] args) {
        // Override Server IP if provided as command line argument
        if (args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            SERVER_IP = args[0].trim();
            System.out.println("[*] Using Server IP from command line: " + SERVER_IP);
        } else {
            // Add a warning if the default is still localhost and no arg provided
            if (SERVER_IP.equals("127.0.0.1")) {
                System.out.println("[!] WARNING: Using default Server IP '127.0.0.1'. Pass the actual server IP as an argument if nodes are on different machines!");
            } else {
                 System.out.println("[*] Using configured Server IP: " + SERVER_IP);
            }
        }

        // Scanner for user input (used for username prompt and main loop)
        Scanner inputScanner = new Scanner(System.in);

        try {
            // Resolve server address ONCE at the start
            System.out.println("[*] Resolving server address: " + SERVER_IP + ":" + SERVER_PORT + "...");
            serverAddress = new InetSocketAddress(InetAddress.getByName(SERVER_IP), SERVER_PORT);
            System.out.println("[*] Server address resolved to: " + serverAddress);

            // Setup UDP Socket
            udpSocket = new DatagramSocket(LOCAL_UDP_PORT); // Bind to specific local port or 0 for any
            udpSocket.setReuseAddress(true); // Helpful for quick restarts
            int actualLocalPort = udpSocket.getLocalPort();
            System.out.println("[*] UDP Socket bound to local address: " + udpSocket.getLocalAddress().getHostAddress() + ":" + actualLocalPort);

            // --- Prompt for Username ---
            System.out.print("[?] Enter your desired username: ");
            String inputName = "";
            while(inputName == null || inputName.trim().isEmpty()) {
                if (inputScanner.hasNextLine()) {
                    inputName = inputScanner.nextLine().trim();
                    if (inputName.isEmpty()) {
                        System.out.print("[!] Username cannot be empty. Please enter a username: ");
                    }
                } else {
                    // Handle case where input stream closes unexpectedly during prompt
                    System.out.println("\n[*] Input stream closed during username entry. Using default.");
                    inputName = "User" + (int)(Math.random() * 1000); // Use default fallback
                    break;
                }
            }
            myUsername = inputName;
            System.out.println("[*] Username set to: " + myUsername);
            // --- End Username Prompt ---

            // Discover local endpoints
            myLocalEndpoints = getLocalNetworkEndpoints(actualLocalPort);
            System.out.println("[*] Discovered Local Endpoints:");
            if(myLocalEndpoints.isEmpty()) System.out.println("    <None found>");
            else myLocalEndpoints.forEach(ep -> System.out.println("    - " + ep));

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(P2PNode::shutdown));

            // Start listener thread using its own executor
            listenerExecutor.submit(P2PNode::udpListenerLoop);

            // Register with Server - Needs listener running to receive reply
            if (!registerWithServer()) { // Sends username now
                System.err.println("[!!!] Failed to register with server. Exiting.");
                shutdown(); // Trigger shutdown sequence
                return;
            }
            System.out.println("[+] Successfully registered with server. Node ID: " + myNodeId);


            // Start connection manager (keep-alives, pings) using scheduled executor
            startConnectionManager();

            // Start user interaction loop (state machine) - runs in the main thread
            userInteractionLoop(inputScanner); // Pass the scanner

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
            // Shutdown might have been called by hook or error, call again to ensure cleanup
            // (shutdown method handles multiple calls)
            shutdown();
            System.out.println("[*] Node main method finished.");
            // Closing System.in scanner can sometimes cause issues if other parts expect it open.
            // Often left for JVM to handle on exit unless resource leaks are a specific concern.
            // if (inputScanner != null) inputScanner.close();
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
                    // Skip loopback, virtual, P2P, and down interfaces. Consider !ni.supportsMulticast() ?
                    if (ni.isLoopback() || !ni.isUp() || ni.isVirtual() || ni.isPointToPoint()) {
                        continue;
                    }

                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        // Skip link-local, multicast, wildcard, loopback any local address (like 0.0.0.0)
                        if (addr.isLinkLocalAddress() || addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                            continue;
                        }

                        String ip = addr.getHostAddress();
                        String type;
                        // Basic check for IPv4 vs IPv6
                        if (addr instanceof Inet6Address) {
                            // Remove scope ID if present (e.g., %eth0 or %12)
                            int scopeIndex = ip.indexOf('%');
                            if (scopeIndex > 0) {
                                ip = ip.substring(0, scopeIndex);
                            }
                            // Skip IPv4-mapped IPv6 addresses
                            if (ip.startsWith("::ffff:")) continue;
                            type = "local_v6";
                        } else if (addr instanceof Inet4Address) {
                            type = "local_v4";
                        } else {
                            continue; // Skip unknown types
                        }
                        endpoints.add(new Endpoint(ip, udpPort, type));
                    }
                } catch (SocketException se) {
                    System.err.println("[!] SocketException checking interface " + ni.getDisplayName() + ": " + se.getMessage());
                }
            }
        } catch (SocketException e) {
            System.err.println("[!] Error getting network interfaces: " + e.getMessage());
        }

        if (endpoints.isEmpty()) {
            System.out.println("[!] No suitable local non-loopback IPs found. Will rely on public IP seen by server.");
        }
        // Deduplicate based on unique Endpoint definition (uses equals/hashCode)
        return new ArrayList<>(new HashSet<>(endpoints));
    }

    private static boolean registerWithServer() {
        JSONObject registerMsg = new JSONObject();
        registerMsg.put("action", "register");
        registerMsg.put("username", myUsername); // <-- Send username
        JSONArray localEndpointsJson = new JSONArray();
        myLocalEndpoints.forEach(ep -> localEndpointsJson.put(ep.toJson()));
        registerMsg.put("local_endpoints", localEndpointsJson);

        System.out.println("[*] Sending registration to server " + serverAddress + "...");
        boolean sent = sendUdp(registerMsg, serverAddress);
        if (!sent) {
            System.err.println("[!] Failed to send registration message.");
            return false;
        }

        // Simple wait for registration confirmation (handled in listener)
        long startTime = System.currentTimeMillis();
        long timeout = 5000; // 5 second timeout for registration reply
        while (myNodeId == null && System.currentTimeMillis() - startTime < timeout) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[!] Interrupted while waiting for registration confirmation.");
                return false;
            }
        }

        if(myNodeId == null) {
            System.err.println("[!] No registration confirmation received from server within " + timeout/1000 + " seconds.");
            return false;
        }
        // myNodeId is now set if successful
        return true;
    }


    // --- Main Loops (Listener, Manager, User Interaction) ---

    private static void udpListenerLoop() {
        System.out.println("[Listener Thread] Started. Listening for UDP packets...");
        byte[] buffer = new byte[BUFFER_SIZE];
        while (running && udpSocket != null && !udpSocket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                udpSocket.receive(packet); // Blocking call

                // Process packet only if still running after potentially long block
                if (!running) break;

                InetSocketAddress senderAddr = (InetSocketAddress) packet.getSocketAddress();
                String messageStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                 // Ignore empty messages
                 if (messageStr.isEmpty()) {
                     System.out.println("[Listener] Received empty UDP packet from " + senderAddr);
                     continue;
                 }

                JSONObject data = new JSONObject(messageStr);

                // Check if message is from the server (compare resolved addresses)
                boolean fromServer = serverAddress != null && serverAddress.equals(senderAddr);

                if (fromServer) {
                    handleServerMessage(data);
                } else {
                    handlePeerMessage(data, senderAddr);
                }

            } catch (SocketException e) {
                // This exception is expected when the socket is closed during shutdown
                if (running) { // Only log if shutdown wasn't initiated
                    System.err.println("[Listener Thread] Socket closed or error: " + e.getMessage());
                }
                // Loop condition 'while (running && ...)' will handle termination
            }
            catch (IOException e) {
                if (running) {
                    System.err.println("[Listener Thread] I/O error receiving UDP packet: " + e.getMessage());
                     try { Thread.sleep(200); } catch (InterruptedException ignored) {} // Avoid tight loop
                }
            } catch (org.json.JSONException e) {
                 InetSocketAddress senderAddr = (packet != null && packet.getSocketAddress() instanceof InetSocketAddress) ? (InetSocketAddress) packet.getSocketAddress() : null;
                 System.err.println("[Listener Thread] Invalid JSON received from " + (senderAddr != null ? senderAddr : "unknown") + ": " + e.getMessage());
            }
            catch (Exception e) {
                // Catch other unexpected errors during packet processing
                if (running) {
                    System.err.println("[Listener Thread] Error processing UDP packet: " + e.getClass().getName() + " - " + e.getMessage());
                    e.printStackTrace();
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            }
        }
        System.out.println("[*] UDP Listener thread finished.");
    }

    private static void startConnectionManager() {
        // Combined Task for Keep-Alives and Pinging
        scheduledExecutor.scheduleWithFixedDelay(() -> {
            if (!running || udpSocket == null || udpSocket.isClosed()) return; // Exit if not running

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

                // --- P2P Pinging / Hole Punching ---
                if (currentState == NodeState.ATTEMPTING && currentTarget != null && !peerCandidates.isEmpty() && !p2pConnectionEstablished.get()) {
                    if (pingAttempts < MAX_PING_ATTEMPTS) {
                        JSONObject pingMsg = new JSONObject();
                        pingMsg.put("action", "ping");
                        pingMsg.put("node_id", myNodeId);

                        // Make a copy to avoid ConcurrentModificationException
                        List<Endpoint> currentCandidates = new ArrayList<>(peerCandidates);
                        currentCandidates.sort(Comparator.comparing(ep -> (ep.type != null && ep.type.contains("public")) ? 0 : 1));

                        boolean sentPingThisRound = false;
                        for (Endpoint candidate : currentCandidates) {
                            try {
                                InetSocketAddress candidateAddr = new InetSocketAddress(InetAddress.getByName(candidate.ip), candidate.port);
                                sendUdp(pingMsg, candidateAddr);
                                sentPingThisRound = true;
                            } catch (UnknownHostException e) {
                                System.err.println("[Manager] Failed to resolve candidate address: " + candidate.ip + " - " + e.getMessage());
                            } catch(Exception e){
                                System.err.println("[Manager] Error sending ping to " + candidate + " - " + e.getMessage());
                            }
                        }
                        if(sentPingThisRound) {
                            synchronized(stateLock) { // Ensure atomic increment and check
                                if(currentState == NodeState.ATTEMPTING) { // Only increment if still attempting
                                    pingAttempts++;
                                }
                            }
                        }
                    }
                }
                // --- Peer Keep-Alive (if connected) ---
                else if (currentState == NodeState.CONNECTED_IDLE && confirmedPeer != null) {
                    JSONObject keepAliveMsg = new JSONObject();
                    keepAliveMsg.put("action", "keepalive"); // Simple keepalive
                    keepAliveMsg.put("node_id", myNodeId);
                    boolean sent = sendUdp(keepAliveMsg, confirmedPeer);
                    if (!sent) {
                         System.err.println("[Manager] Failed to send keepalive to peer " + getPeerDisplayName() + ". Connection might be lost.");
                         // Optionally trigger connection loss check here
                    }
                }

            } catch (Exception e) {
                System.err.println("[!!!] Error in Connection Manager task: " + e.getMessage());
                e.printStackTrace(); // Log unexpected errors in this task
            }

        }, 1000, // Initial delay 1 second
            Math.min(KEEPALIVE_PEER_INTERVAL_MS, PING_INTERVAL_MS), // Run frequently enough for pings/keepalives
            TimeUnit.MILLISECONDS);


        System.out.println("[*] Started Connection Manager task.");
    }

     // Use the scanner passed from main
    private static void userInteractionLoop(Scanner scanner) {
        try {
            System.out.println("\n--- P2P Node Ready (Username: " + myUsername + ", Node ID: " + myNodeId + ") ---");
            System.out.println("--- Enter 'connect <peer_id>', 'status', or 'quit' ---");

            while (running) {
                try {
                    // --- Check State Transitions based on async events ---
                    checkStateTransitions(); // Update state based on flags set by listener/manager

                    // --- Print Current State Periodically or Handle Input ---
                    long now = Instant.now().toEpochMilli();
                    String prompt = getPrompt(); // Get prompt based on current state

                    // Print status periodically and check timeouts ONLY if waiting/attempting
                    if (currentState == NodeState.WAITING_MATCH || currentState == NodeState.ATTEMPTING) {
                        if (now - lastStatePrintTime > STATE_PRINT_INTERVAL_MS) {
                            System.out.println(getStateDescription()); // Print status
                            System.out.print(prompt); // Reprint prompt
                            lastStatePrintTime = now;
                        }

                        // Check for timeouts
                        if (currentState == NodeState.WAITING_MATCH && (now - waitingSince > WAIT_MATCH_TIMEOUT_MS)) {
                            System.out.println("\n[!] Timed out waiting for peer match (" + WAIT_MATCH_TIMEOUT_MS / 1000 + "s). Cancelling.");
                            resetConnectionState();
                            currentState = NodeState.DISCONNECTED;
                            System.out.println(getStateDescription());
                            System.out.print(getPrompt());
                            continue;
                        }
                         // Check ping timeout
                         if (currentState == NodeState.ATTEMPTING && (pingAttempts >= MAX_PING_ATTEMPTS) && !p2pConnectionEstablished.get()) {
                            System.out.println("\n[!] Failed to establish UDP connection with peer " + targetPeerId.get() + " after " + MAX_PING_ATTEMPTS + " ping cycles.");
                            resetConnectionState();
                            currentState = NodeState.DISCONNECTED;
                            System.out.println(getStateDescription());
                            System.out.print(getPrompt());
                            continue;
                         }

                        // Minimal sleep while waiting to allow state changes/interrupts
                        if (System.in.available() > 0) {
                            // Input detected, fall through
                        } else {
                            Thread.sleep(200); // No input, sleep briefly
                            continue; // Re-check state/timeouts
                        }
                    } else {
                        // Not waiting/attempting, print prompt normally
                        System.out.print(prompt);
                    }

                    // --- Handle User Input ---
                    if (!scanner.hasNextLine()) { // Handle EOF or closed input stream
                        System.out.println("\n[*] Input stream closed. Exiting.");
                        running = false; // Signal shutdown
                        break; // Exit loop
                     }
                    String line = scanner.nextLine().trim();

                    // Re-check state immediately after input, before processing command
                    checkStateTransitions();
                    NodeState stateBeforeCommand = currentState;

                    if (line.isEmpty()) continue;
                    String[] parts = line.split(" ", 2);
                    String command = parts[0].toLowerCase();

                    // Only process commands if the state hasn't changed unexpectedly
                    if(currentState != stateBeforeCommand) {
                        System.out.println("\n[*] State changed during input, please retry.");
                        continue;
                    }

                    // Process commands based on the state WHEN THE COMMAND WAS ENTERED
                    switch (command) {
                        case "quit":
                        case "exit":
                            System.out.println("[*] Quit command received. Initiating shutdown...");
                            shutdown();      // Start the shutdown sequence explicitly
                            System.exit(0); // Exit the JVM gracefully (runs shutdown hooks)
                            break; // Technically unreachable
                        case "connect":
                            if (stateBeforeCommand == NodeState.DISCONNECTED) {
                                if (parts.length > 1 && !parts[1].isEmpty()) {
                                    String peerToConnect = parts[1].trim();
                                    if(peerToConnect.equals(myNodeId)){
                                        System.out.println("[!] Cannot connect to yourself.");
                                    } else if (peerToConnect.length() < 5) { // Basic sanity check
                                        System.out.println("[!] Invalid Peer ID format.");
                                    }
                                    else {
                                        startConnectionAttempt(peerToConnect); // This will change state internally
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
                            if (stateBeforeCommand == NodeState.CONNECTED_IDLE) {
                                System.out.println("[*] Disconnecting from peer " + getPeerDisplayName() + "...");
                                resetConnectionState(); // <-- Resets history manager too
                                currentState = NodeState.DISCONNECTED;
                            } else if (stateBeforeCommand == NodeState.WAITING_MATCH || stateBeforeCommand == NodeState.ATTEMPTING) {
                                System.out.println("[*] Cancelling connection attempt to " + targetPeerId.get() + "...");
                                resetConnectionState();
                                currentState = NodeState.DISCONNECTED;
                            } else {
                                System.out.println("[!] Not currently connected or attempting connection.");
                            }
                            break;
                        case "chat":
                        case "c": // Alias
                            if (stateBeforeCommand == NodeState.CONNECTED_IDLE) {
                                if (parts.length > 1 && !parts[1].isEmpty()) {
                                    sendMessageToPeer(parts[1]); // <-- Saves to history now
                                } else {
                                    System.out.println("[!] Usage: chat <message>");
                                }
                            } else {
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
                            System.out.println("[!] Unknown command. Available: connect, disconnect/cancel, chat, status, id, quit");
                            break;
                    }
                } // End inner try block for loop iteration

                catch (NoSuchElementException e) { // Scanner closed unexpectedly
                    System.out.println("\n[*] Input stream ended unexpectedly. Shutting down.");
                    running = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("\n[*] Interrupted. Shutting down.");
                    running = false;
                } catch (Exception e) {
                    // Catch other errors within the loop to prevent crashing
                    System.err.println("\n[!!!] Error in user interaction loop: " + e.getMessage());
                    e.printStackTrace();
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            } // End while(running)
        } finally {
             // Decide if scanner needs closing here or in main's finally. Avoid double-close.
        }
        System.out.println("[*] User Interaction loop finished.");
    }


    // --- State Management & Transitions ---

    /** Checks atomic flags set by other threads and updates the main state machine. */
    private static void checkStateTransitions() {
        NodeState previousState = currentState;

        synchronized (stateLock) { // Synchronize checks and state changes
            // Event: Received connection info while waiting for match
            if (currentState == NodeState.WAITING_MATCH && connectionInfoReceived.compareAndSet(true, false)) {
                if (!peerCandidates.isEmpty()) { // Ensure we actually got candidates
                    currentState = NodeState.ATTEMPTING;
                    pingAttempts = 0; // Reset ping counter
                    System.out.println("\n[*] Received peer info for " + getPeerDisplayName() +". Attempting UDP P2P connection...");
                } else {
                    System.out.println("\n[!] Received connection info response, but peer candidate list was empty. Cancelling.");
                    resetConnectionState();
                    currentState = NodeState.DISCONNECTED;
                }
            }
            // Event: P2P connection established while waiting or attempting
            else if ((currentState == NodeState.WAITING_MATCH || currentState == NodeState.ATTEMPTING) && p2pConnectionEstablished.get()) {
                currentState = NodeState.CONNECTED_IDLE;
                InetSocketAddress confirmedAddr = peerAddrConfirmed.get();
                String peerId = connectedPeerId.get(); // Get confirmed peer ID
                String peerUsername = connectedPeerUsername.get(); // Get confirmed peer Username

                System.out.println("\n-----------------------------------------------------");
                System.out.println("[+] UDP P2P CONNECTION ESTABLISHED with " + getPeerDisplayName() + "!");
                System.out.println("    Using path: YOU -> " + (confirmedAddr != null ? confirmedAddr : "???"));
                System.out.println("-----------------------------------------------------");

                // --- Initialize History Manager ---
                if (peerId != null) { // Should always be non-null here
                    chatHistoryManager = new ChatHistoryManager();
                    chatHistoryManager.initialize(myNodeId, peerId, peerUsername); // Pass peer details
                } else {
                    System.err.println("[!] Cannot initialize history: Peer Node ID is null upon connection!");
                    chatHistoryManager = null;
                }
                // --- End History Manager Initialization ---

                targetPeerId.set(null); // Clear the target, we are now connected
                connectionInfoReceived.set(false); // Reset flags
                peerCandidates.clear();
                pingAttempts = 0;
            }
            // Event: Connection lost while connected (flag cleared externally)
            else if (currentState == NodeState.CONNECTED_IDLE && !p2pConnectionEstablished.get()) {
                System.out.println("\n[*] UDP P2P connection with " + getPeerDisplayName() + " lost or disconnected.");
                resetConnectionState(); // <-- Resets history manager too
                currentState = NodeState.DISCONNECTED;
            }
        } // End synchronized block

        if (currentState != previousState) {
            System.out.println("[State Change] " + previousState + " -> " + currentState);
            // Print prompt again if state changed non-interactively
            System.out.print(getPrompt());
        }
    }

     /** Gets a description of the current node state. */
    private static String getStateDescription() {
        switch (currentState) {
            case DISCONNECTED: return "[Status] Disconnected. Your Node ID: " + myNodeId + " | Username: " + myUsername;
            case WAITING_MATCH:
                long elapsed = (System.currentTimeMillis() - waitingSince) / 1000;
                return "[Status] Request sent. Waiting for peer " + targetPeerId.get().substring(0,8) + "... (" + elapsed + "s / " + WAIT_MATCH_TIMEOUT_MS/1000 + "s)";
            case ATTEMPTING:
                return "[Status] Attempting UDP P2P connection to " + targetPeerId.get().substring(0,8) + "... (Ping cycle: " + pingAttempts + "/" + MAX_PING_ATTEMPTS + ")";
            case CONNECTED_IDLE:
                InetSocketAddress addr = peerAddrConfirmed.get();
                return "[Status] UDP P2P Connected to " + getPeerDisplayName() + " (" + (addr != null ? addr : "???") + ")"; // Use helper
            default: return "[Status] Unknown State";
        }
    }

    /** Gets the appropriate command prompt based on the current state. */
    private static String getPrompt() {
        switch (currentState) {
            case DISCONNECTED: return "[?] Enter 'connect <peer_id>', 'status', 'id', 'quit': ";
            case WAITING_MATCH: return "[Waiting for " + targetPeerId.get().substring(0,8) + "... ('cancel'/'disconnect' to stop)] ";
            case ATTEMPTING: return "[Attempting " + targetPeerId.get().substring(0,8) + "... ('cancel'/'disconnect' to stop)] ";
            case CONNECTED_IDLE: return "[Chat (" + getPeerDisplayName() + ")] ('chat <msg>', 'disconnect', 'status', 'quit'): "; // Use helper
            default: return "> ";
        }
    }

    /** Gets the display name for the connected peer (Username or ID prefix). */
    private static String getPeerDisplayName() {
        String username = connectedPeerUsername.get();
        if (username != null && !username.isEmpty()) {
            return username;
        }
        String peerId = connectedPeerId.get();
        if (peerId != null) {
            // Avoid potential NPE if peerId becomes null concurrently, though unlikely with atomics
            return peerId.substring(0, Math.min(8, peerId.length())) + "...";
        }
        String targetId = targetPeerId.get(); // Fallback for attempting/waiting state display maybe?
        if(targetId != null) {
             return targetId.substring(0, Math.min(8, targetId.length())) + "...";
        }
        return "???";
    }

    /** Initiates a connection attempt to the specified peer ID. */
    private static void startConnectionAttempt(String peerToConnect) {
        synchronized (stateLock) {
            if (currentState != NodeState.DISCONNECTED) {
                System.out.println("[!] Cannot start new connection attempt while in state: " + currentState);
                return;
            }
            System.out.println("[*] Requesting connection info for peer: " + peerToConnect.substring(0, Math.min(8, peerToConnect.length())) + "...");
            resetConnectionState(); // Ensure clean state before starting

            targetPeerId.set(peerToConnect); // Set who we want to connect to

            JSONObject requestMsg = new JSONObject();
            requestMsg.put("action", "request_connection");
            requestMsg.put("node_id", myNodeId);
            requestMsg.put("target_id", peerToConnect);
            boolean sent = sendUdp(requestMsg, serverAddress);

            if (sent) {
                currentState = NodeState.WAITING_MATCH;
                waitingSince = System.currentTimeMillis();
                lastStatePrintTime = 0; // Force print status on next loop iteration
            } else {
                 System.err.println("[!] Failed to send connection request to server.");
                 targetPeerId.set(null); // Reset target if send failed
                 // Stay in DISCONNECTED state
            }
        }
    }

    /** Resets all variables related to an active or pending connection attempt. */
    private static void resetConnectionState() {
        synchronized (stateLock) { // Ensure atomic reset
            targetPeerId.set(null);
            connectedPeerId.set(null);
            connectedPeerUsername.set(null);
            peerCandidates.clear();
            peerAddrConfirmed.set(null);
            connectionInfoReceived.set(false);
            p2pConnectionEstablished.set(false); // Crucial: signal connection is down
            pingAttempts = 0;
            waitingSince = 0;
            chatHistoryManager = null; // <-- Reset history manager instance
            System.out.println("[*] Reset connection state variables.");
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
                myNodeId = receivedNodeId; // Set our Node ID
                JSONObject publicEpJson = data.optJSONObject("your_public_endpoint");
                if(publicEpJson != null) {
                    myPublicEndpointSeen = Endpoint.fromJson(publicEpJson);
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
                String peerUsername = data.optString("peer_username", null); // <-- Extract peer username

                if (candidatesJson != null) {
                    synchronized(stateLock) { // Protect access to shared state
                        peerCandidates.clear(); // Clear old candidates
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
                            connectionInfoReceived.set(true); // Signal that valid info was received
                            // Store the peer's username if we received it
                            if (peerUsername != null && !peerUsername.isEmpty()) {
                                connectedPeerUsername.set(peerUsername); // Set atomic reference
                                System.out.println("[*] Received Peer Username: " + peerUsername);
                            } else {
                                connectedPeerUsername.set(null); // Clear if not received
                                System.out.println("[!] Peer username not provided by server.");
                            }
                        } else {
                            System.out.println("[!] Received empty or invalid candidate list for peer " + receivedPeerId.substring(0,8) + "...");
                            connectedPeerUsername.set(null); // Clear if candidates invalid
                        }
                    } // End synchronized block
                } else {
                    System.out.println("[!] Received 'connection_info' but missing 'peer_endpoints' array.");
                    connectedPeerUsername.set(null); // Clear if message invalid
                }
            } else {
                System.out.println("[?] Received connection_info for unexpected peer " + receivedPeerId + " while targeting " + currentTarget + ". Ignoring.");
            }
        }
         // --- Server Error Messages ---
         else if ("error".equals(status)) {
            String message = data.optString("message", "Unknown error");
            System.err.println("\n[!] Server Error: " + message);
            synchronized(stateLock) {
                if (currentState == NodeState.WAITING_MATCH || currentState == NodeState.ATTEMPTING) {
                    System.out.println("    Cancelling connection attempt due to server error.");
                    resetConnectionState();
                    currentState = NodeState.DISCONNECTED; // Force back to disconnected
                }
            }
         }
         // --- Acknowledgment of Connection Request ---
         else if ("connection_request_received".equals(status) && "ack".equals(action)) {
             String waitingFor = data.optString("waiting_for", "?");
             if (currentState == NodeState.WAITING_MATCH && waitingFor.equals(targetPeerId.get())) {
                 System.out.println("[*] Server acknowledged request. Waiting for peer " + waitingFor.substring(0,8) + "... to connect.");
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

        // --- Processing During Connection Attempt (State: WAITING_MATCH or ATTEMPTING) ---
        if (!p2pConnectionEstablished.get() && currentTarget != null && currentTarget.equals(senderId)) {
            boolean senderIsCandidate = false;
             synchronized(stateLock){ // Access peerCandidates safely
                 senderIsCandidate = peerCandidates.stream().anyMatch(cand -> {
                     try {
                         return new InetSocketAddress(InetAddress.getByName(cand.ip), cand.port).equals(peerAddr);
                     } catch (UnknownHostException e) { return false; }
                 });
             }

            if (senderIsCandidate || action.equals("ping") || action.equals("pong")) {
                 synchronized(stateLock) { // Lock for atomic update
                     if (peerAddrConfirmed.compareAndSet(null, peerAddr)) {
                         System.out.println("\n[*] CONFIRMED receiving directly from Peer " + senderId.substring(0,8) + "... at " + peerAddr + "!");
                         connectedPeerId.set(senderId); // Set the connected peer ID now
                         // Note: We get the username via server's connection_info, not directly here.
                     }
                 } // End synchronized block

                if ("ping".equals(action)) {
                    JSONObject pongMsg = new JSONObject();
                    pongMsg.put("action", "pong");
                    pongMsg.put("node_id", myNodeId);
                    sendUdp(pongMsg, peerAddr);
                    if (p2pConnectionEstablished.compareAndSet(false, true)) {
                        System.out.println("[+] P2P UDP Path Confirmed (Got PING, Sent PONG)!");
                        // State transition happens in main loop check
                    }
                } else if ("pong".equals(action)) {
                    if (p2pConnectionEstablished.compareAndSet(false, true)) {
                         System.out.println("[+] P2P UDP Path Confirmed (Got PONG)!");
                         // State transition happens in main loop check
                    }
                }
                 else if (!action.equals("keepalive")) {
                     System.out.println("[?] Ignoring action '" + action + "' from " + senderId.substring(0,8) + " during connection setup.");
                 }

            } else {
                System.out.println("[?] Received message from expected peer " + senderId.substring(0,8) + " but from an unexpected address " + peerAddr + ". Ignoring.");
            }
        }
        // --- Processing After Connection Established (State: CONNECTED_IDLE) ---
        else if (p2pConnectionEstablished.get() && currentPeer != null && currentPeer.equals(senderId)) {
            InetSocketAddress confirmedAddr = peerAddrConfirmed.get();

            // SECURITY: Only accept messages from the confirmed peer address once connected
            if (confirmedAddr == null || !peerAddr.equals(confirmedAddr)) {
                System.out.println("[?] WARNING: Received message from connected peer " + getPeerDisplayName() + " but from wrong address " + peerAddr + " (expected " + confirmedAddr + "). IGNORING.");
                return;
            }

            switch (action) {
                case "chat": { // Use braces to scope variables
                    String messageContent = data.optString("message", "");
                    String usernameFromPayload = data.optString("username", null); // Username sent with chat msg

                    String knownPeerUsername = connectedPeerUsername.get(); // Username learned at connection time
                    String displayUsername;

                    if (knownPeerUsername != null && !knownPeerUsername.isEmpty()) {
                        displayUsername = knownPeerUsername; // Prefer username known for the connection
                    } else if (usernameFromPayload != null && !usernameFromPayload.isEmpty()) {
                        displayUsername = usernameFromPayload; // Fallback to username in message
                    } else {
                        // Fallback to Node ID prefix if no username known
                        displayUsername = senderId.substring(0, Math.min(8, senderId.length())) + "...";
                    }

                    System.out.print("\r[" + displayUsername + "]: " + messageContent + "\n" + getPrompt()); // Display first

                    // --- ADD RECEIVED MESSAGE TO HISTORY ---
                    if (chatHistoryManager != null) {
                        long timestamp = System.currentTimeMillis(); // Timestamp on receive
                        // Use the username associated with the connection if possible, else the one from payload
                        String peerUsernameForHistory = (knownPeerUsername != null) ? knownPeerUsername : usernameFromPayload;
                        // Ensure senderId is the peer's ID for history context
                        String peerIdForHistory = senderId;
                        chatHistoryManager.addMessage(timestamp, peerIdForHistory, peerUsernameForHistory, "RECEIVED", messageContent);
                    }
                    // --- END ADD RECEIVED MESSAGE TO HISTORY ---
                    break;
                } // end case "chat"
                case "keepalive":
                    // Received keepalive from peer. Good. No action needed currently.
                    break;
                case "ping":
                    // Still respond to pings even when connected
                    JSONObject pongMsg = new JSONObject();
                    pongMsg.put("action", "pong");
                    pongMsg.put("node_id", myNodeId);
                    sendUdp(pongMsg, peerAddr);
                    break;
                case "pong":
                    // Received pong (e.g., in response to our keepalive ping) - do nothing.
                    break;
                default:
                    System.out.println("[?] Received unknown action '" + action + "' from connected peer " + getPeerDisplayName());
                    break;
            }
        }
    }


    // --- Sending Messages ---

    /** Safely sends a UDP datagram with JSON payload. Returns true on success, false on failure. */
    private static boolean sendUdp(JSONObject jsonObject, InetSocketAddress destination) {
        if (udpSocket == null || udpSocket.isClosed()) {
            System.err.println("[!] Cannot send UDP: Socket is null or closed.");
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
            System.err.println("[!] Error sending UDP to " + destination + ": Port Unreachable. Peer might be offline.");
             // Could trigger connection loss logic here if this happens with a connected peer.
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

    /** Sends a chat message to the currently connected peer. */
    private static void sendMessageToPeer(String message) {
        InetSocketAddress peerAddr = peerAddrConfirmed.get();
        String peerId = connectedPeerId.get(); // Get the ID of the connected peer

        if (peerAddr != null && peerId != null && p2pConnectionEstablished.get()) { // Check all conditions
            JSONObject chatMsg = new JSONObject();
            chatMsg.put("action", "chat");
            chatMsg.put("node_id", myNodeId); // Include our ID
            chatMsg.put("username", myUsername); // <-- Include our username
            chatMsg.put("message", message);

            boolean sent = sendUdp(chatMsg, peerAddr);

            // --- ADD SENT MESSAGE TO HISTORY ---
            if (sent && chatHistoryManager != null) {
                long timestamp = System.currentTimeMillis(); // Get timestamp *after* trying to send
                String peerUsername = connectedPeerUsername.get(); // Get known username
                chatHistoryManager.addMessage(timestamp, peerId, peerUsername, "SENT", message);
            }
            // --- END ADD SENT MESSAGE TO HISTORY ---

            if (!sent) {
                System.out.println("[!] Failed to send chat message. Connection might be lost.");
                // Consider triggering a connection check or reset here
            }
        } else {
            System.out.println("[!] Cannot send chat message, not fully connected to a peer.");
        }
    }

    // --- Shutdown ---

    /** Gracefully shuts down the node, closing sockets and stopping threads. */
    private static synchronized void shutdown() { // Synchronized to prevent concurrent shutdown calls
        if (!running) {
            return; // Already shutting down or shut down
        }
        System.out.println("\n[*] Shutting down node...");
        running = false; // Signal all loops and tasks to stop

        // 1. Shutdown scheduled tasks forcefully
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdownNow();
            System.out.println("[*] Scheduled executor shutdown requested.");
            try {
                if (!scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println("[!] Scheduled executor did not terminate cleanly.");
                } else {
                    System.out.println("[*] Scheduled executor terminated.");
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // 2. Close UDP Socket (this should interrupt the blocking receive)
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            System.out.println("[*] UDP Socket closed.");
        }

        // 3. Shutdown listener executor gracefully
        if (listenerExecutor != null && !listenerExecutor.isShutdown()) {
            listenerExecutor.shutdown();
            System.out.println("[*] Listener executor shutdown requested.");
             try {
                if (!listenerExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    System.err.println("[!] Listener thread did not terminate cleanly after 2s, forcing...");
                    listenerExecutor.shutdownNow(); // Force
                } else {
                    System.out.println("[*] Listener executor terminated.");
                }
             } catch (InterruptedException e) {
                listenerExecutor.shutdownNow(); // Force if interrupted
                Thread.currentThread().interrupt();
             }
        }

        // 4. Optional: Clean up history manager resources if needed
        // if (chatHistoryManager != null) { chatHistoryManager.close(); }

        System.out.println("[*] Node shutdown sequence complete.");
    }
} // End of P2PNode class