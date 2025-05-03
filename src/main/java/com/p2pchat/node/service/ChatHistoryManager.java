package main.java.com.p2pchat.node.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
// No need to import File if using NIO Path consistently
// import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream; // Import needed
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime; // <<< Import needed
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher; // Import needed
import java.util.regex.Pattern; // Import needed
import java.io.FileOutputStream; // <<< Import needed for correct FileWriter usage
import java.io.OutputStreamWriter; // <<< Import needed for correct FileWriter usage

public class ChatHistoryManager {

    // Package-private or public constant if needed elsewhere, otherwise private
    static final String HISTORY_DIR = "chat_history";

    // Filename format: chat_PeerUsername_PeerNodeID.csv
    // Node ID is usually a UUID: 8-4-4-4-12 hex chars with dashes
    private static final Pattern HISTORY_FILENAME_PATTERN = Pattern.compile(
            // Use non-greedy match for username to handle underscores in names properly
            "^chat_(.+?)_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.csv$");

    private static final DateTimeFormatter DISPLAY_TIMESTAMP_FORMAT = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT) // e.g., 5/3/25, 6:30 PM
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter ISO_TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(ZoneId.systemDefault());
    private static final String CSV_HEADER = "TimestampISO,PeerNodeID,PeerUsername,Direction,Message\n";

    private Path historyFilePath = null;
    private final Object fileLock = new Object(); // Lock for writing to specific instance file

    // <<< Simple inner class/record to hold history info >>>
    // Using a simple class for Java 8 compatibility (records are Java 14+)
    public static class HistoryInfo {
        public final String peerUsername;
        public final String peerNodeId;
        public final long lastModifiedMillis;

        public HistoryInfo(String peerUsername, String peerNodeId, long lastModifiedMillis) {
            this.peerUsername = peerUsername;
            this.peerNodeId = peerNodeId;
            this.lastModifiedMillis = lastModifiedMillis;
        }

        // Getters might be useful depending on usage style
        public String getPeerUsername() {
            return peerUsername;
        }

        public String getPeerNodeId() {
            return peerNodeId;
        }

        public long getLastModifiedMillis() {
            return lastModifiedMillis;
        }

        // For display in selection dialog
        @Override
        public String toString() {
            // Ensure peerNodeId is not null before substring
            String idPrefix = (peerNodeId != null && peerNodeId.length() > 8) ? peerNodeId.substring(0, 8)
                    : "invalidID";
            return String.format("%s (%s...)", peerUsername, idPrefix);
        }
    }

    /**
     * Initializes the history manager for a specific peer connection.
     * Determines the file path and ensures the file/header exists.
     * Should be called when a secure connection is established.
     * 
     * @param myNodeId     Current node's ID (not used in filename currently).
     * @param peerNodeId   The ID of the connected peer.
     * @param peerUsername The username of the connected peer.
     */
    public void initialize(String myNodeId, String peerNodeId, String peerUsername) {
        if (peerNodeId == null || peerNodeId.isEmpty()) {
            System.err.println("[History] Cannot initialize: Peer Node ID is missing.");
            return;
        }
        // Use placeholder for file operations if username is missing/empty
        String usernameForFile = (peerUsername == null || peerUsername.trim().isEmpty()) ? "unknown_user"
                : peerUsername;

        Path determinedPath = getHistoryFilePath(usernameForFile, peerNodeId);
        if (determinedPath == null) {
            System.err.println("[History] Failed to determine history file path for peer " + peerNodeId);
            return;
        }
        this.historyFilePath = determinedPath;

        // Create file and header if it doesn't exist (check directory first)
        try {
            Path historyDir = Paths.get(HISTORY_DIR);
            if (!Files.exists(historyDir)) {
                Files.createDirectories(historyDir);
                System.out.println("[History] Created history directory: " + historyDir.toAbsolutePath());
            }

            if (!Files.exists(this.historyFilePath)) {
                synchronized (fileLock) { // Lock for file creation/header write
                    if (!Files.exists(this.historyFilePath)) { // Double-check
                        // Use OutputStreamWriter to specify charset and append mode
                        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                                new FileOutputStream(this.historyFilePath.toFile(), true), // true for append
                                StandardCharsets.UTF_8))) {
                            writer.print(CSV_HEADER);
                            System.out.println("[History] Created history file for peer " + usernameForFile + ": "
                                    + this.historyFilePath.getFileName());
                        } catch (IOException | SecurityException e) {
                            System.err.println("[History] Error creating/writing history file header for peer "
                                    + peerNodeId + ": " + e.getMessage());
                            this.historyFilePath = null; // Mark as unusable
                        }
                    }
                }
            } else {
                System.out.println("[History] Using existing history file for peer " + usernameForFile + ": "
                        + this.historyFilePath.getFileName());
            }
        } catch (IOException | SecurityException e) {
            System.err.println(
                    "[History] Error ensuring history directory exists or accessing file path: " + e.getMessage());
            this.historyFilePath = null;
        }
    }

    /**
     * Adds a message entry to the currently initialized history file.
     * 
     * @param timestampMs    Timestamp in milliseconds.
     * @param peerNodeId     ID of the peer involved.
     * @param peerUsername   Username of the peer involved.
     * @param direction      "SENT" or "RECEIVED".
     * @param messageContent The message content.
     */
    public void addMessage(long timestampMs, String peerNodeId, String peerUsername, String direction,
            String messageContent) {
        if (this.historyFilePath == null) {
            return;
        } // Not initialized or init failed
        if (!"SENT".equals(direction) && !"RECEIVED".equals(direction)) {
            System.err.println("[History] Invalid message direction: " + direction);
            return;
        }

        String timestampIso = ISO_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampMs));
        // Use a consistent default for logging if username is somehow null/empty here
        String safePeerUsername = (peerUsername != null && !peerUsername.trim().isEmpty()) ? peerUsername : "Peer";

        // Data order must match CSV_HEADER
        String[] data = {
                timestampIso,
                peerNodeId != null ? peerNodeId : "N/A",
                safePeerUsername,
                direction,
                messageContent != null ? messageContent : ""
        };

        StringBuilder csvLine = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            csvLine.append(escapeCsvField(data[i]));
            if (i < data.length - 1) {
                csvLine.append(",");
            }
        }
        csvLine.append("\n");

        synchronized (fileLock) { // Lock for writing to this specific file instance
            // Use OutputStreamWriter and BufferedWriter for efficiency and encoding control
            try (PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(this.historyFilePath.toFile(), true), // true for append
                    StandardCharsets.UTF_8)))) {
                writer.print(csvLine.toString());
            } catch (IOException e) {
                // Log error, maybe disable further writes for this session?
                System.err.println("[History] Error writing message to history file ("
                        + this.historyFilePath.getFileName() + "): " + e.getMessage());
            }
        }
    }

    /**
     * Scans the history directory and returns information about available chat
     * logs.
     * Includes last modified time for sorting.
     * 
     * @return A List of HistoryInfo objects. Empty if none found/error.
     */
    public static List<HistoryInfo> listAvailableHistories() { // <<< METHOD ADDED
        List<HistoryInfo> histories = new ArrayList<>();
        Path historyDir = Paths.get(HISTORY_DIR);

        if (!Files.isDirectory(historyDir)) {
            System.out.println("[History List] History directory not found: " + HISTORY_DIR);
            return histories;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(historyDir, "chat_*.csv")) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry))
                    continue;
                String filename = entry.getFileName().toString();
                Matcher matcher = HISTORY_FILENAME_PATTERN.matcher(filename);
                if (matcher.matches()) {
                    try {
                        String username = matcher.group(1);
                        String nodeId = matcher.group(2);
                        FileTime lastModifiedTime = Files.getLastModifiedTime(entry);
                        long lastModifiedMillis = lastModifiedTime.toMillis();

                        username = username.equals("unknown_user") ? "<Unknown User>" : username;
                        histories.add(new HistoryInfo(username, nodeId, lastModifiedMillis));
                    } catch (IOException e) {
                        System.err.println("[History List] Error getting last modified time for " + filename + ": "
                                + e.getMessage());
                    }
                } else {
                    System.out.println("[History List] Skipping file with unexpected name format: " + filename);
                }
            }
        } catch (IOException | SecurityException e) {
            System.err.println("[History List] Error listing history files: " + e.getMessage());
        }
        return histories;
    }

    /**
     * Reads the chat history for a given peer, formats it for display.
     * 
     * @param peerUsername The username of the peer (can be "<Unknown User>").
     * @param peerNodeId   The node ID of the peer.
     * @return A formatted string containing the chat history, or an error/status
     *         message.
     */
    public static String readFormattedHistory(String peerUsername, String peerNodeId) { // <<< UPDATED/STATIC
        if (peerNodeId == null || peerNodeId.isEmpty()) {
            return "Error: Cannot read history without Peer ID.";
        }
        // Use placeholder username if needed to find the correct file
        String usernameForFile = (peerUsername == null || peerUsername.equals("<Unknown User>")
                || peerUsername.trim().isEmpty()) ? "unknown_user" : peerUsername;
        Path historyFile = getHistoryFilePath(usernameForFile, peerNodeId);

        if (historyFile == null) {
            return "Error: Could not determine history file path.";
        }
        if (!Files.exists(historyFile)) {
            return "No chat history found for this peer.";
        }
        if (!Files.isReadable(historyFile)) {
            return "Error: Cannot read history file (check permissions): " + historyFile.getFileName();
        }

        StringBuilder formattedHistory = new StringBuilder();
        long lineCount = 0;
        try (BufferedReader reader = Files.newBufferedReader(historyFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (lineCount == 1) {
                    continue;
                } // Skip header
                if (line.trim().isEmpty())
                    continue;

                List<String> parts = parseCsvLine(line);
                // Expecting 5 fields: TimestampISO,PeerNodeID,PeerUsername,Direction,Message
                if (parts.size() >= 5) {
                    try {
                        Instant timestamp = Instant.from(ISO_TIMESTAMP_FORMAT.parse(parts.get(0).trim()));
                        String displayTime = DISPLAY_TIMESTAMP_FORMAT.format(timestamp);
                        String direction = parts.get(3).trim();
                        String message = parts.get(4); // Message comes pre-unescaped from parser
                        // Get username from the file's record for accuracy
                        String senderNameInFile = parts.get(2).equals("unknown_user") ? "<Unknown User>" : parts.get(2);
                        String senderName = direction.equals("SENT") ? "You" : senderNameInFile;

                        formattedHistory.append("[").append(displayTime).append("] ")
                                .append(senderName).append(": ")
                                .append(message).append("\n");
                    } catch (DateTimeParseException e) {
                        System.err.println(
                                "[History Read] Skipping line " + lineCount + " (Timestamp Error): " + e.getMessage());
                    } catch (IndexOutOfBoundsException e) {
                        System.err.println("[History Read] Skipping line " + lineCount + " (Field Count Error).");
                    } catch (Exception e) {
                        System.err.println(
                                "[History Read] Skipping line " + lineCount + " (Other Error): " + e.getMessage());
                    }
                } else {
                    System.err.println("[History Read] Skipping line " + lineCount + " with incorrect field count: "
                            + parts.size());
                }
            }
        } catch (IOException | SecurityException e) {
            System.err
                    .println("[History Read] Error reading file " + historyFile.getFileName() + ": " + e.getMessage());
            return "Error reading history file: " + e.getMessage();
        }

        if (lineCount <= 1) { // Only header was present
            return "Chat history is empty for this peer.";
        }

        return formattedHistory.toString();
    }

    // --- Static Helper Methods ---
    private static Path getHistoryFilePath(String peerUsername, String peerNodeId) { // <<< STATIC
        try {
            Path historyDir = Paths.get(HISTORY_DIR);
            // Ensure directory exists
            if (!Files.exists(historyDir)) {
                Files.createDirectories(historyDir);
                // No need to print message every time path is resolved
                // System.out.println("[History Util] Created history directory: " +
                // historyDir.toAbsolutePath());
            }
            // Sanitize parts before creating filename
            String sanitizedUsername = sanitizeFilename(peerUsername);
            String sanitizedNodeId = sanitizeFilename(peerNodeId); // UUIDs should be safe, but sanitize anyway
            String filename = "chat_" + sanitizedUsername + "_" + sanitizedNodeId + ".csv";
            return historyDir.resolve(filename);
        } catch (Exception e) { // Catch IO, Security, InvalidPath etc.
            System.err.println(
                    "[History Util] Error resolving history path for peer " + peerNodeId + ": " + e.getMessage());
            return null;
        }
    }

    private static String sanitizeFilename(String input) { // <<< STATIC
        if (input == null || input.trim().isEmpty())
            return "invalid_input_" + UUID.randomUUID().toString().substring(0, 4);
        // Allow alphanumeric, dot, underscore, hyphen. Replace others with underscore.
        String sanitized = input.trim().replaceAll("[^a-zA-Z0-9.\\-_]", "_");
        // Limit length to prevent excessively long filenames
        final int MAX_LEN = 100;
        return sanitized.length() > MAX_LEN ? sanitized.substring(0, MAX_LEN) : sanitized;
    }

    private static String escapeCsvField(String field) { // <<< STATIC
        if (field == null)
            return "\"\"";
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            String escapedField = field.replace("\"", "\"\"");
            return "\"" + escapedField + "\"";
        } else {
            return field;
        }
    }

    private static List<String> parseCsvLine(String line) { // <<< STATIC
        List<String> fields = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return fields;
        }
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    currentField.append('"');
                    i++; // Append one quote, skip second
                } else {
                    inQuotes = !inQuotes;
                } // Toggle quote state
            } else if (c == ',' && !inQuotes) {
                fields.add(currentField.toString());
                currentField.setLength(0); // End field
            } else {
                currentField.append(c);
            } // Append character
        }
        fields.add(currentField.toString()); // Add last field
        return fields;
    }
}