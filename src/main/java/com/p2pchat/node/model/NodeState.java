package main.java.com.p2pchat.node.model;

public enum NodeState {
    INITIALIZING,
    DISCONNECTED,
    REGISTERING,
    WAITING_MATCH,
    ATTEMPTING_UDP,
    // EXCHANGING_KEYS merged into ATTEMPTING_UDP/CONNECTED_SECURE transition
    CONNECTED_SECURE,
    SHUTTING_DOWN
}