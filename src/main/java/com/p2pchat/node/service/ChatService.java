package main.java.com.p2pchat.node.service;

import main.java.com.p2pchat.common.CryptoUtils;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState;
import main.java.com.p2pchat.node.network.NetworkManager;
// Import the callback interface
import main.java.com.p2pchat.node.ui.GuiCallback;

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

    // Called by ServerMessageHandler when connection_info received
    public boolean generateSharedKeyAndStore(String peerId, String peerPublicKeyBase64) {
        String peerDisplay = peerId != null ? peerId.substring(0,Math.min(8, peerId.length())) + "..." : "peer";
        if (context.myKeyPair == null) {
            String msg = "[!] Cannot generate shared key: Own keypair is missing.";
            System.err.println(msg);
            // <<< Notify GUI >>> (Maybe not needed here, handled by connection failure?)
            // if (guiCallback != null) guiCallback.appendMessage("System: Error - " + msg);
            return false;
        }
        if (peerId == null || peerPublicKeyBase64 == null) {
             String msg = "[!] Cannot generate shared key: Peer info missing.";
             System.err.println(msg);
             // <<< Notify GUI >>> (Maybe not needed here)
             // if (guiCallback != null) guiCallback.appendMessage("System: Error - " + msg);
             return false;
        }

        try {
            PublicKey peerPublicKey = CryptoUtils.decodePublicKey(peerPublicKeyBase64);
            System.out.println("[*] Performing ECDH key agreement with peer " + peerDisplay + "...");
            byte[] sharedSecretBytes = CryptoUtils.generateSharedSecret(context.myKeyPair.getPrivate(), peerPublicKey);

            System.out.println("[*] Deriving AES symmetric key from shared secret...");
            SecretKey derivedKey = CryptoUtils.deriveSymmetricKey(sharedSecretBytes);

            context.peerSymmetricKeys.put(peerId, derivedKey);
            context.sharedKeyEstablished.set(true); // Signal success
            String successMsg = "[+] Shared symmetric key established successfully for peer " + peerDisplay + "!";
            System.out.println(successMsg);
            // <<< Notify GUI >>>
            if (guiCallback != null) {
                 // No direct state change, but log success
                 // guiCallback.appendMessage("System: Key exchange successful with " + peerDisplay);
            }
            return true;

        } catch (NoSuchAlgorithmException | InvalidKeyException | InvalidKeySpecException e) {
            String errorMsg = "[!] Cryptographic error during key exchange with " + peerDisplay + ": " + e.getMessage();
            System.err.println(errorMsg);
             if (guiCallback != null) {
                 // guiCallback.appendMessage("System: Error - Key exchange failed: " + e.getMessage());
             }
        } catch (Exception e) {
            String errorMsg = "[!] Unexpected error during shared key generation with " + peerDisplay + ": " + e.getMessage();
            System.err.println(errorMsg);
             if (guiCallback != null) {
                // guiCallback.appendMessage("System: Error - Unexpected key exchange failure.");
             }
            e.printStackTrace();
        }
        context.sharedKeyEstablished.set(false); // Ensure flag is false on error
        return false;
    }

    // Called by GUI to send a message
    public void sendChatMessage(String message) {
        // State check is done by the GUI before calling this, but double-check
        if (context.currentState.get() != NodeState.CONNECTED_SECURE) {
            if (guiCallback != null) guiCallback.appendMessage("System: Cannot send, not connected.");
            return;
        }

        java.net.InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
        String peerId = context.connectedPeerId.get();
        SecretKey sharedKey = (peerId != null) ? context.peerSymmetricKeys.get(peerId) : null;
        String peerDisplay = context.getPeerDisplayName(); // For logging

        if (peerAddr == null || peerId == null || sharedKey == null) {
            String msg = "[!] Cannot send chat message: Missing peer address, ID, or key.";
            System.err.println(msg);
            if (guiCallback != null) guiCallback.appendMessage("System: Error - " + msg);
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

            // Log sent message AFTER attempting send
            if (context.chatHistoryManager != null) {
                long timestamp = System.currentTimeMillis();
                context.chatHistoryManager.addMessage(timestamp, peerId, context.connectedPeerUsername.get(), "SENT", message);
                 // <<< Notify GUI - Display own sent message >>>
                 if (guiCallback != null) {
                     guiCallback.appendMessage("[You]: " + message);
                 }
            } else if (guiCallback != null) {
                 // If history isn't working, still show own message in GUI
                 guiCallback.appendMessage("[You]: " + message);
            }


            if (!sent) {
                 String failMsg = "[!] Failed to send chat message to " + peerDisplay + ". Connection might be lost.";
                 System.err.println(failMsg);
                 if (guiCallback != null) guiCallback.appendMessage("System: Error - " + failMsg);
                 // Consider triggering connection loss check
                 // connectionService.handleConnectionLoss(); // Let network errors trigger this?
            }

        } catch (GeneralSecurityException e) {
             String errorMsg = "[!] Failed to encrypt message: " + e.getMessage();
             System.err.println(errorMsg);
             if (guiCallback != null) guiCallback.appendMessage("System: Error - " + errorMsg);
        } catch (Exception e) {
             String errorMsg = "[!] Unexpected error sending chat message: " + e.getMessage();
             System.err.println(errorMsg);
             if (guiCallback != null) guiCallback.appendMessage("System: Error - " + errorMsg);
             e.printStackTrace();
        }
    }

    // Called by PeerMessageHandler when e_chat received
    public void receiveEncryptedChat(JSONObject data) {
        String peerId = context.connectedPeerId.get();
        SecretKey sharedKey = (peerId != null) ? context.peerSymmetricKeys.get(peerId) : null;
        String peerDisplay = context.getPeerDisplayName(); // Get sender display name

        if (sharedKey == null) {
            String errorMsg = "[!] CRITICAL: No shared key found for connected peer " + peerDisplay + ". Cannot decrypt.";
            System.err.println("\n" + errorMsg); // Add newline for visibility
            if (guiCallback != null) guiCallback.appendMessage("System: Error - " + errorMsg);
            // Should potentially disconnect here or signal critical error
            return;
        }

        CryptoUtils.EncryptedPayload payload = CryptoUtils.EncryptedPayload.fromJson(data);
        if (payload == null) {
            String errorMsg = "[!] Received invalid encrypted chat payload from " + peerDisplay;
            System.err.println("\n" + errorMsg);
            if (guiCallback != null) guiCallback.appendMessage("System: Error - " + errorMsg);
            return;
        }
        try {
            String decryptedMessage = CryptoUtils.decrypt(payload, sharedKey);

            // <<< Notify GUI >>>
            if (guiCallback != null) {
                guiCallback.appendMessage("[" + peerDisplay + "]: " + decryptedMessage);
            } else { // Fallback to console if no GUI
                System.out.println("[" + peerDisplay + "]: " + decryptedMessage);
            }

            // Log to history AFTER decrypting
            if (context.chatHistoryManager != null) {
                long timestamp = System.currentTimeMillis();
                String senderId = data.optString("node_id", peerId); // Use sender from packet if available
                context.chatHistoryManager.addMessage(timestamp, senderId, context.connectedPeerUsername.get(), "RECEIVED", decryptedMessage);
            }

        } catch (GeneralSecurityException e) {
            String errorMsg = "[!] Failed to decrypt message from " + peerDisplay + ". " + e.getMessage();
            System.err.println("\n" + errorMsg);
            if (guiCallback != null) guiCallback.appendMessage("System: Error - " + errorMsg);
        } catch (Exception e) {
            String errorMsg = "[!] Error handling decrypted message from " + peerDisplay + ": " + e.getMessage();
            System.err.println("\n" + errorMsg);
            if (guiCallback != null) guiCallback.appendMessage("System: Error - " + errorMsg);
            e.printStackTrace();
        }
    }
}