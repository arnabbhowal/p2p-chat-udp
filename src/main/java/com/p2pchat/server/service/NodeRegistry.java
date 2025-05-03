package main.java.com.p2pchat.server.service;

import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.server.config.ServerConfig;
import main.java.com.p2pchat.server.model.NodeInfo;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class NodeRegistry {

    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();
    private final Map<String, String> pendingConnections = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleanupExecutor;

    public void startCleanupTask() {
        if (cleanupExecutor != null && !cleanupExecutor.isShutdown()) {
            cleanupExecutor.shutdownNow();
        }
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NodeCleanupThread");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::cleanupInactiveNodes,
                ServerConfig.CLEANUP_INTERVAL_MS, ServerConfig.CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        System.out.println("[*] Started inactive node cleanup task (Interval: " + ServerConfig.CLEANUP_INTERVAL_MS + "ms).");
    }

    public void stopCleanupTask() {
        if (cleanupExecutor != null && !cleanupExecutor.isShutdown()) {
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

    private void cleanupInactiveNodes() {
        long now = Instant.now().toEpochMilli();
        List<String> nodesToRemove = new ArrayList<>();

        try {
            nodes.forEach((nodeId, nodeInfo) -> {
                if (now - nodeInfo.getLastSeen() > ServerConfig.NODE_TIMEOUT_MS) {
                    nodesToRemove.add(nodeId);
                    Endpoint pubEp = nodeInfo.getPublicEndpointSeen();
                    String endpointStr = (pubEp != null) ? (pubEp.ip + ":" + pubEp.port) : "Unknown";
                    System.out.println("[-] Node " + nodeInfo.getUsername() + " (" + nodeId + ") timed out (Last seen: " + ((now - nodeInfo.getLastSeen())/1000) + "s ago, Last Addr: " + endpointStr + "). Removing.");
                }
            });

            if (!nodesToRemove.isEmpty()) {
                for (String nodeId : nodesToRemove) {
                    nodes.remove(nodeId);
                    boolean removedPendingBy = pendingConnections.remove(nodeId) != null;
                    boolean removedPendingFor = pendingConnections.values().removeIf(targetId -> targetId.equals(nodeId));

                    if (removedPendingBy || removedPendingFor) {
                        System.out.println("    - Cleared pending requests associated with " + nodeId);
                    }
                }
                System.out.println("[*] Cleanup complete. Current Nodes (" + nodes.size() + "): " + String.join(", ", nodes.keySet()));
            }
        } catch (Exception e) {
            System.err.println("[!!!] Error during node cleanup task: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public NodeInfo getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public NodeInfo registerNode(InetSocketAddress initialAddr, String username, String publicKeyBase64, List<Endpoint> localEndpoints) {
        String newNodeId = UUID.randomUUID().toString();
        NodeInfo newNodeInfo = new NodeInfo(newNodeId, initialAddr);

        String finalUsername = (username == null || username.trim().isEmpty()) ? "User_" + newNodeId.substring(0, 4) : username.trim();
        newNodeInfo.setUsername(finalUsername);

        newNodeInfo.setPublicKeyBase64(publicKeyBase64); // Assume already validated by handler

        int addedCount = 0;
        for (Endpoint ep : localEndpoints) {
            if (ep != null && newNodeInfo.getEndpoints().stream().noneMatch(existing -> existing.equals(ep))) {
                newNodeInfo.getEndpoints().add(ep);
                addedCount++;
            }
        }

        newNodeInfo.updatePublicEndpoint(initialAddr); // Update public seen

        // Check if any endpoint exists now
        if (newNodeInfo.getEndpoints().isEmpty()) {
             System.out.println("[!] Registration failed: No valid endpoints (local or public) could be determined for " + initialAddr);
             return null; // Indicate failure
        }

        nodes.put(newNodeId, newNodeInfo);
        System.out.println("[*] Node registered: " + newNodeInfo.getUsername() + " (" + newNodeId + ") (" + nodes.size() + " total)");
        System.out.println("    Endpoints recorded: " + newNodeInfo.getEndpoints().stream().map(Endpoint::toString).collect(Collectors.joining(", ")));
        System.out.println("    Public Key stored: Yes");
        System.out.println("    Source Addr: " + initialAddr);
        return newNodeInfo;
    }

    public void addPendingConnection(String requesterId, String targetId) {
        pendingConnections.put(requesterId, targetId);
    }

    public String getPendingTarget(String requesterId) {
        return pendingConnections.get(requesterId);
    }

     public String getPendingRequester(String targetId) {
         // Find if anyone is waiting for this targetId
         for (Map.Entry<String, String> entry : pendingConnections.entrySet()) {
             if (entry.getValue().equals(targetId)) {
                 return entry.getKey(); // Return the ID of the node waiting for targetId
             }
         }
         return null;
     }

    public void removePendingConnection(String nodeA, String nodeB) {
        pendingConnections.remove(nodeA);
        pendingConnections.remove(nodeB);
    }

    public int getNodeCount() {
        return nodes.size();
    }

     public void updateNodeLastSeen(String nodeId, InetSocketAddress senderAddr) {
         NodeInfo nodeInfo = nodes.get(nodeId);
         if (nodeInfo != null) {
             nodeInfo.updateLastSeen(senderAddr);
             nodeInfo.updatePublicEndpoint(senderAddr); // Also update public endpoint on keep-alive etc.
         }
     }
}