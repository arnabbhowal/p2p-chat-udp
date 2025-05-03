package main.java.com.p2pchat.node.model;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// Represents the state of an ongoing file transfer
public class FileTransferState {

    public enum Status {
        // Common states
        NONE, FAILED, COMPLETED, CANCELLED, REJECTED, // <-- REJECTED Added
        // Sender states
        OFFER_SENT, AWAITING_ACCEPT, TRANSFERRING_SEND,
        // Receiver states
        OFFER_RECEIVED, TRANSFERRING_RECV
    }

    // Identifying info
    public final String transferId;
    public final String filename;
    public final long filesize;
    public final String peerNodeId;
    public final boolean isSender; // True if this node initiated the transfer

    // Status & Progress
    public volatile Status status = Status.NONE;
    public final AtomicLong transferredBytes = new AtomicLong(0); // Bytes sent or received

    // Sender specific state
    public Path sourcePath;
    public InputStream inputStream;
    public final AtomicInteger currentSeqNum = new AtomicInteger(0); // Next sequence number to send
    public final AtomicInteger lastAckReceivedSeqNum = new AtomicInteger(-1); // Last ACK received
    public final AtomicInteger retryCount = new AtomicInteger(0);
    public final AtomicLong lastPacketSentTime = new AtomicLong(0); // Timestamp for timeout check

    // Receiver specific state
    public Path downloadPath;
    public OutputStream outputStream;
    public final AtomicInteger expectedSeqNum = new AtomicInteger(0); // Next sequence number expected

    public FileTransferState(String transferId, String filename, long filesize, String peerNodeId, boolean isSender) {
        this.transferId = transferId;
        this.filename = filename;
        this.filesize = filesize;
        this.peerNodeId = peerNodeId;
        this.isSender = isSender;
        this.status = isSender ? Status.NONE : Status.OFFER_RECEIVED; // Initial status
    }

    // Convenience method to check if transfer is in a final state
    public boolean isTerminated() {
         return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED || status == Status.REJECTED || status == Status.NONE;
    }

    // Add methods to safely close streams if needed
    public void closeStreams() {
        try {
            if (inputStream != null) inputStream.close();
        } catch (Exception e) { /* ignore */ }
        try {
            if (outputStream != null) outputStream.close();
        } catch (Exception e) { /* ignore */ }
        inputStream = null;
        outputStream = null;
    }

    @Override
    public String toString() {
        return "FileTransferState{" +
                "transferId='" + transferId + '\'' +
                ", filename='" + filename + '\'' +
                ", filesize=" + filesize +
                ", peerNodeId='" + peerNodeId + '\'' +
                ", isSender=" + isSender +
                ", status=" + status +
                ", transferredBytes=" + transferredBytes.get() +
                (isSender ? ", currentSeqNum=" + currentSeqNum.get() : ", expectedSeqNum=" + expectedSeqNum.get()) +
                '}';
    }
}