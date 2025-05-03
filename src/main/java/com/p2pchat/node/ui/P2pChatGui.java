package main.java.com.p2pchat.node.ui;

import main.java.com.p2pchat.node.P2PNode;
import main.java.com.p2pchat.node.model.FileTransferState;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.service.ChatService;
import main.java.com.p2pchat.node.service.ConnectionService;
import main.java.com.p2pchat.node.service.FileTransferService;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

public class P2pChatGui implements GuiCallback {

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
    private JButton sendFileButton;
    private JLabel statusLabel;
    private JLabel nodeIdLabel;
    private JPanel transferPanel;
    private JButton copyIdButton;

    // State
    private String ownNodeId = null;

    // Maps for transfer tracking
    private final ConcurrentHashMap<String, JProgressBar> transferProgressBars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JLabel> transferLabels = new ConcurrentHashMap<>();

    public P2pChatGui(NodeContext context, ConnectionService connService, ChatService chatSvc, FileTransferService ftService) {
        this.nodeContext = context;
        this.connectionService = connService;
        this.chatService = chatSvc;
        this.fileTransferService = ftService;
        SwingUtilities.invokeLater(this::initializeGui);
    }

    private void initializeGui() {
        frame = new JFrame("P2P Secure Chat");
        // Increased default size and added minimum size
        frame.setSize(850, 650);
        frame.setMinimumSize(new Dimension(650, 450));
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                displaySystemMessage("System: Shutdown initiated by window close.");
                P2PNode.shutdown();
                frame.dispose();
                System.exit(0);
            }
        });
        frame.setLayout(new BorderLayout(5, 5));

        // --- Top Panel ---
        JPanel topPanel = new JPanel(new BorderLayout(10, 5));
        topPanel.setBorder(BorderFactory.createEtchedBorder());

        // --- Left side of Top Panel (ID, Copy, Status) ---
        JPanel leftTopPanel = new JPanel();
        leftTopPanel.setLayout(new BoxLayout(leftTopPanel, BoxLayout.Y_AXIS));
        leftTopPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // Node ID Area (Label + Copy Button)
        JPanel nodeIdPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        nodeIdPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        nodeIdLabel = new JLabel("Node ID: <Registering...>");

        // Create Icon Button
        copyIdButton = new JButton();
        try {
            // Load icon from '/icons/copy_icon.png' relative to classpath root
            // REQUIRES 'resources' folder added to classpath in the 'java -cp ...' command
            URL iconUrl = getClass().getResource("/icons/copy_icon.png");

            if (iconUrl != null) {
                ImageIcon copyIcon = new ImageIcon(iconUrl);
                // Optional scaling:
                // Image img = copyIcon.getImage().getScaledInstance(16, 16, Image.SCALE_SMOOTH);
                // copyIcon = new ImageIcon(img);
                copyIdButton.setIcon(copyIcon);
                copyIdButton.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
                copyIdButton.setContentAreaFilled(false);
                copyIdButton.setFocusPainted(false);
                copyIdButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                copyIdButton.setPreferredSize(new Dimension(copyIcon.getIconWidth() + 4, copyIcon.getIconHeight() + 4));
            } else {
                System.err.println("Warning: /icons/copy_icon.png not found in classpath. Using text button '[C]'.");
                copyIdButton.setText("[C]");
                copyIdButton.setMargin(new Insets(1, 1, 1, 1));
                copyIdButton.setFont(new Font("Arial", Font.BOLD, 10));
            }
        } catch (Exception e) {
            System.err.println("Error loading copy icon: " + e.getMessage());
            copyIdButton.setText("[C]");
            copyIdButton.setMargin(new Insets(1, 1, 1, 1));
            copyIdButton.setFont(new Font("Arial", Font.BOLD, 10));
        }
        copyIdButton.setToolTipText("Copy Node ID to clipboard");
        copyIdButton.setEnabled(false);

        nodeIdPanel.add(nodeIdLabel);
        nodeIdPanel.add(copyIdButton);

        // Status Label
        statusLabel = new JLabel("Status: Initializing...");
        statusLabel.setForeground(Color.ORANGE);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        leftTopPanel.add(nodeIdPanel);
        leftTopPanel.add(statusLabel);

        // --- Right side of Top Panel (Action Buttons) ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        connectButton = new JButton("Connect");
        disconnectButton = new JButton("Disconnect");
        sendFileButton = new JButton("Send File");
        buttonPanel.add(connectButton);
        buttonPanel.add(disconnectButton);
        buttonPanel.add(sendFileButton);

        topPanel.add(leftTopPanel, BorderLayout.CENTER);
        topPanel.add(buttonPanel, BorderLayout.EAST);

        // --- Center Panel (Chat Area) ---
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        JScrollPane chatScroll = new JScrollPane(chatArea);

        // --- File Transfer Panel (Right Side) ---
        transferPanel = new JPanel();
        transferPanel.setLayout(new BoxLayout(transferPanel, BoxLayout.Y_AXIS));
        transferPanel.setBorder(BorderFactory.createTitledBorder("File Transfers"));
        transferPanel.setPreferredSize(new Dimension(200, 100));
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
        frame.add(transferScroll, BorderLayout.EAST);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        // --- Action Listeners ---
        connectButton.addActionListener(this::handleConnect);
        disconnectButton.addActionListener(this::handleDisconnect);
        sendButton.addActionListener(this::handleSendMessage);
        messageField.addActionListener(this::handleSendMessage);
        sendFileButton.addActionListener(this::handleSendFile);
        copyIdButton.addActionListener(this::handleCopyNodeId);

        // Set initial state
        updateState(NodeState.INITIALIZING, null, null);
        frame.setLocationRelativeTo(null); // Center window
        frame.setVisible(true);
        displaySystemMessage("System: GUI Initialized. Waiting for registration...");
    }

    // --- Action Handlers ---
    private void handleConnect(ActionEvent e) {
        String peerId = JOptionPane.showInputDialog(frame, "Enter Peer Node ID:", "Connect to Peer", JOptionPane.PLAIN_MESSAGE);
        if (peerId != null && !peerId.trim().isEmpty()) {
            displaySystemMessage("System: User initiated connection to: " + peerId.trim().substring(0, Math.min(8, peerId.trim().length())) + "...");
            connectionService.initiateConnection(peerId.trim());
        }
    }

    private void handleDisconnect(ActionEvent e) {
        displaySystemMessage("System: User initiated disconnect.");
        connectionService.disconnectPeer();
    }

    private void handleSendMessage(ActionEvent e) {
        String message = messageField.getText().trim();
        if (!message.isEmpty()) {
            if (nodeContext.currentState.get() == NodeState.CONNECTED_SECURE) {
                chatService.sendChatMessage(message);
                messageField.setText("");
            } else {
                displaySystemMessage("System: Cannot send message, not securely connected.");
            }
        }
    }

    private void handleSendFile(ActionEvent e) {
        if (nodeContext.currentState.get() != NodeState.CONNECTED_SECURE) {
            displaySystemMessage("System: Cannot send file, not securely connected.");
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select File to Send");
        int result = fileChooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            displaySystemMessage("System: User selected file: " + selectedFile.getName());
            fileTransferService.initiateTransfer(selectedFile.getAbsolutePath());
        }
    }

    private void handleCopyNodeId(ActionEvent e) {
        if (this.ownNodeId != null) {
            StringSelection stringSelection = new StringSelection(this.ownNodeId);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(stringSelection, null);
            displaySystemMessage("System: Node ID copied to clipboard!");
            copyIdButton.setToolTipText("Copied!");
            Timer timer = new Timer(1500, ae -> copyIdButton.setToolTipText("Copy Node ID to clipboard"));
            timer.setRepeats(false);
            timer.start();
        }
    }

    // --- GuiCallback Implementation ---
    @Override
    public void displayNodeId(String nodeId) {
        this.ownNodeId = nodeId;
        SwingUtilities.invokeLater(() -> {
            nodeIdLabel.setText("Node ID: " + nodeId);
            copyIdButton.setEnabled(true);
            displaySystemMessage("System: Registered with ID: " + nodeId);
        });
    }

    @Override
    public void updateState(NodeState newState, String peerUsername, String peerNodeId) {
        SwingUtilities.invokeLater(() -> {
            String statusText;
            Color statusColor;
            boolean connected = (newState == NodeState.CONNECTED_SECURE);
            boolean connecting = (newState == NodeState.WAITING_MATCH || newState == NodeState.ATTEMPTING_UDP);
            boolean canConnect = (newState == NodeState.DISCONNECTED && this.ownNodeId != null);
            String peerDisplay = "Peer";
            if (connected || connecting) {
                peerDisplay = (peerUsername != null && !peerUsername.isEmpty()) ? peerUsername : (peerNodeId != null ? peerNodeId.substring(0, 8) + "..." : "Peer");
            }

            switch (newState) {
                case INITIALIZING: statusText = "Status: Initializing..."; statusColor = Color.ORANGE; break;
                case REGISTERING: statusText = "Status: Registering..."; statusColor = Color.ORANGE; break;
                case DISCONNECTED: statusText = "Status: Disconnected"; statusColor = Color.RED; break;
                case WAITING_MATCH: statusText = "Status: Waiting for " + peerDisplay + " match..."; statusColor = Color.ORANGE; break;
                case ATTEMPTING_UDP: statusText = "Status: Attempting P2P with " + peerDisplay + "..."; statusColor = Color.ORANGE; break;
                case CONNECTED_SECURE: statusText = "Status: ✅ Connected to " + peerDisplay; statusColor = new Color(0, 128, 0); break;
                case SHUTTING_DOWN: statusText = "Status: Shutting down..."; statusColor = Color.GRAY; break;
                default: statusText = "Status: Unknown"; statusColor = Color.BLACK; break;
            }

            statusLabel.setText(statusText);
            statusLabel.setForeground(statusColor);

            connectButton.setEnabled(canConnect);
            disconnectButton.setEnabled(connected || connecting);
            sendButton.setEnabled(connected);
            messageField.setEnabled(connected);
            sendFileButton.setEnabled(connected);
            copyIdButton.setEnabled(this.ownNodeId != null);

            if (!connected && !connecting) {
                clearFileProgress();
            }
        });
    }

    @Override
    public void appendMessage(String message) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendMessage(message));
            return;
        }
        chatArea.append(message + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    @Override
    public void displaySystemMessage(String message) {
        System.out.println("GUI_LOG: " + message);
    }

    @Override
    public void showFileOffer(String transferId, String filename, long filesize, String senderUsername) {
        SwingUtilities.invokeLater(() -> {
            String message = String.format("Incoming file offer from %s:\nFile: %s\nSize: %.2f KB\n\nAccept this file?",
                    senderUsername, filename, filesize / 1024.0);
            int choice = JOptionPane.showConfirmDialog(frame, message, "File Offer",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                displaySystemMessage("System: Accepting file offer for '" + filename + "' [ID: " + transferId.substring(0, 8) + "]");
                fileTransferService.respondToOffer(transferId, true);
            } else {
                displaySystemMessage("System: Rejecting file offer for '" + filename + "' [ID: " + transferId.substring(0, 8) + "]");
                fileTransferService.respondToOffer(transferId, false);
            }
        });
    }

    @Override
    public void updateTransferProgress(String transferId, String filename, long currentBytes, long totalBytes, boolean isSending, FileTransferState.Status status) {
        SwingUtilities.invokeLater(() -> {
            JProgressBar progressBar = transferProgressBars.get(transferId);
            JLabel label = transferLabels.get(transferId);

            if (progressBar == null) {
                JPanel entryPanel = new JPanel(new BorderLayout(5, 0));
                entryPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
                entryPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

                label = new JLabel();
                transferLabels.put(transferId, label);

                progressBar = new JProgressBar(0, 100);
                progressBar.setStringPainted(true);
                transferProgressBars.put(transferId, progressBar);

                entryPanel.add(label, BorderLayout.NORTH);
                entryPanel.add(progressBar, BorderLayout.CENTER);
                transferPanel.add(entryPanel);
                transferPanel.add(Box.createRigidArea(new Dimension(0, 5)));
                transferPanel.revalidate();
                transferPanel.repaint();
            }

            String direction = isSending ? "Sending" : "Receiving";
            int percentage = (totalBytes > 0) ? (int) ((100 * currentBytes) / totalBytes) : 0;
            String statusText = status.toString().replace("TRANSFERRING_", "").replace("OFFER_", "").replace("AWAITING_", "Wait");
            String shortFilename = filename.length() > 15 ? filename.substring(0, 12) + "..." : filename;
            String currentStatusText = String.format("%s '%s' (%s)", direction, shortFilename, statusText);

            label.setText(currentStatusText);
            label.setToolTipText(String.format("%s '%s' (%s)", direction, filename, statusText));
            progressBar.setValue(percentage);
            progressBar.setString(String.format("%d%%", percentage));

            if (status == FileTransferState.Status.FAILED || status == FileTransferState.Status.CANCELLED || status == FileTransferState.Status.REJECTED) {
                progressBar.setForeground(Color.RED);
            } else if (status == FileTransferState.Status.COMPLETED) {
                progressBar.setForeground(new Color(0, 150, 0));
            } else if (isSending) {
                progressBar.setForeground(Color.BLUE);
            } else {
                progressBar.setForeground(new Color(0, 150, 0));
            }
        });
    }

    @Override
    public void transferFinished(String transferId, String message) {
        displaySystemMessage("System: File Transfer [" + transferId.substring(0, 8) + "] " + message);
        SwingUtilities.invokeLater(() -> {
            JProgressBar progressBar = transferProgressBars.get(transferId);
            JLabel label = transferLabels.get(transferId);
            boolean success = message.toLowerCase().contains("complete");

            if (progressBar != null) {
                progressBar.setValue(100);
                progressBar.setString(message);
                progressBar.setForeground(success ? new Color(0, 150, 0) : Color.RED);
            }
            if (label != null) {
                label.setToolTipText(label.getToolTipText() + " - " + message);
            }

            Timer timer = new Timer(success ? 5000 : 8000, e -> removeTransferEntry(transferId));
            timer.setRepeats(false);
            timer.start();
        });
    }

    @Override
    public void clearFileProgress() {
        SwingUtilities.invokeLater(() -> {
            transferProgressBars.keySet().forEach(this::removeTransferEntry);
            transferPanel.removeAll();
            transferPanel.revalidate();
            transferPanel.repaint();
        });
    }

    private void removeTransferEntry(String transferId) {
        JProgressBar bar = transferProgressBars.remove(transferId);
        JLabel label = transferLabels.remove(transferId);
        Component entryPanelToRemove = null;

        if (label != null) { entryPanelToRemove = label.getParent(); }
        else if (bar != null) { entryPanelToRemove = bar.getParent();}

        if (entryPanelToRemove instanceof JPanel) {
            Container grandParent = entryPanelToRemove.getParent();
            if (grandParent == transferPanel) {
                Component[] components = grandParent.getComponents();
                int indexToRemove = -1;
                for (int i = 0; i < components.length; i++) {
                    if (components[i] == entryPanelToRemove) {
                        indexToRemove = i;
                        break;
                    }
                }

                if (indexToRemove != -1) {
                    if (indexToRemove + 1 < components.length && components[indexToRemove + 1] instanceof Box.Filler) {
                         grandParent.remove(indexToRemove + 1);
                    }
                    grandParent.remove(indexToRemove);
                    grandParent.revalidate();
                    grandParent.repaint();
                }
            }
        }
    }

} // End of P2pChatGui class