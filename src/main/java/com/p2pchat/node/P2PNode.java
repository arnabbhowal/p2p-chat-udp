package main.java.com.p2pchat.node;

import main.java.com.p2pchat.node.config.NodeConfig;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.network.MessageHandler;
import main.java.com.p2pchat.node.network.NetworkManager;
import main.java.com.p2pchat.node.network.PeerMessageHandler;
import main.java.com.p2pchat.node.network.ServerMessageHandler;
import main.java.com.p2pchat.node.service.ChatService;
import main.java.com.p2pchat.node.service.ConnectionService;
import main.java.com.p2pchat.node.service.FileTransferService;
import main.java.com.p2pchat.node.service.RegistrationService;
// Import the GUI class and callback
import main.java.com.p2pchat.node.ui.GuiCallback;
import main.java.com.p2pchat.node.ui.P2pChatGui;

import javax.swing.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public class P2PNode {

    // Static references accessible for shutdown and GUI initialization
    private static NodeContext nodeContext;
    private static NetworkManager networkManager;
    private static ConnectionService connectionService;
    private static FileTransferService fileTransferService;
    private static RegistrationService registrationService;
    private static ChatService chatService;
    private static P2pChatGui gui; // Reference to the GUI
    private static volatile boolean isShuttingDown = false;

    // --- Static Shutdown Method ---
    public static synchronized void shutdown() {
        if (isShuttingDown) return;
        isShuttingDown = true;
        System.out.println("\n[*] Shutting down node...");
        if (nodeContext != null) {
            nodeContext.running.set(false);
            nodeContext.currentState.set(NodeState.SHUTTING_DOWN);
            // Notify GUI about shutdown state
            if (gui != null) {
                // Ensure GUI update happens on EDT, though it might be closing anyway
                SwingUtilities.invokeLater(() -> gui.updateState(NodeState.SHUTTING_DOWN, null, null));
            }
        }
        // Stop services in appropriate order
        if (fileTransferService != null) fileTransferService.stop();
        if (connectionService != null) connectionService.stop();
        if (networkManager != null) networkManager.stop(); // Stops listener and socket

        // Clean up context data
        if (nodeContext != null) {
            nodeContext.cancelAndCleanupTransfersForPeer(null, "Node shutdown");
            nodeContext.peerSymmetricKeys.clear();
            nodeContext.myKeyPair = null;
            System.out.println("[*] Cleared cryptographic keys.");
        }
        System.out.println("[*] Node shutdown sequence complete.");
    }

    // --- Main Entry Point ---
    public static void main(String[] args) {
        // --- Server IP Configuration ---
        String serverIpArg = null;
        if (args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            serverIpArg = args[0].trim();
            System.out.println("[*] Using Server IP from command line: " + serverIpArg);
        } else {
            if (NodeConfig.SERVER_IP.equals("127.0.0.1")) {
                System.out.println("[!] WARNING: Using default Server IP '127.0.0.1'. Pass the actual server IP as an argument if needed.");
            } else {
                System.out.println("[*] Using configured Server IP: " + NodeConfig.SERVER_IP);
            }
            serverIpArg = NodeConfig.SERVER_IP; // Use default/configured
        }
        final String serverIp = serverIpArg; // Final for use in Swing thread

        // --- Backend Initialization (run sequentially before GUI) ---
        nodeContext = new NodeContext();
        nodeContext.currentState.set(NodeState.INITIALIZING);

        // Add shutdown hook EARLY
        Runtime.getRuntime().addShutdownHook(new Thread(P2PNode::shutdown));

        // Perform critical initializations that must succeed
        if (!resolveServerAddress(serverIp)) { System.exit(1); return; }
        if (!initializeBackendServices()) { System.exit(1); return; }
        if (!registrationService.generateKeys()) { System.exit(1); return; }
        if (!networkManager.initializeSocket()) { System.exit(1); return; }

        // --- Get Username via GUI Dialog ---
        String username = promptForUsername();
        if (username == null) { // User cancelled
            System.out.println("[!] Username input cancelled. Exiting.");
            System.exit(0); return;
        }
        nodeContext.myUsername = username;
        System.out.println("[*] Username set to: " + nodeContext.myUsername);

        // --- Launch GUI and Start Network Tasks ---
        SwingUtilities.invokeLater(() -> {
            // Create GUI instance
            gui = new P2pChatGui(nodeContext, connectionService, chatService, fileTransferService);

            // Set the GUI callback reference in backend services
            // *** This assumes you have ADDED the setGuiCallback methods to the services ***
            setGuiCallbackForAllServices(gui);

            // --- Perform Network/Registration Tasks Off EDT ---
            new Thread(() -> {
                try {
                    gui.appendMessage("System: Discovering local endpoints...");
                    registrationService.discoverLocalEndpoints();

                    gui.appendMessage("System: Network listener starting...");
                    networkManager.startListening();
                    gui.appendMessage("System: Network listener started.");

                    gui.appendMessage("System: Registering with server...");
                    if (!registrationService.registerWithServer()) {
                        gui.appendMessage("System: [!!!] FAILED TO REGISTER WITH SERVER. Check server IP/status and restart.");
                        // Update state to reflect failure if registration fails
                        SwingUtilities.invokeLater(() -> gui.updateState(NodeState.DISCONNECTED, null, null));
                        // Don't start background tasks if registration failed
                    } else {
                        // Registration successful, state will be updated via callback
                        // Start background service tasks ONLY after successful registration attempt
                        connectionService.start();
                        fileTransferService.start();
                        gui.appendMessage("System: Ready. Use 'Connect' button.");
                    }
                } catch (Exception e) {
                    System.err.println("[!!!] Error during background initialization: " + e.getMessage());
                    e.printStackTrace();
                    SwingUtilities.invokeLater(() -> gui.appendMessage("System: [!!!] Critical error during startup. Please restart."));
                    // Consider a more robust error handling/display mechanism
                }
            }, "Backend-Init-Thread").start();

        });

        // Main thread completes; application runs on EDT and backend threads
        System.out.println("[*] Main thread finished launching GUI.");
    }


    // --- Helper Methods ---

    private static boolean resolveServerAddress(String serverIp) {
        System.out.println("[*] Resolving server address: " + serverIp + ":" + NodeConfig.SERVER_PORT + "...");
        try {
            nodeContext.serverAddress = new InetSocketAddress(InetAddress.getByName(serverIp), NodeConfig.SERVER_PORT);
            System.out.println("[*] Server address resolved to: " + nodeContext.serverAddress);
            return true;
        } catch (UnknownHostException e) { System.err.println("[!!!] FATAL: Could not resolve server hostname: " + serverIp + " - " + e.getMessage()); return false;
        } catch (SecurityException e) { System.err.println("[!!!] FATAL: Security Manager prevented resolving address: " + e.getMessage()); return false; }
        catch (IllegalArgumentException e) { System.err.println("[!!!] FATAL: Invalid port number: " + NodeConfig.SERVER_PORT); return false;}
    }

    private static boolean initializeBackendServices() {
        System.out.println("[*] Initializing backend services...");
        try {
            networkManager = new NetworkManager(nodeContext);
            registrationService = new RegistrationService(nodeContext, networkManager);
            chatService = new ChatService(nodeContext, networkManager);
            connectionService = new ConnectionService(nodeContext, networkManager);
            fileTransferService = new FileTransferService(nodeContext, networkManager);

            // Initialize message handlers (these don't hold the callback directly in this design)
            ServerMessageHandler serverMsgHandler = new ServerMessageHandler(nodeContext, connectionService, chatService);
            PeerMessageHandler peerMsgHandler = new PeerMessageHandler(nodeContext, connectionService, chatService, fileTransferService, networkManager);
            MessageHandler messageHandler = new MessageHandler(nodeContext, serverMsgHandler, peerMsgHandler, connectionService);

            // Inject handler into NetworkManager
            networkManager.setMessageHandler(messageHandler);

            // Inject handlers into services that might need them (if any - current design avoids this)

            System.out.println("[+] Backend services initialized.");
            return true;
        } catch(Exception e) {
            System.err.println("[!!!] FATAL: Failed to initialize node services: " + e.getMessage());
            e.printStackTrace(); return false;
        }
    }

    // Helper to set the callback on all relevant services
    // *** ASSUMES setGuiCallback(GuiCallback) methods exist in these classes ***
    private static void setGuiCallbackForAllServices(GuiCallback callback) {
        System.out.println("[*] Setting GUI callbacks for backend services...");
        if (callback == null) {
             System.err.println("[!] Error: Cannot set null GUI callback!");
             return;
        }
        try {
            // Call the setters you added to the service classes
            connectionService.setGuiCallback(callback);
            chatService.setGuiCallback(callback);
            fileTransferService.setGuiCallback(callback);
            registrationService.setGuiCallback(callback); // For displaying Node ID post-registration

            // Also set callback for message handlers if they directly update GUI state
            // This requires adding the method and potentially getters in MessageHandler
            MessageHandler msgHandler = networkManager.getMessageHandler(); // Assume getter exists
             if (msgHandler != null) {
                 // Option 1: Handler holds callback (Requires adding setter to handlers)
                 // msgHandler.getServerMessageHandler().setGuiCallback(callback);
                 // msgHandler.getPeerMessageHandler().setGuiCallback(callback);

                 // Option 2: Services handle ALL GUI updates (Preferred)
                 // No need to set callback on handlers if services manage all GUI updates
                 System.out.println("[*] Note: Assumes services handle all GUI updates via callbacks.");

             } else {
                 System.err.println("[!] Could not get MessageHandler to set callbacks.");
             }
             System.out.println("[+] GUI callbacks set.");

        } catch (NullPointerException npe) {
             System.err.println("[!!!] Error setting GUI callbacks: A service reference is null. Initialization failed.");
             npe.printStackTrace();
             // Handle this critical error, maybe exit?
             JOptionPane.showMessageDialog(null, "Critical error during initialization.\nPlease restart.", "Fatal Error", JOptionPane.ERROR_MESSAGE);
             System.exit(1);
        } catch (Exception e) {
             System.err.println("[!!!] Unexpected error setting GUI callbacks: " + e.getMessage());
             e.printStackTrace();
        }
    }

    // Helper to get username via dialog
    private static String promptForUsername() {
        System.out.println("[*] Prompting for username...");
        String username = null;
        while (username == null || username.trim().isEmpty()) {
            username = JOptionPane.showInputDialog(null, // Parent component (null centers on screen)
                    "Enter your desired username:", // Message
                    "Username Required", // Title
                    JOptionPane.PLAIN_MESSAGE); // Icon

            if (username == null) { // User pressed Cancel or closed the dialog
                return null; // Indicate cancellation
            }
            if (username.trim().isEmpty()) {
                JOptionPane.showMessageDialog(null, "Username cannot be empty. Please try again.", "Invalid Input", JOptionPane.WARNING_MESSAGE);
            }
        }
        return username.trim();
    }

} // End of P2PNode class