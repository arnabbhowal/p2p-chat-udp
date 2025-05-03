package main.java.com.p2pchat.server;

import main.java.com.p2pchat.common.Endpoint;
// Removed technically unused import: import org.json.JSONObject;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
// Removed unused import: import java.util.stream.Collectors;


// Represents information stored about a registered node
class NodeInfo { // Keep package-private
    String nodeId;
    String username; // << NEW: Store username
    List<Endpoint> endpoints = new CopyOnWriteArrayList<>(); // Thread-safe list
    long lastSeen;
    InetSocketAddress lastAddr; // Last address the node contacted us from

    NodeInfo(String nodeId, InetSocketAddress initialAddr) {
        this.nodeId = nodeId;
        this.lastSeen = Instant.now().toEpochMilli();
        this.lastAddr = initialAddr;
        this.username = "User_" + nodeId.substring(0,4); // Default username until set by register
    }

    void updateLastSeen(InetSocketAddress currentAddr) {
        this.lastSeen = Instant.now().toEpochMilli();
        this.lastAddr = currentAddr;
    }

    // Tries to find or add/update the public endpoint based on the address server saw
    void updatePublicEndpoint(InetSocketAddress publicAddr) {
        if (publicAddr == null || publicAddr.getAddress() == null) {
            System.err.println("[!] Invalid publicAddr provided to updatePublicEndpoint for node " + nodeId);
            return;
        }

        String publicIp = publicAddr.getAddress().getHostAddress();
        int publicPort = publicAddr.getPort();
        String type = "public_" + (publicAddr.getAddress() instanceof Inet6Address ? "v6" : "v4") + "_seen";

        // Clean ::ffff: prefix if present (IPv4 mapped IPv6)
        if (publicAddr.getAddress() instanceof Inet6Address && publicIp != null && publicIp.startsWith("::ffff:")) {
            publicIp = publicIp.substring("::ffff:".length());
            type = "public_v4_seen"; // Treat as v4
        }

        // Sanity check IP and Port
        if (publicIp == null || publicIp.isEmpty() || publicPort <= 0 || publicPort > 65535) {
             System.err.println("[!] Invalid IP/Port derived from publicAddr for update: " + publicIp + ":" + publicPort);
             return;
        }

        boolean found = false;
        for (Endpoint ep : endpoints) {
            if (ep.type != null && ep.type.equals(type)) {
                // Only update if IP or port actually changed
                if (!ep.ip.equals(publicIp) || ep.port != publicPort) {
                    System.out.println("    Updating existing " + type + " for " + nodeId + " from " + ep.ip + ":" + ep.port + " to " + publicIp + ":" + publicPort);
                    ep.ip = publicIp;
                    ep.port = publicPort;
                }
                found = true;
                break;
            }
        }
        if (!found) {
            Endpoint newPublicEp = new Endpoint(publicIp, publicPort, type);
            endpoints.add(0, newPublicEp); // Add to beginning
            System.out.println("    Added new " + type + " for " + nodeId + ": " + publicIp + ":" + publicPort);
        }
    }

    Endpoint getPublicEndpointSeen() {
        // Prefer IPv4 public seen if available, otherwise take first public seen one
        Endpoint ipv4Public = null;
        Endpoint firstPublic = null;
        for (Endpoint ep : endpoints) {
            if (ep.type != null && ep.type.contains("_seen")) {
                if (firstPublic == null) firstPublic = ep; // Grab the first one found
                if (ep.type.equals("public_v4_seen")) {
                    ipv4Public = ep;
                    break; // Found IPv4, prefer this
                }
            }
        }
        return (ipv4Public != null) ? ipv4Public : firstPublic;
    }
}