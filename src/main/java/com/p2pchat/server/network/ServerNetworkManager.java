package main.java.com.p2pchat.server.network;

import main.java.com.p2pchat.server.config.ServerConfig;
import main.java.com.p2pchat.server.service.NodeRegistry;
import org.json.JSONObject;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class ServerNetworkManager {

    private DatagramSocket socket;
    private volatile boolean running = true;
    private final ServerMessageHandler messageHandler;

    public ServerNetworkManager(NodeRegistry registry) {
        this.messageHandler = new ServerMessageHandler(registry, this); // Pass registry and self (for sending)
    }

    public void start() throws SocketException, UnknownHostException {
        socket = new DatagramSocket(ServerConfig.PORT, InetAddress.getByName(ServerConfig.BIND_ADDRESS));
        socket.setReuseAddress(true);
        System.out.println("[*] Coordination Server listening on UDP port " + ServerConfig.PORT);

        byte[] buffer = new byte[ServerConfig.BUFFER_SIZE];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                if (running) {
                    messageHandler.handlePacket(packet);
                }
            } catch (SocketException se) {
                if (running) {
                    System.err.println("[!] Socket Exception (likely closed): " + se.getMessage());
                }
                running = false;
            } catch (IOException e) {
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
    }

    public boolean sendResponse(JSONObject jsonObject, InetSocketAddress destination) {
        if (socket == null || socket.isClosed() || destination == null) {
            System.err.println("[!] Cannot send response, socket closed or destination null.");
            return false;
        }
        try {
            byte[] data = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > ServerConfig.BUFFER_SIZE) {
                 System.err.println("[!] Cannot send response: Message size (" + data.length + ") exceeds buffer size (" + ServerConfig.BUFFER_SIZE + ").");
                 return false;
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

    public void stop() {
        if (!running) return;
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
            System.out.println("[*] Server socket closed.");
        }
        socket = null;
    }
}