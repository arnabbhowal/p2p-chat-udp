package main.java.com.p2pchat.node.service;

import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.node.config.NodeConfig;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.network.NetworkManager;
import main.java.com.p2pchat.node.ui.GuiCallback; // Import the callback interface

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
        this.scheduledExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ConnectionManagerThread");
            t.setDaemon(true);
            return t;
        });
    }

    // <<< Added setter for callback >>>
    public void setGuiCallback(GuiCallback callback) {
        this.guiCallback = callback;
    }

    public void start() {
        if (scheduledExecutor.isShutdown()) {
             System.err.println("[!] ConnectionService executor already shut down. Cannot start.");
             return;
        }
        scheduledExecutor.scheduleWithFixedDelay(this::connectionManagerTask,
                1000, // Initial delay 1 sec
                Math.min(NodeConfig.KEEPALIVE_PEER_INTERVAL_MS, NodeConfig.PING_INTERVAL_MS) / 2, // Check frequently
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
        if (!context.running.get() || context.udpSocket == null || context.udpSocket.isClosed()) return;

        try {
            if (context.myNodeId.get() != null) { sendServerKeepAlive(); }
            manageP2PConnection();
        } catch (Exception e) {
            System.err.println("[!!!] Error in Connection Manager task: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendServerKeepAlive() {
        if (context.serverAddress != null && context.myNodeId.get() != null) {
            JSONObject keepAliveMsg = new JSONObject();
            keepAliveMsg.put("action", "keep_alive");
            keepAliveMsg.put("node_id", context.myNodeId.get());
            networkManager.sendUdp(keepAliveMsg, context.serverAddress);
        }
    }

    private void manageP2PConnection() {
        NodeState stateNow = context.currentState.get();
        if (stateNow == NodeState.ATTEMPTING_UDP) { attemptP2PPing(); }
        else if (stateNow == NodeState.CONNECTED_SECURE) { sendPeerKeepAlive(); }
        checkTimeouts(); // Check timeouts regardless of state (but logic inside handles state)
    }

    private void attemptP2PPing() {
        String currentTarget = context.targetPeerId.get();
        if (currentTarget == null || context.peerCandidates.isEmpty() || context.p2pUdpPathConfirmed.get()) {
            return;
        }

        synchronized(context.stateLock) {
            if (context.currentState.get() != NodeState.ATTEMPTING_UDP) return;

            if (context.pingAttempts < NodeConfig.MAX_PING_ATTEMPTS) {
                JSONObject pingMsg = new JSONObject();
                pingMsg.put("action", "ping");
                pingMsg.put("node_id", context.myNodeId.get());

                List<Endpoint> currentCandidates = new ArrayList<>(context.peerCandidates);
                currentCandidates.sort(Comparator.comparing(ep -> (ep.type != null && ep.type.contains("public")) ? 0 : 1));

                boolean sentPingThisRound = false;
                if(context.pingAttempts == 0) { // Only log first time
                     if (guiCallback != null) guiCallback.displaySystemMessage("System: Sending initial PINGs to candidates for " + currentTarget.substring(0,8)+"...");
                     System.out.println("[*] Sending initial PINGs to " + currentCandidates.size() + " candidates for " + currentTarget.substring(0,8)+"...");
                }
                for (Endpoint candidate : currentCandidates) {
                    try {
                        InetSocketAddress candidateAddr = new InetSocketAddress(InetAddress.getByName(candidate.ip), candidate.port);
                        if (networkManager.sendUdp(pingMsg, candidateAddr)) { sentPingThisRound = true; }
                    } catch (Exception e) { /* Ignore send errors during ping */ }
                }
                if (sentPingThisRound) { context.pingAttempts++; }
            }
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
            cancelConnectionAttempt(reason); // This handles state change and callback
        }
    }


    public void initiateConnection(String peerToConnect) {
        synchronized (context.stateLock) {
            String userMsgPrefix = "System: "; // Prefix for messages shown to user

            if (context.currentState.get() != NodeState.DISCONNECTED) {
                String msg = "Cannot start new connection attempt while in state: " + context.currentState.get();
                if (guiCallback != null) guiCallback.displaySystemMessage(userMsgPrefix + msg); else System.out.println("[!] "+msg);
                return;
            }
            if (context.myKeyPair == null) {
                 String msg = "Error - Cannot start connection: KeyPair missing.";
                 if (guiCallback != null) guiCallback.displaySystemMessage(userMsgPrefix + msg); else System.err.println("[!] "+msg);
                 return;
            }
            if (context.myNodeId.get() == null) {
                 String msg = "Error - Cannot start connection: Not registered (Node ID missing).";
                 if (guiCallback != null) guiCallback.displaySystemMessage(userMsgPrefix + msg); else System.err.println("[!] "+msg);
                 return;
            }
             if (peerToConnect.equals(context.myNodeId.get())) {
                 String msg = "Cannot connect to yourself.";
                 if (guiCallback != null) guiCallback.displaySystemMessage(userMsgPrefix + msg); else System.out.println("[!] "+msg);
                 return;
             }
             if (peerToConnect.length() < 10) {
                 String msg = "Invalid Peer ID format.";
                 if (guiCallback != null) guiCallback.displaySystemMessage(userMsgPrefix + msg); else System.out.println("[!] "+msg);
                 return;
             }

            System.out.println("[*] Requesting connection info for peer: " + peerToConnect);
            context.resetConnectionState(); // Clear previous attempt state first
            context.targetPeerId.set(peerToConnect);

            if (guiCallback != null) guiCallback.displaySystemMessage(userMsgPrefix + "Requesting connection to: " + peerToConnect.substring(0, 8) + "...");

            JSONObject requestMsg = new JSONObject();
            requestMsg.put("action", "request_connection");
            requestMsg.put("node_id", context.myNodeId.get());
            requestMsg.put("target_id", peerToConnect);
            boolean sent = networkManager.sendUdp(requestMsg, context.serverAddress);

            if (sent) {
                context.currentState.set(NodeState.WAITING_MATCH);
                context.waitingSince = System.currentTimeMillis();
                if (guiCallback != null) {
                    guiCallback.updateState(NodeState.WAITING_MATCH, null, peerToConnect);
                }
            } else {
                String msg = "Error - Failed to send connection request to server.";
                System.err.println("[!] "+msg);
                context.resetConnectionState();
                context.currentState.set(NodeState.DISCONNECTED);
                if (guiCallback != null) {
                     guiCallback.displaySystemMessage(userMsgPrefix + msg);
                     guiCallback.updateState(NodeState.DISCONNECTED, null, null);
                }
            }
        }
    }

    public void cancelConnectionAttempt(String reason) {
        synchronized (context.stateLock) {
             NodeState stateNow = context.currentState.get();
             if (stateNow == NodeState.DISCONNECTED || stateNow == NodeState.CONNECTED_SECURE || stateNow == NodeState.INITIALIZING || stateNow == NodeState.SHUTTING_DOWN) { return; }

              System.out.println("[*] Cancelling connection attempt (" + reason + ")");
              context.resetConnectionState();
              context.currentState.set(NodeState.DISCONNECTED);
              if (guiCallback != null) {
                   guiCallback.displaySystemMessage("System: Connection cancelled (" + reason + ")");
                   guiCallback.updateState(NodeState.DISCONNECTED, null, null);
                   guiCallback.clearFileProgress();
              }
        }
    }

    public void disconnectPeer() {
         synchronized(context.stateLock) {
             if (context.currentState.get() != NodeState.CONNECTED_SECURE) {
                  String msg = "Not currently connected to a peer.";
                  if (guiCallback != null) guiCallback.displaySystemMessage("System: " + msg); else System.out.println("[!] "+msg);
                  return;
             }
             String peerDisplay = context.getPeerDisplayName();
             System.out.println("[*] Disconnecting from peer " + peerDisplay + "...");
             sendDisconnectNotification(); // Notify peer if possible
             context.resetConnectionState();
             context.currentState.set(NodeState.DISCONNECTED);
              if (guiCallback != null) {
                   guiCallback.displaySystemMessage("System: Disconnected from " + peerDisplay + ".");
                   guiCallback.updateState(NodeState.DISCONNECTED, null, null);
                   guiCallback.clearFileProgress();
              }
         }
    }

    public void handlePeerDisconnect() {
         synchronized(context.stateLock) {
              if (context.currentState.get() == NodeState.CONNECTED_SECURE) {
                   String peerDisplay = context.getPeerDisplayName();
                   System.out.println("[*] Peer " + peerDisplay + " has disconnected.");
                   context.resetConnectionState();
                   context.currentState.set(NodeState.DISCONNECTED);
                    if (guiCallback != null) {
                         guiCallback.displaySystemMessage("System: Peer " + peerDisplay + " disconnected.");
                         guiCallback.updateState(NodeState.DISCONNECTED, null, null);
                         guiCallback.clearFileProgress();
                    }
              }
         }
    }

     public void handleConnectionLoss() {
          synchronized(context.stateLock) {
               NodeState stateNow = context.currentState.get();
               if (stateNow == NodeState.CONNECTED_SECURE || stateNow == NodeState.ATTEMPTING_UDP || stateNow == NodeState.WAITING_MATCH) {
                    String peerDisplay = context.getPeerDisplayName();
                    System.out.println("[!] Connection lost with " + peerDisplay + ".");
                    context.resetConnectionState();
                    context.currentState.set(NodeState.DISCONNECTED);
                     if (guiCallback != null) {
                         guiCallback.displaySystemMessage("System: Connection lost with " + peerDisplay + ".");
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
             if(networkManager.sendUdp(disconnectMsg, peerAddr)) {
                System.out.println("[*] Sent disconnect notification to peer.");
             } else {
                 System.out.println("[!] Failed to send disconnect notification to peer.");
             }
         }
     }

     // Called by ServerMessageHandler
     public void processReceivedConnectionInfo() {
         synchronized (context.stateLock) {
             if (context.currentState.get() == NodeState.WAITING_MATCH && context.connectionInfoReceived.get() && context.sharedKeyEstablished.get()) {
                 String targetId = context.targetPeerId.get();
                 System.out.println("[*] Received peer info for " + targetId + ". Attempting UDP P2P connection...");
                 context.currentState.set(NodeState.ATTEMPTING_UDP);
                 context.pingAttempts = 0;
                  if (guiCallback != null) {
                       guiCallback.displaySystemMessage("System: Received peer info. Attempting P2P connection...");
                       guiCallback.updateState(NodeState.ATTEMPTING_UDP, null, targetId);
                  }
             } else {
                 String msg = "State mismatch or missing info when processing connection info. Cancelling.";
                 System.err.println("[!] "+msg);
                 cancelConnectionAttempt("Internal state error processing connection info");
             }
         }
     }

    // Called by PeerMessageHandler
    public void processUdpPathConfirmation() {
        synchronized (context.stateLock) {
            if (context.currentState.get() == NodeState.ATTEMPTING_UDP && context.p2pUdpPathConfirmed.get() && context.sharedKeyEstablished.get()) {
                context.currentState.set(NodeState.CONNECTED_SECURE);
                InetSocketAddress confirmedAddr = context.peerAddrConfirmed.get();
                String peerDisplay = context.getPeerDisplayName();
                String peerId = context.connectedPeerId.get();

                String connectMsg = "[+] SECURE E2EE CONNECTION ESTABLISHED with " + peerDisplay + "!";
                String pathMsg = "    Using path: YOU -> " + (confirmedAddr != null ? confirmedAddr : "???");
                System.out.println("\n-----------------------------------------------------");
                System.out.println(connectMsg); System.out.println(pathMsg);
                System.out.println("-----------------------------------------------------");

                if (peerId != null) {
                    context.chatHistoryManager = new ChatHistoryManager();
                    context.chatHistoryManager.initialize(context.myNodeId.get(), peerId, context.connectedPeerUsername.get());
                } else {
                    System.err.println("[!] Cannot initialize history: Peer Node ID is null upon secure connection!");
                    context.chatHistoryManager = null;
                }

                if (guiCallback != null) {
                     guiCallback.displaySystemMessage("System: " + connectMsg); // Log success
                     guiCallback.updateState(NodeState.CONNECTED_SECURE, context.connectedPeerUsername.get(), peerId); // Update state display
                }

                // Clear transient state AFTER updating GUI
                context.targetPeerId.set(null);
                context.peerCandidates.clear();
                context.pingAttempts = 0;
                context.connectionInfoReceived.set(false);
            }
        }
    }
}