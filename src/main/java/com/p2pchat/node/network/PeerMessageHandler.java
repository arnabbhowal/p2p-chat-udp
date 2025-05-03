package main.java.com.p2pchat.node.network;

import main.java.com.p2pchat.common.CryptoUtils;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.service.ChatService;
import main.java.com.p2pchat.node.service.ConnectionService; // Needed to signal UDP path confirmation
import main.java.com.p2pchat.node.service.FileTransferService; // Added for file transfer

import org.json.JSONObject;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.UnknownHostException;


public class PeerMessageHandler {

    private final NodeContext context;
    private final ConnectionService connectionService;
    private final ChatService chatService;
    private final FileTransferService fileTransferService; // Added
    private final NetworkManager networkManager; // Needed to send pong

    // Updated constructor
    public PeerMessageHandler(NodeContext context, ConnectionService connectionService, ChatService chatService, FileTransferService fileTransferService, NetworkManager networkManager) {
        this.context = context;
        this.connectionService = connectionService;
        this.chatService = chatService;
        this.fileTransferService = fileTransferService; // Added
        this.networkManager = networkManager;
    }

    public void handlePeerMessage(JSONObject data, InetSocketAddress peerAddr) {
        String action = data.optString("action", null);
        String senderId = data.optString("node_id", null);

        if (senderId == null || action == null) return; // Ignore invalid

        NodeState stateNow = context.currentState.get();
        String currentTarget = context.targetPeerId.get();
        String currentPeer = context.connectedPeerId.get();

        // --- Processing During UDP Connection Attempt ---
        if (stateNow == NodeState.ATTEMPTING_UDP && currentTarget != null && currentTarget.equals(senderId)) {
            handleMessageDuringAttempt(data, peerAddr, action, senderId);
        }
        // --- Processing After Secure Connection Established ---
        else if (stateNow == NodeState.CONNECTED_SECURE && currentPeer != null && currentPeer.equals(senderId)) {
            handleMessageWhenConnected(data, peerAddr, action, senderId);
        }
        // Ignore messages from unexpected peers or in other states for now
    }


    private void handleMessageDuringAttempt(JSONObject data, InetSocketAddress peerAddr, String action, String senderId) {
         boolean senderIsCandidate = context.peerCandidates.stream().anyMatch(cand -> {
             try { return new InetSocketAddress(InetAddress.getByName(cand.ip), cand.port).equals(peerAddr); }
             catch (UnknownHostException e) { return false; }
         });

        if (senderIsCandidate && (action.equals("ping") || action.equals("pong"))) {
             synchronized (context.stateLock) {
                 if (context.currentState.get() != NodeState.ATTEMPTING_UDP) return; // Re-check state under lock

                 boolean firstConfirmation = context.peerAddrConfirmed.compareAndSet(null, peerAddr);
                 if (firstConfirmation) {
                     System.out.println("\n[*] CONFIRMED receiving directly from Peer " + senderId + " at " + peerAddr + "!");
                     context.connectedPeerId.set(senderId); // Tentatively set connected ID
                 }

                 boolean firstPathConfirm = context.p2pUdpPathConfirmed.compareAndSet(false, true);
                 if (firstPathConfirm) {
                      System.out.println("[+] P2P UDP Path Confirmed (Received " + action.toUpperCase() + ")!");
                      connectionService.processUdpPathConfirmation(); // Signal connection service
                 }

                 if ("ping".equals(action)) {
                     sendPong(peerAddr);
                 }
             } // End synchronized block
        }
    }

    private void handleMessageWhenConnected(JSONObject data, InetSocketAddress peerAddr, String action, String senderId) {
        InetSocketAddress confirmedAddr = context.peerAddrConfirmed.get();

        if (confirmedAddr == null || !peerAddr.equals(confirmedAddr)) {
            System.out.println("[?] WARNING: Received message from connected peer " + context.getPeerDisplayName() + " but from wrong address " + peerAddr + ". IGNORING.");
            return;
        }

        switch (action) {
            // Chat actions
            case "e_chat":
                chatService.receiveEncryptedChat(data);
                break;

            // Connection maintenance actions
            case "keepalive":
                // Log receipt? Update last seen for peer? For now, do nothing.
                break;
            case "ping":
                sendPong(peerAddr); // Still respond to pings to keep NAT open
                break;
            case "pong":
                // Ignore pongs when connected
                break;
            case "disconnect": // Handle explicit disconnect notification from peer
                 System.out.println("\n[*] Received disconnect notification from " + context.getPeerDisplayName() + ".");
                 connectionService.handlePeerDisconnect();
                 break;

             // --- File Transfer Actions ---
             case "file_offer":
                 fileTransferService.handleIncomingOffer(data, peerAddr);
                 break;
             case "file_accept":
                  fileTransferService.handleFileAccept(data);
                  break;
             case "file_reject":
                  fileTransferService.handleFileReject(data);
                  break;
             case "file_data":
                  fileTransferService.handleFileData(data);
                  break;
             case "file_ack":
                  fileTransferService.handleFileAck(data);
                  break;
            // --- End File Transfer Actions ---

            default:
                System.out.println("[?] Received unknown action '" + action + "' from connected peer " + context.getPeerDisplayName());
                break;
        }
    }

     private void sendPong(InetSocketAddress peerAddr) {
         JSONObject pongMsg = new JSONObject();
         pongMsg.put("action", "pong");
         pongMsg.put("node_id", context.myNodeId.get());
         networkManager.sendUdp(pongMsg, peerAddr);
     }
}