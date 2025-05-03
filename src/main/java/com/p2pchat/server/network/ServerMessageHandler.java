package main.java.com.p2pchat.server.network;

import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.server.model.NodeInfo;
import main.java.com.p2pchat.server.service.NodeRegistry;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ServerMessageHandler {

    private final NodeRegistry registry;
    private final ServerNetworkManager networkManager;

    public ServerMessageHandler(NodeRegistry registry, ServerNetworkManager networkManager) {
        this.registry = registry;
        this.networkManager = networkManager;
    }

    public void handlePacket(DatagramPacket packet) {
        if (packet == null || packet.getAddress() == null || packet.getData() == null || packet.getLength() == 0) {
            System.err.println("[!] Received invalid/empty packet. Ignoring.");
            return;
        }
        InetSocketAddress senderAddr = (InetSocketAddress) packet.getSocketAddress();
        String messageStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

        JSONObject data;
        try {
            data = new JSONObject(messageStr);
        } catch (Exception e) {
            String senderIp = senderAddr.getAddress().getHostAddress();
            int senderPort = senderAddr.getPort();
            System.err.println("[!] Received invalid JSON from [" + senderIp + "]:" + senderPort + " - Content: " + messageStr);
            return;
        }

        String action = data.optString("action", null);
        String nodeId = data.optString("node_id", null);

        if (nodeId != null) {
             registry.updateNodeLastSeen(nodeId, senderAddr); // Update last seen regardless of action
        }

        if ("register".equals(action)) {
            handleRegister(data, senderAddr);
        } else if ("keep_alive".equals(action)) {
            handleKeepAlive(nodeId, senderAddr);
        } else if ("request_connection".equals(action)) {
            handleConnectionRequest(data, senderAddr);
        } else {
            if (action != null && !action.isEmpty()) {
                System.out.println("[!] Unknown action '" + action + "' received from " + senderAddr);
            }
        }
    }

    private void handleRegister(JSONObject data, InetSocketAddress senderAddr) {
        String username = data.optString("username", null);
        String publicKeyBase64 = data.optString("public_key", null);
        JSONArray localEndpointsJson = data.optJSONArray("local_endpoints");

        if (publicKeyBase64 == null || publicKeyBase64.trim().isEmpty()) {
            System.out.println("[!] Registration failed: Missing 'public_key' from " + senderAddr);
            sendErrorResponse("Registration failed: 'public_key' field is missing or empty.", senderAddr);
            return;
        }
        if (localEndpointsJson == null) {
             System.out.println("[!] Registration failed: Missing 'local_endpoints' array from " + senderAddr);
             sendErrorResponse("Registration failed: 'local_endpoints' array is missing.", senderAddr);
             return;
        }

        List<Endpoint> localEndpoints = new ArrayList<>();
        for (int i = 0; i < localEndpointsJson.length(); i++) {
            JSONObject epJson = localEndpointsJson.optJSONObject(i);
            if (epJson != null) {
                Endpoint ep = Endpoint.fromJson(epJson);
                if (ep != null) {
                    localEndpoints.add(ep);
                }
            }
        }

        NodeInfo newNodeInfo = registry.registerNode(senderAddr, username, publicKeyBase64.trim(), localEndpoints);

        if (newNodeInfo == null) {
            // Registry already logged the reason
            sendErrorResponse("Registration failed: Could not determine valid network endpoints.", senderAddr);
            return;
        }

        JSONObject response = new JSONObject();
        response.put("status", "registered");
        response.put("node_id", newNodeInfo.getNodeId());
        Endpoint publicEpSeen = newNodeInfo.getPublicEndpointSeen();
        if (publicEpSeen != null) {
            response.put("your_public_endpoint", publicEpSeen.toJson());
        }
        networkManager.sendResponse(response, senderAddr);
    }

    private void handleKeepAlive(String nodeId, InetSocketAddress senderAddr) {
        if (nodeId == null || registry.getNode(nodeId) == null) {
            System.out.println("[!] Keep-alive from unknown node_id: " + nodeId + " @ " + senderAddr + ". Asking to re-register.");
            sendErrorResponse("Unknown node ID. Please re-register.", senderAddr);
        }
        // Last seen already updated in handlePacket
    }

    private void handleConnectionRequest(JSONObject data, InetSocketAddress senderAddr) {
        String requesterId = data.optString("node_id", null);
        String targetId = data.optString("target_id", null);

        if (requesterId == null || targetId == null) {
            System.out.println("[!] Invalid connection request (missing IDs) from " + senderAddr);
            return; // Don't send error for malformed requests
        }

        NodeInfo requesterInfo = registry.getNode(requesterId);

        if (requesterInfo == null) {
            System.out.println("[!] Connection request from unknown node " + requesterId + ". Asking to re-register.");
            sendErrorResponse("Unknown node ID. Please re-register first.", senderAddr);
            return;
        }
        if (requesterInfo.getPublicKeyBase64() == null || requesterInfo.getPublicKeyBase64().isEmpty()) {
             System.out.println("[!] Connection request from node " + requesterInfo.getUsername() + " ("+requesterId+") without a stored public key. Asking to re-register.");
             sendErrorResponse("Missing public key on server. Please re-register.", senderAddr);
             return;
        }
        if (requesterId.equals(targetId)) {
            System.out.println("[!] Node " + requesterInfo.getUsername() + " (" + requesterId + ") tried to connect to itself. Ignoring.");
             sendErrorResponse("Cannot connect to yourself.", senderAddr);
             return;
        }

        System.out.println("[*] Connection request: " + requesterInfo.getUsername() + " (" + requesterId + ") ---> " + targetId);
        NodeInfo targetInfo = registry.getNode(targetId);

        if (targetInfo == null) {
            System.out.println("[*] Target node " + targetId + " not currently registered or timed out. Storing request from " + requesterInfo.getUsername() + "...");
            registry.addPendingConnection(requesterId, targetId);
            sendAckResponse("connection_request_received", targetId, senderAddr);
            return;
        }
        if (targetInfo.getPublicKeyBase64() == null || targetInfo.getPublicKeyBase64().isEmpty()) {
             System.out.println("[!] Target node " + targetInfo.getUsername() + " (" + targetId + ") does not have a public key stored. Cannot complete connection.");
             sendErrorResponse("Target node " + targetInfo.getUsername() + " is missing a public key on the server. Cannot connect.", senderAddr);
             return;
        }

        registry.addPendingConnection(requesterId, targetId);
        String targetWants = registry.getPendingRequester(requesterId); // Check if the target already requested us

        if (targetId.equals(targetWants)) { // Match found! targetWants = target's targetId = requesterId
            System.out.println("[*] Connection match found: " + requesterInfo.getUsername() + " (" + requesterId + ") <===> " + targetInfo.getUsername() + " (" + targetId + ")");

            InetSocketAddress addrA = requesterInfo.getLastAddr();
            InetSocketAddress addrB = targetInfo.getLastAddr();

            if (addrA == null || addrB == null) {
                 System.err.println("[!!!] Critical Error: Missing last_addr for matched nodes " + requesterId + " or " + targetId + ". Cannot send info.");
                 registry.removePendingConnection(requesterId, targetId);
                 return;
            }

            sendConnectionInfo(requesterInfo, targetInfo);
            sendConnectionInfo(targetInfo, requesterInfo); // Send info both ways

            registry.removePendingConnection(requesterId, targetId);

        } else {
            System.out.println("[*] Stored request from " + requesterInfo.getUsername() + " for " + targetInfo.getUsername() + ". Waiting for target.");
            sendAckResponse("connection_request_received", targetId, senderAddr);
        }
    }

    private void sendConnectionInfo(NodeInfo recipientNode, NodeInfo peerNode) {
        JSONObject response = new JSONObject();
        response.put("action", "connection_info");
        response.put("status", "match_found");
        response.put("peer_id", peerNode.getNodeId());
        response.put("peer_username", peerNode.getUsername());
        response.put("peer_public_key", peerNode.getPublicKeyBase64());
        JSONArray endpointsJson = new JSONArray();
        peerNode.getEndpoints().forEach(ep -> endpointsJson.put(ep.toJson()));
        response.put("peer_endpoints", endpointsJson);

        boolean sent = networkManager.sendResponse(response, recipientNode.getLastAddr());
        if(sent) System.out.println("    -> Sent " + peerNode.getUsername() + "'s info (incl. PubKey) to " + recipientNode.getUsername() + " @ " + recipientNode.getLastAddr());
        else System.err.println("[!!!] FAILED to send info to " + recipientNode.getUsername() + " @ " + recipientNode.getLastAddr());
    }

    private void sendErrorResponse(String message, InetSocketAddress destination) {
        JSONObject response = new JSONObject();
        response.put("status", "error");
        response.put("message", message);
        networkManager.sendResponse(response, destination);
    }

     private void sendAckResponse(String status, String waitingFor, InetSocketAddress destination) {
         JSONObject response = new JSONObject();
         response.put("action", "ack"); // Indicate it's an ack
         response.put("status", status);
         if (waitingFor != null) {
             response.put("waiting_for", waitingFor);
         }
         networkManager.sendResponse(response, destination);
     }
}