package main.java.com.p2pchat.server.config;

public class ServerConfig {
    public static final int PORT = 19999;
    public static final int BUFFER_SIZE = 4096;
    public static final int NODE_TIMEOUT_MS = 120 * 1000; // 120 seconds
    public static final long CLEANUP_INTERVAL_MS = NODE_TIMEOUT_MS / 2;
    public static final String BIND_ADDRESS = "0.0.0.0"; // Listen on all interfaces
}