package main.java.com.p2pchat.node.network;

import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.service.ConnectionService; // Needed for error handling disconnect
import org.json.JSONObject;
import org.json.JSONException;

import java.net.InetSocketAddress;
import java.net.PortUnreachableException;

public class MessageHandler {

    private final NodeContext context;
    private final ServerMessageHandler serverMessageHandler;
    private final PeerMessageHandler peerMessageHandler;
    private final ConnectionService connectionService; // Need this to trigger disconnect on severe errors

    public MessageHandler(NodeContext context, ServerMessageHandler serverMsgHandler, PeerMessageHandler peerMsgHandler, ConnectionService connService) {
        this.context = context;
        this.serverMessageHandler = serverMsgHandler;
        this.peerMessageHandler = peerMsgHandler;
        this.connectionService = connService;
    }

    public void handleIncomingPacket(String messageStr, InetSocketAddress senderAddr) {
         JSONObject data;
         try {
             data = new JSONObject(messageStr);
         } catch (JSONException e) {
             System.err.println("[Listener] Invalid JSON received from " + senderAddr + ". Content snippet: " + messageStr.substring(0, Math.min(100, messageStr.length())) + "...");
             return;
         }

        boolean fromServer = context.serverAddress != null && context.serverAddress.equals(senderAddr);

        if (fromServer) {
            serverMessageHandler.handleServerMessage(data);
        } else {
            peerMessageHandler.handlePeerMessage(data, senderAddr);
        }
    }

    // Handle specific send errors, potentially triggering disconnects
    public void handleSendError(InetSocketAddress destination, Exception error) {
         InetSocketAddress confirmedPeer = context.peerAddrConfirmed.get();
         if (destination != null && destination.equals(confirmedPeer)) {
             // Error sending to the confirmed peer address
             if (error instanceof PortUnreachableException) {
                 System.out.println("[!] Connection possibly lost with " + context.getPeerDisplayName() + " (Port unreachable).");
                 connectionService.handleConnectionLoss(); // Notify connection service
             } else {
                  System.out.println("[!] IO Error sending to peer " + context.getPeerDisplayName() + ": " + error.getMessage());
                  // Consider if other IO errors should also trigger disconnect
                  connectionService.handleConnectionLoss();
             }
         } else if (destination != null && destination.equals(context.serverAddress)) {
              System.err.println("[!] Error sending message to server: " + error.getMessage());
              // Maybe retry later? Or indicate server issue? For now, just log.
         }
         // Errors sending to candidate addresses during ping are ignored by sendUdp caller directly
    }
}