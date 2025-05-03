package main.java.com.p2pchat.node.service;

import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.node.config.NodeConfig;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.network.NetworkManager;

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

    public ConnectionService(NodeContext context, NetworkManager networkManager) {
        this.context = context;
        this.networkManager = networkManager;
        this.scheduledExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ConnectionManagerThread");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduledExecutor.scheduleWithFixedDelay(this::connectionManagerTask,
                1000, // Initial delay 1 sec
                Math.min(NodeConfig.KEEPALIVE_PEER_INTERVAL_MS, NodeConfig.PING_INTERVAL_MS), // Run frequently
                TimeUnit.MILLISECONDS);
        System.out.println("[*] Started Connection Manager task.");
    }

    public void stop() {
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdownNow();
            System.out.println("[*] Scheduled executor shutdown requested.");
            try {
                if (!scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS))
                    System.err.println("[!] Scheduled executor did not terminate cleanly.");
                else System.out.println("[*] Scheduled executor terminated.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void connectionManagerTask() {
        if (!context.running.get() || context.udpSocket == null || context.udpSocket.isClosed()) return;

        try {
            sendServerKeepAlive();
            manageP2PConnection();
        } catch (Exception e) {
            System.err.println("[!!!] Error in Connection Manager task: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendServerKeepAlive() {
        if (context.myNodeId.get() != null && context.serverAddress != null) {
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
        checkTimeouts();
    }

    private void attemptP2PPing() {
        String currentTarget = context.targetPeerId.get();
         if (currentTarget == null || context.peerCandidates.isEmpty() || context.p2pUdpPathConfirmed.get()) {
             return; // No target, no candidates, or already confirmed
         }

         synchronized(context.stateLock) { // Lock to check/increment attempts atomically with state check
             if (context.currentState.get() != NodeState.ATTEMPTING_UDP) return; // Recheck state

             if (context.pingAttempts < NodeConfig.MAX_PING_ATTEMPTS) {
                 JSONObject pingMsg = new JSONObject();
                 pingMsg.put("action", "ping");
                 pingMsg.put("node_id", context.myNodeId.get());

                 // Sort candidates: public first
                 List<Endpoint> currentCandidates = new ArrayList<>(context.peerCandidates);
                 currentCandidates.sort(Comparator.comparing(ep -> (ep.type != null && ep.type.contains("public")) ? 0 : 1));

                 boolean sentPingThisRound = false;
                 for (Endpoint candidate : currentCandidates) {
                     try {
                         InetSocketAddress candidateAddr = new InetSocketAddress(InetAddress.getByName(candidate.ip), candidate.port);
                         if (networkManager.sendUdp(pingMsg, candidateAddr)) sentPingThisRound = true;
                     } catch (Exception e) { /* Ignore send errors during ping */ }
                 }
                 if (sentPingThisRound) {
                     context.pingAttempts++;
                 }
             }
              // Timeout check moved to checkTimeouts()
         }
    }

    private void sendPeerKeepAlive() {
         InetSocketAddress confirmedPeer = context.peerAddrConfirmed.get();
         if (confirmedPeer != null) {
             JSONObject keepAliveMsg = new JSONObject();
             keepAliveMsg.put("action", "keepalive");
             keepAliveMsg.put("node_id", context.myNodeId.get());
             networkManager.sendUdp(keepAliveMsg, confirmedPeer);
         }
    }

     private void checkTimeouts() {
         NodeState stateNow = context.currentState.get();
         long now = System.currentTimeMillis();

         if (stateNow == NodeState.WAITING_MATCH && (now - context.waitingSince > NodeConfig.WAIT_MATCH_TIMEOUT_MS)) {
              System.out.println("\n[!] Timed out waiting for peer match (" + NodeConfig.WAIT_MATCH_TIMEOUT_MS / 1000 + "s). Cancelling.");
              cancelConnectionAttempt("Timeout waiting for match");
              // UI should react to state change
         }
         else if (stateNow == NodeState.ATTEMPTING_UDP && (context.pingAttempts >= NodeConfig.MAX_PING_ATTEMPTS) && !context.p2pUdpPathConfirmed.get()) {
              System.out.println("\n[!] Failed to establish UDP connection with peer " + context.getPeerDisplayName() + " after " + NodeConfig.MAX_PING_ATTEMPTS + " ping cycles.");
              cancelConnectionAttempt("Ping timeout");
               // UI should react to state change
         }
          // Add timeout for key exchange phase if it were separate? Not needed now.
     }


    // Called by UI to start the process
    public void initiateConnection(String peerToConnect) {
        synchronized (context.stateLock) {
            if (context.currentState.get() != NodeState.DISCONNECTED) {
                System.out.println("[!] Cannot start new connection attempt while in state: " + context.currentState.get());
                return;
            }
            if (context.myKeyPair == null) {
                System.err.println("[!] Cannot start connection: KeyPair missing."); return;
            }
             if (peerToConnect.equals(context.myNodeId.get())) {
                System.out.println("[!] Cannot connect to yourself."); return;
             }
             if (peerToConnect.length() < 10) { // Basic check
                System.out.println("[!] Invalid Peer ID format."); return;
             }

            System.out.println("[*] Requesting connection info for peer: " + peerToConnect);
            context.resetConnectionState(); // Clear previous attempt state first

            context.targetPeerId.set(peerToConnect);

            JSONObject requestMsg = new JSONObject();
            requestMsg.put("action", "request_connection");
            requestMsg.put("node_id", context.myNodeId.get());
            requestMsg.put("target_id", peerToConnect);
            boolean sent = networkManager.sendUdp(requestMsg, context.serverAddress);

            if (sent) {
                context.currentState.set(NodeState.WAITING_MATCH);
                context.waitingSince = System.currentTimeMillis();
            } else {
                System.err.println("[!] Failed to send connection request to server.");
                context.resetConnectionState(); // Reset if send fails
                context.currentState.set(NodeState.DISCONNECTED); // Stay disconnected
            }
        }
    }

    // Called by UI or internally on error/timeout
    public void cancelConnectionAttempt(String reason) {
        synchronized (context.stateLock) {
             NodeState stateNow = context.currentState.get();
             if (stateNow == NodeState.DISCONNECTED || stateNow == NodeState.CONNECTED_SECURE || stateNow == NodeState.INITIALIZING) {
                  // No active attempt to cancel
                  // If connected, use disconnectPeer() instead
                  return;
             }
              System.out.println("[*] Cancelling connection attempt (" + reason + ")");
              context.resetConnectionState();
              context.currentState.set(NodeState.DISCONNECTED);
              // UI should update based on state change
        }
    }

    // Called by UI
    public void disconnectPeer() {
         synchronized(context.stateLock) {
             if (context.currentState.get() != NodeState.CONNECTED_SECURE) {
                  System.out.println("[!] Not currently connected to a peer.");
                  return;
             }
             System.out.println("[*] Disconnecting from peer " + context.getPeerDisplayName() + "...");

              // Optionally notify peer
              sendDisconnectNotification();

              context.resetConnectionState();
              context.currentState.set(NodeState.DISCONNECTED);
              // UI will update based on state change
         }
    }

    // Called internally when peer sends disconnect message or connection drops
    public void handlePeerDisconnect() {
         synchronized(context.stateLock) {
              if (context.currentState.get() == NodeState.CONNECTED_SECURE) {
                   System.out.println("[*] Peer " + context.getPeerDisplayName() + " has disconnected.");
                   context.resetConnectionState();
                   context.currentState.set(NodeState.DISCONNECTED);
                   // UI should update based on state change
              }
         }
    }

     // Called internally on P2P send error indicating connection loss
     public void handleConnectionLoss() {
          synchronized(context.stateLock) {
               NodeState stateNow = context.currentState.get();
               // Only act if connected or trying to connect P2P
               if (stateNow == NodeState.CONNECTED_SECURE || stateNow == NodeState.ATTEMPTING_UDP) {
                    System.out.println("[!] Connection lost with " + context.getPeerDisplayName() + ".");
                    context.resetConnectionState();
                    context.currentState.set(NodeState.DISCONNECTED);
                     // UI should update based on state change
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
                   System.out.println("[*] Received peer info for " + context.targetPeerId.get() + ". Attempting UDP P2P connection...");
                   context.currentState.set(NodeState.ATTEMPTING_UDP);
                   context.pingAttempts = 0; // Reset ping counter
                   // No need to reset connectionInfoReceived flag here, let resetConnectionState handle it
               } else {
                    // Should not happen if logic is correct, but handle defensively
                    System.err.println("[!] State mismatch or missing info when processing connection info. Cancelling.");
                    cancelConnectionAttempt("Internal state error processing connection info");
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
                  System.out.println("\n-----------------------------------------------------");
                  System.out.println("[+] SECURE E2EE CONNECTION ESTABLISHED with " + context.getPeerDisplayName() + "!");
                  System.out.println("    Using path: YOU -> " + (confirmedAddr != null ? confirmedAddr : "???"));
                  System.out.println("-----------------------------------------------------");

                  // Initialize history manager now
                  String peerId = context.connectedPeerId.get();
                  if (peerId != null) {
                       context.chatHistoryManager = new ChatHistoryManager();
                       context.chatHistoryManager.initialize(context.myNodeId.get(), peerId, context.connectedPeerUsername.get());
                  } else {
                       System.err.println("[!] Cannot initialize history: Peer Node ID is null upon secure connection!");
                       context.chatHistoryManager = null;
                  }
                  // Clear transient state
                  context.targetPeerId.set(null);
                  context.peerCandidates.clear();
                  context.pingAttempts = 0;
                  context.connectionInfoReceived.set(false); // Reset for next time
              }
               // If UDP confirmed but key not ready, state remains ATTEMPTING_UDP.
               // If Key ready but UDP not confirmed, state remains ATTEMPTING_UDP.
               // The transition only happens when BOTH are true.
         }
    }
}