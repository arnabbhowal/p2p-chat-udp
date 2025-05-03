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
import main.java.com.p2pchat.node.service.RegistrationService;
import main.java.com.p2pchat.node.ui.CommandLineInterface;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Scanner;


public class P2PNode {

    private static NodeContext nodeContext;
    private static NetworkManager networkManager;
    private static ConnectionService connectionService;
    private static CommandLineInterface commandLineInterface;
    private static RegistrationService registrationService;
    private static ChatService chatService;

    public static void main(String[] args) {
        if (args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            NodeConfig.SERVER_IP = args[0].trim();
            System.out.println("[*] Using Server IP from command line: " + NodeConfig.SERVER_IP);
        } else {
            if (NodeConfig.SERVER_IP.equals("127.0.0.1")) {
                System.out.println("[!] WARNING: Using default Server IP '127.0.0.1'. Pass the actual server IP as an argument if nodes are on different machines!");
            } else {
                 System.out.println("[*] Using configured Server IP: " + NodeConfig.SERVER_IP);
            }
        }

        nodeContext = new NodeContext();
        nodeContext.currentState.set(NodeState.INITIALIZING);

        // --- Initialization ---
        if (!resolveServerAddress()) return; // Exit if server address fails
        if (!initializeServices()) return; // Exit if services fail to init
        if (!registrationService.generateKeys()) return; // Exit if key gen fails
        if (!networkManager.initializeSocket()) return; // Exit if socket fails

        // --- Get Username ---
        getUsernameInput();

        // --- Network Setup ---
        registrationService.discoverLocalEndpoints();
        networkManager.startListening();

        // --- Registration ---
         if (!registrationService.registerWithServer()) {
              System.err.println("[!!!] Failed to register with server. Exiting.");
              shutdown();
              return;
         }
         // Registration successful, state is now DISCONNECTED

        // --- Start background services and UI ---
        connectionService.start(); // Start keepalives, pinging logic
        commandLineInterface = new CommandLineInterface(nodeContext, connectionService, chatService);

        // Add shutdown hook AFTER essential services are created
        Runtime.getRuntime().addShutdownHook(new Thread(P2PNode::shutdown));

         // Run the UI loop (this will block until quit or error)
        commandLineInterface.run();

        // --- Shutdown initiated by UI loop finishing ---
        shutdown();
        System.out.println("[*] Node main method finished.");
    }

    private static boolean resolveServerAddress() {
         System.out.println("[*] Resolving server address: " + NodeConfig.SERVER_IP + ":" + NodeConfig.SERVER_PORT + "...");
         try {
              nodeContext.serverAddress = new InetSocketAddress(InetAddress.getByName(NodeConfig.SERVER_IP), NodeConfig.SERVER_PORT);
              System.out.println("[*] Server address resolved to: " + nodeContext.serverAddress);
              return true;
         } catch (UnknownHostException e) {
              System.err.println("[!!!] FATAL: Could not resolve server hostname: " + NodeConfig.SERVER_IP + " - " + e.getMessage());
              return false;
         } catch (SecurityException e) {
              System.err.println("[!!!] FATAL: Security Manager prevented resolving address: " + e.getMessage());
              return false;
         }
    }

    // Using the cleaner, setter-based initialization
    private static boolean initializeServices() {
        try {
            // Create services, passing dependencies via constructors where possible
            networkManager = new NetworkManager(nodeContext); // Construct with context only
            registrationService = new RegistrationService(nodeContext, networkManager);
            chatService = new ChatService(nodeContext, networkManager);
            connectionService = new ConnectionService(nodeContext, networkManager);

            // Create message handlers which need other services
            ServerMessageHandler serverMsgHandler = new ServerMessageHandler(nodeContext, connectionService, chatService);
            PeerMessageHandler peerMsgHandler = new PeerMessageHandler(nodeContext, connectionService, chatService, networkManager);
            MessageHandler messageHandler = new MessageHandler(nodeContext, serverMsgHandler, peerMsgHandler, connectionService);

            // Inject the fully configured MessageHandler into NetworkManager using the setter
            networkManager.setMessageHandler(messageHandler);

            return true;
        } catch(Exception e) {
             System.err.println("[!!!] FATAL: Failed to initialize node services: " + e.getMessage());
             e.printStackTrace();
             return false;
        }
    }

    private static void getUsernameInput() {
        System.out.print("[?] Enter your desired username: ");
        Scanner inputScanner = new Scanner(System.in); // Use a temporary scanner for username only
        String inputName = "";
        while (inputName == null || inputName.trim().isEmpty()) {
            if (inputScanner.hasNextLine()) {
                inputName = inputScanner.nextLine().trim();
                if (inputName.isEmpty()) {
                    System.out.print("[!] Username cannot be empty. Please enter a username: ");
                }
            } else {
                System.out.println("\n[*] Input stream closed during username entry. Using default.");
                inputName = nodeContext.myUsername; // Keep default
                break;
            }
        }
         nodeContext.myUsername = inputName;
         System.out.println("[*] Username set to: " + nodeContext.myUsername);
         // Don't close System.in scanner
    }


    private static synchronized void shutdown() {
        if (nodeContext == null || !nodeContext.running.get()) return; // Already shut down or not initialized

         System.out.println("\n[*] Shutting down node...");
         nodeContext.running.set(false); // Signal all loops to stop
         nodeContext.currentState.set(NodeState.SHUTTING_DOWN);


        // Stop services that have background threads first
        if (connectionService != null) {
            connectionService.stop();
        }
        if (networkManager != null) {
            networkManager.stop(); // Stops listener thread and closes socket
        }

         // Clear sensitive data
         nodeContext.peerSymmetricKeys.clear();
         nodeContext.myKeyPair = null;
         System.out.println("[*] Cleared cryptographic keys.");

        System.out.println("[*] Node shutdown sequence complete.");
    }
}