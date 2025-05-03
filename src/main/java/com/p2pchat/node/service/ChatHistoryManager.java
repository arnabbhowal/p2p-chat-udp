package main.java.com.p2pchat.node.service;

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

 public class ChatHistoryManager {

     private static final String HISTORY_DIR = "chat_history";
     private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());
     private static final String CSV_HEADER = "TimestampISO,PeerNodeID,PeerUsername,Direction,Message\n";

     private Path historyFilePath = null;
     private final Object fileLock = new Object();

     public void initialize(String myNodeId, String peerNodeId, String peerUsername) {
         if (peerNodeId == null || peerNodeId.isEmpty()) {
             System.err.println("[History] Cannot initialize chat history without a valid peer Node ID.");
             return;
         }
         String usernameForFile = (peerUsername == null || peerUsername.trim().isEmpty()) ? "unknown_user" : peerUsername;

         try {
             Path historyDir = Paths.get(HISTORY_DIR);
             if (!Files.exists(historyDir)) {
                 Files.createDirectories(historyDir);
                 System.out.println("[History] Created history directory: " + historyDir.toAbsolutePath());
             }

             String sanitizedUsername = sanitizeFilename(usernameForFile);
             String sanitizedNodeId = sanitizeFilename(peerNodeId);
             String filename = "chat_" + sanitizedUsername + "_" + sanitizedNodeId + ".csv";
             this.historyFilePath = historyDir.resolve(filename);

             if (!Files.exists(this.historyFilePath)) {
                 synchronized (fileLock) {
                     if (!Files.exists(this.historyFilePath)) { // Double-check lock
                         try (PrintWriter writer = new PrintWriter(new FileWriter(this.historyFilePath.toFile(), true))) { // Append mode
                             writer.print(CSV_HEADER);
                             System.out.println("[History] Created history file for peer " + usernameForFile + ": " + this.historyFilePath.getFileName());
                         }
                     }
                 }
             } else {
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

     public void addMessage(long timestampMs, String peerNodeId, String peerUsername, String direction, String messageContent) {
         if (this.historyFilePath == null) {
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

         synchronized (fileLock) {
             try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(this.historyFilePath.toFile(), true)))) {
                 writer.print(csvLine.toString());
             } catch (IOException e) {
                 System.err.println("[History] Error writing message to history file ("+ this.historyFilePath.getFileName() +"): " + e.getMessage());
             }
         }
     }

     private String sanitizeFilename(String input) {
         if (input == null) return "invalid_input";
         return input.replaceAll("[^a-zA-Z0-9.-]", "_");
     }

     private String escapeCsvField(String field) {
         if (field == null) return "\"\"";

         if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
             String escapedField = field.replace("\"", "\"\"");
             return "\"" + escapedField + "\"";
         } else {
             return field;
         }
     }
 }