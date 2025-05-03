package main.java.com.p2pchat.node.service;

import main.java.com.p2pchat.common.CryptoUtils;
import main.java.com.p2pchat.node.config.NodeConfig;
import main.java.com.p2pchat.node.model.FileTransferState;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.network.NetworkManager;
// Import the callback interface
import main.java.com.p2pchat.node.ui.GuiCallback;

import org.json.JSONObject;

import javax.crypto.SecretKey;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Base64;
// Import Map for logging helper if needed, though logging moved
// import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FileTransferService {

    private final NodeContext context;
    private final NetworkManager networkManager;
    private final ScheduledExecutorService timeoutExecutor;
    private GuiCallback guiCallback = null; // <<< Added callback reference

    public FileTransferService(NodeContext context, NetworkManager networkManager) {
        this.context = context;
        this.networkManager = networkManager;
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FileTransferTimeoutThread");
            t.setDaemon(true);
            return t;
        });
    }

    // <<< Added setter for callback >>>
    public void setGuiCallback(GuiCallback callback) {
        this.guiCallback = callback;
    }

    public void start() {
         if (timeoutExecutor.isShutdown()) {
              System.err.println("[!] FileTransferService executor already shut down. Cannot start.");
              return;
         }
        timeoutExecutor.scheduleAtFixedRate(this::checkTimeouts,
                NodeConfig.FILE_ACK_TIMEOUT_MS, // Initial delay
                NodeConfig.FILE_ACK_TIMEOUT_MS / 2, // Check frequently
                TimeUnit.MILLISECONDS);
        System.out.println("[*] Started File Transfer Timeout checker task.");
    }

    public void stop() {
        if (timeoutExecutor != null && !timeoutExecutor.isShutdown()) {
            timeoutExecutor.shutdownNow();
            System.out.println("[*] File Transfer Timeout executor shutdown requested.");
            try {
                if (!timeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
                    System.err.println("[!] File Transfer Timeout executor did not terminate cleanly.");
                else System.out.println("[*] File Transfer Timeout executor terminated.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Called by GUI to initiate sending a file
    public void initiateTransfer(String filePathStr) {
        String userMsgPrefix = "System: "; // For GUI messages
        if (context.currentState.get() != NodeState.CONNECTED_SECURE) {
            String msg = "[!] You must be connected to a peer to send a file.";
            System.out.println("\n" + msg);
            if (guiCallback != null) guiCallback.appendMessage(userMsgPrefix + msg);
            return;
        }
        String peerId = context.connectedPeerId.get();
        InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
        String peerDisplay = context.getPeerDisplayName();

        if (peerId == null || peerAddr == null) {
            String msg = "[!] Connection state invalid, cannot send file.";
            System.out.println("\n" + msg);
            if (guiCallback != null) guiCallback.appendMessage(userMsgPrefix + msg);
            return;
        }

        try {
            Path filePath = Paths.get(filePathStr);
            if (!Files.exists(filePath) || !Files.isReadable(filePath) || Files.isDirectory(filePath)) {
                String msg = "[!] File not found or cannot be read: " + filePathStr;
                System.out.println("\n" + msg);
                if (guiCallback != null) guiCallback.appendMessage(userMsgPrefix + msg);
                return;
            }

            long fileSize = Files.size(filePath);
            if (fileSize == 0) {
                 String msg = "[!] Cannot send empty file.";
                 System.out.println("\n" + msg);
                 if (guiCallback != null) guiCallback.appendMessage(userMsgPrefix + msg);
                 return;
            }
            if (fileSize > NodeConfig.MAX_FILE_SIZE_BYTES) {
                String msg = "[!] File exceeds size limit (" + String.format("%.2f MiB", NodeConfig.MAX_FILE_SIZE_BYTES / (1024.0 * 1024.0)) + "): " + filePath.getFileName();
                System.out.println("\n" + msg);
                if (guiCallback != null) guiCallback.appendMessage(userMsgPrefix + msg);
                return;
            }

            String transferId = UUID.randomUUID().toString();
            String filename = filePath.getFileName().toString();

            FileTransferState state = new FileTransferState(transferId, filename, fileSize, peerId, true);
            state.sourcePath = filePath;
            state.status = FileTransferState.Status.OFFER_SENT; // Mark as offer sent initially
            context.ongoingTransfers.put(transferId, state);

            // <<< Notify GUI: Transfer initiated >>>
            if (guiCallback != null) {
                guiCallback.updateTransferProgress(transferId, filename, 0, fileSize, true, state.status);
            }

            JSONObject offerMsg = new JSONObject();
            offerMsg.put("action", "file_offer");
            offerMsg.put("node_id", context.myNodeId.get());
            offerMsg.put("transfer_id", transferId);
            offerMsg.put("filename", filename);
            offerMsg.put("filesize", fileSize);

            String offerLogMsg = String.format("Sending file offer to %s for '%s' (%.2f KB) [ID: %s]",
                                            peerDisplay, filename, fileSize / 1024.0, transferId.substring(0,8));
            System.out.println("\n[*] " + offerLogMsg);
            // Don't use appendMessage for this, let GUI handle offer state via progress update

            boolean sent = networkManager.sendUdp(offerMsg, peerAddr);

            if (!sent) {
                String failMsg = "[!] Failed to send file offer for '" + filename + "'.";
                System.err.println(failMsg);
                // Use failTransfer to handle cleanup and GUI notification
                failTransfer(state, "Failed to send offer");
            } else {
                 state.status = FileTransferState.Status.AWAITING_ACCEPT; // Update status after successful send
                 logTransferEvent(state, "SENT", "[File Transfer] Offered file '" + filename + "' (" + fileSize + " bytes)");
                 // <<< Notify GUI: Status changed >>>
                 if (guiCallback != null) {
                     guiCallback.updateTransferProgress(transferId, filename, 0, fileSize, true, state.status);
                 }
            }

        } catch (IOException e) {
            String msg = "[!] Error accessing file: " + e.getMessage();
            System.err.println("\n" + msg);
            if (guiCallback != null) guiCallback.appendMessage(userMsgPrefix + msg);
        } catch (Exception e) {
            String msg = "[!] Unexpected error initiating transfer: " + e.getMessage();
            System.err.println("\n" + msg);
            if (guiCallback != null) guiCallback.appendMessage(userMsgPrefix + msg);
            e.printStackTrace();
        }
    }

    // Called by PeerMessageHandler when a file_offer is received
    public void handleIncomingOffer(JSONObject data, InetSocketAddress peerAddr) {
        String transferId = data.optString("transfer_id", null);
        String filename = data.optString("filename", null);
        long filesize = data.optLong("filesize", -1);
        String senderId = data.optString("node_id", null);
        String senderDisplay = context.getPeerDisplayName(); // Assumes offer is from connected peer

        // --- Basic Validation ---
        if (transferId == null || filename == null || filesize <= 0 || senderId == null) {
            System.err.println("\n[!] Received invalid file offer (missing fields).");
            // No GUI action needed, just log error
            return;
        }
        // Ensure offer is from the currently connected peer
        if (!senderId.equals(context.connectedPeerId.get()) || !peerAddr.equals(context.peerAddrConfirmed.get())) {
            System.err.println("\n[!] Received file offer from unexpected peer/address. Ignoring.");
            return;
        }
         // Check if transfer ID already exists
         if (context.ongoingTransfers.containsKey(transferId)) {
              System.err.println("\n[!] Received duplicate file offer ID: " + transferId + ". Ignoring.");
              return;
         }
         // Check size limit
         if (filesize > NodeConfig.MAX_FILE_SIZE_BYTES) {
             String sizeMsg = String.format("Auto-rejecting file offer '%s' from %s - exceeds size limit (%.2f MiB).",
                                            filename, senderDisplay, filesize / (1024.0*1024.0));
             System.out.println("\n[!] " + sizeMsg);
             // Send reject without creating persistent state
             FileTransferState tempState = new FileTransferState(transferId, filename, filesize, senderId, false);
             respondToOfferInternal(transferId, false, "File size limit exceeded", tempState, false); // Auto-reject
             // <<< Notify GUI (Optional) >>>
             if (guiCallback != null) guiCallback.appendMessage("System: " + sizeMsg);
             return;
         }
        // --- End Validation ---


        // Offer seems valid, create state
        FileTransferState state = new FileTransferState(transferId, filename, filesize, senderId, false);
        state.status = FileTransferState.Status.OFFER_RECEIVED;
        context.ongoingTransfers.put(transferId, state);

        // <<< Notify GUI to show the offer dialog >>>
        if (guiCallback != null) {
            guiCallback.showFileOffer(transferId, filename, filesize, senderDisplay);
        } else { // Fallback to console if no GUI
            System.out.println("\n-----------------------------------------------------");
            System.out.println("[*] Incoming file offer from " + senderDisplay + ":");
            System.out.println("    File: '" + filename + "'");
            System.out.println("    Size: " + filesize + " bytes");
            System.out.println("    Transfer ID: " + transferId);
            System.out.println("    To accept, type: accept " + transferId);
            System.out.println("    To reject, type: reject " + transferId);
            System.out.println("-----------------------------------------------------");
        }
    }

     // Called by GUI when user clicks 'accept' or 'reject'
     public void respondToOffer(String transferId, boolean accepted) {
         FileTransferState state = context.ongoingTransfers.get(transferId);
         if (state == null) {
             String msg = "[!] No pending file offer found with ID: " + transferId.substring(0,8) + "...";
             System.out.println("\n" + msg);
             if (guiCallback != null) guiCallback.appendMessage("System: " + msg);
             return;
         }
         // Use internal helper, triggered by user action
         respondToOfferInternal(transferId, accepted, null, state, true);
     }

    // Internal method with optional reason, state, and source (user/auto)
    private void respondToOfferInternal(String transferId, boolean accepted, String reason, FileTransferState state, boolean userAction) {
        String userMsgPrefix = userAction ? "System: " : "System (Auto): "; // Distinguish user vs auto actions

        // Validate state
        if (state.isSender || state.status != FileTransferState.Status.OFFER_RECEIVED) {
            if (state.status != FileTransferState.Status.FAILED && state.status != FileTransferState.Status.REJECTED) { // Avoid duplicate msg
                String msg = "[!] Cannot respond to offer " + transferId.substring(0,8) + " (Invalid state: " + state.status + ")";
                System.out.println("\n" + msg);
                // if (guiCallback != null) guiCallback.appendMessage(userMsgPrefix + msg); // Maybe too noisy
            }
            return;
        }

        String peerId = state.peerNodeId;
        InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
        String peerDisplay = context.getPeerDisplayName();

        // Double check peer connection state
        if (!peerId.equals(context.connectedPeerId.get()) || peerAddr == null) {
            String msg = "[!] Peer connection lost or changed, cannot respond to offer " + transferId.substring(0,8);
            System.out.println("\n" + msg);
            failTransfer(state, "Connection lost before response"); // Handles cleanup and GUI notification
             // No need for separate GUI message here, failTransfer covers it
            return;
        }

        JSONObject responseMsg = new JSONObject();
        responseMsg.put("node_id", context.myNodeId.get());
        responseMsg.put("transfer_id", transferId);

        if (accepted) {
            responseMsg.put("action", "file_accept");
            state.status = FileTransferState.Status.TRANSFERRING_RECV; // Prepare to receive

            // <<< Notify GUI: Status changed >>>
            if (guiCallback != null) {
                guiCallback.updateTransferProgress(transferId, state.filename, state.transferredBytes.get(), state.filesize, false, state.status);
            }

            // Prepare file path and output stream
            if (!prepareDownloadFile(state)) {
                failTransfer(state, "Failed to create local file");
                // Send reject instead
                respondToOfferInternal(transferId, false, "Failed to create local file", state, false); // Resend as auto-reject
                return;
            }

            String acceptMsg = String.format("Accepting file transfer '%s' from %s [ID: %s]. Receiving...",
                                            state.filename, peerDisplay, transferId.substring(0,8));
            System.out.println("\n[*] " + acceptMsg);
            // <<< Notify GUI >>> (appendMessage might be redundant if progress shows 'Receiving')
            // if (guiCallback != null) guiCallback.appendMessage(userMsgPrefix + acceptMsg);
            logTransferEvent(state, "RECEIVED", "[File Transfer] Accepted file '" + state.filename + "'");

        } else { // Rejected
            responseMsg.put("action", "file_reject");
            if (reason != null && !reason.isEmpty()) {
                 responseMsg.put("reason", reason);
            }
            state.status = FileTransferState.Status.REJECTED; // Mark as rejected locally

            String rejectMsg = String.format("Rejecting file transfer '%s' from %s [ID: %s].",
                                            state.filename, peerDisplay, transferId.substring(0,8));
            System.out.println("\n[*] " + rejectMsg);

            logTransferEvent(state, "RECEIVED", "[File Transfer] Rejected file '" + state.filename + "'" + (reason != null ? ". Reason: " + reason : ""));

            // <<< Notify GUI: Transfer finished (rejected) >>>
             if (guiCallback != null) {
                 guiCallback.transferFinished(transferId, "Rejected" + (reason != null ? ": " + reason : ""));
             }

            // Clean up receiver state immediately on rejection
            state.closeStreams();
            context.ongoingTransfers.remove(transferId); // Remove here, not in failTransfer
        }

        // Send the response (Accept or Reject)
        boolean sent = networkManager.sendUdp(responseMsg, peerAddr);
        if (!sent) {
             String sendFailMsg = "[!] Failed to send " + (accepted ? "accept" : "reject") + " response for transfer " + transferId.substring(0,8);
             System.err.println(sendFailMsg);
             // If sending ACCEPT fails, fail the transfer properly
             if (accepted && state.status != FileTransferState.Status.FAILED) {
                 failTransfer(state, "Failed to send accept response");
             } else if (!accepted) {
                 // If sending REJECT fails, we already cleaned up locally, just log error
                 if (guiCallback != null) guiCallback.appendMessage("System: Error - " + sendFailMsg);
             }
        }
    }

     // Helper to create downloads dir and prepare output stream
     private boolean prepareDownloadFile(FileTransferState state) {
          try {
               Path downloadDir = Paths.get(NodeConfig.DOWNLOADS_DIR);
               if (!Files.exists(downloadDir)) {
                   Files.createDirectories(downloadDir);
                   System.out.println("[*] Created downloads directory: " + downloadDir.toAbsolutePath());
               }

               String sanitizedFilename = state.filename.replaceAll("[^a-zA-Z0-9._-]", "_");
               if (sanitizedFilename.isEmpty() || sanitizedFilename.equals(".") || sanitizedFilename.equals("..")) {
                    sanitizedFilename = "downloaded_file_" + UUID.randomUUID().toString().substring(0, 4); // Fallback
               }
               Path filePath = downloadDir.resolve(sanitizedFilename);

               // Handle name collisions
               int count = 0;
               String baseName = sanitizedFilename;
               String extension = "";
               int dotIndex = sanitizedFilename.lastIndexOf('.');
               if (dotIndex > 0 && dotIndex < sanitizedFilename.length() - 1) {
                   baseName = sanitizedFilename.substring(0, dotIndex);
                   extension = sanitizedFilename.substring(dotIndex);
               } else if (dotIndex == 0) {
                   extension = sanitizedFilename;
                   baseName = "file";
               }

               while(Files.exists(filePath)) {
                    count++;
                    filePath = downloadDir.resolve(baseName + "_" + count + extension);
                    if (count > 999) { throw new IOException("Too many file collisions for: " + sanitizedFilename); }
               }

               state.downloadPath = filePath;
               state.outputStream = new BufferedOutputStream(Files.newOutputStream(filePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
               System.out.println("[*] Receiving file to: " + filePath.toAbsolutePath());
               return true;

          } catch (IOException | SecurityException | UnsupportedOperationException e) {
               String errorMsg = "[!] Error preparing download file '" + state.filename + "': " + e.getMessage();
               System.err.println("\n" + errorMsg);
               if (guiCallback != null) guiCallback.appendMessage("System: Error - " + errorMsg);
               return false;
          }
     }


    // Called by PeerMessageHandler when a file_accept is received
    public void handleFileAccept(JSONObject data) {
        String transferId = data.optString("transfer_id", null);
        String receiverId = data.optString("node_id", null);
        FileTransferState state = context.ongoingTransfers.get(transferId);
        String peerDisplay = context.getPeerDisplayName();

        // Validate state and sender
        if (state == null || !state.isSender || state.status != FileTransferState.Status.AWAITING_ACCEPT) return;
        if (!state.peerNodeId.equals(receiverId)) return;

        String acceptMsg = String.format("Peer %s accepted file transfer '%s' [ID: %s]. Starting send...",
                                        peerDisplay, state.filename, transferId.substring(0,8));
        System.out.println("\n[*] " + acceptMsg);
        state.status = FileTransferState.Status.TRANSFERRING_SEND;
        // <<< Notify GUI >>>
        if (guiCallback != null) {
             // guiCallback.appendMessage("System: " + acceptMsg); // Maybe redundant
             guiCallback.updateTransferProgress(transferId, state.filename, state.transferredBytes.get(), state.filesize, true, state.status);
        }
        logTransferEvent(state, "SENT", "[File Transfer] Peer accepted file '" + state.filename + "'");

        // Open input stream
        try {
             state.closeStreams(); // Ensure closed first
             state.inputStream = new BufferedInputStream(Files.newInputStream(state.sourcePath));
        } catch (IOException e) {
             String errorMsg = "[!] Failed to open file for reading: " + state.sourcePath + " - " + e.getMessage();
             System.err.println(errorMsg);
             failTransfer(state, "Failed to open file"); // Handles GUI update
             return;
        }

        // Start sending the first chunk
        sendNextChunk(state);
    }

    // Called by PeerMessageHandler when a file_reject is received
    public void handleFileReject(JSONObject data) {
        String transferId = data.optString("transfer_id", null);
        String reason = data.optString("reason", "No reason given");
        String receiverId = data.optString("node_id", null);
        FileTransferState state = context.ongoingTransfers.get(transferId);
        String peerDisplay = context.getPeerDisplayName();

        // Validate state and sender
        if (state == null || !state.isSender || state.isTerminated()) return;
        if (!state.peerNodeId.equals(receiverId)) return;

        String rejectMsg = String.format("Peer %s rejected file transfer '%s' [ID: %s]. Reason: %s",
                                        peerDisplay, state.filename, transferId.substring(0,8), reason);
        System.out.println("\n[!] " + rejectMsg);

        logTransferEvent(state, "SENT", "[File Transfer] Peer rejected file '" + state.filename + "'. Reason: " + reason);
        // Use failTransfer for cleanup and GUI notification
        failTransfer(state, "Peer rejected: " + reason);
    }

    // Called by PeerMessageHandler when a file_data chunk is received
    public void handleFileData(JSONObject data) {
        String transferId = data.optString("transfer_id", null);
        FileTransferState state = context.ongoingTransfers.get(transferId);

        // Validate state
        if (state == null || state.isSender || state.status != FileTransferState.Status.TRANSFERRING_RECV) return;

        int seqNum = data.optInt("seq_num", -1);
        String ivBase64 = data.optString("iv", null);
        String encryptedPayloadBase64 = data.optString("e_payload", null);
        boolean isLast = data.optBoolean("is_last", false);
        String senderId = data.optString("node_id", null);

        // Validate packet data and sender
        if (seqNum < 0 || ivBase64 == null || encryptedPayloadBase64 == null) {
            System.err.println("[!] Received invalid file_data packet for transfer " + transferId.substring(0,8) + " (missing fields)."); return;
        }
        if (!state.peerNodeId.equals(senderId)) {
             System.err.println("[!] Received file_data packet from unexpected sender for transfer " + transferId.substring(0,8)); return;
        }
        if (state.outputStream == null) {
             failTransfer(state, "Output stream missing"); return;
        }

        // Process based on sequence number
        if (seqNum == state.expectedSeqNum.get()) {
            // Expected chunk
            SecretKey sharedKey = context.peerSymmetricKeys.get(state.peerNodeId);
            if (sharedKey == null) { failTransfer(state, "Missing decryption key"); return; }

            try {
                CryptoUtils.EncryptedPayload encryptedPayload = new CryptoUtils.EncryptedPayload(ivBase64, encryptedPayloadBase64);
                String decryptedChunkStr = CryptoUtils.decrypt(encryptedPayload, sharedKey);
                byte[] decryptedChunk = Base64.getDecoder().decode(decryptedChunkStr);

                // Write to file
                state.outputStream.write(decryptedChunk);
                state.transferredBytes.addAndGet(decryptedChunk.length);

                // <<< Notify GUI: Progress update >>>
                if (guiCallback != null) {
                     // Update progress frequently or based on seqNum modulo
                     if (seqNum % 10 == 0 || isLast) { // Update every 10 chunks or on last
                         guiCallback.updateTransferProgress(transferId, state.filename, state.transferredBytes.get(), state.filesize, false, state.status);
                     }
                }

                // Send ACK
                sendAck(state, seqNum);

                // Increment expected sequence number
                state.expectedSeqNum.incrementAndGet();

                // Handle completion
                if (isLast) {
                     state.outputStream.flush();
                     state.closeStreams();
                     state.status = FileTransferState.Status.COMPLETED;
                     String completeMsg = String.format("File transfer COMPLETED: '%s' [ID: %s] received successfully.", state.filename, transferId.substring(0,8));
                     System.out.println("\n[*] " + completeMsg);
                     System.out.println("    Saved to: " + state.downloadPath);

                     logTransferEvent(state, "RECEIVED", "[File Transfer] Completed receiving '" + state.filename + "'");
                     // <<< Notify GUI: Transfer finished >>>
                     if (guiCallback != null) {
                         guiCallback.transferFinished(transferId, "Completed");
                     }
                     context.ongoingTransfers.remove(transferId); // Remove completed transfer state
                }

            } catch (GeneralSecurityException e) { failTransfer(state, "Decryption failed (chunk " + seqNum + ")");
            } catch (IOException e) { failTransfer(state, "File write error (chunk " + seqNum + ")");
            } catch (IllegalArgumentException e) { failTransfer(state, "Data decoding error (chunk " + seqNum + ")"); }

        } else if (seqNum < state.expectedSeqNum.get()) {
             // Duplicate chunk, resend ACK for that chunk
             sendAck(state, seqNum);
        } else {
            // Future chunk, ignore in stop-and-wait
        }
    }

    // Send acknowledgment for a received chunk
    private void sendAck(FileTransferState receiverState, int ackSeqNum) {
        InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
        // Basic check if we can send
        if (peerAddr == null || !receiverState.peerNodeId.equals(context.connectedPeerId.get())) {
             if (!receiverState.isTerminated()) {
                 failTransfer(receiverState, "Connection lost before sending ACK " + ackSeqNum);
             }
             return;
        }

        JSONObject ackMsg = new JSONObject();
        ackMsg.put("action", "file_ack");
        ackMsg.put("node_id", context.myNodeId.get());
        ackMsg.put("transfer_id", receiverState.transferId);
        ackMsg.put("ack_seq_num", ackSeqNum);

        networkManager.sendUdp(ackMsg, peerAddr); // Fire and forget for ACK
    }


    // Called by PeerMessageHandler when a file_ack is received
    public void handleFileAck(JSONObject data) {
        String transferId = data.optString("transfer_id", null);
        int ackSeqNum = data.optInt("ack_seq_num", -1);
        String ackingPeerId = data.optString("node_id", null);
        FileTransferState state = context.ongoingTransfers.get(transferId);

        // Validate state and sender
        if (state == null || !state.isSender || state.status != FileTransferState.Status.TRANSFERRING_SEND) return;
        if (!state.peerNodeId.equals(ackingPeerId)) return;

        // Process ACK if it's the one we're waiting for
        if (ackSeqNum == state.currentSeqNum.get()) {
             state.lastAckReceivedSeqNum.set(ackSeqNum);
             state.retryCount.set(0); // Reset retry count
             state.currentSeqNum.incrementAndGet(); // Move to next sequence number

             // Check if transfer is complete (all bytes ACKed)
             // Note: state.transferredBytes reflects bytes *sent*, not necessarily ACKed here.
             // We rely on the final ACK for the last chunk to signal completion.
             // If the last chunk was ACKed (ackSeqNum corresponds to the last seqNum sent), then complete.
             long totalChunks = (long) Math.ceil((double) state.filesize / NodeConfig.FILE_CHUNK_SIZE);
             boolean isLastAck = (ackSeqNum == totalChunks - 1); // Check if this was the ACK for the last chunk

             if (isLastAck) {
                  state.status = FileTransferState.Status.COMPLETED;
                  state.closeStreams();
                  String completeMsg = String.format("File transfer COMPLETED: '%s' [ID: %s] sent successfully.", state.filename, transferId.substring(0,8));
                  System.out.println("\n[*] " + completeMsg);

                  logTransferEvent(state, "SENT", "[File Transfer] Completed sending '" + state.filename + "'");
                  // <<< Notify GUI: Transfer finished >>>
                  if (guiCallback != null) {
                      guiCallback.transferFinished(transferId, "Completed");
                  }
                  context.ongoingTransfers.remove(transferId); // Remove completed transfer state
             } else {
                  // Send the next chunk
                  sendNextChunk(state);
             }
        }
        // Ignore duplicate or old ACKs
    }

    // Sends the next chunk of the file for the given transfer state
    private void sendNextChunk(FileTransferState senderState) {
         if (senderState.status != FileTransferState.Status.TRANSFERRING_SEND) return;

         InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
         SecretKey sharedKey = context.peerSymmetricKeys.get(senderState.peerNodeId);

         // Validate connection and resources
        if (peerAddr == null || !senderState.peerNodeId.equals(context.connectedPeerId.get())) {
             failTransfer(senderState, "Connection lost before sending chunk " + senderState.currentSeqNum.get()); return;
        }
        if (sharedKey == null) { failTransfer(senderState, "Missing encryption key"); return; }
        if (senderState.inputStream == null) { failTransfer(senderState, "File input stream lost"); return; }

        try {
            byte[] chunk = new byte[NodeConfig.FILE_CHUNK_SIZE];
            int bytesRead = senderState.inputStream.read(chunk);

            if (bytesRead == -1) {
                // Should not happen if completion logic (isLastAck in handleFileAck) is correct
                 System.err.println("[!] Unexpected EOF reached for transfer " + senderState.transferId.substring(0,8) + " after ACK for chunk " + senderState.lastAckReceivedSeqNum.get());
                 // If we somehow get here, assume complete based on last ACK
                 if(senderState.status != FileTransferState.Status.COMPLETED) {
                     failTransfer(senderState, "Unexpected EOF after sending"); // Fail defensively
                 }
                 return;
            }

            byte[] actualChunk;
            // Determine if this is the last chunk based on bytes read *relative to filesize*
             boolean isLast = (senderState.transferredBytes.get() + bytesRead >= senderState.filesize);

            if (bytesRead < NodeConfig.FILE_CHUNK_SIZE) {
                 actualChunk = new byte[bytesRead];
                 System.arraycopy(chunk, 0, actualChunk, 0, bytesRead);
                 if (!isLast) {
                     System.err.printf("[!] Read %d bytes (expected %d) but not calculated as last chunk for %s. Forcing isLast=true.\n",
                                       bytesRead, NodeConfig.FILE_CHUNK_SIZE, senderState.transferId.substring(0,8));
                     isLast = true; // Force last chunk flag
                 }
            } else {
                 actualChunk = chunk;
            }

            // Base64 encode raw chunk, then encrypt the result
            String chunkBase64 = Base64.getEncoder().encodeToString(actualChunk);
            CryptoUtils.EncryptedPayload encryptedPayload = CryptoUtils.encrypt(chunkBase64, sharedKey);

            int currentSeq = senderState.currentSeqNum.get();
            JSONObject dataMsg = new JSONObject();
            dataMsg.put("action", "file_data");
            dataMsg.put("node_id", context.myNodeId.get());
            dataMsg.put("transfer_id", senderState.transferId);
            dataMsg.put("seq_num", currentSeq);
            dataMsg.put("iv", encryptedPayload.ivBase64);
            dataMsg.put("e_payload", encryptedPayload.ciphertextBase64);
            dataMsg.put("is_last", isLast);

            boolean sent = networkManager.sendUdp(dataMsg, peerAddr);
            if (sent) {
                senderState.lastPacketSentTime.set(System.currentTimeMillis()); // Record time for timeout check
                // Update total bytes *attempted* to send (confirmation comes from ACK)
                senderState.transferredBytes.addAndGet(actualChunk.length);

                // <<< Notify GUI: Progress update >>>
                 if (guiCallback != null) {
                     // Update progress frequently or based on seqNum modulo
                     if (currentSeq % 10 == 0 || isLast) { // Update every 10 chunks or on last
                         guiCallback.updateTransferProgress(senderState.transferId, senderState.filename, senderState.transferredBytes.get(), senderState.filesize, true, senderState.status);
                     }
                 }
                if (isLast) {
                     System.out.println("[*] Sent final chunk (" + currentSeq + ") for transfer " + senderState.transferId.substring(0,8) + ". Waiting for final ACK.");
                }

            } else {
                System.err.println("[!] Failed to send chunk " + currentSeq + " for transfer " + senderState.transferId.substring(0,8));
                // Let timeout handler deal with retries/failure
                senderState.lastPacketSentTime.set(0); // Ensure timeout check happens if needed
            }

        } catch (IOException e) { failTransfer(senderState, "File read error (chunk " + senderState.currentSeqNum.get() + ")");
        } catch (GeneralSecurityException e) { failTransfer(senderState, "Encryption error (chunk " + senderState.currentSeqNum.get() + ")");
        } catch (Exception e) {
             System.err.println("[!!!] Unexpected error sending chunk " + senderState.currentSeqNum.get() + ": " + e.getMessage());
             e.printStackTrace();
             failTransfer(senderState, "Unexpected error sending chunk");
        }
    }

     // Periodically checks for timed out chunks for sending transfers
     private void checkTimeouts() {
         if (context.ongoingTransfers.isEmpty() || !context.running.get()) return;

         long now = System.currentTimeMillis();
         context.ongoingTransfers.forEach((id, state) -> {
             // Check only active sending transfers
             if (state.isSender && state.status == FileTransferState.Status.TRANSFERRING_SEND) {
                 // Check if we are waiting for an ACK
                 if (state.currentSeqNum.get() > state.lastAckReceivedSeqNum.get()) {
                     long lastSent = state.lastPacketSentTime.get();
                     if (lastSent > 0 && (now - lastSent > NodeConfig.FILE_ACK_TIMEOUT_MS)) {
                         // Timeout!
                         if (state.retryCount.incrementAndGet() <= NodeConfig.FILE_MAX_RETRIES) {
                              String retryMsg = String.format("Timeout waiting for ACK chunk %d (Transfer %s). Retrying (%d/%d)...",
                                                             state.currentSeqNum.get(), id.substring(0,4), state.retryCount.get(), NodeConfig.FILE_MAX_RETRIES);
                              System.out.println("\n[!] " + retryMsg);
                              // <<< Notify GUI (Optional): Indicate retry attempt? >>>
                              // if (guiCallback != null) guiCallback.appendMessage("System: " + retryMsg);
                               if (guiCallback != null) { // Update progress bar status text
                                     guiCallback.updateTransferProgress(id, state.filename, state.transferredBytes.get(), state.filesize, true, state.status); // Resend current status
                               }
                              resendChunk(state); // Attempt to resend
                         } else {
                              String failMsg = String.format("Max retries exceeded for chunk %d (Transfer %s). Failing transfer.",
                                                             state.currentSeqNum.get(), id.substring(0,4));
                              System.out.println("\n[!] " + failMsg);
                              failTransfer(state, "Timeout waiting for ACK"); // Handles GUI update
                         }
                     }
                 } else {
                      // ACK received for last sent packet, reset timer timestamp
                      state.lastPacketSentTime.set(0);
                 }
             }
         });
     }

     // Helper to resend the current chunk (seek and read approach)
     private void resendChunk(FileTransferState senderState) {
          InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
          SecretKey sharedKey = context.peerSymmetricKeys.get(senderState.peerNodeId);
          int currentSeq = senderState.currentSeqNum.get(); // Chunk to resend

          // Validate connection and resources
          if (peerAddr == null || !senderState.peerNodeId.equals(context.connectedPeerId.get())) { failTransfer(senderState, "Connection lost before resending chunk " + currentSeq); return; }
          if (sharedKey == null) { failTransfer(senderState, "Missing key for resend"); return; }
          // Input stream needs to be valid and capable of seeking/re-reading
          // Reopening is simpler than managing mark/reset
          if (senderState.sourcePath == null) { failTransfer(senderState, "Missing source path for resend"); return; }


          try {
              // --- Calculate chunk details ---
              long offset = (long)currentSeq * NodeConfig.FILE_CHUNK_SIZE;
              int bytesToRead = NodeConfig.FILE_CHUNK_SIZE;
              long remainingBytes = senderState.filesize - offset;
              boolean isLast = false;

              if (remainingBytes <= 0) { failTransfer(senderState, "Resend state mismatch with file size"); return; }
              if (remainingBytes < bytesToRead) { bytesToRead = (int) remainingBytes; isLast = true; }

              // --- Re-read the specific chunk ---
              byte[] actualChunk;
              try (InputStream tempStream = new BufferedInputStream(Files.newInputStream(senderState.sourcePath))) {
                   long skipped = tempStream.skip(offset);
                   if (skipped != offset) { throw new IOException("Failed to skip to offset " + offset + " for resend (skipped " + skipped + ")"); }

                   byte[] chunkBuffer = new byte[bytesToRead];
                   int bytesActuallyRead = tempStream.read(chunkBuffer);
                   if (bytesActuallyRead != bytesToRead) { throw new IOException("Mismatch re-reading chunk " + currentSeq + " (read " + bytesActuallyRead + " != expected " + bytesToRead + ")"); }
                   actualChunk = chunkBuffer;
              } catch (IOException e) {
                   System.err.println("[!] Failed to reopen/read stream for resend: " + e.getMessage());
                   failTransfer(senderState, "Stream reposition/read failed for resend");
                   return;
              }

              // Base64 encode and encrypt
              String chunkBase64 = Base64.getEncoder().encodeToString(actualChunk);
              CryptoUtils.EncryptedPayload encryptedPayload = CryptoUtils.encrypt(chunkBase64, sharedKey);

              JSONObject dataMsg = new JSONObject();
              dataMsg.put("action", "file_data");
              dataMsg.put("node_id", context.myNodeId.get());
              dataMsg.put("transfer_id", senderState.transferId);
              dataMsg.put("seq_num", currentSeq); // Send the same sequence number
              dataMsg.put("iv", encryptedPayload.ivBase64);
              dataMsg.put("e_payload", encryptedPayload.ciphertextBase64);
              dataMsg.put("is_last", isLast);

              boolean sent = networkManager.sendUdp(dataMsg, peerAddr);
              if (sent) {
                  senderState.lastPacketSentTime.set(System.currentTimeMillis()); // Update sent time
                  System.out.println("[*] Resent chunk " + currentSeq + " for transfer " + senderState.transferId.substring(0,4) + "...");
                  // No GUI progress update needed unless showing retry count specifically
              } else {
                  System.err.println("[!] Failed to resend chunk " + currentSeq + " for transfer " + senderState.transferId.substring(0,4));
                  senderState.lastPacketSentTime.set(0); // Ensure timeout still active
              }

          } catch (GeneralSecurityException e) { failTransfer(senderState, "Error during chunk resend encryption (chunk " + currentSeq + ")");
          } catch (Exception e) { // Catch broader exceptions during resend logic
               System.err.println("[!] Unexpected Error during attempt to resend chunk " + currentSeq + ": " + e.getMessage());
               e.printStackTrace();
               failTransfer(senderState, "Unexpected error during chunk resend");
          }
     }

     // Fails a transfer and cleans up resources, notifying GUI
     public void failTransfer(FileTransferState state, String reason) {
          if (state == null || state.isTerminated()) return; // Already finished or null state

          String failMsg = String.format("File transfer FAILED: '%s' [ID: %s]. Reason: %s",
                                         state.filename, state.transferId.substring(0,8), reason);
          System.out.println("\n[!] " + failMsg);

          // Log event before changing state/removing
          String direction = state.isSender ? "SENT" : "RECEIVED";
          if (state.peerNodeId != null) {
               logTransferEvent(state, direction, "[File Transfer] FAILED " + (state.isSender ? "sending" : "receiving") + " '" + state.filename + "'. Reason: " + reason);
          }

          state.status = FileTransferState.Status.FAILED;

          // <<< Notify GUI: Transfer finished (failed) >>>
          if (guiCallback != null) {
              guiCallback.transferFinished(state.transferId, "Failed: " + reason);
          }

          // Cleanup
          state.closeStreams();
          context.ongoingTransfers.remove(state.transferId); // Remove failed transfer from map
     }

     // Helper method to log transfer events to chat history
     private void logTransferEvent(FileTransferState state, String direction, String message) {
         if (state == null || state.peerNodeId == null) return; // Basic validation

         if (context.chatHistoryManager != null) {
             long timestamp = System.currentTimeMillis();
             String peerUsername = context.getPeerDisplayName(); // Use context helper for best name
             // Ensure peerUsername isn't null if possible, fallback needed if context disconnected
             if (peerUsername.equals("???") || peerUsername.equals("N/A")) {
                  peerUsername = state.peerNodeId.substring(0, Math.min(8, state.peerNodeId.length())) + "...";
             }

             context.chatHistoryManager.addMessage(
                     timestamp,
                     state.peerNodeId,
                     peerUsername,
                     direction, // Should be "SYSTEM" or similar for file events? Using SENT/RECEIVED for now.
                     message
             );
         }
     }
}