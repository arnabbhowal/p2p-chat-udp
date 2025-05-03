package main.java.com.p2pchat.node.service;

import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.node.config.NodeConfig;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.network.NetworkManager;
// Import the callback interface
import main.java.com.p2pchat.node.ui.GuiCallback;

import org.json.JSONObject;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ConnectionService {

    private final NodeContext context;
    private final NetworkManager networkManager;
    private final ScheduledExecutorService scheduledExecutor;
    private GuiCallback guiCallback = null; // <<< Added callback reference

    public ConnectionService(NodeContext context, NetworkManager networkManager) {
        this.context = context;
        this.networkManager = networkManager;
        // Use daemon thread factory for executor
        this.scheduledExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ConnectionManagerThread");
            t.setDaemon(true); // Make it a daemon thread
            return t;
        });
    }

    // <<< Added setter for callback >>>
    public void setGuiCallback(GuiCallback callback) {
        this.guiCallback = callback;
    }

    public void start() {
        // Check if already shut down
        if (scheduledExecutor.isShutdown()) {
             System.err.println("[!] ConnectionService executor already shut down. Cannot start.");
             return;
        }
        scheduledExecutor.scheduleWithFixedDelay(this::connectionManagerTask,
                1000, // Initial delay 1 sec
                Math.min(NodeConfig.KEEPALIVE_PEER_INTERVAL_MS, NodeConfig.PING_INTERVAL_MS) / 2, // Check more frequently
                TimeUnit.MILLISECONDS);
        System.out.println("[*] Started Connection Manager task.");
    }

    public void stop() {
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdownNow();
            System.out.println("[*] Connection Manager executor shutdown requested.");
            try {
                if (!scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS))
                    System.err.println("[!] Connection Manager executor did not terminate cleanly.");
                else System.out.println("[*] Connection Manager executor terminated.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void connectionManagerTask() {
        // Prevent task running during shutdown or if socket closed
        if (!context.running.get() || context.udpSocket == null || context.udpSocket.isClosed()) return;

        try {
            // Only send keepalive if registered
            if (context.myNodeId.get() != null) {
                sendServerKeepAlive();
            }
            // Manage P2P connection based on current state
            manageP2PConnection();
        } catch (Exception e) {
            System.err.println("[!!!] Error in Connection Manager task: " + e.getMessage());
            e.printStackTrace(); // Log stack trace for debugging
        }
    }

    private void sendServerKeepAlive() {
        // No state change here, just network activity
        if (context.serverAddress != null && context.myNodeId.get() != null) {
            JSONObject keepAliveMsg = new JSONObject();
            keepAliveMsg.put("action", "keep_alive");
            keepAliveMsg.put("node_id", context.myNodeId.get());
            networkManager.sendUdp(keepAliveMsg, context.serverAddress);
        }
    }

    private void manageP2PConnection() {
        NodeState stateNow = context.currentState.get();

        // --- P2P Pinging (Only during ATTEMPTING_UDP) ---
        if (stateNow == NodeState.ATTEMPTING_UDP) {
            attemptP2PPing();
        }
        // --- Peer Keep-Alive (if securely connected) ---
        else if (stateNow == NodeState.CONNECTED_SECURE) {
            sendPeerKeepAlive();
        }
        // --- Timeout Checks (for intermediate states) ---
        checkTimeouts(); // This might change state and call the callback
    }

    private void attemptP2PPing() {
        String currentTarget = context.targetPeerId.get();
        // Check if we should be pinging
        if (currentTarget == null || context.peerCandidates.isEmpty() || context.p2pUdpPathConfirmed.get()) {
            return; // No target, no candidates, or already confirmed
        }

        synchronized(context.stateLock) { // Lock for state check and attempt increment
            if (context.currentState.get() != NodeState.ATTEMPTING_UDP) return; // Recheck state

            if (context.pingAttempts < NodeConfig.MAX_PING_ATTEMPTS) {
                JSONObject pingMsg = new JSONObject();
                pingMsg.put("action", "ping");
                pingMsg.put("node_id", context.myNodeId.get());

                // Prioritize public endpoints
                List<Endpoint> currentCandidates = new ArrayList<>(context.peerCandidates);
                currentCandidates.sort(Comparator.comparing(ep -> (ep.type != null && ep.type.contains("public")) ? 0 : 1));

                boolean sentPingThisRound = false;
                System.out.println("[Ping Cycle " + (context.pingAttempts + 1) + "] Sending PING to " + currentCandidates.size() + " candidates for " + currentTarget.substring(0,8)+"...");
                for (Endpoint candidate : currentCandidates) {
                    try {
                        InetSocketAddress candidateAddr = new InetSocketAddress(InetAddress.getByName(candidate.ip), candidate.port);
                        if (networkManager.sendUdp(pingMsg, candidateAddr)) {
                            sentPingThisRound = true;
                            // System.out.println("    -> PING sent to " + candidateAddr); // Verbose
                        }
                    } catch (Exception e) {
                        // System.err.println("    -> Failed PING to " + candidate + ": " + e.getMessage()); // Verbose error
                    }
                }
                if (sentPingThisRound) {
                    context.pingAttempts++;
                    // No state change yet, GUI update handled by state polling or timeout check
                } else {
                    System.out.println("[Ping Cycle " + (context.pingAttempts + 1) + "] Failed to send any pings this round.");
                }
            }
            // Timeout check handled by checkTimeouts()
        }
    }

    private void sendPeerKeepAlive() {
        InetSocketAddress confirmedPeer = context.peerAddrConfirmed.get();
        if (confirmedPeer != null && context.myNodeId.get() != null) {
            JSONObject keepAliveMsg = new JSONObject();
            keepAliveMsg.put("action", "keepalive");
            keepAliveMsg.put("node_id", context.myNodeId.get());
            networkManager.sendUdp(keepAliveMsg, confirmedPeer);
        }
    }

    private void checkTimeouts() {
        NodeState stateNow = context.currentState.get();
        long now = System.currentTimeMillis();

        String reason = null;

        if (stateNow == NodeState.WAITING_MATCH && context.waitingSince > 0 && (now - context.waitingSince > NodeConfig.WAIT_MATCH_TIMEOUT_MS)) {
            reason = "Timeout waiting for match (" + NodeConfig.WAIT_MATCH_TIMEOUT_MS / 1000 + "s)";
        }
        else if (stateNow == NodeState.ATTEMPTING_UDP && !context.p2pUdpPathConfirmed.get() && (context.pingAttempts >= NodeConfig.MAX_PING_ATTEMPTS)) {
            reason = "Ping timeout (" + NodeConfig.MAX_PING_ATTEMPTS + " cycles)";
        }

        if (reason != null) {
            System.out.println("\n[!] " + reason + ". Cancelling connection attempt.");
            // Use cancelConnectionAttempt which handles state change and callback
            cancelConnectionAttempt(reason);
        }
    }


    // Called by GUI to start the process
    public void initiateConnection(String peerToConnect) {
        synchronized (context.stateLock) {
            if (context.currentState.get() != NodeState.DISCONNECTED) {
                String msg = "[!] Cannot start new connection attempt while in state: " + context.currentState.get();
                System.out.println(msg);
                if (guiCallback != null) guiCallback.appendMessage("System: " + msg);
                return;
            }
            if (context.myKeyPair == null) {
                 String msg = "[!] Cannot start connection: KeyPair missing.";
                 System.err.println(msg);
                 if (guiCallback != null) guiCallback.appendMessage("System: Error - " + msg);
                 return;
            }
            if (context.myNodeId.get() == null) {
                 String msg = "[!] Cannot start connection: Not registered (Node ID missing).";
                 System.err.println(msg);
                 if (guiCallback != null) guiCallback.appendMessage("System: Error - " + msg);
                 return;
            }
             if (peerToConnect.equals(context.myNodeId.get())) {
                 String msg = "[!] Cannot connect to yourself.";
                 System.out.println(msg);
                 if (guiCallback != null) guiCallback.appendMessage("System: " + msg);
                 return;
             }
             if (peerToConnect.length() < 10) { // Basic validation
                 String msg = "[!] Invalid Peer ID format.";
                 System.out.println(msg);
                 if (guiCallback != null) guiCallback.appendMessage("System: " + msg);
                 return;
             }

            System.out.println("[*] Requesting connection info for peer: " + peerToConnect);
            context.resetConnectionState(); // Clear previous attempt state first

            context.targetPeerId.set(peerToConnect);
            if (guiCallback != null) guiCallback.appendMessage("System: Requesting connection to: " + peerToConnect.substring(0, 8) + "...");

            JSONObject requestMsg = new JSONObject();
            requestMsg.put("action", "request_connection");
            requestMsg.put("node_id", context.myNodeId.get());
            requestMsg.put("target_id", peerToConnect);
            boolean sent = networkManager.sendUdp(requestMsg, context.serverAddress);

            if (sent) {
                context.currentState.set(NodeState.WAITING_MATCH);
                context.waitingSince = System.currentTimeMillis();
                // <<< Notify GUI >>>
                if (guiCallback != null) {
                    guiCallback.updateState(NodeState.WAITING_MATCH, null, peerToConnect);
                }
            } else {
                String msg = "[!] Failed to send connection request to server.";
                System.err.println(msg);
                context.resetConnectionState(); // Reset if send fails
                context.currentState.set(NodeState.DISCONNECTED); // Stay disconnected
                // <<< Notify GUI >>>
                if (guiCallback != null) {
                     guiCallback.appendMessage("System: Error - " + msg);
                     guiCallback.updateState(NodeState.DISCONNECTED, null, null);
                }
            }
        }
    }

    // Called by GUI or internally on error/timeout
    public void cancelConnectionAttempt(String reason) {
        synchronized (context.stateLock) {
            NodeState stateNow = context.currentState.get();
            if (stateNow == NodeState.DISCONNECTED || stateNow == NodeState.CONNECTED_SECURE || stateNow == NodeState.INITIALIZING || stateNow == NodeState.SHUTTING_DOWN) {
                // No active attempt to cancel, or already connected/disconnected/shutting down
                return;
            }
            System.out.println("[*] Cancelling connection attempt (" + reason + ")");
            context.resetConnectionState(); // Clear connection specific state
            context.currentState.set(NodeState.DISCONNECTED); // Set final state
            // <<< Notify GUI >>>
            if (guiCallback != null) {
                 guiCallback.appendMessage("System: Connection cancelled (" + reason + ")");
                 guiCallback.updateState(NodeState.DISCONNECTED, null, null);
                 guiCallback.clearFileProgress(); // Clear any potential lingering transfers
            }
        }
    }

    // Called by GUI
    public void disconnectPeer() {
        synchronized(context.stateLock) {
            if (context.currentState.get() != NodeState.CONNECTED_SECURE) {
                 String msg = "[!] Not currently connected to a peer.";
                 System.out.println(msg);
                  if (guiCallback != null) guiCallback.appendMessage("System: " + msg);
                 return;
            }
            String peerDisplay = context.getPeerDisplayName();
            System.out.println("[*] Disconnecting from peer " + peerDisplay + "...");

            // Optionally notify peer
            sendDisconnectNotification();

            context.resetConnectionState(); // Clears peer info, keys, transfers
            context.currentState.set(NodeState.DISCONNECTED);
             // <<< Notify GUI >>>
             if (guiCallback != null) {
                  guiCallback.appendMessage("System: Disconnected from " + peerDisplay + ".");
                  guiCallback.updateState(NodeState.DISCONNECTED, null, null);
                  guiCallback.clearFileProgress(); // Ensure progress indicators are gone
             }
        }
    }

    // Called internally when peer sends disconnect message
    public void handlePeerDisconnect() {
        synchronized(context.stateLock) {
            if (context.currentState.get() == NodeState.CONNECTED_SECURE) {
                String peerDisplay = context.getPeerDisplayName();
                System.out.println("[*] Peer " + peerDisplay + " has disconnected.");
                context.resetConnectionState();
                context.currentState.set(NodeState.DISCONNECTED);
                 // <<< Notify GUI >>>
                 if (guiCallback != null) {
                      guiCallback.appendMessage("System: Peer " + peerDisplay + " disconnected.");
                      guiCallback.updateState(NodeState.DISCONNECTED, null, null);
                      guiCallback.clearFileProgress();
                 }
            }
        }
    }

     // Called internally on P2P send error indicating connection loss
     public void handleConnectionLoss() {
         synchronized(context.stateLock) {
             NodeState stateNow = context.currentState.get();
             // Only act if connected or trying to connect P2P
             if (stateNow == NodeState.CONNECTED_SECURE || stateNow == NodeState.ATTEMPTING_UDP || stateNow == NodeState.WAITING_MATCH) {
                 String peerDisplay = context.getPeerDisplayName();
                 System.out.println("[!] Connection lost with " + peerDisplay + ".");
                 context.resetConnectionState();
                 context.currentState.set(NodeState.DISCONNECTED);
                 // <<< Notify GUI >>>
                 if (guiCallback != null) {
                     guiCallback.appendMessage("System: Connection lost with " + peerDisplay + ".");
                     guiCallback.updateState(NodeState.DISCONNECTED, null, null);
                     guiCallback.clearFileProgress();
                 }
             }
         }
     }

     private void sendDisconnectNotification() {
         InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
         if (peerAddr != null && context.myNodeId.get() != null) {
             JSONObject disconnectMsg = new JSONObject();
             disconnectMsg.put("action", "disconnect");
             disconnectMsg.put("node_id", context.myNodeId.get());
             networkManager.sendUdp(disconnectMsg, peerAddr);
             System.out.println("[*] Sent disconnect notification to peer.");
         }
     }


     // Called by ServerMessageHandler when connection_info is fully processed
     public void processReceivedConnectionInfo() {
         synchronized (context.stateLock) {
             if (context.currentState.get() == NodeState.WAITING_MATCH && context.connectionInfoReceived.get() && context.sharedKeyEstablished.get()) {
                 String targetId = context.targetPeerId.get();
                 System.out.println("[*] Received peer info for " + targetId + ". Attempting UDP P2P connection...");
                 context.currentState.set(NodeState.ATTEMPTING_UDP);
                 context.pingAttempts = 0; // Reset ping counter
                  // <<< Notify GUI >>>
                  if (guiCallback != null) {
                       // Pass targetId as peerNodeId for this state
                       guiCallback.updateState(NodeState.ATTEMPTING_UDP, null, targetId);
                  }
             } else {
                 // Should not happen if logic is correct, but handle defensively
                 String msg = "[!] State mismatch or missing info when processing connection info. Cancelling.";
                 System.err.println(msg);
                 cancelConnectionAttempt("Internal state error processing connection info"); // This will update GUI
             }
         }
     }

    // Called by PeerMessageHandler when ping/pong confirms UDP path
    public void processUdpPathConfirmation() {
        synchronized (context.stateLock) {
            // Check if we are in the right state and the shared key is also ready
            if (context.currentState.get() == NodeState.ATTEMPTING_UDP && context.p2pUdpPathConfirmed.get() && context.sharedKeyEstablished.get()) {
                context.currentState.set(NodeState.CONNECTED_SECURE);
                InetSocketAddress confirmedAddr = context.peerAddrConfirmed.get();
                String peerDisplay = context.getPeerDisplayName();
                String peerId = context.connectedPeerId.get(); // Should be set now

                String connectMsg = "[+] SECURE E2EE CONNECTION ESTABLISHED with " + peerDisplay + "!";
                String pathMsg = "    Using path: YOU -> " + (confirmedAddr != null ? confirmedAddr : "???");

                System.out.println("\n-----------------------------------------------------");
                System.out.println(connectMsg);
                System.out.println(pathMsg);
                System.out.println("-----------------------------------------------------");

                // Initialize history manager
                if (peerId != null) {
                    context.chatHistoryManager = new ChatHistoryManager();
                    context.chatHistoryManager.initialize(context.myNodeId.get(), peerId, context.connectedPeerUsername.get());
                } else {
                    System.err.println("[!] Cannot initialize history: Peer Node ID is null upon secure connection!");
                    context.chatHistoryManager = null;
                }

                // <<< Notify GUI >>>
                if (guiCallback != null) {
                     guiCallback.appendMessage("System: " + connectMsg);
                     guiCallback.updateState(NodeState.CONNECTED_SECURE, context.connectedPeerUsername.get(), peerId);
                }

                // Clear transient state AFTER updating GUI
                context.targetPeerId.set(null);
                context.peerCandidates.clear();
                context.pingAttempts = 0;
                context.connectionInfoReceived.set(false); // Reset for next time

            }
            // If UDP confirmed but key not ready, or vice-versa, state remains ATTEMPTING_UDP.
            // No GUI update needed until both are true and state changes to CONNECTED_SECURE.
        }
    }
}