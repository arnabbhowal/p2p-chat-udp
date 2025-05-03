package main.java.com.p2pchat.node.ui;

import main.java.com.p2pchat.node.model.FileTransferState;
import main.java.com.p2pchat.node.model.NodeState;

/**
 * Defines methods the backend services can call to update the GUI safely.
 */
public interface GuiCallback {

    /**
     * Displays the node's own ID after successful registration.
     * @param nodeId The node's ID.
     */
    void displayNodeId(String nodeId);

    /**
     * Updates the GUI to reflect a change in the node's overall state.
     * This should update status labels, button enablement, etc.
     * @param newState The new state of the node.
     * @param peerUsername The current peer's username (if applicable, null otherwise).
     * @param peerNodeId The current peer's node ID (if applicable, null otherwise).
     */
    void updateState(NodeState newState, String peerUsername, String peerNodeId);

    /**
     * Appends a CHAT message (from user or peer) to the main chat display area.
     * Use displaySystemMessage for non-chat status/log messages.
     * @param message The message string to display.
     */
    void appendMessage(String message);

    /**
     * Displays an incoming file transfer offer to the user.
     * Should typically show a dialog with Accept/Reject options.
     * @param transferId The unique ID for this transfer.
     * @param filename The name of the file being offered.
     * @param filesize The size of the file in bytes.
     * @param senderUsername The username of the peer sending the file.
     */
    void showFileOffer(String transferId, String filename, long filesize, String senderUsername);

    /**
     * Updates the progress of an ongoing file transfer.
     * @param transferId The ID of the transfer being updated.
     * @param filename The name of the file.
     * @param currentBytes The number of bytes transferred so far.
     * @param totalBytes The total size of the file.
     * @param isSending True if this node is sending, false if receiving.
     * @param status The current status of the transfer.
     */
    void updateTransferProgress(String transferId, String filename, long currentBytes, long totalBytes, boolean isSending, FileTransferState.Status status);

    /**
     * Indicates a file transfer has finished (completed, failed, cancelled, rejected).
     * @param transferId The ID of the finished transfer.
     * @param message A message describing the outcome (e.g., "Completed", "Failed: Timeout").
     */
    void transferFinished(String transferId, String message);

     /**
      * Clears all active file transfer progress indicators from the GUI.
      * Called typically on disconnect or shutdown.
      */
     void clearFileProgress();

     /**
      * Displays a system-level message (e.g., status updates, internal events).
      * Distinct from user chat messages. Can be logged differently (e.g., console).
      * @param message The system message string.
      */
     void displaySystemMessage(String message); // <<< Added method
}