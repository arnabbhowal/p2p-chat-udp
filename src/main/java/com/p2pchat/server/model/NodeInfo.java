package main.java.com.p2pchat.server.model;

import main.java.com.p2pchat.common.Endpoint;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class NodeInfo {
    String nodeId;
    String username;
    String publicKeyBase64;
    List<Endpoint> endpoints = new CopyOnWriteArrayList<>();
    long lastSeen;
    InetSocketAddress lastAddr;

    public NodeInfo(String nodeId, InetSocketAddress initialAddr) {
        this.nodeId = nodeId;
        this.lastSeen = Instant.now().toEpochMilli();
        this.lastAddr = initialAddr;
        this.username = "User_" + nodeId.substring(0,4);
        this.publicKeyBase64 = null;
    }

    // Added public getters for registry access
    public String getNodeId() { return nodeId; }
    public String getUsername() { return username; }
    public String getPublicKeyBase64() { return publicKeyBase64; }
    public List<Endpoint> getEndpoints() { return endpoints; } // Returns thread-safe list directly
    public InetSocketAddress getLastAddr() { return lastAddr; }
    public long getLastSeen() { return lastSeen; }

    // Added public setters needed by registry/handlers
    public void setUsername(String username) { this.username = username; }
    public void setPublicKeyBase64(String publicKeyBase64) { this.publicKeyBase64 = publicKeyBase64; }

    public void updateLastSeen(InetSocketAddress currentAddr) {
        this.lastSeen = Instant.now().toEpochMilli();
        this.lastAddr = currentAddr;
    }

    public void updatePublicEndpoint(InetSocketAddress publicAddr) {
        if (publicAddr == null || publicAddr.getAddress() == null) {
            System.err.println("[!] Invalid publicAddr provided to updatePublicEndpoint for node " + nodeId);
            return;
        }

        String publicIp = publicAddr.getAddress().getHostAddress();
        int publicPort = publicAddr.getPort();
        String type = "public_" + (publicAddr.getAddress() instanceof Inet6Address ? "v6" : "v4") + "_seen";

        if (publicAddr.getAddress() instanceof Inet6Address && publicIp != null && publicIp.startsWith("::ffff:")) {
            publicIp = publicIp.substring("::ffff:".length());
            type = "public_v4_seen";
        }

        if (publicIp == null || publicIp.isEmpty() || publicPort <= 0 || publicPort > 65535) {
             System.err.println("[!] Invalid IP/Port derived from publicAddr for update: " + publicIp + ":" + publicPort);
             return;
        }

        boolean found = false;
        for (Endpoint ep : endpoints) {
            if (ep.type != null && ep.type.equals(type)) {
                if (!ep.ip.equals(publicIp) || ep.port != publicPort) {
                    ep.ip = publicIp;
                    ep.port = publicPort;
                }
                found = true;
                break;
            }
        }
        if (!found) {
            Endpoint newPublicEp = new Endpoint(publicIp, publicPort, type);
            endpoints.add(0, newPublicEp);
        }
    }

    public Endpoint getPublicEndpointSeen() {
        Endpoint ipv4Public = null;
        Endpoint firstPublic = null;
        for (Endpoint ep : endpoints) {
            if (ep.type != null && ep.type.contains("_seen")) {
                if (firstPublic == null) firstPublic = ep;
                if (ep.type.equals("public_v4_seen")) {
                    ipv4Public = ep;
                    break;
                }
            }
        }
        return (ipv4Public != null) ? ipv4Public : firstPublic;
    }
}