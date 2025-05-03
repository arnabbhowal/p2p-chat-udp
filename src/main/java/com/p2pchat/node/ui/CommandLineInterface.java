package main.java.com.p2pchat.node.ui;

import main.java.com.p2pchat.node.config.NodeConfig;
import main.java.com.p2pchat.node.model.FileTransferState; // Added
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.service.ChatService;
import main.java.com.p2pchat.node.service.ConnectionService;
import main.java.com.p2pchat.node.service.FileTransferService; // Added

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Scanner;

public class CommandLineInterface implements Runnable {

    private final NodeContext context;
    private final ConnectionService connectionService;
    private final ChatService chatService;
    private final FileTransferService fileTransferService; // Added
    private final Scanner scanner;
    private long lastStatePrintTime = 0;

    // Updated constructor
    public CommandLineInterface(NodeContext context, ConnectionService connectionService, ChatService chatService, FileTransferService fileTransferService) {
        this.context = context;
        this.connectionService = connectionService;
        this.chatService = chatService;
        this.fileTransferService = fileTransferService; // Added
        this.scanner = new Scanner(System.in); // Scanner for user input
    }

    @Override
    public void run() {
        try {
             // Print initial ready messages once after registration in P2PNode.main
            // System.out.println("\n--- P2P Node Ready (Username: " + context.myUsername + ", Node ID: " + context.myNodeId.get() + ") ---");
            // System.out.println("--- Enter 'connect <peer_id>', 'status', or 'quit' ---");
            // Initial messages moved to P2PNode after registration for clarity

            while (context.running.get()) {
                 boolean redrawRequired = context.redrawPrompt.compareAndSet(true, false);

                 NodeState stateNow = context.currentState.get();
                 printStateUpdateIfNeeded(stateNow); // Print periodic status if needed

                 String prompt = getPrompt(stateNow); // Get the correct prompt

                 // If a redraw was flagged (e.g., by received message), print a newline before the prompt
                 if (redrawRequired) {
                    System.out.println(); // Ensure prompt starts on a new line after async messages
                 }

                 System.out.print(prompt); // Display the prompt

                 // Wait for and read user input
                 if (!scanner.hasNextLine()) {
                      System.out.println("\n[*] Input stream closed. Exiting.");
                      context.running.set(false); // Signal shutdown
                      break;
                 }
                 String line = scanner.nextLine().trim();


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
             if (scanner != null) {
                 // scanner.close(); // Avoid closing System.in wrapper
             }
        }
    }


    private void processCommand(String line) {
        NodeState stateNow = context.currentState.get();

        String[] parts = line.split(" ", 2);
        String command = parts[0].toLowerCase();
        // Allow argument to be null if only command is present
        String argument = (parts.length > 1 && parts[1] != null) ? parts[1].trim() : null;

        switch (command) {
            case "quit":
            case "exit":
                System.out.println("[*] Quit command received. Initiating shutdown...");
                context.running.set(false);
                // Let shutdown hook handle cleanup System.exit(0); // Avoid forcing exit if possible
                break;
            case "connect":
                if (stateNow == NodeState.DISCONNECTED) {
                    if (argument != null && !argument.isEmpty()) {
                        connectionService.initiateConnection(argument);
                    } else System.out.println("[!] Usage: connect <peer_node_id>");
                } else System.out.println("[!] Already connecting or connected. Disconnect first ('disconnect').");
                break;
            case "disconnect":
            case "cancel": // Also use cancel for disconnecting/stopping attempts
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

            // --- File Transfer Commands ---
            case "send":
                 if (stateNow == NodeState.CONNECTED_SECURE) {
                      if (argument != null && !argument.isEmpty()) {
                           fileTransferService.initiateTransfer(argument); // Pass filepath
                      } else System.out.println("[!] Usage: send <path/to/your/file>");
                 } else System.out.println("[!] Not connected to a peer. Use 'connect <peer_id>' first.");
                 break;
            case "accept":
                 if (stateNow == NodeState.CONNECTED_SECURE) {
                      if (argument != null && !argument.isEmpty()) {
                           fileTransferService.respondToOffer(argument, true); // Pass transfer ID
                      } else System.out.println("[!] Usage: accept <transfer_id>");
                 } else System.out.println("[!] Not connected to a peer.");
                 break;
             case "reject":
                  if (stateNow == NodeState.CONNECTED_SECURE) {
                       if (argument != null && !argument.isEmpty()) {
                            fileTransferService.respondToOffer(argument, false); // Pass transfer ID
                       } else System.out.println("[!] Usage: reject <transfer_id>");
                  } else System.out.println("[!] Not connected to a peer.");
                  break;
             // --- End File Transfer Commands ---

            case "status":
            case "s": // Added short alias for status
                System.out.println(getStateDescription(stateNow));
                 // Also print ongoing transfer status
                 printTransferStatus();
                break;
            case "id":
                System.out.println("[*] Your Username: " + context.myUsername);
                System.out.println("[*] Your Node ID: " + context.myNodeId.get());
                break;
            default:
                 // If connected, treat unknown commands as chat messages
                 if (stateNow == NodeState.CONNECTED_SECURE) {
                      // Treat any non-empty input that isn't a known command as a chat message
                      chatService.sendChatMessage(line); // Send the whole line as message
                 } else {
                    // If not connected, show general help
                    System.out.println("[!] Unknown command. Available: connect, status, id, quit");
                 }
                break;
        }
    }

     // Print status of ongoing file transfers
     private void printTransferStatus() {
         if (!context.ongoingTransfers.isEmpty()) {
              System.out.println("--- Ongoing File Transfers ---");
              context.ongoingTransfers.forEach((id, state) -> {
                  // Avoid printing completed/failed states here, focus on active ones
                  if (!state.isTerminated()) {
                       String direction = state.isSender ? "Sending" : "Receiving";
                       String progress = "";
                       if (state.status == FileTransferState.Status.TRANSFERRING_RECV || state.status == FileTransferState.Status.TRANSFERRING_SEND) {
                           if (state.filesize > 0) { // Avoid division by zero
                                double percent = (100.0 * state.transferredBytes.get() / state.filesize);
                                progress = String.format(" (%.1f%%)", percent);
                           } else {
                               progress = " (Calculating %)"; // Or handle 0 size case
                           }
                       } else if (state.status == FileTransferState.Status.OFFER_RECEIVED) {
                            progress = " (Waiting for your accept/reject)";
                       } else if (state.status == FileTransferState.Status.AWAITING_ACCEPT) {
                            progress = " (Waiting for peer response)";
                       }
                       System.out.println("  ID: " + id.substring(0, 8) + "... | " + direction + " '" + state.filename + "' | Status: " + state.status + progress);
                  }
              });
              System.out.println("----------------------------");
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
             lastStatePrintTime = 0; // Reset timer if not in waiting state
         }
    }

    private String getPrompt(NodeState stateNow) {
        String peerName = context.getPeerDisplayName();
        switch (stateNow) {
            case DISCONNECTED: return "[?] Enter 'connect', 'status', 'id', 'quit': ";
            case WAITING_MATCH: return "[Waiting:" + peerName + "] ('cancel')> ";
            case ATTEMPTING_UDP: return "[Pinging:" + peerName + "] ('cancel')> ";
            case CONNECTED_SECURE:
                // Show if there are pending file offers for the user to act on
                 boolean hasPendingOffer = context.ongoingTransfers.values().stream()
                                                 .anyMatch(s -> !s.isSender && s.status == FileTransferState.Status.OFFER_RECEIVED);
                 String fileOfferIndicator = hasPendingOffer ? " [FILE OFFER!] |" : "";
                 // Base prompt for connected state
                 String basePrompt = "[Chat 🔒 " + peerName + fileOfferIndicator + "] ";
                 // Add available commands based on state
                 // basePrompt += "(Type msg, or: send, accept, reject, disconnect, status, quit): ";
                 // Simpler: Just show the base prompt, user knows commands from help/README
                 return basePrompt + "> ";

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
                 String pingProgress = (context.pingAttempts > 0) ? " (Ping cycle: " + context.pingAttempts + "/" + NodeConfig.MAX_PING_ATTEMPTS + ")" : "";
                return "[Status] Attempting UDP P2P connection to " + fullPeerId + pingProgress;
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