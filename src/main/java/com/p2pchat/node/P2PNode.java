package main.java.com.p2pchat.node;

import main.java.com.p2pchat.common.Endpoint;

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
import java.util.stream.Collectors;


public class P2PNode {

    // --- Configuration ---
    // IMPORTANT: Replace 127.0.0.1 with the ACTUAL PUBLIC IP of your server machine
    //            OR pass it as a command-line argument.
    private static String SERVER_IP = "127.0.0.1"; // <--- CHANGE THIS OR PASS VIA ARGS
    private static final int SERVER_PORT = 19999;
    private static final int BUFFER_SIZE = 4096;
    private static final int LOCAL_UDP_PORT = 0; // 0 = Bind to any available port
    private static final long KEEPALIVE_SERVER_INTERVAL_MS = 20 * 1000; // Send keepalive to server every 20s
    private static final long KEEPALIVE_PEER_INTERVAL_MS = 5 * 1000;   // Send keepalive to peer every 5s when connected
    private static final long PING_INTERVAL_MS = 400;     // Send pings to candidates every 400ms when attempting connection
    private static final int MAX_PING_ATTEMPTS = 15;      // Max pings per candidate set (~6 seconds of pinging)
    private static final long WAIT_MATCH_TIMEOUT_MS = 60 * 1000; // Timeout if peer doesn't send request within 60s
    private static final long STATE_PRINT_INTERVAL_MS = 5 * 1000; // How often to print status when waiting/attempting

    // --- State ---
    private static String myNodeId = null;
    private static List<Endpoint> myLocalEndpoints = new ArrayList<>();
    private static Endpoint myPublicEndpointSeen = null;
    // Who we WANT to connect to (set when user types 'connect')
    private static final AtomicReference<String> targetPeerId = new AtomicReference<>(null);
    // Who we ARE connected to (set when P2P connection established)
    private static final AtomicReference<String> connectedPeerId = new AtomicReference<>(null);
    // Potential endpoints received from server for the targetPeerId
    private static final List<Endpoint> peerCandidates = new CopyOnWriteArrayList<>(); // Thread-safe for read/write
    // The specific address of the peer that we successfully communicated with
    private static final AtomicReference<InetSocketAddress> peerAddrConfirmed = new AtomicReference<>(null);

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
    // *** MOVED DECLARATION TO CLASS LEVEL ***
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
            if (!registerWithServer()) {
                System.err.println("[!!!] Failed to register with server. Exiting.");
                shutdown(); // Trigger shutdown sequence
                return;
            }
            System.out.println("[+] Successfully registered with server. Node ID: " + myNodeId);


            // Start connection manager (keep-alives, pings) using scheduled executor
            startConnectionManager();

            // Start user interaction loop (state machine) - runs in the main thread
            userInteractionLoop();

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
                         // System.out.println("    Skipping interface: " + ni.getDisplayName() + " (Loopback/Down/Virtual/P2P)");
                        continue;
                    }
                    // System.out.println("    Checking interface: " + ni.getDisplayName() + " (Up:" + ni.isUp() + ", MTU: " + ni.getMTU() + ")");


                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        // Skip link-local, multicast, wildcard, loopback any local address (like 0.0.0.0)
                        // SiteLocal is often desirable for LAN connections, so we might keep it
                        if (addr.isLinkLocalAddress() || addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                           // System.out.println("        Skipping address: " + addr.getHostAddress() + " (LinkLocal/Loopback/AnyLocal/Multicast)");
                            continue;
                        }

                        String ip = addr.getHostAddress();
                        String type;
                        // Basic check for IPv4 vs IPv6 - may need refinement for specific formats like mapped addresses
                        if (addr instanceof Inet6Address) {
                            // Remove scope ID if present (e.g., %eth0 or %12)
                             int scopeIndex = ip.indexOf('%');
                             if (scopeIndex > 0) {
                                 ip = ip.substring(0, scopeIndex);
                             }
                             // Skip IPv4-mapped IPv6 addresses reported by interface discovery if possible
                             // (they are handled by the socket binding automatically)
                             // Note: This check is basic, might need refinement
                             if (ip.startsWith("::ffff:")) continue; // Skip mapped ones found locally

                            type = "local_v6";
                        } else if (addr instanceof Inet4Address) {
                            type = "local_v4";
                        } else {
                            // System.out.println("        Skipping address: " + ip + " (Unknown type: " + addr.getClass().getName() + ")");
                            continue; // Skip unknown types
                        }

                        // System.out.println("        Found usable address: " + ip + " (" + type + " on " + ni.getName() +")");
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
            // Fallback not strictly needed as server discovers public IP anyway
            // If needed, could add 0.0.0.0 here but it's not very useful info for the peer
        }
        // Deduplicate based on unique Endpoint definition (uses equals/hashCode)
        return new ArrayList<>(new HashSet<>(endpoints));
    }

     private static boolean registerWithServer() {
         JSONObject registerMsg = new JSONObject();
         registerMsg.put("action", "register");
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
         // Relies on the listener thread setting myNodeId upon success
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

                 String shortSenderId = data.has("node_id") ? data.getString("node_id").substring(0, Math.min(8, data.getString("node_id").length())) + "..." : "N/A";
                 // System.out.println("[Listener] Received from " + senderAddr + " (Peer: " + shortSenderId + "): Action: " + data.optString("action","?") ); // Debug: very noisy

                // Check if message is from the server (compare resolved addresses)
                 boolean fromServer = serverAddress != null && serverAddress.equals(senderAddr);

                if (fromServer) {
                    handleServerMessage(data);
                } else {
                    handlePeerMessage(data, senderAddr);
                }

            } catch (SocketException e) {
                 if (running) System.err.println("[Listener Thread] Socket closed or error: " + e.getMessage());
                 // Ensure loop terminates if socket is closed by shutdown()
                 running = false;
             }
            catch (IOException e) {
                if (running) {
                    System.err.println("[Listener Thread] I/O error receiving UDP packet: " + e.getMessage());
                    // Avoid tight loop on persistent I/O errors
                     try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            } catch (org.json.JSONException e) {
                 InetSocketAddress senderAddr = (packet != null && packet.getSocketAddress() instanceof InetSocketAddress) ? (InetSocketAddress) packet.getSocketAddress() : null;
                 System.err.println("[Listener Thread] Invalid JSON received from " + (senderAddr != null ? senderAddr : "unknown") + ": " + e.getMessage());
                 // Optionally log the raw message string here if debugging
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
                      // System.out.println("[Manager] Sent keep-alive to server."); // Debug
                  }

                  String currentTarget = targetPeerId.get();
                  InetSocketAddress confirmedPeer = peerAddrConfirmed.get();

                  // --- P2P Pinging / Hole Punching ---
                  if (currentState == NodeState.ATTEMPTING && currentTarget != null && !peerCandidates.isEmpty() && !p2pConnectionEstablished.get()) {
                      if (pingAttempts < MAX_PING_ATTEMPTS) {
                          // System.out.println("[Manager] Pinging candidates for " + currentTarget + " (Attempt " + (pingAttempts+1) + ")");
                          JSONObject pingMsg = new JSONObject();
                          pingMsg.put("action", "ping");
                          pingMsg.put("node_id", myNodeId);

                           // Prioritize public endpoints, then others
                           // Make a copy to avoid ConcurrentModificationException if list changes
                          List<Endpoint> currentCandidates = new ArrayList<>(peerCandidates);
                           currentCandidates.sort(Comparator.comparing(ep -> (ep.type != null && ep.type.contains("public")) ? 0 : 1));


                          boolean sentPingThisRound = false;
                          for (Endpoint candidate : currentCandidates) {
                              try {
                                  // Resolve address inside the loop in case DNS changes (unlikely but possible)
                                  InetSocketAddress candidateAddr = new InetSocketAddress(InetAddress.getByName(candidate.ip), candidate.port);
                                  // TODO: Add check if candidate address family is compatible with our socket?
                                  // Java's socket might handle sending v4-to-v6 mapped automatically, needs testing.
                                  sendUdp(pingMsg, candidateAddr);
                                  sentPingThisRound = true;
                                  // System.out.println("    -> PING to " + candidate.type + " " + candidate.ip + ":" + candidate.port);
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
                      // Timeout check is now handled in the user interaction loop based on pingAttempts counter
                  }

                  // --- Peer Keep-Alive (if connected) ---
                  else if (currentState == NodeState.CONNECTED_IDLE && confirmedPeer != null) {
                       JSONObject keepAliveMsg = new JSONObject();
                       keepAliveMsg.put("action", "keepalive"); // Simple keepalive, no payload needed
                       keepAliveMsg.put("node_id", myNodeId);
                       boolean sent = sendUdp(keepAliveMsg, confirmedPeer);
                       if (!sent) {
                            System.err.println("[Manager] Failed to send keepalive to peer " + connectedPeerId.get() + ". Connection might be lost.");
                            // Optionally trigger a connection loss check here
                       }
                       // System.out.println("[Manager] Sent keep-alive to peer " + connectedPeerId.get()); // Debug
                  }

             } catch (Exception e) {
                  System.err.println("[!!!] Error in Connection Manager task: " + e.getMessage());
                  e.printStackTrace(); // Log unexpected errors in this task
             }

         // Run this combined task roughly every KEEPALIVE_PEER_INTERVAL_MS. Pinging happens faster when in ATTEMPTING state.
         // The delay calculation ensures it runs reasonably often but respects the intervals.
         }, 1000, // Initial delay 1 second
            Math.min(KEEPALIVE_PEER_INTERVAL_MS, KEEPALIVE_SERVER_INTERVAL_MS) / 2, // Run frequently enough for both keepalives
            TimeUnit.MILLISECONDS);


         System.out.println("[*] Started Connection Manager task.");
     }

     private static void userInteractionLoop() {
          // Use try-with-resources for Scanner to ensure it's closed
          try (Scanner scanner = new Scanner(System.in)) {
             // *** REMOVED LOCAL DECLARATION ***
             // long lastStatePrintTime = 0;
             System.out.println("\n--- P2P Node Ready (Node ID: " + myNodeId + ") ---");
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
                                 resetConnectionState(); // Reset all connection vars
                                 currentState = NodeState.DISCONNECTED;
                                 System.out.println(getStateDescription()); // Print new status
                                 System.out.print(getPrompt()); // Print new prompt
                                 continue; // Restart loop for new input
                            }
                             // Check ping timeout (max attempts reached)
                            if (currentState == NodeState.ATTEMPTING && (pingAttempts >= MAX_PING_ATTEMPTS) && !p2pConnectionEstablished.get()) {
                                 System.out.println("\n[!] Failed to establish UDP connection with peer " + targetPeerId.get() + " after " + MAX_PING_ATTEMPTS + " ping cycles.");
                                 resetConnectionState(); // Reset all connection vars
                                 currentState = NodeState.DISCONNECTED;
                                 System.out.println(getStateDescription()); // Print new status
                                 System.out.print(getPrompt()); // Print new prompt
                                 continue; // Restart loop for new input
                            }

                            // Minimal sleep while waiting to allow state changes/interrupts
                            // Check if input is available without blocking indefinitely
                            if (System.in.available() > 0) {
                                 // Input detected, fall through to scanner.nextLine()
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
                        checkStateTransitions(); // Check if state changed while waiting for input
                        NodeState stateBeforeCommand = currentState; // Capture state before processing command

                       if (line.isEmpty()) continue; // Ignore empty input
                       String[] parts = line.split(" ", 2);
                       String command = parts[0].toLowerCase();

                       // Only process commands if the state hasn't changed unexpectedly
                       if(currentState != stateBeforeCommand) {
                           System.out.println("\n[*] State changed during input, please retry.");
                           continue; // Loop again to show correct prompt/state
                       }

                        // Process commands based on the state WHEN THE COMMAND WAS ENTERED
                        switch (command) {
                            case "quit":
                            case "exit":
                                running = false; // Signal shutdown
                                break; // Exit loop
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
                                    System.out.println("[*] Disconnecting from peer " + connectedPeerId.get() + "...");
                                    // No explicit message needed, just stop keepalives and reset state
                                    resetConnectionState();
                                    currentState = NodeState.DISCONNECTED; // Immediate state change
                                } else if (stateBeforeCommand == NodeState.WAITING_MATCH || stateBeforeCommand == NodeState.ATTEMPTING) {
                                     System.out.println("[*] Cancelling connection attempt to " + targetPeerId.get() + "...");
                                     resetConnectionState();
                                     currentState = NodeState.DISCONNECTED; // Immediate state change
                                } else {
                                     System.out.println("[!] Not currently connected or attempting connection.");
                                }
                                break;
                            case "chat":
                            case "c": // Alias
                                if (stateBeforeCommand == NodeState.CONNECTED_IDLE) {
                                     if (parts.length > 1 && !parts[1].isEmpty()) {
                                         sendMessageToPeer(parts[1]);
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
                                 System.out.println("[*] Your Node ID: " + myNodeId);
                                 break;
                            default:
                                System.out.println("[!] Unknown command. Available: connect, disconnect/cancel, chat, status, id, quit");
                                break;
                        }
                   } // End inner try block for loop iteration

                   catch (NoSuchElementException e) { // Scanner closed unexpectedly
                       System.out.println("\n[*] Input stream ended unexpectedly. Shutting down.");
                       running = false; // Signal shutdown
                   } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println("\n[*] Interrupted. Shutting down.");
                        running = false; // Signal shutdown
                   } catch (Exception e) {
                        // Catch other errors within the loop to prevent crashing
                        System.err.println("\n[!!!] Error in user interaction loop: " + e.getMessage());
                        e.printStackTrace();
                        // Avoid fast loop on error
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                   }
              } // End while(running)
          } // End try-with-resources (Scanner closure)
         System.out.println("[*] User Interaction loop finished.");
     }


    // --- State Management & Transitions ---

     /**
      * Checks atomic flags set by other threads and updates the main state machine.
      * This should be called periodically in the main loop.
      */
     private static void checkStateTransitions() {
          NodeState previousState = currentState;

          synchronized (stateLock) { // Synchronize checks and state changes
               // Event: Received connection info while waiting for match
               if (currentState == NodeState.WAITING_MATCH && connectionInfoReceived.compareAndSet(true, false)) {
                   if (!peerCandidates.isEmpty()) { // Ensure we actually got candidates
                        currentState = NodeState.ATTEMPTING;
                         pingAttempts = 0; // Reset ping counter for new attempt
                         System.out.println("\n[*] Received peer info. Attempting UDP P2P connection to " + targetPeerId.get() + "...");
                         // Manager thread's scheduled task will now start pinging based on ATTEMPTING state
                    } else {
                        System.out.println("\n[!] Received connection info response, but peer candidate list was empty. Cancelling.");
                        resetConnectionState();
                        currentState = NodeState.DISCONNECTED;
                    }
               }
               // Event: P2P connection established while waiting or attempting
               else if ((currentState == NodeState.WAITING_MATCH || currentState == NodeState.ATTEMPTING) && p2pConnectionEstablished.get()) {
                    currentState = NodeState.CONNECTED_IDLE;
                     String peer = connectedPeerId.get(); // Should be set by listener
                     InetSocketAddress confirmedAddr = peerAddrConfirmed.get();
                     System.out.println("\n-----------------------------------------------------");
                     System.out.println("[+] UDP P2P CONNECTION ESTABLISHED with " + (peer != null ? peer.substring(0,8)+"..." : "???") + "!");
                     System.out.println("    Using path: YOU -> " + (confirmedAddr != null ? confirmedAddr : "???"));
                     System.out.println("-----------------------------------------------------");
                     targetPeerId.set(null); // Clear the target, we are now connected
                     connectionInfoReceived.set(false); // Reset flags
                     peerCandidates.clear();
                     pingAttempts = 0; // Reset ping counter
               }
               // Event: Connection lost while connected
               else if (currentState == NodeState.CONNECTED_IDLE && !p2pConnectionEstablished.get()) {
                    // This flag is usually cleared when explicitly disconnecting or potentially
                    // if the keepalive mechanism determines loss (though that's not explicitly implemented here)
                    // We primarily rely on user 'disconnect' or socket errors for now.
                    System.out.println("\n[*] UDP P2P connection with " + connectedPeerId.get() + " lost or disconnected.");
                    resetConnectionState(); // Clear peer state
                    currentState = NodeState.DISCONNECTED;
               }
               // Check if connection failed during ATTEMPTING state (handled by timeout in interaction loop now)

           } // End synchronized block


          if (currentState != previousState) {
              System.out.println("[State Change] " + previousState + " -> " + currentState);
               // Print prompt again if state changed non-interactively
               System.out.print(getPrompt());
          }
     }

    /** Gets a description of the current node state. */
    private static String getStateDescription() {
         // Access volatile/atomic variables directly - safe for reads
         switch (currentState) {
              case DISCONNECTED: return "[Status] Disconnected. Your Node ID: " + myNodeId;
              case WAITING_MATCH:
                    long elapsed = (System.currentTimeMillis() - waitingSince) / 1000;
                    return "[Status] Request sent. Waiting for peer " + targetPeerId.get().substring(0,8) + "... (" + elapsed + "s / " + WAIT_MATCH_TIMEOUT_MS/1000 + "s)";
              case ATTEMPTING:
                   return "[Status] Attempting UDP P2P connection to " + targetPeerId.get().substring(0,8) + "... (Ping cycle: " + pingAttempts + "/" + MAX_PING_ATTEMPTS + ")";
              case CONNECTED_IDLE:
                   String peerId = connectedPeerId.get();
                   InetSocketAddress addr = peerAddrConfirmed.get();
                   return "[Status] UDP P2P Connected to " + (peerId != null ? peerId.substring(0,8)+"..." : "???") + " (" + (addr != null ? addr : "???") + ")";
              default: return "[Status] Unknown State";
         }
     }

     /** Gets the appropriate command prompt based on the current state. */
     private static String getPrompt() {
           switch (currentState) {
                case DISCONNECTED: return "[?] Enter 'connect <peer_id>', 'status', 'id', 'quit': ";
                // For waiting states, show status but don't explicitly solicit input often
                case WAITING_MATCH: return "[Waiting for " + targetPeerId.get().substring(0,8) + "... ('cancel'/'disconnect' to stop)] ";
                case ATTEMPTING: return "[Attempting " + targetPeerId.get().substring(0,8) + "... ('cancel'/'disconnect' to stop)] ";
                case CONNECTED_IDLE: return "[Chat (" + connectedPeerId.get().substring(0,8) + "...)] ('chat <msg>', 'disconnect', 'status', 'quit'): ";
                default: return "> ";
           }
      }

    /** Initiates a connection attempt to the specified peer ID. */
    private static void startConnectionAttempt(String peerToConnect) {
        synchronized (stateLock) { // Ensure atomic state update
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
                 currentState = NodeState.WAITING_MATCH; // Move to waiting state
                 waitingSince = System.currentTimeMillis(); // Start timeout timer
                 // *** UPDATED LINE ***
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
              peerCandidates.clear();
              peerAddrConfirmed.set(null);
              connectionInfoReceived.set(false);
              p2pConnectionEstablished.set(false); // Crucial: signal connection is down
              pingAttempts = 0;
              waitingSince = 0;
              // Note: Doesn't change 'currentState' itself, the caller should handle the transition
              // (e.g., back to DISCONNECTED) after calling this.
              System.out.println("[*] Reset connection state variables.");
          }
     }


    // --- Message Handling ---

    /** Handles messages received from the Coordination Server. */
    private static void handleServerMessage(JSONObject data) {
        String status = data.optString("status", null);
        String action = data.optString("action", null);

        // System.out.println("[Server Message] Status: " + status + ", Action: " + action); // Debug

        // --- Registration Reply ---
        if ("registered".equals(status) && myNodeId == null) { // Only process if not yet registered
            String receivedNodeId = data.optString("node_id");
             if (receivedNodeId != null && !receivedNodeId.isEmpty()) {
                 myNodeId = receivedNodeId; // Set our Node ID
                 JSONObject publicEpJson = data.optJSONObject("your_public_endpoint");
                 if(publicEpJson != null) {
                     myPublicEndpointSeen = Endpoint.fromJson(publicEpJson);
                 }
                 // No state change needed here, main thread waits for myNodeId != null
                 // Logging is done in main thread after successful registration wait
             } else {
                  System.err.println("[!] Received 'registered' status from server but missing 'node_id'.");
             }
        }
        // --- Connection Info Reply ---
        else if ("connection_info".equals(action) && "match_found".equals(status)) {
             String receivedPeerId = data.optString("peer_id");
             String currentTarget = targetPeerId.get(); // Who are we expecting info for?

             // Check if this info is for the peer we are currently trying to connect to
             if (currentTarget != null && currentTarget.equals(receivedPeerId)) {
                  JSONArray candidatesJson = data.optJSONArray("peer_endpoints");
                  if (candidatesJson != null) {
                       synchronized(stateLock) { // Protect access to peerCandidates list
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
                                // State transition to ATTEMPTING will happen in main loop
                            } else {
                                System.out.println("[!] Received empty or invalid candidate list for peer " + receivedPeerId.substring(0,8) + "...");
                                // Do not set connectionInfoReceived to true, stay in WAITING_MATCH (will eventually time out)
                            }
                        } // End synchronized block
                  } else {
                       System.out.println("[!] Received 'connection_info' but missing 'peer_endpoints' array.");
                  }
             } else {
                  // Received info for a peer we are not currently targeting - might be stale?
                  System.out.println("[?] Received connection_info for unexpected peer " + receivedPeerId + " while targeting " + currentTarget + ". Ignoring.");
             }
        }
         // --- Server Error Messages ---
        else if ("error".equals(status)) {
            String message = data.optString("message", "Unknown error");
            System.err.println("\n[!] Server Error: " + message);
             // If we get an error while trying to connect, cancel the attempt
             synchronized(stateLock) {
                 if (currentState == NodeState.WAITING_MATCH || currentState == NodeState.ATTEMPTING) {
                      System.out.println("    Cancelling connection attempt due to server error.");
                      resetConnectionState();
                      currentState = NodeState.DISCONNECTED; // Force back to disconnected
                 }
                  // Special case: If server doesn't know our ID, try to re-register?
                  // This is tricky, might cause loops. For now, just log the error.
                  // if (message.contains("Unknown node ID")) { ... }
             }

        }
         // --- Acknowledgment of Connection Request ---
        else if ("connection_request_received".equals(status) && "ack".equals(action)) {
             String waitingFor = data.optString("waiting_for", "?");
              if (currentState == NodeState.WAITING_MATCH && waitingFor.equals(targetPeerId.get())) {
                  System.out.println("[*] Server acknowledged request. Waiting for peer " + waitingFor.substring(0,8) + "... to connect.");
                  // No state change, just confirmation. Stay in WAITING_MATCH.
              }
        }
         // --- Other or Unknown Server Messages ---
         // else { System.out.println("[?] Unhandled message from server: action=" + action + ", status=" + status); }

    }

    /** Handles messages received directly from other Peers. */
    private static void handlePeerMessage(JSONObject data, InetSocketAddress peerAddr) {
        String action = data.optString("action", null);
        String senderId = data.optString("node_id", null);

        if (senderId == null || action == null) {
             System.out.println("[?] Received invalid message (no action/node_id) from " + peerAddr);
             return; // Ignore invalid messages
         }

        String currentTarget = targetPeerId.get(); // Who we are trying to connect to
        String currentPeer = connectedPeerId.get(); // Who we are already connected to

        // --- Processing During Connection Attempt (State: WAITING_MATCH or ATTEMPTING) ---
         // Check if the sender is the one we're trying to connect to AND we are not already connected
        if (!p2pConnectionEstablished.get() && currentTarget != null && currentTarget.equals(senderId)) {
             // Check if the sender's address is one of the candidates we received OR if it's a PING/PONG used for punching
             boolean senderIsCandidate = false;
              synchronized(stateLock){ // Access peerCandidates safely
                  senderIsCandidate = peerCandidates.stream().anyMatch(cand -> {
                      try {
                           // Compare InetSocketAddress objects for IP+Port match
                           return new InetSocketAddress(InetAddress.getByName(cand.ip), cand.port).equals(peerAddr);
                      } catch (UnknownHostException e) { return false; /* Can't resolve candidate, can't match */ }
                  });
              }


             // Accept if address is a known candidate OR if it's a PING/PONG (essential for hole punching)
             if (senderIsCandidate || action.equals("ping") || action.equals("pong")) {
                  synchronized(stateLock) { // Lock for atomic update of confirmed address and connected ID
                       // Try to set the confirmed address - only succeeds the first time
                       if (peerAddrConfirmed.compareAndSet(null, peerAddr)) {
                            System.out.println("\n[*] CONFIRMED receiving directly from Peer " + senderId.substring(0,8) + "... at " + peerAddr + "!");
                            connectedPeerId.set(senderId); // Set the connected peer ID now that we have a working address
                       }
                  } // End synchronized block

                  // --- Handle PING/PONG for establishing connection ---
                  if ("ping".equals(action)) {
                       // Respond with PONG
                       JSONObject pongMsg = new JSONObject();
                       pongMsg.put("action", "pong");
                       pongMsg.put("node_id", myNodeId);
                       sendUdp(pongMsg, peerAddr);
                       // Atomically set connection established flag - only succeeds the first time
                       if (p2pConnectionEstablished.compareAndSet(false, true)) {
                            System.out.println("[+] P2P UDP Path Confirmed (Got PING, Sent PONG) with " + senderId.substring(0,8) + "... @ " + peerAddr + "!");
                            // State transition to CONNECTED_IDLE will happen in main loop check
                       }
                  } else if ("pong".equals(action)) {
                        // Received PONG in response to our PING
                        // Atomically set connection established flag - only succeeds the first time
                        if (p2pConnectionEstablished.compareAndSet(false, true)) {
                            System.out.println("[+] P2P UDP Path Confirmed (Got PONG) with " + senderId.substring(0,8) + "... @ " + peerAddr + "!");
                             // State transition to CONNECTED_IDLE will happen in main loop check
                       }
                  }
                  // Other actions (like chat) might arrive during attempt, ignore them until fully connected
                  else if (!action.equals("keepalive")) { // Ignore keepalives during setup phase
                       System.out.println("[?] Ignoring action '" + action + "' from " + senderId.substring(0,8) + " during connection setup.");
                  }

             } else {
                  // Message from the correct peer ID, but from an unexpected address, and not a PING/PONG
                 System.out.println("[?] Received message from expected peer " + senderId.substring(0,8) + " but from an unexpected address " + peerAddr + ". Ignoring.");
             }

        } // End handling during connection attempt


        // --- Processing After Connection Established (State: CONNECTED_IDLE) ---
        else if (p2pConnectionEstablished.get() && currentPeer != null && currentPeer.equals(senderId)) {
             // Get the confirmed address ONCE for comparison
             InetSocketAddress confirmedAddr = peerAddrConfirmed.get();

             // SECURITY: Only accept messages from the confirmed peer address once connected
             if (confirmedAddr == null || !peerAddr.equals(confirmedAddr)) {
                 System.out.println("[?] WARNING: Received message from connected peer " + senderId.substring(0,8) + " but from wrong address " + peerAddr + " (expected " + confirmedAddr + "). IGNORING.");
                 return;
             }

             // Process actions from the confirmed peer
             switch (action) {
                  case "chat":
                       // Use \n for newline before chat, use \r to potentially overwrite prompt cleanly (might depend on terminal)
                       System.out.print("\r[Chat from " + senderId.substring(0,8) + "...]: " + data.optString("message", "") + "\n" + getPrompt());
                       break;
                  case "keepalive":
                       // We received a keepalive from the peer. Good. No action needed.
                       // Could potentially reset a peer timeout counter here if implementing peer timeouts.
                       break;
                  case "ping":
                        // Still respond to pings even when connected (peer might be checking reachability)
                        JSONObject pongMsg = new JSONObject();
                        pongMsg.put("action", "pong");
                        pongMsg.put("node_id", myNodeId);
                        sendUdp(pongMsg, peerAddr);
                       break;
                  case "pong":
                        // Received pong (e.g., in response to our keepalive ping) - do nothing.
                        break;
                  // TCP Proxy actions omitted
                  default:
                      System.out.println("[?] Received unknown action '" + action + "' from connected peer " + senderId.substring(0,8) + "...");
                      break;
             }
        } // End handling after connection established

        // --- Ignore messages if: ---
        // - From a peer we are not targeting and not connected to.
        // - From a peer we were previously connected to but are now disconnected.
        // else { System.out.println("[?] Ignoring message from " + senderId.substring(0,8) + " in current state " + currentState); }
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
             // System.out.println("Sent to " + destination + ": " + jsonObject.toString()); // Debug: Very noisy
             return true;
         } catch (PortUnreachableException e) {
             // This can happen if the destination port is closed (e.g., peer app not running)
              System.err.println("[!] Error sending UDP to " + destination + ": Port Unreachable. Peer might be offline.");
              // Could trigger connection loss logic here if this happens with a connected peer.
              return false;
         }
         catch (IOException e) {
             // More general I/O errors (network issues, etc.)
             System.err.println("[!] IO Error sending UDP to " + destination + ": " + e.getMessage());
             return false;
         } catch (Exception e) {
             // Catch any other unexpected errors during send
             System.err.println("[!] Unexpected Error sending UDP to " + destination + ": " + e.getClass().getName() + " - " + e.getMessage());
              return false;
         }
     }

      /** Sends a chat message to the currently connected peer. */
      private static void sendMessageToPeer(String message) {
           // Read atomic references safely
          InetSocketAddress peerAddr = peerAddrConfirmed.get();
          String peerId = connectedPeerId.get(); // Get the ID of the connected peer

          if (peerAddr != null && peerId != null && p2pConnectionEstablished.get()) { // Check all conditions
              JSONObject chatMsg = new JSONObject();
              chatMsg.put("action", "chat");
              chatMsg.put("node_id", myNodeId); // Include our ID
              chatMsg.put("message", message);
              boolean sent = sendUdp(chatMsg, peerAddr);
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
             // Already shutting down or shut down
             return;
        }
        System.out.println("\n[*] Shutting down node...");
        running = false; // Signal all loops and tasks to stop

        // 1. Shutdown scheduled tasks (KeepAlive, Pinging) forcefully
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdownNow(); // Interrupt tasks
             System.out.println("[*] Scheduled executor shutdown requested.");
             try {
                 if (!scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                     System.err.println("[!] Scheduled executor did not terminate cleanly.");
                 } else {
                     System.out.println("[*] Scheduled executor terminated.");
                 }
             } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
         }

        // 2. Close UDP Socket (this should interrupt the blocking receive in the listener)
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            System.out.println("[*] UDP Socket closed.");
        }

        // 3. Shutdown listener executor gracefully (allow current task attempt to finish if possible)
        if (listenerExecutor != null && !listenerExecutor.isShutdown()) {
             listenerExecutor.shutdown(); // Signal shutdown, don't force immediately
             System.out.println("[*] Listener executor shutdown requested.");
              try {
                  // Wait a short time for the listener loop to exit cleanly after socket close
                  if (!listenerExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                      System.err.println("[!] Listener thread did not terminate cleanly after 2s, forcing...");
                      listenerExecutor.shutdownNow(); // Force shutdown if needed
                  } else {
                       System.out.println("[*] Listener executor terminated.");
                  }
              } catch (InterruptedException e) {
                  listenerExecutor.shutdownNow(); // Force if interrupted
                  Thread.currentThread().interrupt();
              }
        }

        System.out.println("[*] Node shutdown sequence complete.");
    }

} // End of P2PNode class