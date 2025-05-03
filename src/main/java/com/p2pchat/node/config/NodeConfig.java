package main.java.com.p2pchat.node.config;

public class NodeConfig {
    public static String SERVER_IP = "127.0.0.1"; // Default, can be overridden
    public static final int SERVER_PORT = 19999;
    public static final int BUFFER_SIZE = 4096;
    public static final int LOCAL_UDP_PORT = 0; // Auto-assign
    public static final long KEEPALIVE_SERVER_INTERVAL_MS = 20 * 1000;
    public static final long KEEPALIVE_PEER_INTERVAL_MS = 5 * 1000;
    public static final long PING_INTERVAL_MS = 400;
    public static final int MAX_PING_ATTEMPTS = 15;
    public static final long WAIT_MATCH_TIMEOUT_MS = 60 * 1000;
    public static final long STATE_PRINT_INTERVAL_MS = 5 * 1000;

    // --- File Transfer Config ---
    public static final String DOWNLOADS_DIR = "downloads";
    public static final long MAX_FILE_SIZE_BYTES = 1 * 1024 * 1024; // 1 MiB limit
    public static final int FILE_CHUNK_SIZE = 1024; // Raw bytes per chunk before encryption
    public static final long FILE_ACK_TIMEOUT_MS = 2000; // Timeout for waiting for a file chunk ACK
    public static final int FILE_MAX_RETRIES = 5;      // Max times to resend a chunk before failing
    // --- End File Transfer Config ---
}