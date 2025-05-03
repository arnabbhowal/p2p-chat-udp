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
            context.udpSocket = new DatagramSocket(NodeConfig.LOCAL_UDP_PORT);
            context.udpSocket.setReuseAddress(true);
            System.out.println("[*] UDP Socket bound to local address: " + context.udpSocket.getLocalAddress().getHostAddress() + ":" + context.udpSocket.getLocalPort());
            return true;
        } catch (SocketException e) {
            System.err.println("[!!!] FATAL: Failed to create or bind UDP socket: " + e.getMessage());
            System.err.println("    Check if another application is using the port or if permissions are sufficient.");
            return false;
        } catch (SecurityException e) {
             System.err.println("[!!!] FATAL: Security Manager prevented socket creation: " + e.getMessage());
             return false;
        }
    }

    public void startListening() {
        if (context.udpSocket == null) {
            System.err.println("[!] Cannot start listener, socket not initialized.");
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
                context.udpSocket.receive(packet);
                if (!context.running.get()) break;

                InetSocketAddress senderAddr = (InetSocketAddress) packet.getSocketAddress();
                String messageStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                 if (messageStr.isEmpty()) continue;

                 // Ensure handler is set before using it
                 if (messageHandler != null) {
                    messageHandler.handleIncomingPacket(messageStr, senderAddr);
                 } else {
                     System.err.println("[!] Listener received packet but MessageHandler is null!");
                 }


            } catch (SocketException e) {
                if (context.running.get()) System.err.println("[Listener Thread] Socket closed or error: " + e.getMessage());
            } catch (IOException e) {
                if (context.running.get()) System.err.println("[Listener Thread] I/O error receiving UDP packet: " + e.getMessage());
            } catch (Exception e) {
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
             System.err.println("[!] Cannot send UDP: Socket is not open.");
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
            System.err.println("[!] Error sending UDP to " + destination + ": Port Unreachable.");
            if (messageHandler != null) messageHandler.handleSendError(destination, e); // Notify handler
            return false;
        } catch (IOException e) {
            System.err.println("[!] IO Error sending UDP to " + destination + ": " + e.getMessage());
            if (messageHandler != null) messageHandler.handleSendError(destination, e); // Notify handler
            return false;
        } catch (Exception e) {
            System.err.println("[!] Unexpected Error sending UDP to " + destination + ": " + e.getClass().getName() + " - " + e.getMessage());
            if (messageHandler != null) messageHandler.handleSendError(destination, e); // Notify handler
            return false;
        }
    }

    public void stop() {
        // Listener loop checks context.running
        if (context.udpSocket != null && !context.udpSocket.isClosed()) {
            context.udpSocket.close();
            System.out.println("[*] UDP Socket closed.");
        }
         context.udpSocket = null; // Ensure it's null after closing

        if (listenerExecutor != null && !listenerExecutor.isShutdown()) {
            listenerExecutor.shutdown();
            System.out.println("[*] Listener executor shutdown requested.");
            try {
                if (!listenerExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println("[!] Listener thread did not terminate cleanly, forcing...");
                    listenerExecutor.shutdownNow();
                } else {
                    System.out.println("[*] Listener executor terminated.");
                }
            } catch (InterruptedException e) {
                listenerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public int getLocalPort() {
        return (context.udpSocket != null && !context.udpSocket.isClosed()) ? context.udpSocket.getLocalPort() : -1;
    }
}