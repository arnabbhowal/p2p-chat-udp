package main.java.com.p2pchat.node.network;

import main.java.com.p2pchat.node.config.NodeConfig;
import main.java.com.p2pchat.node.model.NodeContext;
import org.json.JSONObject;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit; // Added for awaitTermination

public class NetworkManager {

    private final NodeContext context;
    private MessageHandler messageHandler; // Removed final, will be set via setter
    // Ensure listener thread is DAEMON
    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "UDPListenerThread");
        t.setDaemon(true); // Make the listener thread a daemon thread
        return t;
    });

    // Constructor now only needs context
    public NetworkManager(NodeContext context) {
        this.context = context;
        this.messageHandler = null; // Initialize as null
    }

    // *** NEW SETTER METHOD ***
    public void setMessageHandler(MessageHandler handler) {
        this.messageHandler = handler;
    }
    // *************************

    public boolean initializeSocket() {
        try {
            // Try to bind explicitly if possible, fallback to auto-assign
             InetAddress bindAddr = null; // Allow binding to wildcard by default
             // Example: If you wanted to force IPv4
             // bindAddr = InetAddress.getByName("0.0.0.0");
             // Example: Choose a specific local interface if needed (more complex)

             // Use LOCAL_UDP_PORT (0 means auto-assign)
            context.udpSocket = (bindAddr != null) ? new DatagramSocket(NodeConfig.LOCAL_UDP_PORT, bindAddr) : new DatagramSocket(NodeConfig.LOCAL_UDP_PORT);

            context.udpSocket.setReuseAddress(true); // Good practice
            // Set a socket timeout? Helps listener thread exit if socket isn't closed cleanly.
            // context.udpSocket.setSoTimeout(5000); // e.g., 5 seconds

            System.out.println("[*] UDP Socket bound to local address: " + context.udpSocket.getLocalAddress().getHostAddress() + ":" + context.udpSocket.getLocalPort());
            return true;
        } catch (SocketException e) {
            System.err.println("[!!!] FATAL: Failed to create or bind UDP socket: " + e.getMessage());
            System.err.println("    Check if another application is using the port or if permissions are sufficient.");
            return false;
        } catch (SecurityException e) {
             System.err.println("[!!!] FATAL: Security Manager prevented socket creation: " + e.getMessage());
             return false;
        } catch (Exception e) { // Catch other potential errors during socket init
             System.err.println("[!!!] FATAL: Unexpected error during socket initialization: " + e.getMessage());
             e.printStackTrace();
             return false;
        }
    }

    public void startListening() {
        if (context.udpSocket == null || context.udpSocket.isClosed()) { // Check isClosed too
            System.err.println("[!] Cannot start listener, socket not initialized or closed.");
            return;
        }
        if (this.messageHandler == null) {
             System.err.println("[!] Cannot start listener, MessageHandler not set.");
             return;
        }
        listenerExecutor.submit(this::udpListenerLoop);
    }

    private void udpListenerLoop() {
        System.out.println("[Listener Thread] Started. Listening for UDP packets...");
        byte[] buffer = new byte[NodeConfig.BUFFER_SIZE];
        while (context.running.get() && context.udpSocket != null && !context.udpSocket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                // This blocks until a packet is received or the socket is closed/times out
                context.udpSocket.receive(packet);

                // Re-check running state immediately after receive returns
                if (!context.running.get()) break;

                InetSocketAddress senderAddr = (InetSocketAddress) packet.getSocketAddress();
                String messageStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                 if (messageStr.isEmpty()) continue;

                 // Ensure handler is set before using it
                 if (messageHandler != null) {
                    // Run handler logic in a separate thread? Or keep it sequential?
                    // For simplicity, keep sequential for now. If handlers block, it delays listening.
                    messageHandler.handleIncomingPacket(messageStr, senderAddr);
                 } else {
                     System.err.println("[!] Listener received packet but MessageHandler is null!");
                 }


            } catch (SocketTimeoutException e) {
                 // Ignore timeout if we set one, just loop again if still running
                 continue;
            } catch (SocketException e) {
                // SocketException expected when socket is closed during shutdown
                if (context.running.get()) { // Only log if not expected during shutdown
                     System.err.println("[Listener Thread] Socket Exception (Possibly closed or error): " + e.getMessage());
                     // Consider stopping if a critical socket error occurs?
                     // context.running.set(false);
                }
                // Exit loop if socket closed or error occurs
                break;
            } catch (IOException e) {
                if (context.running.get()) System.err.println("[Listener Thread] I/O error receiving UDP packet: " + e.getMessage());
                // Maybe add a small delay before retrying after IO error?
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            } catch (Exception e) { // Catch unexpected errors during packet handling
                if (context.running.get()) {
                    System.err.println("[Listener Thread] Error processing UDP packet from " + ((packet != null && packet.getSocketAddress() != null) ? packet.getSocketAddress() : "unknown") + ": " + e.getClass().getName() + " - " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        System.out.println("[*] UDP Listener thread finished.");
    }

    public boolean sendUdp(JSONObject jsonObject, InetSocketAddress destination) {
        if (context.udpSocket == null || context.udpSocket.isClosed()) {
             // System.err.println("[!] Cannot send UDP: Socket is not open."); // Reduce noise
             return false;
        }
        if (destination == null) {
            System.err.println("[!] Cannot send UDP: Destination address is null.");
            return false;
        }
        try {
            byte[] data = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > NodeConfig.BUFFER_SIZE) {
                System.err.println("[!] Cannot send UDP: Message size (" + data.length + ") exceeds buffer size (" + NodeConfig.BUFFER_SIZE + ").");
                return false;
            }
            DatagramPacket packet = new DatagramPacket(data, data.length, destination);
            context.udpSocket.send(packet);
            return true;
        } catch (PortUnreachableException e) {
            // This is common if peer is offline, reduce log noise? Only log first time?
             System.err.println("[!] Error sending UDP to " + destination + ": Port Unreachable.");
            if (messageHandler != null) messageHandler.handleSendError(destination, e); // Notify handler
            return false;
        } catch (IOException e) {
            // Log other IO errors which might be more serious
            System.err.println("[!] IO Error sending UDP to " + destination + ": " + e.getMessage());
            if (messageHandler != null) messageHandler.handleSendError(destination, e); // Notify handler
            return false;
        } catch (Exception e) {
            System.err.println("[!] Unexpected Error sending UDP to " + destination + ": " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace(); // Print stack trace for unexpected errors
            if (messageHandler != null) messageHandler.handleSendError(destination, e); // Notify handler
            return false;
        }
    }

    public void stop() {
        System.out.println("[NetworkManager] Stop requested.");
        // Listener loop checks context.running flag AND socket state

        // Close the socket first - this should interrupt the listener thread's receive() call
        if (context.udpSocket != null && !context.udpSocket.isClosed()) {
            System.out.println("[*] Closing UDP Socket...");
            context.udpSocket.close();
            System.out.println("[*] UDP Socket closed.");
        }
         context.udpSocket = null; // Ensure it's null after closing

        // Now shutdown the executor service for the listener thread
        if (listenerExecutor != null && !listenerExecutor.isShutdown()) {
            System.out.println("[*] Shutting down Listener executor...");
            listenerExecutor.shutdown(); // Initiate shutdown
            try {
                // Wait for the listener thread to finish
                if (!listenerExecutor.awaitTermination(2, TimeUnit.SECONDS)) { // Increased timeout slightly
                    System.err.println("[!] Listener thread did not terminate cleanly after 2s, forcing...");
                    listenerExecutor.shutdownNow(); // Force shutdown
                } else {
                    System.out.println("[*] Listener executor terminated successfully.");
                }
            } catch (InterruptedException e) {
                System.err.println("[!] Interrupted while waiting for listener executor termination.");
                listenerExecutor.shutdownNow(); // Force shutdown on interrupt
                Thread.currentThread().interrupt(); // Preserve interrupt status
            }
        }
        System.out.println("[NetworkManager] Stop completed.");
    }

    public int getLocalPort() {
        return (context.udpSocket != null && !context.udpSocket.isClosed()) ? context.udpSocket.getLocalPort() : -1;
    }
}