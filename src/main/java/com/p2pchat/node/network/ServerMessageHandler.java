package main.java.com.p2pchat.node.network;

import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.service.ConnectionService; // Needed to signal received info
import main.java.com.p2pchat.node.service.ChatService; // Needed for key generation

import org.json.JSONArray;
import org.json.JSONObject;

public class ServerMessageHandler {

    private final NodeContext context;
    private final ConnectionService connectionService;
    private final ChatService chatService;


    public ServerMessageHandler(NodeContext context, ConnectionService connectionService, ChatService chatService) {
        this.context = context;
        this.connectionService = connectionService;
        this.chatService = chatService;
    }

    public void handleServerMessage(JSONObject data) {
        String status = data.optString("status", null);
        String action = data.optString("action", null);

        if ("registered".equals(status) && context.myNodeId.get() == null) {
            handleRegistrationResponse(data);
        } else if ("connection_info".equals(action) && "match_found".equals(status)) {
            handleConnectionInfo(data);
        } else if ("error".equals(status)) {
            handleServerError(data);
        } else if ("connection_request_received".equals(status) && "ack".equals(action)) {
            handleConnectionAck(data);
        }
    }

    private void handleRegistrationResponse(JSONObject data) {
         String receivedNodeId = data.optString("node_id");
         if (receivedNodeId != null && !receivedNodeId.isEmpty()) {
             if(context.myNodeId.compareAndSet(null, receivedNodeId)) { // Set only if null
                 JSONObject publicEpJson = data.optJSONObject("your_public_endpoint");
                 if (publicEpJson != null) {
                     context.myPublicEndpointSeen = Endpoint.fromJson(publicEpJson);
                     System.out.println("[*] Server recorded public endpoint: " + context.myPublicEndpointSeen);
                 }
                 System.out.println("[+] Successfully registered with server. Node ID: " + context.myNodeId.get());
                 // Signal successful registration if needed, maybe change state here?
                 context.currentState.set(NodeState.DISCONNECTED); // Move from REGISTERING to DISCONNECTED
             }
         } else {
             System.err.println("[!] Received 'registered' status from server but missing 'node_id'.");
             context.currentState.set(NodeState.DISCONNECTED); // Go back to disconnected if registration failed
         }
    }

    private void handleConnectionInfo(JSONObject data) {
        String receivedPeerId = data.optString("peer_id");
        String currentTarget = context.targetPeerId.get();

        if (currentTarget == null || !currentTarget.equals(receivedPeerId)) {
             System.out.println("[?] Received connection_info for unexpected peer " + receivedPeerId + ". Ignoring.");
             return;
        }

         // Process only if we are waiting for it
        synchronized (context.stateLock) {
            if (context.currentState.get() != NodeState.WAITING_MATCH) {
                 System.out.println("[?] Received connection_info while not in WAITING_MATCH state. Ignoring.");
                 return;
            }

            JSONArray candidatesJson = data.optJSONArray("peer_endpoints");
            String peerUsername = data.optString("peer_username", null);
            String peerPublicKeyBase64 = data.optString("peer_public_key", null);

            if (candidatesJson == null || peerPublicKeyBase64 == null || peerPublicKeyBase64.isEmpty()) {
                System.err.println("[!] Received incomplete 'connection_info' from server (missing endpoints or public key). Cancelling.");
                connectionService.cancelConnectionAttempt("Incomplete server info");
                return;
            }

            // Generate Shared Key FIRST
            boolean keyGenSuccess = chatService.generateSharedKeyAndStore(receivedPeerId, peerPublicKeyBase64);
            if (!keyGenSuccess) {
                System.err.println("[!] Failed to establish shared secret with peer " + receivedPeerId + ". Cancelling connection.");
                connectionService.cancelConnectionAttempt("Key exchange setup failed");
                return;
            }

            // Store peer username
            if (peerUsername != null && !peerUsername.isEmpty()) {
                 context.connectedPeerUsername.set(peerUsername);
                 System.out.println("[*] Received Peer Username: " + peerUsername);
            } else {
                 context.connectedPeerUsername.set(null);
                 System.out.println("[!] Peer username not provided by server.");
            }

            // Store peer candidates
            context.peerCandidates.clear();
            System.out.println("[*] Received Peer Candidates from server for " + receivedPeerId + ":");
            int validCandidates = 0;
            for (int i = 0; i < candidatesJson.length(); i++) {
                JSONObject epJson = candidatesJson.optJSONObject(i);
                if (epJson != null) {
                    Endpoint ep = Endpoint.fromJson(epJson);
                    if (ep != null) {
                        context.peerCandidates.add(ep);
                        System.out.println("    - " + ep);
                        validCandidates++;
                    }
                }
            }

            if (validCandidates > 0) {
                context.connectionInfoReceived.set(true); // Signal OK for ConnectionService
                connectionService.processReceivedConnectionInfo(); // Tell connection service to proceed
            } else {
                System.out.println("[!] Received empty or invalid candidate list for peer " + receivedPeerId + "...");
                connectionService.cancelConnectionAttempt("No valid peer candidates");
            }
        } // End synchronized block
    }

    private void handleServerError(JSONObject data) {
         String message = data.optString("message", "Unknown error");
         System.err.println("\n[!] Server Error: " + message);
         synchronized (context.stateLock) {
             NodeState stateNow = context.currentState.get();
             if (stateNow == NodeState.WAITING_MATCH || stateNow == NodeState.ATTEMPTING_UDP) {
                 System.out.println("    Cancelling connection attempt due to server error.");
                 connectionService.cancelConnectionAttempt("Server error: " + message);
             }
             // If error during registration?
             else if (stateNow == NodeState.REGISTERING) {
                  System.out.println("    Registration failed due to server error.");
                  context.currentState.set(NodeState.DISCONNECTED); // Force back to disconnected
                  // Maybe signal main thread to exit? Or allow retry? For now just disconnect.
             }
         }
    }

    private void handleConnectionAck(JSONObject data) {
         String waitingFor = data.optString("waiting_for", "?");
         if (context.currentState.get() == NodeState.WAITING_MATCH && waitingFor.equals(context.targetPeerId.get())) {
             System.out.println("[*] Server acknowledged request. Waiting for peer " + context.getPeerDisplayName() + " to connect.");
             // Update UI maybe? For now just log.
         }
    }
}