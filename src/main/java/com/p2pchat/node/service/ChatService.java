package main.java.com.p2pchat.node.service;

import main.java.com.p2pchat.common.CryptoUtils;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.network.NetworkManager;
import main.java.com.p2pchat.node.ui.GuiCallback; // Import the callback interface

import org.json.JSONObject;
import javax.crypto.SecretKey;
import java.security.*;
import java.security.spec.InvalidKeySpecException;

public class ChatService {

    private final NodeContext context;
    private final NetworkManager networkManager;
    private GuiCallback guiCallback = null; // <<< Added callback reference

    public ChatService(NodeContext context, NetworkManager networkManager) {
        this.context = context;
        this.networkManager = networkManager;
    }

    // <<< Added setter for callback >>>
    public void setGuiCallback(GuiCallback callback) {
        this.guiCallback = callback;
    }

    // Called by ConnectionService/ServerMessageHandler during connection setup
    public boolean generateSharedKeyAndStore(String peerId, String peerPublicKeyBase64) {
        String peerDisplay = peerId != null ? peerId.substring(0,Math.min(8, peerId.length())) + "..." : "peer";
        if (context.myKeyPair == null) {
            String msg = "Cannot generate shared key: Own keypair is missing."; System.err.println("[!] "+msg);
            // Don't call GUI here, let ConnectionService handle overall failure
            return false;
        }
        if (peerId == null || peerPublicKeyBase64 == null) {
             String msg = "Cannot generate shared key: Peer info missing."; System.err.println("[!] "+msg);
             return false;
        }

        try {
            PublicKey peerPublicKey = CryptoUtils.decodePublicKey(peerPublicKeyBase64);
            System.out.println("[*] Performing ECDH key agreement with peer " + peerDisplay + "...");
            byte[] sharedSecretBytes = CryptoUtils.generateSharedSecret(context.myKeyPair.getPrivate(), peerPublicKey);

            System.out.println("[*] Deriving AES symmetric key from shared secret...");
            SecretKey derivedKey = CryptoUtils.deriveSymmetricKey(sharedSecretBytes);

            context.peerSymmetricKeys.put(peerId, derivedKey);
            context.sharedKeyEstablished.set(true);
            String successMsg = "[+] Shared symmetric key established successfully for peer " + peerDisplay + "!";
            System.out.println(successMsg);
            if (guiCallback != null) {
                 // Log success as system message
                 guiCallback.displaySystemMessage("System: Key exchange successful with " + peerDisplay);
            }
            return true;

        } catch (NoSuchAlgorithmException | InvalidKeyException | InvalidKeySpecException e) {
            String errorMsg = "Cryptographic error during key exchange with " + peerDisplay + ": " + e.getMessage(); System.err.println("[!] "+errorMsg);
             if (guiCallback != null) {
                 guiCallback.displaySystemMessage("System: Error - Key exchange failed: " + e.getMessage());
             }
        } catch (Exception e) {
            String errorMsg = "Unexpected error during shared key generation with " + peerDisplay + ": " + e.getMessage(); System.err.println("[!] "+errorMsg);
             if (guiCallback != null) {
                guiCallback.displaySystemMessage("System: Error - Unexpected key exchange failure.");
             }
            e.printStackTrace();
        }
        context.sharedKeyEstablished.set(false);
        return false;
    }

    // Called by GUI to send a message
    public void sendChatMessage(String message) {
        if (context.currentState.get() != NodeState.CONNECTED_SECURE) {
            if (guiCallback != null) guiCallback.displaySystemMessage("System: Cannot send, not connected.");
            return;
        }

        java.net.InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
        String peerId = context.connectedPeerId.get();
        SecretKey sharedKey = (peerId != null) ? context.peerSymmetricKeys.get(peerId) : null;
        String peerDisplay = context.getPeerDisplayName();

        if (peerAddr == null || peerId == null || sharedKey == null) {
            String msg = "Cannot send chat message: Missing peer address, ID, or key.";
            if (guiCallback != null) guiCallback.displaySystemMessage("System: Error - " + msg); else System.err.println("[!] "+msg);
            return;
        }

        try {
            CryptoUtils.EncryptedPayload payload = CryptoUtils.encrypt(message, sharedKey);

            JSONObject chatMsg = new JSONObject();
            chatMsg.put("action", "e_chat");
            chatMsg.put("node_id", context.myNodeId.get());
            chatMsg.put("iv", payload.ivBase64);
            chatMsg.put("e_payload", payload.ciphertextBase64);

            boolean sent = networkManager.sendUdp(chatMsg, peerAddr);

            // Display own message and log AFTER attempting send
            if (guiCallback != null) {
                 guiCallback.appendMessage("[You]: " + message); // Show in main chat
            }
            if (context.chatHistoryManager != null) { // Log regardless of GUI
                long timestamp = System.currentTimeMillis();
                context.chatHistoryManager.addMessage(timestamp, peerId, context.connectedPeerUsername.get(), "SENT", message);
            }

            if (!sent) {
                 String failMsg = "Failed to send chat message to " + peerDisplay + ". Connection might be lost.";
                 if (guiCallback != null) guiCallback.displaySystemMessage("System: Error - " + failMsg); else System.err.println("[!] "+failMsg);
                 // Let network errors or keep-alive failures trigger connection loss handling
            }

        } catch (GeneralSecurityException e) {
             String errorMsg = "Failed to encrypt message: " + e.getMessage();
             if (guiCallback != null) guiCallback.displaySystemMessage("System: Error - " + errorMsg); else System.err.println("[!] "+errorMsg);
        } catch (Exception e) {
             String errorMsg = "Unexpected error sending chat message: " + e.getMessage();
             if (guiCallback != null) guiCallback.displaySystemMessage("System: Error - " + errorMsg); else System.err.println("[!] "+errorMsg);
             e.printStackTrace();
        }
    }

    // Called by PeerMessageHandler when e_chat received
    public void receiveEncryptedChat(JSONObject data) {
        String peerId = context.connectedPeerId.get();
        SecretKey sharedKey = (peerId != null) ? context.peerSymmetricKeys.get(peerId) : null;
        String peerDisplay = context.getPeerDisplayName();

        if (sharedKey == null) {
            String errorMsg = "CRITICAL: No shared key found for connected peer " + peerDisplay + ". Cannot decrypt.";
            if (guiCallback != null) guiCallback.appendMessage("System: Error - " + errorMsg); else System.err.println("\n[!] "+errorMsg);
            return;
        }

        CryptoUtils.EncryptedPayload payload = CryptoUtils.EncryptedPayload.fromJson(data);
        if (payload == null) {
            String errorMsg = "Received invalid encrypted chat payload from " + peerDisplay;
            if (guiCallback != null) guiCallback.displaySystemMessage("System: Error - " + errorMsg); else System.err.println("\n[!] "+errorMsg);
            return;
        }
        try {
            String decryptedMessage = CryptoUtils.decrypt(payload, sharedKey);

            // <<< Notify GUI - Append peer's message >>>
            if (guiCallback != null) {
                guiCallback.appendMessage("[" + peerDisplay + "]: " + decryptedMessage);
            } else { // Fallback to console
                System.out.println("[" + peerDisplay + "]: " + decryptedMessage);
            }

            // Log to history AFTER decrypting and displaying
            if (context.chatHistoryManager != null) {
                long timestamp = System.currentTimeMillis();
                String senderId = data.optString("node_id", peerId);
                context.chatHistoryManager.addMessage(timestamp, senderId, context.connectedPeerUsername.get(), "RECEIVED", decryptedMessage);
            }

        } catch (GeneralSecurityException e) {
            String errorMsg = "Failed to decrypt message from " + peerDisplay + ". " + e.getMessage();
            if (guiCallback != null) guiCallback.appendMessage("System: Error - " + errorMsg); else System.err.println("\n[!] "+errorMsg);
        } catch (Exception e) {
            String errorMsg = "Error handling decrypted message from " + peerDisplay + ": " + e.getMessage();
            if (guiCallback != null) guiCallback.appendMessage("System: Error - " + errorMsg); else System.err.println("\n[!] "+errorMsg);
            e.printStackTrace();
        }
    }
}