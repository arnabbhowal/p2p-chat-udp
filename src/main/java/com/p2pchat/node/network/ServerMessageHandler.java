package main.java.com.p2pchat.node.network;

import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.service.ConnectionService;
import main.java.com.p2pchat.node.service.ChatService;
// Import RegistrationService to call its methods
import main.java.com.p2pchat.node.service.RegistrationService;


import org.json.JSONArray;
import org.json.JSONObject;

public class ServerMessageHandler {

    private final NodeContext context;
    private final ConnectionService connectionService;
    private final ChatService chatService;
    // Add RegistrationService reference
    private final RegistrationService registrationService;

    // Updated Constructor
    public ServerMessageHandler(NodeContext context, ConnectionService connectionService, ChatService chatService, RegistrationService registrationService) {
        this.context = context;
        this.connectionService = connectionService;
        this.chatService = chatService;
        this.registrationService = registrationService; // Store reference
    }

    // No GuiCallback field or setter needed here

    public void handleServerMessage(JSONObject data) {
        String status = data.optString("status", null);
        String action = data.optString("action", null);

        // --- Registration Response ---
        if ("registered".equals(status) && context.myNodeId.get() == null) {
            handleRegistrationResponse(data);
        }
        // --- Connection Info ---
        else if ("connection_info".equals(action) && "match_found".equals(status)) {
            handleConnectionInfo(data); // Stays the same, calls ConnectionService internally
        }
        // --- Server Error ---
        else if ("error".equals(status)) {
            handleServerError(data);
        }
        // --- Connection Request Ack ---
        else if ("connection_request_received".equals(status) && "ack".equals(action)) {
            handleConnectionAck(data); // Minor update, maybe GUI message?
        }
        // --- Unknown ---
        // else { System.out.println("[?] Unknown message from server: " + data.toString()); }
    }

    private void handleRegistrationResponse(JSONObject data) {
         String receivedNodeId = data.optString("node_id");
         if (receivedNodeId != null && !receivedNodeId.isEmpty()) {
             // Attempt to set Node ID atomically
             if(context.myNodeId.compareAndSet(null, receivedNodeId)) {
                 // Store public endpoint if provided
                 JSONObject publicEpJson = data.optJSONObject("your_public_endpoint");
                 if (publicEpJson != null) {
                     context.myPublicEndpointSeen = Endpoint.fromJson(publicEpJson);
                     // Logged by RegistrationService now
                 }
                 // --- Call RegistrationService to handle success and GUI update ---
                 registrationService.processRegistrationSuccess(receivedNodeId, context.myPublicEndpointSeen);
                 // Set backend state AFTER notifying service (which notifies GUI)
                 context.currentState.set(NodeState.DISCONNECTED);

             } // Else: Node ID already set, ignore duplicate response
         } else {
             System.err.println("[!] Received 'registered' status from server but missing 'node_id'.");
             // --- Call RegistrationService to handle failure and GUI update ---
             registrationService.processRegistrationFailure("Missing 'node_id' in server response");
              // Set backend state AFTER notifying service
             context.currentState.set(NodeState.DISCONNECTED);
         }
    }

    private void handleConnectionInfo(JSONObject data) {
        String receivedPeerId = data.optString("peer_id");
        String currentTarget = context.targetPeerId.get();

        // Validate target peer
        if (currentTarget == null || !currentTarget.equals(receivedPeerId)) {
             System.out.println("[?] Received connection_info for unexpected peer " + receivedPeerId + ". Current target: " + currentTarget + ". Ignoring.");
             return;
        }

        // Process only if waiting
        synchronized (context.stateLock) {
            if (context.currentState.get() != NodeState.WAITING_MATCH) {
                 System.out.println("[?] Received connection_info while not in WAITING_MATCH state (" + context.currentState.get() + "). Ignoring.");
                 return;
            }

            JSONArray candidatesJson = data.optJSONArray("peer_endpoints");
            String peerUsername = data.optString("peer_username", null);
            String peerPublicKeyBase64 = data.optString("peer_public_key", null);

            // Validate required fields
            if (candidatesJson == null || peerPublicKeyBase64 == null || peerPublicKeyBase64.isEmpty()) {
                System.err.println("[!] Received incomplete 'connection_info' from server (missing endpoints or public key). Cancelling.");
                connectionService.cancelConnectionAttempt("Incomplete server info"); // Service handles GUI update
                return;
            }

            // --- Perform Key Exchange FIRST ---
            boolean keyGenSuccess = chatService.generateSharedKeyAndStore(receivedPeerId, peerPublicKeyBase64);
            if (!keyGenSuccess) {
                System.err.println("[!] Failed to establish shared secret with peer " + receivedPeerId + ". Cancelling connection.");
                connectionService.cancelConnectionAttempt("Key exchange setup failed"); // Service handles GUI update
                return;
            }
            // Key exchange successful, context.sharedKeyEstablished is true

            // Store peer username
            if (peerUsername != null && !peerUsername.isEmpty()) {
                 context.connectedPeerUsername.set(peerUsername);
                 System.out.println("[*] Received Peer Username: " + peerUsername);
            } else {
                 context.connectedPeerUsername.set(null); // Ensure it's null if not provided
                 System.out.println("[!] Peer username not provided by server.");
            }

            // Store peer candidates
            context.peerCandidates.clear();
            System.out.println("[*] Received Peer Candidates from server for " + receivedPeerId.substring(0,8) + "...:");
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

            // --- Proceed only if candidates exist ---
            if (validCandidates > 0) {
                context.connectionInfoReceived.set(true); // Mark info received
                // --- Tell connection service to proceed (which will update state and GUI) ---
                connectionService.processReceivedConnectionInfo();
            } else {
                System.out.println("[!] Received empty or invalid candidate list for peer " + receivedPeerId + "...");
                connectionService.cancelConnectionAttempt("No valid peer candidates"); // Service handles GUI update
            }
        } // End synchronized block
    }

    private void handleServerError(JSONObject data) {
         String message = data.optString("message", "Unknown error");
         System.err.println("\n[!] Server Error: " + message);
         synchronized (context.stateLock) {
             NodeState stateNow = context.currentState.get();
             // If error during connection attempt, cancel it
             if (stateNow == NodeState.WAITING_MATCH || stateNow == NodeState.ATTEMPTING_UDP) {
                 connectionService.cancelConnectionAttempt("Server error: " + message); // Service handles GUI update
             }
             // If error during registration, notify RegistrationService
             else if (stateNow == NodeState.REGISTERING) {
                 registrationService.processRegistrationFailure("Server error: " + message); // Service handles GUI update
                 context.currentState.set(NodeState.DISCONNECTED); // Ensure backend state correct
             }
             // Else: Error received in unrelated state, maybe just log?
             else {
                  // Optionally notify GUI about generic server error?
                   // if (registrationService.getGuiCallback() != null) registrationService.getGuiCallback().appendMessage("System: Server Error - " + message);
             }
         }
    }

    private void handleConnectionAck(JSONObject data) {
         String waitingFor = data.optString("waiting_for", "?");
         // Only relevant if we are actually waiting for this peer
         if (context.currentState.get() == NodeState.WAITING_MATCH && waitingFor.equals(context.targetPeerId.get())) {
             String ackMsg = "[*] Server acknowledged request. Waiting for peer " + context.getPeerDisplayName() + " to connect.";
             System.out.println(ackMsg);
             // <<< Notify GUI (Optional) >>>
             // GuiCallback callback = connectionService.getGuiCallback(); // Need getter or way to access callback
             // if (callback != null) { callback.appendMessage("System: Server acknowledged connection request."); }
         }
    }
}