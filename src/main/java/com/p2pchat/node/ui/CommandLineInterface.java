package main.java.com.p2pchat.node.ui;

import main.java.com.p2pchat.node.config.NodeConfig;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.service.ChatService;
import main.java.com.p2pchat.node.service.ConnectionService;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Scanner;

public class CommandLineInterface implements Runnable {

    private final NodeContext context;
    private final ConnectionService connectionService;
    private final ChatService chatService;
    private final Scanner scanner;
    private long lastStatePrintTime = 0;

    public CommandLineInterface(NodeContext context, ConnectionService connectionService, ChatService chatService) {
        this.context = context;
        this.connectionService = connectionService;
        this.chatService = chatService;
        this.scanner = new Scanner(System.in); // Scanner for user input
    }

    @Override
    public void run() {
        try {
             // Print initial ready messages once after registration in P2PNode.main
            System.out.println("\n--- P2P Node Ready (Username: " + context.myUsername + ", Node ID: " + context.myNodeId.get() + ") ---");
            System.out.println("--- Enter 'connect <peer_id>', 'status', or 'quit' ---");


            while (context.running.get()) {
                 // Check if an async message requested a prompt redraw
                 // We just clear the flag; the prompt is always printed below.
                 context.redrawPrompt.compareAndSet(true, false);

                 NodeState stateNow = context.currentState.get();
                 printStateUpdateIfNeeded(stateNow); // Print periodic status if needed
                 String prompt = getPrompt(stateNow); // Get the correct prompt
                 System.out.print(prompt); // Display the prompt

                 // Wait for and read user input
                 if (!scanner.hasNextLine()) {
                      System.out.println("\n[*] Input stream closed. Exiting.");
                      context.running.set(false); // Signal shutdown
                      break;
                 }
                 String line = scanner.nextLine().trim();

                 // Prompt redraw logic moved to the start of the loop

                 if (line.isEmpty()) continue; // Ignore empty input

                 processCommand(line); // Process the command read
            }
        } catch (NoSuchElementException e) {
             System.out.println("\n[*] Input stream ended unexpectedly. Shutting down.");
             context.running.set(false);
        } catch (Exception e) {
            System.err.println("\n[!!!] Error in user interaction loop: " + e.getMessage());
            e.printStackTrace();
            context.running.set(false); // Stop on major UI errors
        } finally {
            System.out.println("[*] User Interaction loop finished.");
        }
    }


    private void processCommand(String line) {
        NodeState stateNow = context.currentState.get();

        String[] parts = line.split(" ", 2);
        String command = parts[0].toLowerCase();
        String argument = (parts.length > 1) ? parts[1] : null;

        switch (command) {
            case "quit":
            case "exit":
                System.out.println("[*] Quit command received. Initiating shutdown...");
                context.running.set(false);
                System.exit(0); // Force exit
                break;
            case "connect":
                if (stateNow == NodeState.DISCONNECTED) {
                    if (argument != null && !argument.isEmpty()) {
                        connectionService.initiateConnection(argument.trim());
                    } else System.out.println("[!] Usage: connect <peer_node_id>");
                } else System.out.println("[!] Already connecting or connected. Disconnect first ('disconnect').");
                break;
            case "disconnect":
            case "cancel":
                if (stateNow == NodeState.CONNECTED_SECURE) {
                     connectionService.disconnectPeer();
                } else if (stateNow == NodeState.WAITING_MATCH || stateNow == NodeState.ATTEMPTING_UDP) {
                     connectionService.cancelConnectionAttempt("User cancelled");
                } else System.out.println("[!] Not currently connected or attempting connection.");
                break;
            case "chat":
            case "c": // Added short alias for chat
                if (stateNow == NodeState.CONNECTED_SECURE) {
                    if (argument != null && !argument.isEmpty()) {
                         chatService.sendChatMessage(argument); // Pass only the message part
                    } else System.out.println("[!] Usage: chat <message>");
                } else if (stateNow == NodeState.ATTEMPTING_UDP || stateNow == NodeState.WAITING_MATCH) {
                     System.out.println("[!] Waiting for secure connection to be established...");
                } else System.out.println("[!] Not connected to a peer. Use 'connect <peer_id>' first.");
                break;
            case "status":
            case "s": // Added short alias for status
                System.out.println(getStateDescription(stateNow));
                break;
            case "id":
                System.out.println("[*] Your Username: " + context.myUsername);
                System.out.println("[*] Your Node ID: " + context.myNodeId.get());
                break;
            default:
                 // If connected, unknown commands are invalid.
                 // If disconnected, also invalid.
                 if (stateNow == NodeState.CONNECTED_SECURE) {
                    System.out.println("[!] Unknown command. Use 'chat <message>' to send.");
                 } else {
                    System.out.println("[!] Unknown command. Available: connect, status, id, quit");
                 }
                break;
        }
    }

    private void printStateUpdateIfNeeded(NodeState stateNow) {
         long now = Instant.now().toEpochMilli();
         if (stateNow == NodeState.WAITING_MATCH || stateNow == NodeState.ATTEMPTING_UDP) {
              if (now - lastStatePrintTime > NodeConfig.STATE_PRINT_INTERVAL_MS || lastStatePrintTime == 0) {
                   System.out.print("\n" + getStateDescription(stateNow) + "\n");
                   lastStatePrintTime = now;
                   context.redrawPrompt.set(true); // Force prompt redraw after status print
              }
         } else {
             lastStatePrintTime = 0;
         }
    }

    private String getPrompt(NodeState stateNow) {
        String peerName = context.getPeerDisplayName();
        switch (stateNow) {
            case DISCONNECTED: return "[?] Enter 'connect <peer_id>', 'status', 'id', 'quit': ";
            case WAITING_MATCH: return "[Waiting:" + peerName + "] ('cancel'/'disconnect')> ";
            case ATTEMPTING_UDP: return "[Pinging:" + peerName + "] ('cancel'/'disconnect')> ";
            // Changed prompt to reflect chat command requirement
            case CONNECTED_SECURE: return "[Chat 🔒 " + peerName + "] ('chat <msg>', 'disconnect', 'status', 'quit'): ";
            case REGISTERING: return "[Registering...]> ";
            case INITIALIZING: return "[Initializing...]> ";
            case SHUTTING_DOWN: return "[Shutting down...]> ";
            default: return "> ";
        }
    }

    private String getStateDescription(NodeState stateNow) {
        String peerDisplay = context.getPeerDisplayName();
        String fullPeerId = context.getPeerDisplayId();

        switch (stateNow) {
            case DISCONNECTED: return "[Status] Disconnected. Your Node ID: " + context.myNodeId.get() + " | Username: " + context.myUsername;
            case WAITING_MATCH:
                long elapsed = (System.currentTimeMillis() - context.waitingSince) / 1000;
                return "[Status] Request sent. Waiting for peer " + fullPeerId + " (" + elapsed + "s / " + NodeConfig.WAIT_MATCH_TIMEOUT_MS/1000 + "s)";
            case ATTEMPTING_UDP:
                return "[Status] Attempting UDP P2P connection to " + fullPeerId + " (Ping cycle: " + context.pingAttempts + "/" + NodeConfig.MAX_PING_ATTEMPTS + ")";
            case CONNECTED_SECURE:
                InetSocketAddress addr = context.peerAddrConfirmed.get();
                return "[Status] 🔒 E2EE Connected to " + peerDisplay + " (" + (addr != null ? addr : "???") + ")";
             case REGISTERING: return "[Status] Registering with server...";
             case INITIALIZING: return "[Status] Initializing node...";
             case SHUTTING_DOWN: return "[Status] Shutting down...";
            default: return "[Status] Unknown State";
        }
    }
}