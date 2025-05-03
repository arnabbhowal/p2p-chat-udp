package main.java.com.p2pchat.node.service;

import main.java.com.p2pchat.common.CryptoUtils;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState; // Still needed for state check
import main.java.com.p2pchat.node.network.NetworkManager;

import org.json.JSONObject;
import javax.crypto.SecretKey;
// import java.net.InetSocketAddress; // No longer needed here
import java.security.*;


public class ChatService {

    private final NodeContext context;
    private final NetworkManager networkManager;

    public ChatService(NodeContext context, NetworkManager networkManager) {
        this.context = context;
        this.networkManager = networkManager;
    }

     // Called by ServerMessageHandler when connection_info received
     public boolean generateSharedKeyAndStore(String peerId, String peerPublicKeyBase64) {
         if (context.myKeyPair == null) { System.err.println("[!] Cannot generate shared key: Own keypair is missing."); return false; }
         if (peerId == null || peerPublicKeyBase64 == null) { System.err.println("[!] Cannot generate shared key: Peer info missing."); return false; }

         try {
             PublicKey peerPublicKey = CryptoUtils.decodePublicKey(peerPublicKeyBase64);
             System.out.println("[*] Performing ECDH key agreement with peer " + peerId + "...");
             byte[] sharedSecretBytes = CryptoUtils.generateSharedSecret(context.myKeyPair.getPrivate(), peerPublicKey);

             System.out.println("[*] Deriving AES symmetric key from shared secret...");
             SecretKey derivedKey = CryptoUtils.deriveSymmetricKey(sharedSecretBytes);

             context.peerSymmetricKeys.put(peerId, derivedKey);
             context.sharedKeyEstablished.set(true); // Signal success
             System.out.println("[+] Shared symmetric key established successfully for peer " + peerId + "!");
             return true;

         } catch (NoSuchAlgorithmException e) {
              System.err.println("[!] Cryptographic error during key exchange: " + e.getMessage());
         } catch (Exception e) {
              System.err.println("[!] Unexpected error during shared key generation: " + e.getMessage());
              e.printStackTrace();
         }
         context.sharedKeyEstablished.set(false); // Ensure flag is false on error
         return false;
     }

    // Called by UI to send a message
    public void sendChatMessage(String message) {
        if (context.currentState.get() != NodeState.CONNECTED_SECURE) {
             // Error message printed by UI command handler now
             // System.out.println("[!] Cannot send chat message, not securely connected.");
             return;
        }

        java.net.InetSocketAddress peerAddr = context.peerAddrConfirmed.get();
        String peerId = context.connectedPeerId.get();
        SecretKey sharedKey = (peerId != null) ? context.peerSymmetricKeys.get(peerId) : null;

        if (peerAddr == null || peerId == null || sharedKey == null) {
             System.out.println("\n[!] Cannot send chat message: Missing peer address, ID, or key."); // Add newline for clarity
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

            if (sent && context.chatHistoryManager != null) {
                long timestamp = System.currentTimeMillis();
                context.chatHistoryManager.addMessage(timestamp, peerId, context.connectedPeerUsername.get(), "SENT", message);
            }
            if (!sent) System.out.println("\n[!] Failed to send chat message. Connection might be lost."); // Add newline

        } catch (GeneralSecurityException e) { System.err.println("\n[!] Failed to encrypt message: " + e.getMessage()); // Add newline
        } catch (Exception e) { System.err.println("\n[!] Unexpected error sending chat message: " + e.getMessage()); } // Add newline
    }

     // Called by PeerMessageHandler when e_chat received
     public void receiveEncryptedChat(JSONObject data) {
         String peerId = context.connectedPeerId.get();
         SecretKey sharedKey = (peerId != null) ? context.peerSymmetricKeys.get(peerId) : null;

          if (sharedKey == null) {
               // Added newline for cleaner error printing
               System.err.println("\n[!] CRITICAL: No shared key found for connected peer " + context.getPeerDisplayName() + ". Cannot decrypt.");
               return;
          }

         CryptoUtils.EncryptedPayload payload = CryptoUtils.EncryptedPayload.fromJson(data);
         if (payload == null) {
              // Added newline
             System.err.println("\n[!] Received invalid encrypted chat payload from " + context.getPeerDisplayName());
             return;
         }
         try {
             String decryptedMessage = CryptoUtils.decrypt(payload, sharedKey);

             // --- Clean Printing Logic ---
             // Simply use println to print the message on its own line.
             // The UI loop's prompt will appear on the *next* line after this.
             // Removed the '\r' (carriage return).
             System.out.println("[" + context.getPeerDisplayName() + "]: " + decryptedMessage);


             if (context.chatHistoryManager != null) {
                 long timestamp = System.currentTimeMillis();
                 String senderId = data.optString("node_id", peerId); // Use sender from packet if available, else assume connected peer
                 context.chatHistoryManager.addMessage(timestamp, senderId, context.connectedPeerUsername.get(), "RECEIVED", decryptedMessage);
             }

         } catch (GeneralSecurityException e) {
              // Added newline for cleaner error printing
              System.err.println("\n[!] Failed to decrypt message from " + context.getPeerDisplayName() + ". " + e.getMessage());
         } catch (Exception e) {
              // Added newline
             System.err.println("\n[!] Error handling decrypted message from " + context.getPeerDisplayName() + ": " + e.getMessage());
         }
     }
}