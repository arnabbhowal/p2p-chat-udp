package main.java.com.p2pchat.node.service;

import main.java.com.p2pchat.common.CryptoUtils;
import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.node.config.NodeConfig;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.network.NetworkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.Inet6Address;
import java.net.Inet4Address;
import java.security.NoSuchAlgorithmException;
import java.util.*;


public class RegistrationService {

    private final NodeContext context;
    private final NetworkManager networkManager;

    public RegistrationService(NodeContext context, NetworkManager networkManager) {
        this.context = context;
        this.networkManager = networkManager;
    }

     public boolean generateKeys() {
         System.out.println("[*] Generating Elliptic Curve key pair...");
         try {
             context.myKeyPair = CryptoUtils.generateECKeyPair();
             System.out.println("[+] Key pair generated successfully.");
             return true;
         } catch (NoSuchAlgorithmException e) {
             System.err.println("[!!!] FATAL: Failed to generate key pair. Cryptography provider not supported: " + e.getMessage());
             return false;
         }
     }

    public void discoverLocalEndpoints() {
         int localPort = networkManager.getLocalPort();
         if (localPort <= 0) {
             System.err.println("[!] Cannot discover local endpoints, socket not bound correctly.");
             return;
         }
         List<Endpoint> endpoints = new ArrayList<>();
         try {
             Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
             while (interfaces.hasMoreElements()) {
                 NetworkInterface ni = interfaces.nextElement();
                 try {
                     if (ni.isLoopback() || !ni.isUp() || ni.isVirtual() || ni.isPointToPoint()) continue;
                     Enumeration<InetAddress> addresses = ni.getInetAddresses();
                     while (addresses.hasMoreElements()) {
                         InetAddress addr = addresses.nextElement();
                         if (addr.isLinkLocalAddress() || addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()) continue;
                         String ip = addr.getHostAddress();
                         String type;
                         if (addr instanceof Inet6Address) {
                             int scopeIndex = ip.indexOf('%');
                             if (scopeIndex > 0) ip = ip.substring(0, scopeIndex);
                             if (ip.startsWith("::ffff:")) continue; // Skip IPv4 mapped
                             type = "local_v6";
                         } else if (addr instanceof Inet4Address) {
                             type = "local_v4";
                         } else continue;
                         endpoints.add(new Endpoint(ip, localPort, type));
                     }
                 } catch (SocketException se) { /* Ignore interfaces that error */ }
             }
         } catch (SocketException e) {
             System.err.println("[!] Error getting network interfaces: " + e.getMessage());
         }

         // Use CopyOnWriteArrayList directly in context, clear and addAll
         context.myLocalEndpoints.clear();
         context.myLocalEndpoints.addAll(new ArrayList<>(new HashSet<>(endpoints))); // Add unique endpoints

         System.out.println("[*] Discovered Local Endpoints:");
         if (context.myLocalEndpoints.isEmpty()) System.out.println("    <None found>");
         else context.myLocalEndpoints.forEach(ep -> System.out.println("    - " + ep));
         if (context.myLocalEndpoints.isEmpty()) System.out.println("[!] No suitable local non-loopback IPs found. Will rely on public IP seen by server.");
    }


    public boolean registerWithServer() {
        if (context.myKeyPair == null) {
             System.err.println("[!] Cannot register: KeyPair not generated.");
             return false;
        }
        if (context.serverAddress == null) {
            System.err.println("[!] Cannot register: Server address not resolved.");
            return false;
        }

        context.currentState.set(NodeState.REGISTERING); // Set state

        JSONObject registerMsg = new JSONObject();
        registerMsg.put("action", "register");
        registerMsg.put("username", context.myUsername);
        registerMsg.put("public_key", CryptoUtils.encodePublicKey(context.myKeyPair.getPublic()));
        JSONArray localEndpointsJson = new JSONArray();
        context.myLocalEndpoints.forEach(ep -> localEndpointsJson.put(ep.toJson()));
        registerMsg.put("local_endpoints", localEndpointsJson);

        System.out.println("[*] Sending registration (with public key) to server " + context.serverAddress + "...");
        boolean sent = networkManager.sendUdp(registerMsg, context.serverAddress);
        if (!sent) {
            System.err.println("[!] Failed to send registration message.");
            context.currentState.set(NodeState.DISCONNECTED); // Revert state
            return false;
        }

        // Wait for confirmation (Node ID to be set by ServerMessageHandler)
        long startTime = System.currentTimeMillis();
        long timeout = 5000;
        while (context.myNodeId.get() == null && System.currentTimeMillis() - startTime < timeout) {
            // Check if state changed back to disconnected due to error
            if (context.currentState.get() != NodeState.REGISTERING) {
                System.err.println("[!] Registration interrupted or failed.");
                return false;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); context.currentState.set(NodeState.DISCONNECTED); return false; }
        }

        if (context.myNodeId.get() == null) {
            System.err.println("[!] No registration confirmation received from server within " + timeout / 1000 + " seconds.");
            context.currentState.set(NodeState.DISCONNECTED); // Revert state
            return false;
        }

        // State will be set to DISCONNECTED by ServerMessageHandler on success
        return true; // Node ID is set, registration considered successful
    }
}