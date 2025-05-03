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
        // Allow processing file messages even if peerAddr doesn't perfectly match confirmedAddr,
        // as NAT mappings might change. Rely on senderId for peer verification.
        else if (stateNow == NodeState.CONNECTED_SECURE && currentPeer != null && currentPeer.equals(senderId)) {
            handleMessageWhenConnected(data, peerAddr, action, senderId);
        }
        // --- Special Case: Allow file ACKs even if state slightly changes ---
        // This might happen if connection drops right after receiving data
        else if (action.equals("file_ack") && currentPeer != null && currentPeer.equals(senderId)) {
             System.out.println("[?] Received file_ack from previously connected peer " + senderId + " after state change. Processing...");
             handleMessageWhenConnected(data, peerAddr, action, senderId);
        }

        // Ignore messages from unexpected peers or in other states
    }


    private void handleMessageDuringAttempt(JSONObject data, InetSocketAddress peerAddr, String action, String senderId) {
         boolean senderIsCandidate = context.peerCandidates.stream().anyMatch(cand -> {
             try {
                 // Compare IP address strings and ports
                 InetAddress candAddr = InetAddress.getByName(cand.ip);
                 // Handle IPv6 scope IDs if present in peerAddr
                 String peerIpString = peerAddr.getAddress().getHostAddress();
                  int scopeIndex = peerIpString.indexOf('%');
                  if (scopeIndex > 0) peerIpString = peerIpString.substring(0, scopeIndex);

                 return candAddr.getHostAddress().equals(peerIpString) && cand.port == peerAddr.getPort();
             }
             catch (UnknownHostException e) { return false; }
         });

        // Allow PONG from public address even if not in candidates explicitly (server might know better)
         boolean fromPublicSeen = false;
         if (context.myPublicEndpointSeen != null) {
              try {
                  InetAddress pubAddr = InetAddress.getByName(context.myPublicEndpointSeen.ip);
                   String peerIpString = peerAddr.getAddress().getHostAddress();
                   int scopeIndex = peerIpString.indexOf('%');
                   if (scopeIndex > 0) peerIpString = peerIpString.substring(0, scopeIndex);
                  fromPublicSeen = pubAddr.getHostAddress().equals(peerIpString) && context.myPublicEndpointSeen.port == peerAddr.getPort();
              } catch (UnknownHostException e) { /* ignore */ }
         }


        if ((senderIsCandidate || fromPublicSeen) && (action.equals("ping") || action.equals("pong"))) {
             synchronized (context.stateLock) {
                 if (context.currentState.get() != NodeState.ATTEMPTING_UDP) return; // Re-check state under lock

                 // Try to set the confirmed address only once
                 boolean firstConfirmation = context.peerAddrConfirmed.compareAndSet(null, peerAddr);
                 if (firstConfirmation) {
                     System.out.println("\n[*] CONFIRMED receiving directly from Peer " + context.getPeerDisplayName() + " (" + senderId.substring(0,8) + ") at " + peerAddr + "!");
                     // If targetPeerId is still set, update connectedPeerId
                     if(senderId.equals(context.targetPeerId.get())) {
                         context.connectedPeerId.set(senderId);
                     }
                 } else {
                      // If we already confirmed an address but receive from another valid one, log it.
                      if (!peerAddr.equals(context.peerAddrConfirmed.get())) {
                           System.out.println("[?] Received valid ping/pong from alternate address " + peerAddr + " (already confirmed " + context.peerAddrConfirmed.get() + ")");
                      }
                 }

                 // Try to set the path confirmed flag only once
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

        // Relax address check slightly: only warn if address is different, but still process
        if (confirmedAddr != null && !peerAddr.equals(confirmedAddr)) {
             // Check if it's a local vs public mismatch which can happen with NAT reflection
             boolean isLocalPeer = peerAddr.getAddress().isSiteLocalAddress() || peerAddr.getAddress().isLoopbackAddress();
             boolean confirmedIsLocal = confirmedAddr.getAddress().isSiteLocalAddress() || confirmedAddr.getAddress().isLoopbackAddress();
             if (isLocalPeer != confirmedIsLocal) {
                  System.out.println("[?] NOTE: Received message from connected peer " + context.getPeerDisplayName() + " via different address " + peerAddr + " (Confirmed: " + confirmedAddr + "). Processing anyway.");
             } else {
                  // Otherwise, still warn but process
                  System.out.println("[?] WARNING: Received message from connected peer " + context.getPeerDisplayName() + " but from unexpected address " + peerAddr + " (Confirmed: "+ confirmedAddr +"). Processing anyway.");
             }
             // Allow processing, relying on senderId and crypto for security.
        } else if (confirmedAddr == null) {
             System.out.println("[?] WARNING: Processing message from peer " + context.getPeerDisplayName() + " but confirmed address is null.");
             // Maybe confirm the address now?
             context.peerAddrConfirmed.compareAndSet(null, peerAddr);
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
         if (context.myNodeId.get() == null) return; // Don't send if unregistered
         JSONObject pongMsg = new JSONObject();
         pongMsg.put("action", "pong");
         pongMsg.put("node_id", context.myNodeId.get());
         networkManager.sendUdp(pongMsg, peerAddr);
     }
}