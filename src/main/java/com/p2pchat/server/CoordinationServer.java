package main.java.com.p2pchat.server;

import main.java.com.p2pchat.server.config.ServerConfig;
import main.java.com.p2pchat.server.network.ServerNetworkManager;
import main.java.com.p2pchat.server.service.NodeRegistry;

import java.net.SocketException;
import java.net.UnknownHostException;

public class CoordinationServer {

    private static NodeRegistry nodeRegistry;
    private static ServerNetworkManager networkManager;
    private static volatile boolean running = true;

    public static void main(String[] args) {
        System.out.println("[*] Initializing Coordination Server...");
        nodeRegistry = new NodeRegistry();
        networkManager = new ServerNetworkManager(nodeRegistry);

        try {
            nodeRegistry.startCleanupTask();

            Runtime.getRuntime().addShutdownHook(new Thread(CoordinationServer::shutdown));

            networkManager.start(); // This blocks until stopped or error

        } catch (SocketException e) {
            System.err.println("[!!!] Failed to bind UDP socket on port " + ServerConfig.PORT + ": " + e.getMessage());
            e.printStackTrace();
        } catch (UnknownHostException e) {
             System.err.println("[!!!] Failed to resolve bind address: " + e.getMessage());
             e.printStackTrace();
        } catch (SecurityException e) {
            System.err.println("[!!!] Security Manager prevented network operations: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[!!!] Unexpected error during server startup or run: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown(); // Ensure shutdown runs even if start() throws error
            System.out.println("[*] Server main method finished.");
        }
    }

    private static synchronized void shutdown() {
        if (!running) return;
        System.out.println("\n[*] Server shutting down...");
        running = false;

        if (networkManager != null) {
            networkManager.stop();
        }
        if (nodeRegistry != null) {
            nodeRegistry.stopCleanupTask();
        }

        networkManager = null;
        nodeRegistry = null;

        System.out.println("[*] Server shutdown sequence complete.");
    }
}