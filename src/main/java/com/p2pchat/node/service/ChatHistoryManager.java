package main.java.com.p2pchat.node.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream; // <<< Import needed
import java.io.IOException;
import java.io.OutputStreamWriter; // <<< Import needed
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
// import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatHistoryManager {

    // Package-private constant for the directory name
    static final String HISTORY_DIR = "chat_history";

    private static final Pattern HISTORY_FILENAME_PATTERN = Pattern.compile(
            "^chat_(.+?)_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.csv$");

    private static final DateTimeFormatter DISPLAY_TIMESTAMP_FORMAT = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter ISO_TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(ZoneId.systemDefault());
    private static final String CSV_HEADER = "TimestampISO,PeerNodeID,PeerUsername,Direction,Message\n";

    private Path historyFilePath = null;
    private final Object fileLock = new Object();

    public void initialize(String myNodeId, String peerNodeId, String peerUsername) {
        if (peerNodeId == null || peerNodeId.isEmpty()) {
            System.err.println("[History] Cannot initialize: Peer Node ID is missing.");
            return;
        }
        String usernameForFile = (peerUsername == null || peerUsername.trim().isEmpty()) ? "unknown_user"
                : peerUsername;

        Path determinedPath = getHistoryFilePath(usernameForFile, peerNodeId);
        if (determinedPath == null) {
            System.err.println("[History] Failed to determine history file path for peer " + peerNodeId);
            return;
        }
        this.historyFilePath = determinedPath;

        try {
            Path historyDir = Paths.get(HISTORY_DIR);
            if (!Files.exists(historyDir)) {
                Files.createDirectories(historyDir);
                System.out.println("[History] Created history directory: " + historyDir.toAbsolutePath());
            }

            if (!Files.exists(this.historyFilePath)) {
                synchronized (fileLock) {
                    if (!Files.exists(this.historyFilePath)) {
                        // <<< Use OutputStreamWriter to specify charset and append mode >>>
                        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                                new FileOutputStream(this.historyFilePath.toFile(), true), // true for append
                                StandardCharsets.UTF_8))) {
                            writer.print(CSV_HEADER);
                            System.out.println("[History] Created history file for peer " + usernameForFile + ": "
                                    + this.historyFilePath.getFileName());
                        } catch (IOException | SecurityException e) {
                            System.err.println("[History] Error creating/writing header for file "
                                    + this.historyFilePath.getFileName() + ": " + e.getMessage());
                            this.historyFilePath = null;
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

    public void addMessage(long timestampMs, String peerNodeId, String peerUsername, String direction,
            String messageContent) {
        if (this.historyFilePath == null) {
            return;
        }
        if (!"SENT".equals(direction) && !"RECEIVED".equals(direction)) {
            System.err.println("[History] Invalid message direction: " + direction);
            return;
        }

        String timestampIso = ISO_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampMs));
        String safePeerUsername = (peerUsername != null && !peerUsername.trim().isEmpty()) ? peerUsername : "Peer";

        String[] data = { timestampIso, peerNodeId != null ? peerNodeId : "N/A", safePeerUsername, direction,
                messageContent != null ? messageContent : "" };

        StringBuilder csvLine = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            csvLine.append(escapeCsvField(data[i]));
            if (i < data.length - 1) {
                csvLine.append(",");
            }
        }
        csvLine.append("\n");

        synchronized (fileLock) {
            // <<< Use OutputStreamWriter and BufferedWriter for efficiency >>>
            try (PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(this.historyFilePath.toFile(), true), // true for append
                    StandardCharsets.UTF_8)))) {
                writer.print(csvLine.toString());
            } catch (IOException e) {
                System.err.println("[History] Error writing message to history file ("
                        + this.historyFilePath.getFileName() + "): " + e.getMessage());
            }
        }
    }

    // ... (listAvailableHistories - unchanged) ...
    public static List<String[]> listAvailableHistories() {
        List<String[]> histories = new ArrayList<>();
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
                    String username = matcher.group(1);
                    String nodeId = matcher.group(2);
                    username = username.equals("unknown_user") ? "<Unknown User>" : username;
                    histories.add(new String[] { username, nodeId });
                } else {
                    System.out.println("[History List] Skipping file with unexpected name format: " + filename);
                }
            }
        } catch (IOException | SecurityException e) {
            System.err.println("[History List] Error listing history files: " + e.getMessage());
        }
        return histories;
    }

    // ... (readFormattedHistory - unchanged) ...
    public static String readFormattedHistory(String peerUsername, String peerNodeId) {
        if (peerNodeId == null || peerNodeId.isEmpty()) {
            return "Error: Cannot read history without Peer ID.";
        }
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
                }
                if (line.trim().isEmpty())
                    continue;
                List<String> parts = parseCsvLine(line);
                if (parts.size() >= 5) {
                    try {
                        Instant timestamp = Instant.from(ISO_TIMESTAMP_FORMAT.parse(parts.get(0).trim()));
                        String displayTime = DISPLAY_TIMESTAMP_FORMAT.format(timestamp);
                        String direction = parts.get(3).trim();
                        String message = parts.get(4);
                        String senderNameInFile = parts.get(2).equals("unknown_user") ? "<Unknown User>" : parts.get(2);
                        String senderName = direction.equals("SENT") ? "You" : senderNameInFile;
                        formattedHistory.append("[").append(displayTime).append("] ").append(senderName).append(": ")
                                .append(message).append("\n");
                    } catch (Exception e) {
                        System.err.println("[History Read] Skipping line " + lineCount + ": " + e.getMessage());
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
        if (lineCount <= 1) {
            return "Chat history is empty for this peer.";
        }
        return formattedHistory.toString();
    }

    // ... (getHistoryFilePath, sanitizeFilename, escapeCsvField, parseCsvLine -
    // unchanged static helpers) ...
    private static Path getHistoryFilePath(String peerUsername, String peerNodeId) {
        try {
            Path historyDir = Paths.get(HISTORY_DIR);
            if (!Files.exists(historyDir)) {
                Files.createDirectories(historyDir);
            }
            String sanitizedUsername = sanitizeFilename(peerUsername);
            String sanitizedNodeId = sanitizeFilename(peerNodeId);
            String filename = "chat_" + sanitizedUsername + "_" + sanitizedNodeId + ".csv";
            return historyDir.resolve(filename);
        } catch (Exception e) {
            System.err.println(
                    "[History Util] Error resolving history path for peer " + peerNodeId + ": " + e.getMessage());
            return null;
        }
    }

    private static String sanitizeFilename(String input) {
        if (input == null || input.trim().isEmpty())
            return "invalid_input_" + UUID.randomUUID().toString().substring(0, 4);
        String sanitized = input.trim().replaceAll("[^a-zA-Z0-9.\\-_]", "_");
        final int MAX_LEN = 100;
        return sanitized.length() > MAX_LEN ? sanitized.substring(0, MAX_LEN) : sanitized;
    }

    private static String escapeCsvField(String field) {
        if (field == null)
            return "\"\"";
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            String escapedField = field.replace("\"", "\"\"");
            return "\"" + escapedField + "\"";
        } else {
            return field;
        }
    }

    private static List<String> parseCsvLine(String line) {
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
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(currentField.toString());
                currentField.setLength(0);
            } else {
                currentField.append(c);
            }
        }
        fields.add(currentField.toString());
        return fields;
    }
}