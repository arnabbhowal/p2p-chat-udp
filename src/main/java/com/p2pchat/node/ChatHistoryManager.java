package main.java.com.p2pchat.node; // Or main.java.com.p2pchat.util

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Manages storing and retrieving chat history in simple CSV files.
 */
public class ChatHistoryManager {

    private static final String HISTORY_DIR = "chat_history";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());
    private static final String CSV_HEADER = "TimestampISO,PeerNodeID,PeerUsername,Direction,Message\n";

    private Path historyFilePath = null;
    private final Object fileLock = new Object(); // Lock for synchronized file writing

    /**
     * Initializes the history manager for a specific peer conversation.
     * Creates the history directory and file if they don't exist.
     *
     * @param myNodeId     The current node's ID (optional, could be used for dir structure).
     * @param peerNodeId   The node ID of the peer in this conversation.
     * @param peerUsername The username of the peer.
     */
    public void initialize(String myNodeId, String peerNodeId, String peerUsername) {
        if (peerNodeId == null || peerNodeId.isEmpty()) {
            System.err.println("[History] Cannot initialize chat history without a valid peer Node ID.");
            return;
        }

        try {
            Path historyDir = Paths.get(HISTORY_DIR);
            // Create directory if it doesn't exist
            if (!Files.exists(historyDir)) {
                Files.createDirectories(historyDir);
                System.out.println("[History] Created history directory: " + historyDir.toAbsolutePath());
            }

            // Generate filename based on peer Node ID
            String filename = "chat_" + sanitizeFilename(peerNodeId) + ".csv";
            this.historyFilePath = historyDir.resolve(filename);

            // Create file and write header if it's new
            if (!Files.exists(this.historyFilePath)) {
                synchronized (fileLock) { // Ensure header is written only once safely
                    if (!Files.exists(this.historyFilePath)) { // Double check after locking
                        try (PrintWriter writer = new PrintWriter(new FileWriter(this.historyFilePath.toFile(), true))) { // Append mode
                            writer.print(CSV_HEADER);
                            System.out.println("[History] Created history file for peer " + peerUsername + ": " + this.historyFilePath.getFileName());
                        }
                    }
                }
            } else {
                 System.out.println("[History] Using existing history file for peer " + peerUsername + ": " + this.historyFilePath.getFileName());
            }

        } catch (IOException e) {
            System.err.println("[History] Error initializing chat history file for peer " + peerNodeId + ": " + e.getMessage());
            this.historyFilePath = null; // Ensure it's null if init failed
        } catch (Exception e) {
             System.err.println("[History] Unexpected error initializing history: " + e.getMessage());
             this.historyFilePath = null;
        }
    }

    /**
     * Adds a message entry to the current history file.
     *
     * @param timestampMs    The epoch milliseconds timestamp of the message.
     * @param peerNodeId     The Node ID of the peer.
     * @param peerUsername   The username of the peer.
     * @param direction      "SENT" or "RECEIVED".
     * @param messageContent The text content of the message.
     */
    public void addMessage(long timestampMs, String peerNodeId, String peerUsername, String direction, String messageContent) {
        if (this.historyFilePath == null) {
            // System.err.println("[History] History manager not initialized, cannot add message.");
            // Avoid flooding console if connection drops immediately after init failure
            return;
        }
        if (!direction.equals("SENT") && !direction.equals("RECEIVED")) {
            System.err.println("[History] Invalid message direction: " + direction);
            return;
        }

        // Format the timestamp
        String timestampIso = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampMs));

        // Prepare CSV data
        String[] data = {
                timestampIso,
                peerNodeId != null ? peerNodeId : "N/A",
                peerUsername != null ? peerUsername : "N/A",
                direction,
                messageContent != null ? messageContent : "" // Ensure non-null
        };

        // Build the CSV line, escaping fields as needed
        StringBuilder csvLine = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            csvLine.append(escapeCsvField(data[i]));
            if (i < data.length - 1) {
                csvLine.append(",");
            }
        }
        csvLine.append("\n"); // Newline for the next entry

        // Write to file synchronized to prevent race conditions if somehow
        // send/receive happened extremely close together (unlikely but safe)
        synchronized (fileLock) {
            try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(this.historyFilePath.toFile(), true)))) { // Append mode, buffered
                writer.print(csvLine.toString());
            } catch (IOException e) {
                System.err.println("[History] Error writing message to history file ("+ this.historyFilePath.getFileName() +"): " + e.getMessage());
            }
        }
    }

    /**
     * Basic filename sanitization to remove potentially problematic characters.
     * Replace non-alphanumeric characters with underscores.
     */
    private String sanitizeFilename(String input) {
        if (input == null) return "invalid_id";
        return input.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    /**
     * Escapes a string field for CSV format.
     * - Wraps the field in double quotes.
     * - Doubles any existing double quotes within the field.
     */
    private String escapeCsvField(String field) {
        if (field == null) return "\"\""; // Represent null as empty quoted string
        // Replace all occurrences of " with ""
        String escapedField = field.replace("\"", "\"\"");
        // Enclose the whole field in double quotes
        return "\"" + escapedField + "\"";
    }

    // Optional: Method to load history (can be implemented later)
    /*
    public List<String[]> loadHistory() {
        // ... read this.historyFilePath, parse CSV lines ...
        return null;
    }
    */

    // Optional: Explicit close method if needed (try-with-resources handles it now)
    /*
    public void close() {
        // If using resources that need explicit closing
    }
    */
}