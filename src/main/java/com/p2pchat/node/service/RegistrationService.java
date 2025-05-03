package main.java.com.p2pchat.node.service;

import main.java.com.p2pchat.common.CryptoUtils;
import main.java.com.p2pchat.common.Endpoint;
// import main.java.com.p2pchat.node.config.NodeConfig; // Not directly needed here
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.network.NetworkManager;
// Import the callback interface
import main.java.com.p2pchat.node.ui.GuiCallback;


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
    private GuiCallback guiCallback = null; // <<< Added callback reference


    public RegistrationService(NodeContext context, NetworkManager networkManager) {
        this.context = context;
        this.networkManager = networkManager;
    }

    // <<< Added setter for callback >>>
    public void setGuiCallback(GuiCallback callback) {
        this.guiCallback = callback;
    }

     public boolean generateKeys() {
         System.out.println("[*] Generating Elliptic Curve key pair...");
         try {
             context.myKeyPair = CryptoUtils.generateECKeyPair();
             System.out.println("[+] Key pair generated successfully.");
             return true;
         } catch (NoSuchAlgorithmException e) {
             String errorMsg = "[!!!] FATAL: Failed to generate key pair. Cryptography provider not supported: " + e.getMessage();
             System.err.println(errorMsg);
             // <<< Notify GUI >>> (Potentially difficult if GUI not fully initialized)
             // Consider showing JOptionPane here or letting main handle exit
             if (guiCallback != null) guiCallback.appendMessage("System: Error - " + errorMsg);
             return false;
         }
     }

    public void discoverLocalEndpoints() {
         int localPort = networkManager.getLocalPort();
         if (localPort <= 0) {
             System.err.println("[!] Cannot discover local endpoints, socket not bound correctly.");
             return;
         }
         System.out.println("[*] Discovering local network endpoints...");
         List<Endpoint> endpoints = new ArrayList<>();
         try {
             Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
             while (interfaces.hasMoreElements()) {
                 NetworkInterface ni = interfaces.nextElement();
                 try {
                     // Filter out loopback, virtual, down interfaces
                     if (ni.isLoopback() || !ni.isUp() || ni.isVirtual() || ni.isPointToPoint()) continue;
                     // Filter based on name (optional, might exclude valid interfaces)
                     // if (ni.getDisplayName().toLowerCase().contains("virtual") || ni.getDisplayName().toLowerCase().contains("vmnet")) continue;

                     Enumeration<InetAddress> addresses = ni.getInetAddresses();
                     while (addresses.hasMoreElements()) {
                         InetAddress addr = addresses.nextElement();
                         // Filter out non-site-local, loopback, anylocal, multicast
                         if (addr.isLinkLocalAddress() || addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()) continue;

                         String ip = addr.getHostAddress();
                         String type;

                         if (addr instanceof Inet6Address) {
                             // Remove scope ID if present (e.g., %eth0)
                             int scopeIndex = ip.indexOf('%');
                             if (scopeIndex > 0) ip = ip.substring(0, scopeIndex);
                             // Skip IPv4 mapped addresses (::ffff:x.x.x.x)
                             if (ip.startsWith("::ffff:")) continue;
                             // Skip temporary IPv6 addresses (more complex to identify reliably)
                             type = "local_v6";
                         } else if (addr instanceof Inet4Address) {
                             // Skip typical Docker/VM bridge IPs? (e.g., 172.17.x.x) - Adjust if needed
                             // if (ip.startsWith("172.17.")) continue;
                             type = "local_v4";
                         } else {
                             continue; // Skip other address types
                         }
                         endpoints.add(new Endpoint(ip, localPort, type));
                     }
                 } catch (SocketException se) {
                     // Ignore interfaces that cause errors during querying
                     // System.err.println("[?] Minor error querying interface " + ni.getName() + ": " + se.getMessage());
                 }
             }
         } catch (SocketException e) {
             System.err.println("[!] Error getting network interfaces: " + e.getMessage());
         }

         // Use CopyOnWriteArrayList directly in context, clear and addAll unique endpoints
         context.myLocalEndpoints.clear();
         context.myLocalEndpoints.addAll(new ArrayList<>(new HashSet<>(endpoints))); // Add unique endpoints

         System.out.println("[*] Discovered Local Endpoints:");
         if (context.myLocalEndpoints.isEmpty()) {
              System.out.println("    <None found>");
              System.out.println("[!] No suitable local non-loopback IPs found. Will rely on public IP seen by server.");
              if (guiCallback != null) guiCallback.appendMessage("System: Warning - No local IPs found, relying on server detection.");
         }
         else context.myLocalEndpoints.forEach(ep -> System.out.println("    - " + ep));
    }


    public boolean registerWithServer() {
        String errorPrefix = "System: Error - ";
        if (context.myKeyPair == null) {
             String msg = "[!] Cannot register: KeyPair not generated."; System.err.println(msg);
             if (guiCallback != null) guiCallback.appendMessage(errorPrefix + msg); return false;
        }
        if (context.serverAddress == null) {
            String msg = "[!] Cannot register: Server address not resolved."; System.err.println(msg);
             if (guiCallback != null) guiCallback.appendMessage(errorPrefix + msg); return false;
        }

        context.currentState.set(NodeState.REGISTERING);
        // <<< Notify GUI >>>
        if (guiCallback != null) {
             guiCallback.updateState(NodeState.REGISTERING, null, null);
        }

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
            String msg = "[!] Failed to send registration message."; System.err.println(msg);
            context.currentState.set(NodeState.DISCONNECTED); // Revert state
             // <<< Notify GUI >>>
             if (guiCallback != null) {
                 guiCallback.appendMessage(errorPrefix + msg);
                 guiCallback.updateState(NodeState.DISCONNECTED, null, null);
             }
            return false;
        }

        // --- Wait for confirmation (Node ID to be set by ServerMessageHandler) ---
        // ServerMessageHandler is now responsible for calling the GUI callback upon success/failure
        // We just need to wait here for a reasonable time.
        long startTime = System.currentTimeMillis();
        long timeout = 10000; // Increased timeout (10s)
        boolean registrationComplete = false;
        while (context.running.get() && System.currentTimeMillis() - startTime < timeout) {
            // Check if Node ID is set OR if state reverted to DISCONNECTED (indicating error from server)
            if (context.myNodeId.get() != null) {
                 registrationComplete = true;
                 break; // Success!
            }
             // If state changes back to DISCONNECTED during REGISTERING, it likely means server sent an error
             if (context.currentState.get() == NodeState.DISCONNECTED) {
                 System.err.println("[!] Registration failed (Server error or invalid response).");
                 // GUI was likely already updated by ServerMessageHandler error handling
                 registrationComplete = false;
                 break; // Failure
             }
             // If state changes to something else unexpectedly, break loop
             if (context.currentState.get() != NodeState.REGISTERING) {
                 System.err.println("[!] State changed unexpectedly during registration wait: " + context.currentState.get());
                 registrationComplete = false; // Assume failure
                 break;
             }

            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        // After loop, check final outcome
        if (!registrationComplete && context.running.get()) { // Only show timeout if not shutting down
            if (context.myNodeId.get() == null && context.currentState.get() == NodeState.REGISTERING) {
                 // If still registering and timed out
                 String msg = "[!] No registration confirmation received from server within " + timeout / 1000 + " seconds.";
                 System.err.println(msg);
                 context.currentState.set(NodeState.DISCONNECTED); // Set final state
                 // <<< Notify GUI >>>
                 if (guiCallback != null) {
                     guiCallback.appendMessage(errorPrefix + msg);
                     guiCallback.updateState(NodeState.DISCONNECTED, null, null);
                 }
            } else if (context.myNodeId.get() == null) {
                 // Registration failed for other reasons (e.g. server error handled by handler)
                 // GUI should have been updated already.
            }
        }

        // Return true ONLY if Node ID was successfully set
        return context.myNodeId.get() != null;
    }

    // Method called by ServerMessageHandler upon successful registration
    // This method now triggers the GUI update for the Node ID
    public void processRegistrationSuccess(String nodeId, Endpoint publicEndpoint) {
        System.out.println("[+] Registration Confirmed. Node ID: " + nodeId);
        if(publicEndpoint != null) {
            System.out.println("[*] Server recorded public endpoint: " + publicEndpoint);
        }
        // <<< Notify GUI: Display Node ID and set state to Disconnected >>>
        if (guiCallback != null) {
            guiCallback.displayNodeId(nodeId);
            // Update state AFTER displaying ID
            guiCallback.updateState(NodeState.DISCONNECTED, null, null);
        }
    }

     // Method called by ServerMessageHandler upon registration failure reported by server
     public void processRegistrationFailure(String reason) {
         System.err.println("[!] Registration Failed (Server Error): " + reason);
          // <<< Notify GUI: Show error and set state to Disconnected >>>
          if (guiCallback != null) {
              guiCallback.appendMessage("System: Registration Failed - " + reason);
              guiCallback.updateState(NodeState.DISCONNECTED, null, null);
          }
     }
}