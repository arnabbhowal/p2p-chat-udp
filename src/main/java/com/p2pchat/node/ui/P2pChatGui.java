package main.java.com.p2pchat.node.ui;

import main.java.com.p2pchat.node.P2PNode; // Assuming static shutdown method is here
import main.java.com.p2pchat.node.model.FileTransferState;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.service.ChatService;
import main.java.com.p2pchat.node.service.ConnectionService;
import main.java.com.p2pchat.node.service.FileTransferService;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap; // For progress tracking

public class P2pChatGui implements GuiCallback { // Implement the callback interface

    // Backend References
    private final NodeContext nodeContext;
    private final ConnectionService connectionService;
    private final ChatService chatService;
    private final FileTransferService fileTransferService;

    // Swing Components
    private JFrame frame;
    private JTextArea chatArea;
    private JTextField messageField;
    private JButton sendButton;
    private JButton connectButton;
    private JButton disconnectButton;
    // private JButton viewHistoryButton; // History viewing can be added later
    private JButton sendFileButton;
    private JLabel statusLabel;
    private JLabel nodeIdLabel;
    private JPanel transferPanel; // Panel to hold file transfer progress bars

    // Map to track progress bars for ongoing transfers
    private final ConcurrentHashMap<String, JProgressBar> transferProgressBars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JLabel> transferLabels = new ConcurrentHashMap<>();


    public P2pChatGui(NodeContext context, ConnectionService connService, ChatService chatSvc, FileTransferService ftService) {
        this.nodeContext = context;
        this.connectionService = connService;
        this.chatService = chatSvc;
        this.fileTransferService = ftService;

        // Create Swing components on the Event Dispatch Thread
        SwingUtilities.invokeLater(this::initializeGui);
    }

    private void initializeGui() {
        frame = new JFrame("P2P Secure Chat");
        frame.setSize(700, 550); // Slightly larger
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // Handle close via listener
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Initiate backend shutdown
                P2PNode.shutdown(); // Call the static shutdown method
                frame.dispose();
                System.exit(0); // Ensure JVM exits
            }
        });
        frame.setLayout(new BorderLayout(5, 5)); // Add gaps

        // --- Top Panel (Node ID, Status, Buttons) ---
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        nodeIdLabel = new JLabel("Node ID: <Registering...>");
        nodeIdLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 0, 10));
        statusLabel = new JLabel("Status: Initializing...");
        statusLabel.setForeground(Color.ORANGE);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 5, 10));

        JPanel statusPanel = new JPanel(new GridLayout(2, 1)); // Stack Node ID and Status
        statusPanel.add(nodeIdLabel);
        statusPanel.add(statusLabel);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        connectButton = new JButton("Connect");
        disconnectButton = new JButton("Disconnect");
        sendFileButton = new JButton("Send File");
        // viewHistoryButton = new JButton("History");

        buttonPanel.add(connectButton);
        buttonPanel.add(disconnectButton);
        buttonPanel.add(sendFileButton);
        // buttonPanel.add(viewHistoryButton);

        topPanel.add(statusPanel, BorderLayout.CENTER);
        topPanel.add(buttonPanel, BorderLayout.EAST);
        topPanel.setBorder(BorderFactory.createEtchedBorder());

        // --- Center Panel (Chat Area) ---
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        JScrollPane chatScroll = new JScrollPane(chatArea);

        // --- File Transfer Panel (Right Side) ---
        transferPanel = new JPanel();
        transferPanel.setLayout(new BoxLayout(transferPanel, BoxLayout.Y_AXIS)); // Vertical layout
        transferPanel.setBorder(BorderFactory.createTitledBorder("File Transfers"));
        transferPanel.setPreferredSize(new Dimension(200, 100)); // Give it some initial size
        JScrollPane transferScroll = new JScrollPane(transferPanel);
        transferScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        transferScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);


        // --- Bottom Panel (Input Field and Send Button) ---
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 0));
        messageField = new JTextField();
        sendButton = new JButton("Send");
        bottomPanel.add(messageField, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));


        // --- Add panels to frame ---
        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(chatScroll, BorderLayout.CENTER);
        frame.add(transferScroll, BorderLayout.EAST); // Add transfer panel to the right
        frame.add(bottomPanel, BorderLayout.SOUTH);

        // --- Action Listeners ---
        connectButton.addActionListener(this::handleConnect);
        disconnectButton.addActionListener(this::handleDisconnect);
        sendButton.addActionListener(this::handleSendMessage);
        messageField.addActionListener(this::handleSendMessage); // Send on Enter key
        sendFileButton.addActionListener(this::handleSendFile);
        // viewHistoryButton.addActionListener(this::handleViewHistory);

        // Set initial state (most buttons disabled)
        updateState(NodeState.INITIALIZING, null, null);

        frame.setLocationRelativeTo(null); // Center window
        frame.setVisible(true);
        appendMessage("System: GUI Initialized. Waiting for registration...");
    }

    // --- Action Handlers (GUI -> Backend) ---

    private void handleConnect(ActionEvent e) {
        String peerId = JOptionPane.showInputDialog(frame, "Enter Peer Node ID:", "Connect to Peer", JOptionPane.PLAIN_MESSAGE);
        if (peerId != null && !peerId.trim().isEmpty()) {
            connectionService.initiateConnection(peerId.trim());
        }
    }

    private void handleDisconnect(ActionEvent e) {
        connectionService.disconnectPeer();
    }

    private void handleSendMessage(ActionEvent e) {
        String message = messageField.getText().trim();
        if (!message.isEmpty()) {
            // Only send if connected securely
            if (nodeContext.currentState.get() == NodeState.CONNECTED_SECURE) {
                chatService.sendChatMessage(message);
                messageField.setText(""); // Clear field after sending attempt
            } else {
                appendMessage("System: Cannot send message, not securely connected.");
            }
        }
    }

    private void handleSendFile(ActionEvent e) {
        if (nodeContext.currentState.get() != NodeState.CONNECTED_SECURE) {
            appendMessage("System: Cannot send file, not securely connected.");
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select File to Send");
        int result = fileChooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            appendMessage("System: Initiating file transfer for: " + selectedFile.getName());
            fileTransferService.initiateTransfer(selectedFile.getAbsolutePath());
        }
    }

    // private void handleViewHistory(ActionEvent e) {
    //     // TODO: Implement history viewing logic if desired
    //     appendMessage("System: History viewing not implemented yet.");
    // }


    // --- GuiCallback Implementation (Backend -> GUI) ---

    @Override
    public void displayNodeId(String nodeId) {
        SwingUtilities.invokeLater(() -> {
            nodeIdLabel.setText("Node ID: " + nodeId);
            appendMessage("System: Registered with ID: " + nodeId);
        });
    }

    @Override
    public void updateState(NodeState newState, String peerUsername, String peerNodeId) {
        SwingUtilities.invokeLater(() -> {
            String statusText;
            Color statusColor;
            boolean connected = (newState == NodeState.CONNECTED_SECURE);
            boolean connecting = (newState == NodeState.WAITING_MATCH || newState == NodeState.ATTEMPTING_UDP);
            boolean disconnected = (newState == NodeState.DISCONNECTED);

            switch (newState) {
                case INITIALIZING:
                    statusText = "Status: Initializing...";
                    statusColor = Color.ORANGE;
                    break;
                case REGISTERING:
                    statusText = "Status: Registering...";
                    statusColor = Color.ORANGE;
                    break;
                case DISCONNECTED:
                    statusText = "Status: Disconnected";
                    statusColor = Color.RED;
                    break;
                case WAITING_MATCH:
                    statusText = "Status: Waiting for " + (peerNodeId != null ? peerNodeId.substring(0, 8) + "..." : "peer") + " match...";
                    statusColor = Color.ORANGE;
                    break;
                case ATTEMPTING_UDP:
                    statusText = "Status: Attempting P2P with " + (peerNodeId != null ? peerNodeId.substring(0, 8) + "..." : "peer") + "...";
                    statusColor = Color.ORANGE;
                    break;
                case CONNECTED_SECURE:
                    String displayName = (peerUsername != null && !peerUsername.isEmpty()) ? peerUsername : (peerNodeId != null ? peerNodeId.substring(0, 8) + "..." : "Peer");
                    statusText = "Status: ✅ Connected to " + displayName;
                    statusColor = new Color(0, 128, 0); // Dark Green
                    break;
                case SHUTTING_DOWN:
                    statusText = "Status: Shutting down...";
                    statusColor = Color.GRAY;
                    break;
                default:
                    statusText = "Status: Unknown";
                    statusColor = Color.BLACK;
                    break;
            }

            statusLabel.setText(statusText);
            statusLabel.setForeground(statusColor);

            // Update button enablement based on state
            connectButton.setEnabled(disconnected);
            disconnectButton.setEnabled(connected || connecting); // Allow disconnect/cancel during connection attempts
            sendButton.setEnabled(connected);
            messageField.setEnabled(connected);
            sendFileButton.setEnabled(connected);
            // viewHistoryButton.setEnabled(connected);

             // Clear progress bars if disconnected or connection failed
             if (!connected && !connecting) {
                 clearFileProgress();
             }
        });
    }

    @Override
    public void appendMessage(String message) {
        // Ensure update happens on EDT
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendMessage(message));
            return;
        }
        chatArea.append(message + "\n");
        // Auto-scroll to the bottom
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    @Override
    public void showFileOffer(String transferId, String filename, long filesize, String senderUsername) {
        SwingUtilities.invokeLater(() -> {
            String message = String.format("Incoming file offer from %s:\nFile: %s\nSize: %.2f KB\n\nAccept this file?",
                    senderUsername, filename, filesize / 1024.0);
            int choice = JOptionPane.showConfirmDialog(frame,
                    message,
                    "File Offer",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                appendMessage("System: Accepting file offer for '" + filename + "' [ID: " + transferId.substring(0,8) + "]");
                fileTransferService.respondToOffer(transferId, true);
            } else {
                appendMessage("System: Rejecting file offer for '" + filename + "' [ID: " + transferId.substring(0,8) + "]");
                fileTransferService.respondToOffer(transferId, false);
            }
        });
    }

    @Override
    public void updateTransferProgress(String transferId, String filename, long currentBytes, long totalBytes, boolean isSending, FileTransferState.Status status) {
        SwingUtilities.invokeLater(() -> {
            JProgressBar progressBar = transferProgressBars.get(transferId);
            JLabel label = transferLabels.get(transferId);

            // Create progress bar and label if they don't exist
            if (progressBar == null) {
                JPanel entryPanel = new JPanel(new BorderLayout(5, 0));
                entryPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40)); // Limit height
                entryPanel.setAlignmentX(Component.LEFT_ALIGNMENT); // Align left

                label = new JLabel();
                transferLabels.put(transferId, label);

                progressBar = new JProgressBar(0, 100); // Use percentage
                progressBar.setStringPainted(true);
                transferProgressBars.put(transferId, progressBar);

                entryPanel.add(label, BorderLayout.NORTH);
                entryPanel.add(progressBar, BorderLayout.CENTER);
                transferPanel.add(entryPanel);
                transferPanel.add(Box.createRigidArea(new Dimension(0, 5))); // Add spacing
                transferPanel.revalidate();
                transferPanel.repaint();
            }

            // Update label and progress bar
            String direction = isSending ? "Sending" : "Receiving";
            int percentage = (totalBytes > 0) ? (int) ((100 * currentBytes) / totalBytes) : 0;
            String statusText = status.toString().replace("TRANSFERRING_", "").replace("OFFER_", ""); // Shorten status
            label.setText(String.format("%s '%s' (%s)", direction, filename, statusText));
            progressBar.setValue(percentage);
            progressBar.setString(String.format("%d%%", percentage));

            // Set color based on status
            if (status == FileTransferState.Status.FAILED || status == FileTransferState.Status.CANCELLED || status == FileTransferState.Status.REJECTED) {
                 progressBar.setForeground(Color.RED);
            } else if (status == FileTransferState.Status.COMPLETED) {
                 progressBar.setForeground(Color.GREEN);
            } else if (isSending){
                progressBar.setForeground(Color.BLUE); // Default sending color
            } else {
                 progressBar.setForeground(Color.MAGENTA); // Default receiving color
            }

        });
    }

    @Override
    public void transferFinished(String transferId, String message) {
        SwingUtilities.invokeLater(() -> {
            JProgressBar progressBar = transferProgressBars.get(transferId);
            JLabel label = transferLabels.get(transferId);

            if (progressBar != null) {
                progressBar.setValue(progressBar.getMaximum()); // Fill bar on finish/fail
                progressBar.setString(message); // Show final message
                if (message.startsWith("Completed")) {
                     progressBar.setForeground(new Color(0,150,0)); // Darker green
                } else {
                     progressBar.setForeground(Color.RED);
                }
            }
             if (label != null && progressBar != null) {
                 // Keep the label, but ensure progress bar shows final state
                 // Optionally remove the component after a delay?
                 Timer timer = new Timer(5000, e -> removeTransferEntry(transferId)); // Remove after 5s
                 timer.setRepeats(false);
                 timer.start();
             } else {
                 // If somehow only label or bar exists, remove immediately
                 removeTransferEntry(transferId);
             }

            // Append final status to main chat area too
            appendMessage("System: File Transfer [" + transferId.substring(0, 8) + "] " + message);
        });
    }

     @Override
     public void clearFileProgress() {
         SwingUtilities.invokeLater(() -> {
             transferProgressBars.keySet().forEach(this::removeTransferEntry); // Use helper
             transferLabels.clear(); // Clear labels map too
             transferProgressBars.clear();
             transferPanel.removeAll(); // Clear the panel visually
             transferPanel.revalidate();
             transferPanel.repaint();
         });
     }

     // Helper to remove a single transfer entry components
     private void removeTransferEntry(String transferId) {
         JProgressBar bar = transferProgressBars.remove(transferId);
         JLabel label = transferLabels.remove(transferId);
         if (bar != null) {
             Container parent = bar.getParent(); // Should be the entryPanel
             if (parent != null) {
                 Container grandParent = parent.getParent(); // Should be transferPanel
                 if (grandParent != null) {
                     // Also remove the spacing associated with it if possible
                     Component[] components = grandParent.getComponents();
                     for(int i=0; i<components.length; i++){
                         if(components[i] == parent){
                             grandParent.remove(parent);
                             // Try removing the next component if it's rigid area spacing
                             if(i+1 < components.length && components[i+1] instanceof Box.Filler){
                                 grandParent.remove(components[i+1]);
                             }
                             break;
                         }
                     }
                     grandParent.revalidate();
                     grandParent.repaint();
                 }
             }
         } else if (label != null){
              // Should not happen if bar is null, but cleanup defensively
              Container parent = label.getParent(); // Should be the entryPanel
               if (parent != null) {
                     Container grandParent = parent.getParent(); // Should be transferPanel
                     if (grandParent != null) {
                         grandParent.remove(parent); // remove the entryPanel
                         grandParent.revalidate();
                         grandParent.repaint();
                     }
                }
         }
     }

} // End of P2pChatGui class