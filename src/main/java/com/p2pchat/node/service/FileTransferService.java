package main.java.com.p2pchat.node.service;

import main.java.com.p2pchat.common.CryptoUtils;
import main.java.com.p2pchat.node.config.NodeConfig;
import main.java.com.p2pchat.node.model.FileTransferState;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.network.NetworkManager;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FileTransferService {

    private final NodeContext context;
    private final NetworkManager networkManager;
    private final ScheduledExecutorService timeoutExecutor;

    public FileTransferService(NodeContext context, NetworkManager networkManager) {
        this.context = context;
        this.networkManager = networkManager;
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FileTransferTimeoutThread");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        // Schedule the task to check for timeouts periodically
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
        // Cancel any remaining active transfers - Handled by P2PNode.shutdown calling context cleanup
    }

    // Called by UI to initiate sending a file
    public void initiateTransfer(String filePathStr) {
        if (context.currentState.get() != NodeState.CONNECTED_SECURE) {
            System.out.println("\n[!] You must be connected to a peer to send a file.");
            context.redrawPrompt.set(true);
            return;
        }
        String peerId = context.connectedPeerId.get();
        InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
        if (peerId == null || peerAddr == null) {
            System.out.println("\n[!] Connection state invalid, cannot send file.");
            context.redrawPrompt.set(true);
            return;
        }

        try {
            Path filePath = Paths.get(filePathStr);
            if (!Files.exists(filePath) || !Files.isReadable(filePath) || Files.isDirectory(filePath)) {
                System.out.println("\n[!] File not found or cannot be read: " + filePathStr);
                context.redrawPrompt.set(true);
                return;
            }

            long fileSize = Files.size(filePath);
            if (fileSize == 0) {
                 System.out.println("\n[!] Cannot send empty file.");
                 context.redrawPrompt.set(true);
                 return;
            }
            if (fileSize > NodeConfig.MAX_FILE_SIZE_BYTES) {
                System.out.println("\n[!] File exceeds size limit (" + (NodeConfig.MAX_FILE_SIZE_BYTES / (1024.0 * 1024.0)) + " MiB): " + filePath.getFileName());
                context.redrawPrompt.set(true);
                return;
            }

            String transferId = UUID.randomUUID().toString();
            String filename = filePath.getFileName().toString();

            FileTransferState state = new FileTransferState(transferId, filename, fileSize, peerId, true);
            state.sourcePath = filePath;
            state.status = FileTransferState.Status.OFFER_SENT; // Mark as offer sent initially

            // Store state before sending offer
            context.ongoingTransfers.put(transferId, state);

            JSONObject offerMsg = new JSONObject();
            offerMsg.put("action", "file_offer");
            offerMsg.put("node_id", context.myNodeId.get());
            offerMsg.put("transfer_id", transferId);
            offerMsg.put("filename", filename);
            offerMsg.put("filesize", fileSize);

            System.out.println("\n[*] Sending file offer to " + context.getPeerDisplayName() + " for '" + filename + "' (" + fileSize + " bytes) [ID: " + transferId + "]");
            boolean sent = networkManager.sendUdp(offerMsg, peerAddr);

            if (!sent) {
                System.out.println("[!] Failed to send file offer.");
                state.status = FileTransferState.Status.FAILED;
                // ---> ADD LOGGING FOR OFFER FAIL <---
                logTransferEvent(state, "SENT", "[File Transfer] FAILED sending offer for '" + filename + "'");
                // ---> END LOGGING <---
                context.ongoingTransfers.remove(transferId); // Clean up immediately if offer fails
            } else {
                 state.status = FileTransferState.Status.AWAITING_ACCEPT; // Update status after successful send
                 // ---> ADD LOGGING <---
                 logTransferEvent(state, "SENT", "[File Transfer] Offered file '" + filename + "' (" + fileSize + " bytes)");
                 // ---> END LOGGING <---
            }
            context.redrawPrompt.set(true);

        } catch (IOException e) {
            System.err.println("\n[!] Error accessing file: " + e.getMessage());
            context.redrawPrompt.set(true);
        } catch (Exception e) {
            System.err.println("\n[!] Unexpected error initiating transfer: " + e.getMessage());
            context.redrawPrompt.set(true);
            e.printStackTrace();
        }
    }

    // Called by PeerMessageHandler when a file_offer is received
    public void handleIncomingOffer(JSONObject data, InetSocketAddress peerAddr) {
        String transferId = data.optString("transfer_id", null);
        String filename = data.optString("filename", null);
        long filesize = data.optLong("filesize", -1);
        String senderId = data.optString("node_id", null);

        String connectedPeerId = context.connectedPeerId.get();
        InetSocketAddress confirmedAddr = context.peerAddrConfirmed.get();

        // Basic validation
        if (transferId == null || filename == null || filesize <= 0 || senderId == null) {
            System.err.println("\n[!] Received invalid file offer (missing fields).");
            context.redrawPrompt.set(true);
            return;
        }
        // Ensure offer is from the currently connected peer
        if (!senderId.equals(connectedPeerId) || !peerAddr.equals(confirmedAddr)) {
            System.err.println("\n[!] Received file offer from unexpected peer/address. Ignoring.");
            context.redrawPrompt.set(true);
            return;
        }
         // Check if transfer ID already exists (shouldn't happen often)
         if (context.ongoingTransfers.containsKey(transferId)) {
              System.err.println("\n[!] Received duplicate file offer ID: " + transferId + ". Ignoring.");
              context.redrawPrompt.set(true);
              return;
         }
         if (filesize > NodeConfig.MAX_FILE_SIZE_BYTES) {
             System.out.println("\n[!] Received file offer for '" + filename + "' but it exceeds size limit (" + String.format("%.2f", filesize / (1024*1024.0)) + " MiB). Auto-rejecting.");
             // Don't create state, just send reject back directly? Need transferId though.
             // Create minimal state just to reject properly.
             FileTransferState tempState = new FileTransferState(transferId, filename, filesize, senderId, false);
             respondToOffer(transferId, false, "File size limit exceeded", tempState); // Auto-reject using temp state
             context.redrawPrompt.set(true);
             return;
         }


        FileTransferState state = new FileTransferState(transferId, filename, filesize, senderId, false);
        state.status = FileTransferState.Status.OFFER_RECEIVED;
        context.ongoingTransfers.put(transferId, state);

        // Notify UI (This part needs CommandLineInterface to handle it)
        System.out.println("\n-----------------------------------------------------");
        System.out.println("[*] Incoming file offer from " + context.getPeerDisplayName() + ":");
        System.out.println("    File: '" + filename + "'");
        System.out.println("    Size: " + filesize + " bytes");
        System.out.println("    Transfer ID: " + transferId);
        System.out.println("    To accept, type: accept " + transferId);
        System.out.println("    To reject, type: reject " + transferId);
        System.out.println("-----------------------------------------------------");
        context.redrawPrompt.set(true); // Signal UI to redraw prompt
    }

     // Called by UI when user types 'accept' or 'reject'
     public void respondToOffer(String transferId, boolean accepted) {
         FileTransferState state = context.ongoingTransfers.get(transferId);
         if (state == null) {
             System.out.println("\n[!] No pending file offer found with ID: " + transferId);
             context.redrawPrompt.set(true);
             return;
         }
         respondToOffer(transferId, accepted, null, state); // Overload without reason
     }

    // Internal method with optional reason and provided state
    private void respondToOffer(String transferId, boolean accepted, String reason, FileTransferState state) {
        if (state.isSender || state.status != FileTransferState.Status.OFFER_RECEIVED) {
            // Should not happen if called correctly, but check anyway
            if (state.status != FileTransferState.Status.FAILED) { // Avoid duplicate msg if already failed
                System.out.println("\n[!] Cannot respond to offer " + transferId + " (Invalid state: " + state.status + ")");
                context.redrawPrompt.set(true);
            }
            return;
        }

        String peerId = state.peerNodeId;
        InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
        // Double check peer connection state hasn't changed
        if (!peerId.equals(context.connectedPeerId.get()) || peerAddr == null) {
             System.out.println("\n[!] Peer connection lost or changed, cannot respond to offer " + transferId);
             failTransfer(state, "Connection lost before response"); // Use failTransfer to handle cleanup/logging
             return;
        }

        JSONObject responseMsg = new JSONObject();
        responseMsg.put("node_id", context.myNodeId.get());
        responseMsg.put("transfer_id", transferId);

        if (accepted) {
            responseMsg.put("action", "file_accept");
            state.status = FileTransferState.Status.TRANSFERRING_RECV; // Prepare to receive
            // Prepare file path and output stream
            if (!prepareDownloadFile(state)) {
                 // failTransfer will log and clean up
                 failTransfer(state, "Failed to create local file");
                 // Send reject instead
                 respondToOffer(transferId, false, "Failed to create local file", state); // Resend as reject
                 return;
            }
            System.out.println("\n[*] Accepting file transfer '" + state.filename + "' [ID: " + transferId + "]. Receiving...");
            // ---> ADD LOGGING <---
            logTransferEvent(state, "RECEIVED", "[File Transfer] Accepted file '" + state.filename + "'");
            // ---> END LOGGING <---
        } else {
            responseMsg.put("action", "file_reject");
            if (reason != null && !reason.isEmpty()) {
                 responseMsg.put("reason", reason);
            }
            state.status = FileTransferState.Status.REJECTED; // Mark as rejected locally
            System.out.println("\n[*] Rejecting file transfer '" + state.filename + "' [ID: " + transferId + "].");
            // ---> ADD LOGGING <---
            logTransferEvent(state, "RECEIVED", "[File Transfer] Rejected file '" + state.filename + "'" + (reason != null ? ". Reason: " + reason : ""));
            // ---> END LOGGING <---
            // Clean up receiver state immediately on rejection
            state.closeStreams();
            context.ongoingTransfers.remove(transferId);
        }

        boolean sent = networkManager.sendUdp(responseMsg, peerAddr);
        if (!sent) {
             System.out.println("[!] Failed to send response for transfer " + transferId);
             // If sending fails, revert state and clean up
             // Check if we were accepting, if so, need to fail it properly
             if (accepted && state.status != FileTransferState.Status.FAILED) {
                 failTransfer(state, "Failed to send accept response");
             } else {
                 // If rejecting and send fails, it was already removed/logged, do nothing more
             }
        }
        context.redrawPrompt.set(true);
    }

     // Helper to create downloads dir and prepare output stream
     private boolean prepareDownloadFile(FileTransferState state) {
          try {
               Path downloadDir = Paths.get(NodeConfig.DOWNLOADS_DIR);
               if (!Files.exists(downloadDir)) {
                   Files.createDirectories(downloadDir);
                   System.out.println("[*] Created downloads directory: " + downloadDir.toAbsolutePath());
               }

               // Sanitize filename slightly (basic example, could be more robust)
               String sanitizedFilename = state.filename.replaceAll("[^a-zA-Z0-9._-]", "_");
               if (sanitizedFilename.isEmpty() || sanitizedFilename.equals(".") || sanitizedFilename.equals("..")) {
                    sanitizedFilename = "downloaded_file"; // Fallback for invalid names
               }
               Path filePath = downloadDir.resolve(sanitizedFilename);

               // Handle potential name collisions simply by appending a number
               int count = 0;
               String baseName = sanitizedFilename;
               String extension = "";
               int dotIndex = sanitizedFilename.lastIndexOf('.');
               if (dotIndex > 0 && dotIndex < sanitizedFilename.length() - 1) {
                   baseName = sanitizedFilename.substring(0, dotIndex);
                   extension = sanitizedFilename.substring(dotIndex);
               } else if (dotIndex == 0) { // Handle filenames starting with "."
                   extension = sanitizedFilename;
                   baseName = "file";
               }

               while(Files.exists(filePath)) {
                    count++;
                    filePath = downloadDir.resolve(baseName + "_" + count + extension);
                    if (count > 999) { // Safety break
                         System.err.println("[!] Too many file collisions for: " + sanitizedFilename);
                         return false;
                    }
               }

               state.downloadPath = filePath;
               // Open stream using CREATE_NEW to prevent overwriting concurrently created files
               state.outputStream = new BufferedOutputStream(Files.newOutputStream(filePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
               System.out.println("[*] Receiving file to: " + filePath.toAbsolutePath());
               return true;
          } catch (IOException e) {
               System.err.println("\n[!] Error preparing download file '" + state.filename + "': " + e.getMessage());
               context.redrawPrompt.set(true);
               return false;
          } catch (Exception e) { // Catch other potential errors like security exceptions
                System.err.println("\n[!] Unexpected error preparing download file '" + state.filename + "': " + e.getMessage());
                context.redrawPrompt.set(true);
                return false;
          }
     }


    // Called by PeerMessageHandler when a file_accept is received
    public void handleFileAccept(JSONObject data) {
        String transferId = data.optString("transfer_id", null);
        String receiverId = data.optString("node_id", null);
        FileTransferState state = context.ongoingTransfers.get(transferId);

        if (state == null || !state.isSender || state.status != FileTransferState.Status.AWAITING_ACCEPT) {
            // Ignore unexpected accept message
            return;
        }
        // Verify it's from the correct peer
        if (!state.peerNodeId.equals(receiverId)) {
             return;
        }

        System.out.println("\n[*] Peer accepted file transfer '" + state.filename + "' [ID: " + transferId + "]. Starting send...");
        state.status = FileTransferState.Status.TRANSFERRING_SEND;
        context.redrawPrompt.set(true);
        // ---> ADD LOGGING <---
        logTransferEvent(state, "SENT", "[File Transfer] Peer accepted file '" + state.filename + "'");
        // ---> END LOGGING <---

        // Open input stream
        try {
             // Ensure previous stream is closed if somehow open (shouldn't be)
             state.closeStreams();
             state.inputStream = new BufferedInputStream(Files.newInputStream(state.sourcePath));
        } catch (IOException e) {
             System.err.println("[!] Failed to open file for reading: " + state.sourcePath + " - " + e.getMessage());
             failTransfer(state, "Failed to open file");
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

        if (state == null || !state.isSender || state.isTerminated()) {
            // Ignore if transfer doesn't exist or already finished/failed/cancelled
            return;
        }
         // Verify it's from the correct peer
        if (!state.peerNodeId.equals(receiverId)) {
             return;
        }

        System.out.println("\n[!] Peer rejected file transfer '" + state.filename + "' [ID: " + transferId + "]. Reason: " + reason);
        // ---> ADD LOGGING <---
        // Log before failing, as failTransfer removes the state
        logTransferEvent(state, "SENT", "[File Transfer] Peer rejected file '" + state.filename + "'. Reason: " + reason);
        // ---> END LOGGING <---
        failTransfer(state, "Peer rejected: " + reason); // Use failTransfer for cleanup
    }

    // Called by PeerMessageHandler when a file_data chunk is received
    public void handleFileData(JSONObject data) {
        String transferId = data.optString("transfer_id", null);
        FileTransferState state = context.ongoingTransfers.get(transferId);

        if (state == null || state.isSender || state.status != FileTransferState.Status.TRANSFERRING_RECV) {
             // Ignore if transfer not found, we are sender, or not expecting data
            return;
        }

        int seqNum = data.optInt("seq_num", -1);
        String ivBase64 = data.optString("iv", null);
        String encryptedPayloadBase64 = data.optString("e_payload", null);
        boolean isLast = data.optBoolean("is_last", false);

        // Validate received data
        if (seqNum < 0 || ivBase64 == null || encryptedPayloadBase64 == null) {
            System.err.println("[!] Received invalid file_data packet for transfer " + transferId + " (missing fields).");
            return;
        }
        // Verify sender node ID matches peer
         String senderId = data.optString("node_id", null);
         if(!state.peerNodeId.equals(senderId)) {
              System.err.println("[!] Received file_data packet from unexpected sender for transfer " + transferId);
              return;
         }
         if (state.outputStream == null) {
              System.err.println("[!] Output stream is null, cannot write received data for transfer " + transferId);
              failTransfer(state, "Output stream missing");
              return;
         }


        // Check sequence number
        if (seqNum == state.expectedSeqNum.get()) {
            // Expected chunk
            SecretKey sharedKey = context.peerSymmetricKeys.get(state.peerNodeId);
            if (sharedKey == null) {
                System.err.println("[!] CRITICAL: No shared key found for peer " + state.peerNodeId + ". Cannot decrypt file data.");
                failTransfer(state, "Missing decryption key");
                return;
            }

            try {
                CryptoUtils.EncryptedPayload encryptedPayload = new CryptoUtils.EncryptedPayload(ivBase64, encryptedPayloadBase64);
                String decryptedChunkStr = CryptoUtils.decrypt(encryptedPayload, sharedKey); // Assuming decrypt returns String
                byte[] decryptedChunk = Base64.getDecoder().decode(decryptedChunkStr); // Decode base64 chunk

                // Write to file
                state.outputStream.write(decryptedChunk);
                state.transferredBytes.addAndGet(decryptedChunk.length);

                // Send ACK
                sendAck(state, seqNum);

                // Increment expected sequence number
                state.expectedSeqNum.incrementAndGet();

                // Optional: Print progress more frequently
                if (seqNum % 50 == 0 && seqNum > 0) { // Print every 50 chunks
                     double percent = (state.filesize > 0) ? (100.0 * state.transferredBytes.get() / state.filesize) : 0.0;
                     System.out.printf("[FileTransfer:%s] Receiving... %.1f%%%n", transferId.substring(0,4), percent);
                     context.redrawPrompt.set(true);
                }

                // Handle completion
                if (isLast) {
                     state.outputStream.flush(); // Ensure all data is written
                     state.closeStreams(); // Close the file stream
                     state.status = FileTransferState.Status.COMPLETED;
                     System.out.println("\n[*] File transfer COMPLETED: '" + state.filename + "' [ID: " + transferId + "] received successfully.");
                     System.out.println("    Saved to: " + state.downloadPath);
                     // ---> ADD LOGGING <---
                     logTransferEvent(state, "RECEIVED", "[File Transfer] Completed receiving '" + state.filename + "'");
                     // ---> END LOGGING <---
                     context.ongoingTransfers.remove(transferId); // Remove completed transfer state
                     context.redrawPrompt.set(true);
                }

            } catch (GeneralSecurityException e) {
                System.err.println("\n[!] Decryption failed for chunk " + seqNum + " of transfer " + transferId + ": " + e.getMessage());
                failTransfer(state, "Decryption failed");
            } catch (IOException e) {
                System.err.println("\n[!] Error writing file chunk " + seqNum + " for transfer " + transferId + ": " + e.getMessage());
                failTransfer(state, "File write error");
            } catch (IllegalArgumentException e) {
                 System.err.println("\n[!] Error decoding Base64 file chunk " + seqNum + " for transfer " + transferId + ": " + e.getMessage());
                 failTransfer(state, "Data decoding error");
            }

        } else if (seqNum < state.expectedSeqNum.get()) {
            // Duplicate chunk, resend ACK for that chunk
             // System.out.println("[?] Received duplicate chunk " + seqNum + " for transfer " + transferId + ". Resending ACK.");
             sendAck(state, seqNum);
        } else {
            // Unexpected future chunk, ignore for now in stop-and-wait
            // System.out.println("[?] Received out-of-order chunk " + seqNum + " (expected " + state.expectedSeqNum.get() + ") for transfer " + transferId + ". Ignoring.");
        }
    }

    // Send acknowledgment for a received chunk
    private void sendAck(FileTransferState receiverState, int ackSeqNum) {
        InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
        if (peerAddr == null || !receiverState.peerNodeId.equals(context.connectedPeerId.get())) {
             // Connection lost or changed peer
             if (!receiverState.isTerminated()) { // Avoid failing already completed/failed transfers
                 failTransfer(receiverState, "Connection lost before sending ACK");
             }
             return;
        }

        JSONObject ackMsg = new JSONObject();
        ackMsg.put("action", "file_ack");
        ackMsg.put("node_id", context.myNodeId.get());
        ackMsg.put("transfer_id", receiverState.transferId);
        ackMsg.put("ack_seq_num", ackSeqNum);

        networkManager.sendUdp(ackMsg, peerAddr); // Send ACK (fire and forget for ACK itself)
    }


    // Called by PeerMessageHandler when a file_ack is received
    public void handleFileAck(JSONObject data) {
        String transferId = data.optString("transfer_id", null);
        int ackSeqNum = data.optInt("ack_seq_num", -1);
        String ackingPeerId = data.optString("node_id", null);

        FileTransferState state = context.ongoingTransfers.get(transferId);

        if (state == null || !state.isSender || state.status != FileTransferState.Status.TRANSFERRING_SEND) {
            // Ignore if not sending this transfer
            return;
        }
         // Verify ACK is from the correct peer
         if (!state.peerNodeId.equals(ackingPeerId)) {
              return;
         }


        // Check if this ACK is for the chunk we are waiting for
        if (ackSeqNum == state.currentSeqNum.get()) {
             // Correct ACK received
             state.lastAckReceivedSeqNum.set(ackSeqNum);
             state.retryCount.set(0); // Reset retry count on successful ACK
             state.currentSeqNum.incrementAndGet(); // Move to next sequence number

             // Check if transfer is complete (using transferred bytes vs filesize)
             // We check this *before* sending the next chunk
             if (state.transferredBytes.get() >= state.filesize) {
                  state.status = FileTransferState.Status.COMPLETED;
                  state.closeStreams();
                  System.out.println("\n[*] File transfer COMPLETED: '" + state.filename + "' [ID: " + transferId + "] sent successfully.");
                  // ---> ADD LOGGING <---
                  logTransferEvent(state, "SENT", "[File Transfer] Completed sending '" + state.filename + "'");
                  // ---> END LOGGING <---
                  context.ongoingTransfers.remove(transferId); // Remove completed transfer state
                  context.redrawPrompt.set(true);
             } else {
                  // Send the next chunk
                  sendNextChunk(state);
             }
        } else if (ackSeqNum > state.currentSeqNum.get()) {
             // Received ACK for a future packet? Might happen with reordering/loss.
             // Simple stop-and-wait ignores this for now. Could potentially fast-forward.
             // System.out.println("[?] Received unexpected future ACK " + ackSeqNum + " (Current: " + state.currentSeqNum.get() + ")");
        } else {
             // Duplicate or old ACK, ignore
             // System.out.println("[?] Received duplicate/old ACK " + ackSeqNum + " for transfer " + transferId);
        }
    }

    // Sends the next chunk of the file for the given transfer state
    private void sendNextChunk(FileTransferState senderState) {
         if (senderState.status != FileTransferState.Status.TRANSFERRING_SEND) {
              return; // Not in a state to send
         }

         InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
         SecretKey sharedKey = context.peerSymmetricKeys.get(senderState.peerNodeId);

        if (peerAddr == null || !senderState.peerNodeId.equals(context.connectedPeerId.get())) {
             failTransfer(senderState, "Connection lost before sending chunk " + senderState.currentSeqNum.get());
             return;
        }
         if (sharedKey == null) {
              System.err.println("[!] CRITICAL: No shared key found for peer " + senderState.peerNodeId + ". Cannot encrypt file data.");
              failTransfer(senderState, "Missing encryption key");
              return;
         }
         if (senderState.inputStream == null) {
              System.err.println("[!] CRITICAL: Input stream is null for sending transfer " + senderState.transferId);
              failTransfer(senderState, "File input stream lost");
              return;
         }


        try {
            byte[] chunk = new byte[NodeConfig.FILE_CHUNK_SIZE];
            int bytesRead = senderState.inputStream.read(chunk);

            if (bytesRead == -1) {
                 // We've read the whole file according to the stream
                 // Check if transferred bytes match expected size. If not, something is wrong.
                 if (senderState.transferredBytes.get() < senderState.filesize) {
                      System.err.println("[!] Unexpected end of file reached for transfer " + senderState.transferId + " but expected more bytes.");
                      failTransfer(senderState, "Premature EOF");
                 } else {
                      // This means we sent the last chunk but are somehow asked to send more. Should not happen if completion logic is correct.
                       System.err.println("[!] Stream ended but file size seems complete for transfer " + senderState.transferId + ". Assuming complete.");
                       // Mark as complete just in case, although final ACK is the real trigger
                       if(senderState.status != FileTransferState.Status.COMPLETED) {
                           senderState.status = FileTransferState.Status.COMPLETED;
                           senderState.closeStreams();
                           context.ongoingTransfers.remove(senderState.transferId);
                           context.redrawPrompt.set(true);
                       }
                 }
                 return;
            }

            byte[] actualChunk;
            boolean isLast = (senderState.transferredBytes.get() + bytesRead >= senderState.filesize);
            if (bytesRead < NodeConfig.FILE_CHUNK_SIZE) {
                 // This is the last chunk (or the only chunk)
                 actualChunk = new byte[bytesRead];
                 System.arraycopy(chunk, 0, actualChunk, 0, bytesRead);
                 if (!isLast) {
                     // File size mismatch or calculation error?
                     System.err.println("[!] Read less than chunk size but not calculated as last chunk for " + senderState.transferId + ". Marking as last.");
                     isLast = true;
                 }
            } else {
                 actualChunk = chunk;
            }

            // Base64 encode the raw chunk *before* encryption, as encryption handles bytes directly
            String chunkBase64 = Base64.getEncoder().encodeToString(actualChunk);

            // Encrypt the Base64 encoded string
            CryptoUtils.EncryptedPayload encryptedPayload = CryptoUtils.encrypt(chunkBase64, sharedKey);

            JSONObject dataMsg = new JSONObject();
            dataMsg.put("action", "file_data");
            dataMsg.put("node_id", context.myNodeId.get());
            dataMsg.put("transfer_id", senderState.transferId);
            dataMsg.put("seq_num", senderState.currentSeqNum.get());
            dataMsg.put("iv", encryptedPayload.ivBase64);
            dataMsg.put("e_payload", encryptedPayload.ciphertextBase64);
            dataMsg.put("is_last", isLast);

            boolean sent = networkManager.sendUdp(dataMsg, peerAddr);
            if (sent) {
                senderState.lastPacketSentTime.set(System.currentTimeMillis()); // Record time for timeout check
                // Update transferredBytes *after* successful send attempt (potential retry needs this amount)
                // But the actual increment towards filesize happens on ACK
                // Let's track *attempted* sent bytes separately? No, keep it simple.
                // Update based on this attempt, ACK confirms it for the next step.
                senderState.transferredBytes.addAndGet(actualChunk.length);

                // Optional: Print progress
                 long totalChunks = (long) Math.ceil((double) senderState.filesize / NodeConfig.FILE_CHUNK_SIZE);
                 // Avoid division by zero if filesize is 0 (checked earlier but good practice)
                 totalChunks = Math.max(1, totalChunks);
                 System.out.printf("[FileTransfer:%s] Sent chunk %d/%d%n",
                                    senderState.transferId.substring(0,4),
                                    senderState.currentSeqNum.get(),
                                    totalChunks - 1); // Seq num is 0-based
                 context.redrawPrompt.set(true);


            } else {
                System.err.println("[!] Failed to send chunk " + senderState.currentSeqNum.get() + " for transfer " + senderState.transferId);
                // Let timeout handler deal with retries/failure
                senderState.lastPacketSentTime.set(0); // Ensure timeout triggers if send fails immediately
            }

            if (isLast && sent) {
                 // If last chunk sent successfully, don't close stream yet, wait for final ACK
                 System.out.println("[*] Sent final chunk (" + senderState.currentSeqNum.get() + ") for transfer " + senderState.transferId + ". Waiting for final ACK.");
            }

        } catch (IOException e) {
            System.err.println("[!] Error reading file chunk for transfer " + senderState.transferId + ": " + e.getMessage());
            failTransfer(senderState, "File read error");
        } catch (GeneralSecurityException e) {
            System.err.println("[!] Error encrypting file chunk " + senderState.currentSeqNum.get() + " for transfer " + senderState.transferId + ": " + e.getMessage());
            failTransfer(senderState, "Encryption error");
        }
    }

     // Periodically checks for timed out chunks for sending transfers
     private void checkTimeouts() {
         if (context.ongoingTransfers.isEmpty()) return;

         long now = System.currentTimeMillis();
         context.ongoingTransfers.forEach((id, state) -> {
             // Check only transfers where we are the sender and currently transferring
             if (state.isSender && state.status == FileTransferState.Status.TRANSFERRING_SEND) {
                 // Check if we are waiting for an ACK (current seq num > last ack received)
                 if (state.currentSeqNum.get() > state.lastAckReceivedSeqNum.get()) {
                     long lastSent = state.lastPacketSentTime.get();
                     if (lastSent > 0 && (now - lastSent > NodeConfig.FILE_ACK_TIMEOUT_MS)) {
                         // Timeout occurred!
                         if (state.retryCount.incrementAndGet() <= NodeConfig.FILE_MAX_RETRIES) {
                              System.out.println("\n[!] Timeout waiting for ACK for chunk " + state.currentSeqNum.get()
                                                 + " (Transfer " + id.substring(0,4) + "). Retrying ("
                                                 + state.retryCount.get() + "/" + NodeConfig.FILE_MAX_RETRIES + ")...");
                              context.redrawPrompt.set(true);
                              // Resend the *same* chunk
                              resendChunk(state);
                         } else {
                              System.out.println("\n[!] Max retries exceeded for chunk " + state.currentSeqNum.get()
                                                 + " (Transfer " + id.substring(0,4) + "). Failing transfer.");
                              context.redrawPrompt.set(true);
                              failTransfer(state, "Timeout waiting for ACK");
                         }
                     }
                 } else {
                      // We have received ACK for the last sent packet, reset timer logic state if needed
                      state.lastPacketSentTime.set(0); // Ensure timeout doesn't trigger erroneously if processing next chunk is delayed
                 }
             }
         });
     }

     // Helper to resend the current chunk
     // NOTE: This implementation relies on being able to re-read the chunk from the input stream.
     // This requires the stream to be reset or the file to be reopened and seeked.
     // For simplicity, we attempt to reset the stream if supported. Otherwise, this might fail.
     private void resendChunk(FileTransferState senderState) {
          InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
          SecretKey sharedKey = context.peerSymmetricKeys.get(senderState.peerNodeId);

          if (peerAddr == null || !senderState.peerNodeId.equals(context.connectedPeerId.get())) {
               failTransfer(senderState, "Connection lost before resending chunk " + senderState.currentSeqNum.get());
               return;
          }
          if (sharedKey == null || senderState.inputStream == null) {
               failTransfer(senderState, "Missing key or stream for resend");
               return;
          }

          try {
              // --- Attempt to re-read the specific chunk ---
              byte[] actualChunk = null;
              boolean isLast = false;
              long offset = (long)senderState.currentSeqNum.get() * NodeConfig.FILE_CHUNK_SIZE;
              int bytesToRead = NodeConfig.FILE_CHUNK_SIZE;
              long remainingBytes = senderState.filesize - offset;

              if (remainingBytes <= 0) {
                   System.err.println("[!] Resend requested for chunk " + senderState.currentSeqNum.get() + " but file size indicates completion.");
                   // This might happen if the last ACK was lost. Consider if we should just assume complete or fail.
                   // Let's try sending one more 'last packet' if it matches the size calculation? Risky.
                   // For now, fail defensively.
                   failTransfer(senderState, "Resend state mismatch with file size");
                   return;
              }

              if (remainingBytes < bytesToRead) {
                  bytesToRead = (int) remainingBytes; // Read only the remaining bytes
                  isLast = true;
              }

              // Re-reading strategy: Close and reopen the stream, then skip bytes. Crude but avoids mark/reset issues.
              // TODO: A more efficient approach would use FileChannel for seeking.
              try {
                   senderState.inputStream.close(); // Close current stream
                   senderState.inputStream = new BufferedInputStream(Files.newInputStream(senderState.sourcePath)); // Reopen
                   long skipped = senderState.inputStream.skip(offset); // Skip to the required offset
                   if (skipped != offset) {
                        throw new IOException("Failed to skip to correct offset for resend.");
                   }
              } catch (IOException e) {
                   System.err.println("[!] Failed to reposition stream for resend: " + e.getMessage());
                   failTransfer(senderState, "Stream reposition failed for resend");
                   return;
              }

              byte[] chunk = new byte[bytesToRead];
              int bytesRead = senderState.inputStream.read(chunk);

              if (bytesRead != bytesToRead) {
                   System.err.println("[!] Mismatch reading chunk for resend (" + bytesRead + " != " + bytesToRead + ")");
                   failTransfer(senderState, "Chunk re-read error");
                   return;
              }
              actualChunk = chunk;
              // --- End re-read attempt ---

              if (actualChunk == null) { // Safety check if re-read failed silently
                  throw new IOException("Failed to obtain chunk data for resend.");
              }

              // Base64 encode and encrypt
              String chunkBase64 = Base64.getEncoder().encodeToString(actualChunk);
              CryptoUtils.EncryptedPayload encryptedPayload = CryptoUtils.encrypt(chunkBase64, sharedKey);

              JSONObject dataMsg = new JSONObject();
              dataMsg.put("action", "file_data");
              dataMsg.put("node_id", context.myNodeId.get());
              dataMsg.put("transfer_id", senderState.transferId);
              dataMsg.put("seq_num", senderState.currentSeqNum.get());
              dataMsg.put("iv", encryptedPayload.ivBase64);
              dataMsg.put("e_payload", encryptedPayload.ciphertextBase64);
              dataMsg.put("is_last", isLast);

              boolean sent = networkManager.sendUdp(dataMsg, peerAddr);
              if (sent) {
                  senderState.lastPacketSentTime.set(System.currentTimeMillis()); // Update sent time for next timeout check
                  System.out.println("[*] Resent chunk " + senderState.currentSeqNum.get() + " for transfer " + senderState.transferId.substring(0,4) + "...");
                  context.redrawPrompt.set(true);
              } else {
                  System.err.println("[!] Failed to resend chunk " + senderState.currentSeqNum.get() + " for transfer " + senderState.transferId);
                  // Allow timeout mechanism to eventually fail the transfer after max retries
                  senderState.lastPacketSentTime.set(0); // Ensure timeout still active
              }

          } catch (IOException e) {
               System.err.println("[!] IO Error during attempt to resend chunk " + senderState.currentSeqNum.get() + ": " + e.getMessage());
               failTransfer(senderState, "Error during chunk resend IO");
          } catch (GeneralSecurityException e) {
              System.err.println("[!] Encryption Error during attempt to resend chunk " + senderState.currentSeqNum.get() + ": " + e.getMessage());
              failTransfer(senderState, "Error during chunk resend encryption");
          } catch (Exception e) {
              System.err.println("[!] Unexpected Error during attempt to resend chunk " + senderState.currentSeqNum.get() + ": " + e.getMessage());
              e.printStackTrace();
              failTransfer(senderState, "Unexpected error during chunk resend");
          }
     }

     // Fails a transfer and cleans up resources
     public void failTransfer(FileTransferState state, String reason) {
          if (state.isTerminated()) return; // Already finished

          System.out.println("\n[!] File transfer FAILED: '" + state.filename + "' [ID: " + state.transferId + "]. Reason: " + reason);
          // ---> ADD LOGGING <---
          String direction = state.isSender ? "SENT" : "RECEIVED";
          String logReason = reason != null ? reason : "Unknown";
          // Ensure state is not null and peerNodeId is available before logging
          if (state != null && state.peerNodeId != null) {
               logTransferEvent(state, direction, "[File Transfer] FAILED " + (state.isSender ? "sending" : "receiving") + " '" + state.filename + "'. Reason: " + logReason);
          }
          // ---> END LOGGING <---

          state.status = FileTransferState.Status.FAILED;
          state.closeStreams();
          context.ongoingTransfers.remove(state.transferId); // Remove failed transfer from map
          context.redrawPrompt.set(true); // Update UI

          // Optional: Notify peer about failure? Could send a 'file_fail' message.
          // For simplicity, we won't implement peer notification on failure for now.
     }

     // Add this helper method to FileTransferService.java
     private void logTransferEvent(FileTransferState state, String direction, String message) {
         // Ensure state and peerNodeId are valid before proceeding
         if (state == null || state.peerNodeId == null) {
              System.err.println("[!] Cannot log transfer event: Invalid state or peerNodeId.");
              return;
         }

         if (context.chatHistoryManager != null) {
             long timestamp = System.currentTimeMillis();
             // Attempt to get the connected peer's username, fallback if needed
             String peerUsername = null;
             // Check if the peer involved in the transfer is the currently connected one
             if (state.peerNodeId.equals(context.connectedPeerId.get())) {
                 peerUsername = context.connectedPeerUsername.get();
             }
             // Fallback if username is null or peer isn't the currently connected one
             if (peerUsername == null || peerUsername.isEmpty()) {
                  // Use peer ID as fallback username info
                  peerUsername = state.peerNodeId.substring(0, Math.min(8, state.peerNodeId.length())) + "...";
             }


             context.chatHistoryManager.addMessage(
                     timestamp,
                     state.peerNodeId, // Log the ID of the peer involved in the transfer
                     peerUsername,     // Use best available username
                     direction,        // "SENT" or "RECEIVED"
                     message           // Formatted message
             );
         } else {
              // Only print error if we expect history manager (i.e., connected)
              if (context.currentState.get() == NodeState.CONNECTED_SECURE) {
                 System.err.println("[!] ChatHistoryManager not available, cannot log file transfer event.");
              }
         }
     }
}