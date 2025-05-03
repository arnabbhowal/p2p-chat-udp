package main.java.com.p2pchat.node.model;

import main.java.com.p2pchat.common.Endpoint;
import main.java.com.p2pchat.node.service.ChatHistoryManager;

import javax.crypto.SecretKey;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.ArrayList; // Added
import java.util.List;
import java.util.Map; // Added for Map import
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// Holds the shared state of the P2P Node
public class NodeContext {
    // Configuration
    public InetSocketAddress serverAddress = null;

    // Node Identity & Keys
    public final AtomicReference<String> myNodeId = new AtomicReference<>(null);
    public String myUsername = "User" + (int)(Math.random() * 1000);
    public KeyPair myKeyPair = null;
    public final ConcurrentHashMap<String, SecretKey> peerSymmetricKeys = new ConcurrentHashMap<>();

    // Network
    public DatagramSocket udpSocket = null;
    public final List<Endpoint> myLocalEndpoints = new CopyOnWriteArrayList<>(); // Thread-safe for modification
    public Endpoint myPublicEndpointSeen = null;

    // Connection State
    public final AtomicReference<NodeState> currentState = new AtomicReference<>(NodeState.INITIALIZING);
    public final AtomicReference<String> targetPeerId = new AtomicReference<>(null); // Who we are trying to connect TO
    public final AtomicReference<String> connectedPeerId = new AtomicReference<>(null); // Who we are currently connected WITH
    public final AtomicReference<String> connectedPeerUsername = new AtomicReference<>(null);
    public final List<Endpoint> peerCandidates = new CopyOnWriteArrayList<>(); // Candidates for current target
    public final AtomicReference<InetSocketAddress> peerAddrConfirmed = new AtomicReference<>(null); // Confirmed P2P address

    // Flags & Timers
    public final AtomicBoolean connectionInfoReceived = new AtomicBoolean(false); // Server sent peer info
    public final AtomicBoolean p2pUdpPathConfirmed = new AtomicBoolean(false);   // Ping/Pong worked
    public final AtomicBoolean sharedKeyEstablished = new AtomicBoolean(false); // Symmetric key derived
    public final AtomicBoolean running = new AtomicBoolean(true); // Overall node running flag
    public final AtomicBoolean redrawPrompt = new AtomicBoolean(false); // Flag to signal UI prompt redraw

    public long waitingSince = 0; // Timestamp for timeouts
    public int pingAttempts = 0; // Counter for pinging phase

    // Services / Managers
    public ChatHistoryManager chatHistoryManager = null; // Initialized on connection

    // --- File Transfer State ---
    public final ConcurrentHashMap<String, FileTransferState> ongoingTransfers = new ConcurrentHashMap<>();
    // --- End File Transfer State ---

    // Lock for complex state transitions if needed, though atomics handle most
    public final Object stateLock = new Object();

    public void resetConnectionState() {
        String peerId = connectedPeerId.get();
        if (peerId == null) peerId = targetPeerId.get(); // Check target too

        if (peerId != null) {
            peerSymmetricKeys.remove(peerId);
            System.out.println("[*] Cleared session key for peer " + peerId);

            // --- Cancel any ongoing file transfers with this peer ---
            cancelAndCleanupTransfersForPeer(peerId, "Disconnected");
            // --- End file transfer cleanup ---
        }

        targetPeerId.set(null);
        connectedPeerId.set(null);
        connectedPeerUsername.set(null);
        peerCandidates.clear();
        peerAddrConfirmed.set(null);
        connectionInfoReceived.set(false);
        p2pUdpPathConfirmed.set(false);
        sharedKeyEstablished.set(false);
        pingAttempts = 0;
        waitingSince = 0;
        redrawPrompt.set(false); // Reset redraw flag too

        if (chatHistoryManager != null) {
            System.out.println("[*] Chat history logging stopped.");
            chatHistoryManager = null;
        }
        System.out.println("[*] Reset connection state variables.");
    }

    // --- Helper method for cleaning up transfers ---
    public void cancelAndCleanupTransfersForPeer(String peerId, String reason) {
        List<String> transfersToRemove = new ArrayList<>();
        // If peerId is null, cancel ALL transfers (e.g., during shutdown)
        boolean cancelAll = (peerId == null);

        for (Map.Entry<String, FileTransferState> entry : ongoingTransfers.entrySet()) {
            if (cancelAll || entry.getValue().peerNodeId.equals(peerId)) {
                FileTransferState state = entry.getValue();
                if (!state.isTerminated()) {
                    state.status = FileTransferState.Status.CANCELLED; // Mark as cancelled
                    state.closeStreams(); // Ensure streams are closed
                    System.out.println("[FileTransfer] Cancelled transfer " + state.transferId.substring(0,8) + "... with peer " + entry.getValue().peerNodeId.substring(0,8) + "... due to: " + reason);
                }
                transfersToRemove.add(entry.getKey());
            }
        }
        // Remove them outside the loop to avoid ConcurrentModificationException
        transfersToRemove.forEach(ongoingTransfers::remove);
        if (!transfersToRemove.isEmpty()) {
            redrawPrompt.set(true); // Update UI as transfers were cancelled
        }
    }
    // --- End helper method ---


    public String getPeerDisplayId() {
        String cPeer = connectedPeerId.get();
        if (cPeer != null) return cPeer;
        String tPeer = targetPeerId.get();
        if (tPeer != null) return tPeer;
        return "N/A";
    }

    public String getPeerDisplayName() {
        String username = connectedPeerUsername.get();
        String peerId = connectedPeerId.get();

        if (peerId != null) { // Prioritize connected peer info
            if (username != null && !username.isEmpty()) return username;
            return peerId.substring(0, Math.min(8, peerId.length())) + "...";
        }

        String targetId = targetPeerId.get(); // Fallback to target peer
        if (targetId != null) {
             // Username isn't known for target until connection_info arrives
            return targetId.substring(0, Math.min(8, targetId.length())) + "...";
        }
        return "???";
    }
}