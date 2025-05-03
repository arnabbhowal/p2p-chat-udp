package main.java.com.p2pchat.server;

import main.java.com.p2pchat.common.Endpoint; // Import common Endpoint
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;


public class CoordinationServer {

    private static final int PORT = 19999;
    private static final int BUFFER_SIZE = 4096; // Increased slightly for potential key size
    private static final int NODE_TIMEOUT_MS = 120 * 1000; // 120 seconds

    // Use ConcurrentHashMap for thread safety
    private static final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();
    private static final Map<String, String> pendingConnections = new ConcurrentHashMap<>(); // K=requesterId, V=targetId

    private static DatagramSocket socket;
    private static volatile boolean running = true;
    private static ScheduledExecutorService cleanupExecutor;

    public static void main(String[] args) {
        try {
            // Listen on all available interfaces (IPv4 and IPv6)
            socket = new DatagramSocket(PORT, InetAddress.getByName("0.0.0.0"));
            socket.setReuseAddress(true); // Allow reusing address quickly after shutdown
            System.out.println("[*] Coordination Server listening on UDP port " + PORT);

            startCleanupTask();

            // Add shutdown hook for graceful termination
            Runtime.getRuntime().addShutdownHook(new Thread(CoordinationServer::shutdown));


            byte[] buffer = new byte[BUFFER_SIZE];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet); // Blocking call
                    if (running) { // Check running flag again after blocking call
                        handlePacket(packet);
                    }
                } catch (SocketException se) {
                    if (running) {
                        System.err.println("[!] Socket Exception (likely closed): " + se.getMessage());
                    }
                    running = false; // Ensure loop terminates
                }
                catch (IOException e) {
                    if (running) {
                        System.err.println("[!] Socket receive error: " + e.getMessage());
                    }
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                } catch (Exception e) {
                    System.err.println("[!!!] Unhandled error handling packet: " + e.getMessage());
                    e.printStackTrace();
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
            }
        } catch (SocketException e) {
            System.err.println("[!!!] Failed to bind UDP socket on port " + PORT + ": " + e.getMessage());
            e.printStackTrace();
        } catch (UnknownHostException e) {
             System.err.println("[!!!] Failed to resolve bind address: " + e.getMessage());
             e.printStackTrace();
        } finally {
            shutdown();
             System.out.println("[*] Server main method finished.");
        }
    }

    private static void startCleanupTask() {
        if (cleanupExecutor != null && !cleanupExecutor.isShutdown()) {
            cleanupExecutor.shutdownNow();
        }
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NodeCleanupThread");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(CoordinationServer::cleanupInactiveNodes,
            NODE_TIMEOUT_MS / 2, NODE_TIMEOUT_MS / 2, TimeUnit.MILLISECONDS);
        System.out.println("[*] Started inactive node cleanup task (Interval: " + (NODE_TIMEOUT_MS / 2) + "ms).");
    }

    private static void cleanupInactiveNodes() {
        long now = Instant.now().toEpochMilli();
        List<String> nodesToRemove = new ArrayList<>();

        try {
            nodes.forEach((nodeId, nodeInfo) -> {
                if (now - nodeInfo.lastSeen > NODE_TIMEOUT_MS) {
                    nodesToRemove.add(nodeId);
                    Endpoint pubEp = nodeInfo.getPublicEndpointSeen();
                    String endpointStr = (pubEp != null) ? (pubEp.ip + ":" + pubEp.port) : "Unknown";
                    System.out.println("[-] Node " + nodeInfo.username + " (" + nodeId.substring(0,8) + "...) timed out (Last seen: " + ((now - nodeInfo.lastSeen)/1000) + "s ago, Last Addr: " + endpointStr + "). Removing.");
                }
            });

            if (!nodesToRemove.isEmpty()) {
                for (String nodeId : nodesToRemove) {
                    nodes.remove(nodeId);
                    // Clean up pending connections
                    boolean removedPendingBy = pendingConnections.remove(nodeId) != null;
                    boolean removedPendingFor = pendingConnections.values().removeIf(targetId -> targetId.equals(nodeId));

                    if (removedPendingBy || removedPendingFor) {
                        System.out.println("    - Cleared pending requests associated with " + nodeId.substring(0,8) + "...");
                    }
                }
                System.out.println("[*] Cleanup complete. Current Nodes (" + nodes.size() + "): " + nodes.keySet().stream().map(id -> id.substring(0, Math.min(8, id.length())) + "...").collect(Collectors.joining(", ")));
            }
        } catch (Exception e) {
            System.err.println("[!!!] Error during node cleanup task: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private static void handlePacket(DatagramPacket packet) {
        if (packet == null || packet.getAddress() == null || packet.getData() == null || packet.getLength() == 0) {
            System.err.println("[!] Received invalid/empty packet. Ignoring.");
            return;
        }
        InetSocketAddress senderAddr = (InetSocketAddress) packet.getSocketAddress();
        String messageStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        String senderIp = senderAddr.getAddress().getHostAddress();
        int senderPort = senderAddr.getPort();

        // Clean ::ffff: prefix if present
        boolean isIPv6 = senderAddr.getAddress() instanceof Inet6Address;
        if (isIPv6 && senderIp != null && senderIp.startsWith("::ffff:")) {
            senderIp = senderIp.substring("::ffff:".length());
        }


        JSONObject data;
        try {
            data = new JSONObject(messageStr);
        } catch (Exception e) {
            System.err.println("[!] Received invalid JSON from [" + senderIp + "]:" + senderPort + " - Content: " + messageStr);
            return;
        }

        String action = data.optString("action", null);
        String nodeId = data.optString("node_id", null); // Get node_id early

        // --- Update last_seen for known nodes on any valid message ---
        NodeInfo nodeInfo = null;
        String usernameForLog = "???";
        if (nodeId != null) {
            nodeInfo = nodes.get(nodeId);
            if (nodeInfo != null) {
                nodeInfo.updateLastSeen(senderAddr);
                nodeInfo.updatePublicEndpoint(senderAddr); // Also update their perceived public endpoint
                usernameForLog = nodeInfo.username; // Use known username for logging
            } else {
                 usernameForLog = "Unknown:" + nodeId.substring(0, Math.min(8, nodeId.length())) + "...";
            }
        } else {
             usernameForLog = "NoNodeID";
        }
        // --- End Update ---

        // System.out.println("\n[+] UDP Received from [" + senderIp + "]:" + senderPort + " -> Action: " + (action != null ? action : "N/A") + " (Node: " + usernameForLog + ")"); // Make logging less verbose


        if ("register".equals(action)) {
            handleRegister(data, senderAddr);
        } else if ("keep_alive".equals(action)) {
            handleKeepAlive(nodeId, senderAddr, nodeInfo); // Pass potentially found nodeInfo
        } else if ("request_connection".equals(action)) {
            handleConnectionRequest(data, senderAddr, nodeInfo); // Pass potentially found nodeInfo
        } else {
            // Only log unknown actions if they exist, ignore messages without action silently
            if (action != null && !action.isEmpty()) {
                System.out.println("[!] Unknown action '" + action + "' received from " + senderAddr);
            }
        }
    }

    private static void handleRegister(JSONObject data, InetSocketAddress senderAddr) {
        String newNodeId = UUID.randomUUID().toString();
        NodeInfo newNodeInfo = new NodeInfo(newNodeId, senderAddr);

        // Extract and store username
        String username = data.optString("username", "User_" + newNodeId.substring(0, 4)); // Get username or create default
        if (username.trim().isEmpty()) {
             username = "User_" + newNodeId.substring(0, 4); // Ensure non-empty
        }
        newNodeInfo.username = username.trim(); // Store it in NodeInfo

        // Extract and store public key (Base64 encoded)
        String publicKeyBase64 = data.optString("public_key", null);
        if (publicKeyBase64 == null || publicKeyBase64.trim().isEmpty()) {
            System.out.println("[!] Registration failed: Missing 'public_key' from " + senderAddr);
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("status", "error");
            errorResponse.put("message", "Registration failed: 'public_key' field is missing or empty.");
            sendResponse(errorResponse, senderAddr);
            return;
        }
        // Basic validation (e.g., check if it's valid Base64?) - could add later
        newNodeInfo.publicKeyBase64 = publicKeyBase64.trim();


        JSONArray localEndpointsJson = data.optJSONArray("local_endpoints");
        if (localEndpointsJson == null || localEndpointsJson.length() == 0) {
            System.out.println("[!] Registration failed: No local_endpoints provided by " + senderAddr);
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("status", "error");
            errorResponse.put("message", "Registration failed: local_endpoints array missing or empty.");
            sendResponse(errorResponse, senderAddr);
            return;
        }

        // Add reported local endpoints
        int addedCount = 0;
        for (int i = 0; i < localEndpointsJson.length(); i++) {
            JSONObject epJson = localEndpointsJson.optJSONObject(i);
            if (epJson != null) {
                Endpoint ep = Endpoint.fromJson(epJson);
                if (ep != null) {
                    // Avoid adding duplicate local endpoints
                    if (newNodeInfo.endpoints.stream().noneMatch(existing -> existing.equals(ep))) {
                         newNodeInfo.endpoints.add(ep);
                         addedCount++;
                    }
                }
            }
        }
        if (addedCount == 0) {
            System.out.println("[!] Registration failed: No *valid* local_endpoints could be parsed from data provided by " + senderAddr);
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("status", "error");
            errorResponse.put("message", "Registration failed: No valid local_endpoints parsed from request.");
            sendResponse(errorResponse, senderAddr);
            return;
        }

        // Add/Update the public endpoint seen by the server
        newNodeInfo.updatePublicEndpoint(senderAddr);

        // Add to map *after* initial setup
        nodes.put(newNodeId, newNodeInfo);

        JSONObject response = new JSONObject();
        response.put("status", "registered");
        response.put("node_id", newNodeId);
        Endpoint publicEpSeen = newNodeInfo.getPublicEndpointSeen();
        if (publicEpSeen != null) {
            response.put("your_public_endpoint", publicEpSeen.toJson());
        }

        sendResponse(response, senderAddr);
        System.out.println("[*] Node registered: " + newNodeInfo.username + " (" + newNodeId.substring(0,8) + "...) (" + nodes.size() + " total)");
        System.out.println("    Endpoints recorded: " + newNodeInfo.endpoints.stream().map(Endpoint::toString).collect(Collectors.joining(", ")));
        System.out.println("    Public Key stored: " + (newNodeInfo.publicKeyBase64 != null ? "Yes" : "No"));
        System.out.println("    Source Addr: " + senderAddr);
    }

    // Pass nodeInfo which might already be looked up
    private static void handleKeepAlive(String nodeId, InetSocketAddress senderAddr, NodeInfo nodeInfo) {
        if (nodeInfo == null) { // Check if nodeInfo was found by the caller
            System.out.println("[!] Keep-alive from unknown node_id: " + nodeId + " @ " + senderAddr + ". Asking to re-register.");
            JSONObject response = new JSONObject();
            response.put("status", "error");
            response.put("message", "Unknown node ID. Please re-register.");
            sendResponse(response, senderAddr);
            return;
        }
        // last_seen and public endpoint updated earlier by handlePacket
        // System.out.println("[*] Keep-alive received from: " + nodeInfo.username + " (" + nodeId + ")"); // Less noisy
    }

    // Pass requesterInfo which might already be looked up
    private static void handleConnectionRequest(JSONObject data, InetSocketAddress senderAddr, NodeInfo requesterInfo) {
        String requesterId = data.optString("node_id", null);
        String targetId = data.optString("target_id", null);

        if (requesterId == null || targetId == null) {
            System.out.println("[!] Invalid connection request (missing IDs) from " + senderAddr);
            return;
        }

        if (requesterInfo == null) {
            System.out.println("[!] Connection request from unknown node " + requesterId.substring(0,8) + ". Asking to re-register.");
            JSONObject response = new JSONObject();
            response.put("status", "error");
            response.put("message", "Unknown node ID. Please re-register first.");
            sendResponse(response, senderAddr);
            return;
        }
        // Check if requester has a public key stored (needed for exchange)
        if (requesterInfo.publicKeyBase64 == null || requesterInfo.publicKeyBase64.isEmpty()) {
             System.out.println("[!] Connection request from node " + requesterInfo.username + " ("+requesterId.substring(0,8)+") without a stored public key. Asking to re-register.");
             JSONObject response = new JSONObject();
             response.put("status", "error");
             response.put("message", "Missing public key on server. Please re-register.");
             sendResponse(response, senderAddr);
             return;
        }

        if (requesterId.equals(targetId)) {
            System.out.println("[!] Node " + requesterInfo.username + " (" + requesterId.substring(0,8) + ") tried to connect to itself. Ignoring.");
             JSONObject response = new JSONObject();
             response.put("status", "error");
             response.put("message", "Cannot connect to yourself.");
             sendResponse(response, senderAddr);
             return;
        }

        System.out.println("[*] Connection request: " + requesterInfo.username + " (" + requesterId.substring(0,8) + "...) ---> " + targetId.substring(0, Math.min(8, targetId.length())) + "...");

        NodeInfo targetInfo = nodes.get(targetId);

        if (targetInfo == null) {
            System.out.println("[*] Target node " + targetId.substring(0,Math.min(8, targetId.length())) + "... not currently registered or timed out. Storing request from " + requesterInfo.username + "...");
            pendingConnections.put(requesterId, targetId);
            JSONObject response = new JSONObject();
            response.put("status", "connection_request_received");
            response.put("action", "ack");
            response.put("waiting_for", targetId);
            sendResponse(response, senderAddr); // Acknowledge requester
            return;
        }

        // Check if target has a public key stored
        if (targetInfo.publicKeyBase64 == null || targetInfo.publicKeyBase64.isEmpty()) {
             System.out.println("[!] Target node " + targetInfo.username + " (" + targetId.substring(0,8) + ") does not have a public key stored. Cannot complete connection.");
             // Inform requester
             JSONObject response = new JSONObject();
             response.put("status", "error");
             response.put("message", "Target node " + targetInfo.username + " is missing a public key on the server. Cannot connect.");
             sendResponse(response, senderAddr);
             // We don't store the pending connection if target is invalid
             return;
        }


        // Both requester and target are known and have keys, store request
        pendingConnections.put(requesterId, targetId);

        // Check if the target has also requested the requester (match)
        String targetWants = pendingConnections.get(targetId);
        if (requesterId.equals(targetWants)) {
            System.out.println("[*] Connection match found: " + requesterInfo.username + " (" + requesterId.substring(0,8) + "...) <===> " + targetInfo.username + " (" + targetId.substring(0,8) + "...)");

            InetSocketAddress addrA = requesterInfo.lastAddr;
            InetSocketAddress addrB = targetInfo.lastAddr;

            if (addrA == null || addrB == null) {
                 System.err.println("[!!!] Critical Error: Missing last_addr for matched nodes " + requesterId.substring(0,8) + " or " + targetId.substring(0,8) + ". Cannot send info.");
                 pendingConnections.remove(requesterId);
                 pendingConnections.remove(targetId);
                 return;
            }

            // --- Prepare payload for Requester (Node A) -> Contains Target's (Node B) info ---
            JSONObject responseA = new JSONObject();
            responseA.put("action", "connection_info");
            responseA.put("status", "match_found");
            responseA.put("peer_id", targetId);
            responseA.put("peer_username", targetInfo.username);
            responseA.put("peer_public_key", targetInfo.publicKeyBase64); // <-- ADD TARGET'S PUBLIC KEY
            JSONArray endpointsB = new JSONArray();
            targetInfo.endpoints.forEach(ep -> endpointsB.put(ep.toJson()));
            responseA.put("peer_endpoints", endpointsB);

            // --- Prepare payload for Target (Node B) -> Contains Requester's (Node A) info ---
            JSONObject responseB = new JSONObject();
            responseB.put("action", "connection_info");
            responseB.put("status", "match_found");
            responseB.put("peer_id", requesterId);
            responseB.put("peer_username", requesterInfo.username);
            responseB.put("peer_public_key", requesterInfo.publicKeyBase64); // <-- ADD REQUESTER'S PUBLIC KEY
            JSONArray endpointsA = new JSONArray();
            requesterInfo.endpoints.forEach(ep -> endpointsA.put(ep.toJson()));
            responseB.put("peer_endpoints", endpointsA);

            // Send info to both nodes using their last known address
            boolean sentA = sendResponse(responseA, addrA);
            if(sentA) System.out.println("    -> Sent " + targetInfo.username + "'s info (incl. PubKey) to " + requesterInfo.username + " @ " + addrA);
            else System.err.println("[!!!] FAILED to send info to Requester " + requesterInfo.username + " @ " + addrA);

            boolean sentB = sendResponse(responseB, addrB);
            if(sentB) System.out.println("    -> Sent " + requesterInfo.username + "'s info (incl. PubKey) to " + targetInfo.username + " @ " + addrB);
            else System.err.println("[!!!] FAILED to send info to Target " + targetInfo.username + " @ " + addrB);


            // Clean up pending state for this pair AFTER attempting sends
            pendingConnections.remove(requesterId);
            pendingConnections.remove(targetId);

        } else {
            // Target hasn't requested back yet, just acknowledge requester
            System.out.println("[*] Stored request from " + requesterInfo.username + " for " + targetInfo.username + ". Waiting for target.");
            JSONObject response = new JSONObject();
            response.put("status", "connection_request_received");
            response.put("action", "ack");
            response.put("waiting_for", targetId);
            sendResponse(response, senderAddr);
        }
    }

    private static boolean sendResponse(JSONObject jsonObject, InetSocketAddress destination) {
        if (socket == null || socket.isClosed() || destination == null) {
            System.err.println("[!] Cannot send response, socket closed or destination null.");
            return false;
        }
        try {
            byte[] data = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > BUFFER_SIZE) {
                 System.err.println("[!] Cannot send response: Message size (" + data.length + ") exceeds buffer size (" + BUFFER_SIZE + ").");
                 return false; // Avoid sending oversized packets
            }
            DatagramPacket packet = new DatagramPacket(data, data.length, destination);
            socket.send(packet);
            return true;
        } catch (IOException e) {
            System.err.println("[!] Error sending UDP response to " + destination + ": " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("[!] Unexpected error sending UDP response to " + destination + ": " + e.getMessage());
            return false;
        }
    }

    // Synchronized to prevent race conditions during shutdown
    private static synchronized void shutdown() {
        if (!running) { return; } // Already shut down
        System.out.println("\n[*] Server shutting down...");
        running = false; // Signal loops to stop

        // Stop cleanup task first
        if (cleanupExecutor != null) {
            if (!cleanupExecutor.isShutdown()) {
                cleanupExecutor.shutdownNow();
                System.out.println("[*] Cleanup task shutdown requested.");
                try {
                    if (!cleanupExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                         System.err.println("[!] Cleanup task did not terminate cleanly.");
                    } else {
                        System.out.println("[*] Cleanup task terminated.");
                    }
                } catch (InterruptedException e) {
                    System.err.println("[!] Interrupted while waiting for cleanup task termination.");
                    Thread.currentThread().interrupt();
                }
            }
            cleanupExecutor = null;
        }

        // Close socket to interrupt blocking receive
        if (socket != null && !socket.isClosed()) {
            socket.close();
            System.out.println("[*] Server socket closed.");
        }
        socket = null; // Ensure it's nullified

        System.out.println("[*] Server shutdown sequence complete.");
    }
}