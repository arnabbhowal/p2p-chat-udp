package main.java.com.p2pchat.node.ui;

import main.java.com.p2pchat.node.P2PNode;
import main.java.com.p2pchat.node.model.FileTransferState;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.service.ChatHistoryManager;
import main.java.com.p2pchat.node.service.ChatService;
import main.java.com.p2pchat.node.service.ConnectionService;
import main.java.com.p2pchat.node.service.FileTransferService;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private JButton viewHistoryButton;

    // State
    private String ownNodeId = null;
    private Timer statusResetTimer;

    // Maps for transfer tracking
    private final ConcurrentHashMap<String, JProgressBar> transferProgressBars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JLabel> transferLabels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JPanel> transferEntries = new ConcurrentHashMap<>();

    public P2pChatGui(NodeContext context, ConnectionService connService, ChatService chatSvc,
            FileTransferService ftService) {
        this.nodeContext = context;
        this.connectionService = connService;
        this.chatService = chatSvc;
        this.fileTransferService = ftService;
        SwingUtilities.invokeLater(this::initializeGui);
    }

    private void initializeGui() {
        frame = new JFrame("P2P Secure Chat");
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
        JPanel leftTopPanel = new JPanel();
        leftTopPanel.setLayout(new BoxLayout(leftTopPanel, BoxLayout.Y_AXIS));
        leftTopPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        JPanel nodeIdPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        nodeIdPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        nodeIdLabel = new JLabel("Node ID: <Registering...>");
        copyIdButton = new JButton();
        try {
            URL iconUrl = getClass().getResource("/icons/copy_icon.png");
            if (iconUrl != null) {
                ImageIcon copyIcon = new ImageIcon(iconUrl);
                copyIdButton.setIcon(copyIcon);
                copyIdButton.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
                copyIdButton.setContentAreaFilled(false);
                copyIdButton.setFocusPainted(false);
                copyIdButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                copyIdButton.setPreferredSize(new Dimension(copyIcon.getIconWidth() + 4, copyIcon.getIconHeight() + 4));
            } else {
                fallbackCopyButton();
            }
        } catch (Exception e) {
            fallbackCopyButton();
        }
        copyIdButton.setToolTipText("Copy Node ID to clipboard");
        copyIdButton.setEnabled(false);
        nodeIdPanel.add(nodeIdLabel);
        nodeIdPanel.add(copyIdButton);
        statusLabel = new JLabel("Status: Initializing...");
        statusLabel.setForeground(Color.ORANGE);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftTopPanel.add(nodeIdPanel);
        leftTopPanel.add(statusLabel);
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        connectButton = new JButton("Connect");
        disconnectButton = new JButton("Disconnect");
        sendFileButton = new JButton("Send File");
        viewHistoryButton = new JButton("History");
        viewHistoryButton.setToolTipText("View chat history");
        viewHistoryButton.setEnabled(false);
        buttonPanel.add(connectButton);
        buttonPanel.add(disconnectButton);
        buttonPanel.add(sendFileButton);
        buttonPanel.add(viewHistoryButton);
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
        viewHistoryButton.addActionListener(this::handleViewHistory);

        // Set initial state
        updateState(NodeState.INITIALIZING, null, null);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        displaySystemMessage("System: GUI Initialized. Waiting for registration...");
    }

    private void fallbackCopyButton() {
        copyIdButton.setText("[C]");
        copyIdButton.setMargin(new Insets(1, 1, 1, 1));
        copyIdButton.setFont(new Font("Arial", Font.BOLD, 10));
    }

    // --- Action Handlers ---
    private void handleConnect(ActionEvent e) {
        String peerId = JOptionPane.showInputDialog(frame, "Enter Peer Node ID:", "Connect to Peer",
                JOptionPane.PLAIN_MESSAGE);
        if (peerId != null && !peerId.trim().isEmpty()) {
            displaySystemMessage("System: User initiated connection to: "
                    + peerId.trim().substring(0, Math.min(8, peerId.trim().length())) + "...");
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
            if (statusResetTimer != null && statusResetTimer.isRunning()) {
                statusResetTimer.stop();
            }
            final String originalStatusText = statusLabel.getText();
            final Color originalStatusColor = statusLabel.getForeground();
            statusLabel.setText("Status: Node ID Copied!");
            statusLabel.setForeground(Color.BLUE);
            statusResetTimer = new Timer(2000, ae -> {
                if (statusLabel.getText().equals("Status: Node ID Copied!")) {
                    statusLabel.setText(originalStatusText);
                    statusLabel.setForeground(originalStatusColor);
                }
            });
            statusResetTimer.setRepeats(false);
            statusResetTimer.start();
        }
    }

    private void handleViewHistory(ActionEvent e) {
        String targetPeerId = null;
        String targetPeerUsername = null;
        if (nodeContext.currentState.get() == NodeState.CONNECTED_SECURE) {
            targetPeerId = nodeContext.connectedPeerId.get();
            targetPeerUsername = nodeContext.connectedPeerUsername.get();
            if (targetPeerId == null) {
                JOptionPane.showMessageDialog(frame, "Cannot determine connected peer ID.", "View History Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            readAndShowHistory(targetPeerUsername, targetPeerId, false);
        } else {
            promptAndShowHistory();
        }
    }

    public void promptAndShowHistory() {
        displaySystemMessage("System: Listing available chat histories...");
        List<ChatHistoryManager.HistoryInfo> availableHistories = ChatHistoryManager.listAvailableHistories();
        if (availableHistories.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No past chat history files found.", "View History",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<String> choices = new ArrayList<>();
        availableHistories
                .sort(Comparator.<ChatHistoryManager.HistoryInfo, String>comparing(h -> h.peerUsername.toLowerCase())
                        .thenComparing(h -> h.peerNodeId.substring(0, 8)));
        for (ChatHistoryManager.HistoryInfo historyInfo : availableHistories) {
            choices.add(historyInfo.toString());
        }
        Object selectedChoice = JOptionPane.showInputDialog(frame, "Select a past chat history to view:",
                "View Past History", JOptionPane.PLAIN_MESSAGE, null, choices.toArray(),
                (choices.isEmpty() ? null : choices.get(0)));
        if (selectedChoice == null) {
            return;
        }
        int selectedIndex = -1;
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).equals(selectedChoice.toString())) {
                selectedIndex = i;
                break;
            }
        }
        if (selectedIndex != -1) {
            ChatHistoryManager.HistoryInfo selectedHistory = availableHistories.get(selectedIndex);
            String targetPeerUsername = selectedHistory.getPeerUsername();
            String targetPeerId = selectedHistory.getPeerNodeId();
            readAndShowHistory(targetPeerUsername, targetPeerId, true);
        } else {
            JOptionPane.showMessageDialog(frame, "Invalid selection.", "View History Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void readAndShowHistory(String peerUsername, String peerId, boolean showBackButton) {
        final String finalPeerId = peerId;
        final String finalPeerUsername = (peerUsername == null || peerUsername.trim().isEmpty()) ? "<Unknown User>"
                : peerUsername;
        final String dialogTitle = "Chat History with "
                + (finalPeerUsername.equals("<Unknown User>") ? finalPeerId.substring(0, 8) + "..."
                        : finalPeerUsername);
        JDialog loadingDialog = new JDialog(frame, "Loading History...", Dialog.ModalityType.APPLICATION_MODAL);
        JLabel loadingLabel = new JLabel("Reading history file, please wait...", SwingConstants.CENTER);
        loadingLabel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        loadingDialog.add(loadingLabel);
        loadingDialog.pack();
        loadingDialog.setLocationRelativeTo(frame);
        loadingDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                displaySystemMessage("System: Reading history for peer " + finalPeerId.substring(0, 8) + "...");
                return ChatHistoryManager.readFormattedHistory(finalPeerUsername, finalPeerId);
            }

            @Override
            protected void done() {
                loadingDialog.dispose();
                try {
                    String historyContent = get();
                    displaySystemMessage(
                            "System: Finished reading history for peer " + finalPeerId.substring(0, 8) + ".");
                    HistoryDialog historyDialog = new HistoryDialog(frame, dialogTitle, historyContent, showBackButton,
                            P2pChatGui.this);
                    historyDialog.setVisible(true);
                } catch (Exception ex) {
                    String errorMsg = "Error reading/displaying history";
                    if (ex.getCause() != null) {
                        errorMsg += ":\n" + ex.getCause().getMessage();
                    } else {
                        errorMsg += ":\n" + ex.getMessage();
                    }
                    displaySystemMessage("System: Error displaying history for " + finalPeerId.substring(0, 8) + " - "
                            + ex.getMessage());
                    JOptionPane.showMessageDialog(frame, errorMsg, "History Error", JOptionPane.ERROR_MESSAGE);
                    ex.printStackTrace();
                }
            }
        };
        worker.execute();
        loadingDialog.setVisible(true);
    }

    // --- GuiCallback Implementation ---
    @Override
    public void displayNodeId(String nodeId) {
        this.ownNodeId = nodeId;
        SwingUtilities.invokeLater(() -> {
            nodeIdLabel.setText("Node ID: " + nodeId);
            copyIdButton.setEnabled(this.ownNodeId != null);
            if (nodeContext.currentState.get() != NodeState.CONNECTED_SECURE) { 
                viewHistoryButton.setEnabled(this.ownNodeId != null && nodeContext.currentState.get() != NodeState.INITIALIZING && nodeContext.currentState.get() != NodeState.REGISTERING);
            }
            displaySystemMessage("System: Registered with ID: " + nodeId);
        });
    }

    @Override
    public void updateState(NodeState newState, String peerUsername, String peerNodeId) {
        SwingUtilities.invokeLater(() -> {
            String currentStatusText = statusLabel.getText();
            Color currentStatusColor = statusLabel.getForeground();

            if (statusResetTimer != null && statusResetTimer.isRunning()) {
                // Timer active, let it restore the status label
            } else {
                String statusTextUpdate;
                Color statusColorUpdate;
                String peerDisplay = "Peer";
                if (newState == NodeState.CONNECTED_SECURE || newState == NodeState.WAITING_MATCH || newState == NodeState.ATTEMPTING_UDP) {
                    peerDisplay = (peerUsername != null && !peerUsername.isEmpty()) ? peerUsername
                            : (peerNodeId != null ? peerNodeId.substring(0, Math.min(8, peerNodeId.length())) + "..." : "Peer");
                }

                switch (newState) {
                    case INITIALIZING:
                        statusTextUpdate = "Status: Initializing..."; statusColorUpdate = Color.ORANGE; break;
                    case REGISTERING:
                        statusTextUpdate = "Status: Registering..."; statusColorUpdate = Color.ORANGE; break;
                    case DISCONNECTED:
                        statusTextUpdate = "Status: Disconnected"; statusColorUpdate = Color.RED; break;
                    case WAITING_MATCH:
                        statusTextUpdate = "Status: Waiting for " + peerDisplay + " match..."; statusColorUpdate = Color.ORANGE; break;
                    case ATTEMPTING_UDP:
                        statusTextUpdate = "Status: Attempting P2P with " + peerDisplay + "..."; statusColorUpdate = Color.ORANGE; break;
                    case CONNECTED_SECURE:
                        statusTextUpdate = "Status: ✅ Connected to " + peerDisplay; statusColorUpdate = new Color(0, 128, 0); break;
                    case SHUTTING_DOWN:
                        statusTextUpdate = "Status: Shutting down..."; statusColorUpdate = Color.GRAY; break;
                    default:
                        statusTextUpdate = "Status: Unknown"; statusColorUpdate = Color.BLACK; break;
                }
                statusLabel.setText(statusTextUpdate);
                statusLabel.setForeground(statusColorUpdate);
            }

            // Clear chatArea when a new session starts (state becomes CONNECTED_SECURE)
            if (newState == NodeState.CONNECTED_SECURE) {
                if (chatArea != null) { 
                    chatArea.setText(""); // Clear previous messages
                    // The "System: Chat session started with..." message that was here has been REMOVED
                }
            }
            
            boolean connected = (newState == NodeState.CONNECTED_SECURE);
            boolean connecting = (newState == NodeState.WAITING_MATCH || newState == NodeState.ATTEMPTING_UDP);
            boolean canConnect = (newState == NodeState.DISCONNECTED && this.ownNodeId != null);

            connectButton.setEnabled(canConnect);
            disconnectButton.setEnabled(connected || connecting);
            sendButton.setEnabled(connected);
            messageField.setEnabled(connected);
            sendFileButton.setEnabled(connected);
            copyIdButton.setEnabled(this.ownNodeId != null);

            boolean baseHistoryEligibility = (this.ownNodeId != null &&
                                             newState != NodeState.INITIALIZING &&
                                             newState != NodeState.REGISTERING);
            viewHistoryButton.setEnabled(baseHistoryEligibility && !connected);


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
            int choice = JOptionPane.showConfirmDialog(frame, message, "File Offer", JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                displaySystemMessage(
                        "System: Accepting file offer for '" + filename + "' [ID: " + transferId.substring(0, 8) + "]");
                fileTransferService.respondToOffer(transferId, true);
            } else {
                displaySystemMessage(
                        "System: Rejecting file offer for '" + filename + "' [ID: " + transferId.substring(0, 8) + "]");
                fileTransferService.respondToOffer(transferId, false);
            }
        });
    }

    @Override
    public void updateTransferProgress(String transferId, String filename, long currentBytes, long totalBytes,
            boolean isSending, FileTransferState.Status status) {
        SwingUtilities.invokeLater(() -> {
            JProgressBar progressBar = transferProgressBars.get(transferId);
            JLabel label = transferLabels.get(transferId);
            JPanel entryPanel = transferEntries.get(transferId);
            if (entryPanel == null) {
                entryPanel = new JPanel(new BorderLayout(5, 0));
                entryPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));
                entryPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                entryPanel.setName(transferId);
                label = new JLabel();
                label.setOpaque(false);
                transferLabels.put(transferId, label);
                progressBar = new JProgressBar(0, 100);
                progressBar.setStringPainted(true);
                progressBar.setPreferredSize(new Dimension(100, 25));
                progressBar.setOpaque(false);
                transferProgressBars.put(transferId, progressBar);
                entryPanel.add(label, BorderLayout.NORTH);
                entryPanel.add(progressBar, BorderLayout.CENTER);
                entryPanel.setOpaque(false);
                transferEntries.put(transferId, entryPanel);
                transferPanel.add(entryPanel);
                transferPanel.add(Box.createRigidArea(new Dimension(0, 5)));
                transferPanel.revalidate();
                transferPanel.repaint();
            }
            String direction = isSending ? "Sending" : "Receiving";
            int percentage = (totalBytes > 0) ? (int) ((100 * currentBytes) / totalBytes) : 0;
            String statusText = status.toString().replace("TRANSFERRING_", "").replace("OFFER_", "")
                    .replace("AWAITING_", "Wait");
            String shortFilename = filename.length() > 15 ? filename.substring(0, 12) + "..." : filename;
            String currentStatusText = String.format("%s '%s' (%s)", direction, shortFilename, statusText);
            label.setText(currentStatusText);
            label.setToolTipText(String.format("%s '%s' (%s)", direction, filename, statusText));
            progressBar.setValue(percentage);
            progressBar.setString(String.format("%d%%", percentage));
            if (status == FileTransferState.Status.FAILED || status == FileTransferState.Status.CANCELLED
                    || status == FileTransferState.Status.REJECTED) {
                progressBar.setForeground(Color.RED);
            } else if (status == FileTransferState.Status.COMPLETED) {
                progressBar.setForeground(new Color(0, 150, 0));
            } else if (isSending) {
                progressBar.setForeground(Color.BLUE);
            } else {
                progressBar.setForeground(new Color(0, 150, 0));
            }

            boolean completedReceived = (status == FileTransferState.Status.COMPLETED && !isSending);
            MouseListener[] listeners = entryPanel.getMouseListeners();
            for (MouseListener ml : listeners) {
                if (ml instanceof OpenDownloadedFileListener) {
                    if (!completedReceived) {
                        entryPanel.removeMouseListener(ml);
                        entryPanel.setCursor(Cursor.getDefaultCursor());
                        entryPanel.setToolTipText(null);
                        label.setCursor(Cursor.getDefaultCursor());
                    }
                    break;
                }
            }
        });
    }

    @Override
    public void transferFinished(String transferId, String message) {
        displaySystemMessage("System: File Transfer [" + transferId.substring(0, 8) + "] " + message);
        SwingUtilities.invokeLater(() -> {
            JProgressBar progressBar = transferProgressBars.get(transferId);
            JLabel label = transferLabels.get(transferId);
            JPanel entryPanel = transferEntries.get(transferId);
            boolean success = message.toLowerCase().contains("complete");
            if (progressBar != null) {
                progressBar.setValue(100);
                progressBar.setString(message);
                progressBar.setForeground(success ? new Color(0, 150, 0) : Color.RED);
            }
            if (label != null && label.getToolTipText() != null) {
                 label.setToolTipText(label.getToolTipText() + " - " + message);
            } else if (label != null) {
                 FileTransferState tempState = nodeContext.ongoingTransfers.get(transferId);
                 String filename = (tempState != null) ? tempState.filename : "File";
                 String direction = (tempState != null && tempState.isSender) ? "Sending" : "Receiving";
                 label.setToolTipText(String.format("%s '%s' - %s", direction, filename, message));
            }


            FileTransferState finalState = nodeContext.ongoingTransfers.get(transferId);
            if (success && finalState != null && !finalState.isSender && finalState.downloadPath != null
                    && entryPanel != null) {
                final Path downloadedPath = finalState.downloadPath;
                boolean listenerExists = false;
                for (MouseListener ml : entryPanel.getMouseListeners()) {
                    if (ml instanceof OpenDownloadedFileListener) {
                        listenerExists = true;
                        break;
                    }
                }
                if (!listenerExists) {
                    entryPanel.addMouseListener(new OpenDownloadedFileListener(downloadedPath));
                    entryPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    entryPanel.setToolTipText("Click to open received file: " + finalState.filename);
                    if (label != null)
                        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }
            } else if (entryPanel != null) { 
                entryPanel.setCursor(Cursor.getDefaultCursor());
                if (label != null)
                    label.setCursor(Cursor.getDefaultCursor());
                for (MouseListener ml : entryPanel.getMouseListeners()) {
                    if (ml instanceof OpenDownloadedFileListener) {
                        entryPanel.removeMouseListener(ml);
                    }
                }
                 if (entryPanel.getToolTipText() != null && entryPanel.getToolTipText().startsWith("Click to open")) {
                      entryPanel.setToolTipText(null);
                 }
            }
        });
    }

    @Override
    public void clearFileProgress() {
        SwingUtilities.invokeLater(() -> {
            for (JPanel panel : transferEntries.values()) {
                 transferPanel.remove(panel);
            }
            transferEntries.clear();
            transferProgressBars.clear();
            transferLabels.clear();
            transferPanel.revalidate();
            transferPanel.repaint();
        });
    }

    private void removeTransferEntry(String transferId) {
        JProgressBar bar = transferProgressBars.remove(transferId);
        JLabel lbl = transferLabels.remove(transferId);
        JPanel entry = transferEntries.remove(transferId);

        if (entry != null) {
             for (MouseListener ml : entry.getMouseListeners()) {
                 entry.removeMouseListener(ml);
             }
             transferPanel.remove(entry);
             transferPanel.revalidate();
             transferPanel.repaint();
        }
    }

    private class HistoryDialog extends JDialog {
        public HistoryDialog(Frame owner, String title, String content, boolean showBackButton, P2pChatGui parentGui) {
            super(owner, title, false);
            setSize(600, 500);
            setLocationRelativeTo(owner);
            setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            JTextArea historyTextArea = new JTextArea(content);
            historyTextArea.setEditable(false);
            historyTextArea.setLineWrap(true);
            historyTextArea.setWrapStyleWord(true);
            historyTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            historyTextArea.setCaretPosition(0);
            JScrollPane historyScrollPane = new JScrollPane(historyTextArea);
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton closeButton = new JButton("Close");
            closeButton.addActionListener(e -> dispose());
            buttonPanel.add(closeButton);
            if (showBackButton) {
                JButton backButton = new JButton("Back to List");
                backButton.addActionListener(e -> {
                    dispose();
                    SwingUtilities.invokeLater(parentGui::promptAndShowHistory);
                });
                buttonPanel.add(backButton);
            }
            setLayout(new BorderLayout());
            add(historyScrollPane, BorderLayout.CENTER);
            add(buttonPanel, BorderLayout.SOUTH);
        }
    }

    private class OpenDownloadedFileListener extends MouseAdapter {
        private final Path filePath;

        public OpenDownloadedFileListener(Path filePath) {
            this.filePath = filePath;
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (filePath == null) {
                JOptionPane.showMessageDialog(frame, "File path is not available.", "Open Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!Files.exists(filePath)) {
                JOptionPane.showMessageDialog(frame, "File no longer exists:\n" + filePath, "Open Error",
                        JOptionPane.ERROR_MESSAGE);
                 ((JComponent)e.getSource()).removeMouseListener(this);
                 ((JComponent)e.getSource()).setCursor(Cursor.getDefaultCursor());
                return;
            }
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                displaySystemMessage("System Error: Desktop.open action not supported.");
                JOptionPane.showMessageDialog(frame, "Cannot open file automatically on this system.", "Open Error",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            displaySystemMessage("System: Attempting to open received file: " + filePath);
            try {
                Desktop.getDesktop().open(filePath.toFile());
            } catch (IOException | SecurityException | IllegalArgumentException exception) {
                displaySystemMessage("System Error: Error opening file " + filePath + ": " + exception.getMessage());
                JOptionPane.showMessageDialog(frame, "Could not open file:\n" + exception.getMessage(), "Open Error",
                        JOptionPane.ERROR_MESSAGE);
                exception.printStackTrace();
            }
        }
    }

}