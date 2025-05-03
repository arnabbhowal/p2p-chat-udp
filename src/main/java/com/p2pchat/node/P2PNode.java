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
import main.java.com.p2pchat.node.service.FileTransferService; // Added
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
    private static FileTransferService fileTransferService; // Added
    private static CommandLineInterface commandLineInterface;
    private static RegistrationService registrationService;
    private static ChatService chatService;
    private static volatile boolean isShuttingDown = false; // Flag to prevent double shutdown

    public static void main(String[] args) {
        // Set current time for testing purposes
         // String testDate = "2025-05-03T14:43:03-04:00"; // Approx time of request
         // In real code, you'd use Instant.now() or similar

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

        // Add shutdown hook EARLY before services are fully active
        Runtime.getRuntime().addShutdownHook(new Thread(P2PNode::shutdown));

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
              // Shutdown hook will run, but exit explicitly if registration fails critically
              System.exit(1);
              return;
         }
         // Registration successful, state is now DISCONNECTED

         // --- Print Ready Messages (Moved Here) ---
         System.out.println("\n--- P2P Node Ready ---");
         System.out.println("    Username: " + nodeContext.myUsername);
         System.out.println("    Node ID : " + nodeContext.myNodeId.get());
         System.out.println("--- Enter 'connect <peer_id>', 'status', 'id', or 'quit' ---");
         // --- End Ready Messages ---


        // --- Start background services and UI ---
        connectionService.start(); // Start keepalives, pinging logic
        fileTransferService.start(); // Start file transfer timeout checker
        commandLineInterface = new CommandLineInterface(nodeContext, connectionService, chatService, fileTransferService); // Pass file transfer service


         // Run the UI loop (this will block until quit or error)
         try {
            commandLineInterface.run();
         } finally {
             // Ensure shutdown is called even if UI loop throws an exception
             shutdown();
         }

        System.out.println("[*] Node main method finished.");
        // JVM will exit if all non-daemon threads (like main) are done
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
            fileTransferService = new FileTransferService(nodeContext, networkManager); // Added

            // Create message handlers which need other services
            ServerMessageHandler serverMsgHandler = new ServerMessageHandler(nodeContext, connectionService, chatService);
            // Pass fileTransferService to PeerMessageHandler
            PeerMessageHandler peerMsgHandler = new PeerMessageHandler(nodeContext, connectionService, chatService, fileTransferService, networkManager);
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
        // Check if running without an interactive console AND no immediate input
        // Corrected line:
        if (System.console() == null && !inputScanner.hasNextLine()) {
             System.out.println("\n[*] No interactive console detected or input stream closed. Using default username: " + nodeContext.myUsername);
             inputName = nodeContext.myUsername;
        } else {
             while (inputName == null || inputName.trim().isEmpty()) {
                  // Check if running flag became false (e.g., shutdown initiated)
                  if (!nodeContext.running.get()) {
                      System.out.println("\n[*] Shutdown initiated during username input.");
                      inputName = nodeContext.myUsername; // Use default
                      break;
                  }
                  if (inputScanner.hasNextLine()) {
                       inputName = inputScanner.nextLine().trim();
                       if (inputName.isEmpty()) {
                           System.out.print("[!] Username cannot be empty. Please enter a username: ");
                       }
                  } else {
                       // This case handles if the input stream closes unexpectedly while waiting
                       System.out.println("\n[*] Input stream closed during username entry. Using default.");
                       inputName = nodeContext.myUsername; // Keep default
                       break;
                  }
             }
        }
         nodeContext.myUsername = inputName.trim(); // Ensure trimmed
         System.out.println("[*] Username set to: " + nodeContext.myUsername);
         // We don't close the scanner wrapping System.in
    }


    private static synchronized void shutdown() {
        // Prevent shutdown() from running multiple times (e.g., from hook and main thread finally block)
        if (isShuttingDown) return;
        isShuttingDown = true;

        System.out.println("\n[*] Shutting down node...");

        // Signal all loops to stop FIRST
        if (nodeContext != null) {
            nodeContext.running.set(false);
            nodeContext.currentState.set(NodeState.SHUTTING_DOWN);
        }

        // Stop services that have background threads
        // Order might matter slightly - stop accepting new work before stopping listeners
         if (fileTransferService != null) {
             fileTransferService.stop(); // Stops timeout checker
         }
        if (connectionService != null) {
            connectionService.stop(); // Stops connection manager thread
        }
        // Stop network listener last, after other services signal they are stopping
        if (networkManager != null) {
            networkManager.stop(); // Stops listener thread and closes socket
        }


         // Clean up transfers explicitly on shutdown
         if (nodeContext != null) {
              nodeContext.cancelAndCleanupTransfersForPeer(null, "Node shutdown"); // Cancel all transfers
         }

         // Clear sensitive data
         if (nodeContext != null) {
             nodeContext.peerSymmetricKeys.clear();
             nodeContext.myKeyPair = null;
             System.out.println("[*] Cleared cryptographic keys.");
         }

        System.out.println("[*] Node shutdown sequence complete.");
        // Don't call System.exit() here; let the main thread finish naturally
        // The shutdown hook ensures this runs even if the main thread is interrupted.
    }
}