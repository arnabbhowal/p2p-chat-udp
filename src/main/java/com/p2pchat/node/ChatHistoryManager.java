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
    // Using ISO_OFFSET_DATE_TIME provides timezone information, good practice
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());
    // Added PeerUsername to the header for clarity, though it's also in the filename now
    private static final String CSV_HEADER = "TimestampISO,PeerNodeID,PeerUsername,Direction,Message\n";

    private Path historyFilePath = null;
    private final Object fileLock = new Object(); // Lock for synchronized file writing

    /**
     * Initializes the history manager for a specific peer conversation.
     * Creates the history directory and file if they don't exist.
     * Filename format: chat_&lt;peerUsername&gt;_&lt;peerNodeId&gt;.csv
     *
     * @param myNodeId     The current node's ID (not used in filename currently).
     * @param peerNodeId   The node ID of the peer in this conversation.
     * @param peerUsername The username of the peer.
     */
    public void initialize(String myNodeId, String peerNodeId, String peerUsername) {
        if (peerNodeId == null || peerNodeId.isEmpty()) {
            System.err.println("[History] Cannot initialize chat history without a valid peer Node ID.");
            return;
        }
        // Use a default if username is somehow null or empty during initialization
        String usernameForFile = (peerUsername == null || peerUsername.trim().isEmpty()) ? "unknown_user" : peerUsername;

        try {
            Path historyDir = Paths.get(HISTORY_DIR);
            if (!Files.exists(historyDir)) {
                Files.createDirectories(historyDir);
                System.out.println("[History] Created history directory: " + historyDir.toAbsolutePath());
            }

            // --- Filename Generation Logic Change ---
            // Sanitize both username and node ID parts for the filename
            String sanitizedUsername = sanitizeFilename(usernameForFile);
            String sanitizedNodeId = sanitizeFilename(peerNodeId);

            // Construct the new filename including the username
            String filename = "chat_" + sanitizedUsername + "_" + sanitizedNodeId + ".csv";
            // --- End Filename Generation Logic Change ---

            this.historyFilePath = historyDir.resolve(filename);

            // Create file and write header if it's new
            if (!Files.exists(this.historyFilePath)) {
                synchronized (fileLock) {
                    if (!Files.exists(this.historyFilePath)) { // Double-check lock
                        try (PrintWriter writer = new PrintWriter(new FileWriter(this.historyFilePath.toFile(), true))) { // Append mode
                            writer.print(CSV_HEADER);
                            // Log using the potentially non-sanitized username for clarity
                            System.out.println("[History] Created history file for peer " + usernameForFile + ": " + this.historyFilePath.getFileName());
                        }
                    }
                }
            } else {
                 // Log using the potentially non-sanitized username for clarity
                 System.out.println("[History] Using existing history file for peer " + usernameForFile + ": " + this.historyFilePath.getFileName());
            }

        } catch (IOException e) {
            System.err.println("[History] Error initializing chat history file for peer " + peerNodeId + ": " + e.getMessage());
            this.historyFilePath = null;
        } catch (Exception e) {
             System.err.println("[History] Unexpected error initializing history: " + e.getMessage());
             this.historyFilePath = null;
        }
    }

    /**
     * Adds a message entry to the current history file.
     * (No changes needed in this method for the filename request)
     *
     * @param timestampMs    The epoch milliseconds timestamp of the message.
     * @param peerNodeId     The Node ID of the peer.
     * @param peerUsername   The username of the peer.
     * @param direction      "SENT" or "RECEIVED".
     * @param messageContent The text content of the message (plaintext, already decrypted if received).
     */
    public void addMessage(long timestampMs, String peerNodeId, String peerUsername, String direction, String messageContent) {
        if (this.historyFilePath == null) {
            // Avoid logging error repeatedly if init failed
            return;
        }
        if (!direction.equals("SENT") && !direction.equals("RECEIVED")) {
            System.err.println("[History] Invalid message direction: " + direction);
            return;
        }

        String timestampIso = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampMs));

        String[] data = {
                timestampIso,
                peerNodeId != null ? peerNodeId : "N/A",
                peerUsername != null ? peerUsername : "N/A",
                direction,
                messageContent != null ? messageContent : "" // Ensure non-null
        };

        StringBuilder csvLine = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            csvLine.append(escapeCsvField(data[i]));
            if (i < data.length - 1) {
                csvLine.append(",");
            }
        }
        csvLine.append("\n");

        synchronized (fileLock) {
            try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(this.historyFilePath.toFile(), true)))) {
                writer.print(csvLine.toString());
            } catch (IOException e) {
                System.err.println("[History] Error writing message to history file ("+ this.historyFilePath.getFileName() +"): " + e.getMessage());
            }
        }
    }

    /**
     * Basic filename sanitization. Replaces non-alphanumeric chars (excluding '.' and '-') with underscores.
     */
    private String sanitizeFilename(String input) {
        if (input == null) return "invalid_input";
        // Keep letters, numbers, period, hyphen. Replace others with underscore.
        return input.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    /**
     * Escapes a string field for CSV format (RFC 4180 rules).
     * - Wraps fields containing commas, double quotes, or newlines in double quotes.
     * - Doubles any existing double quotes within the field.
     */
    private String escapeCsvField(String field) {
        if (field == null) return "\"\""; // Represent null as empty quoted string

        // Check if quoting is necessary
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            // Replace all occurrences of " with ""
            String escapedField = field.replace("\"", "\"\"");
            // Enclose the whole field in double quotes
            return "\"" + escapedField + "\"";
        } else {
            // No special characters, return as is (though quoting everything is also valid)
            return field;
        }
    }
}