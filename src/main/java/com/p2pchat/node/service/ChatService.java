package main.java.com.p2pchat.node.service;

import main.java.com.p2pchat.common.CryptoUtils;
import main.java.com.p2pchat.node.model.NodeContext;
import main.java.com.p2pchat.node.model.NodeState; // Still needed for state check in sendChatMessage
import main.java.com.p2pchat.node.network.NetworkManager;

import org.json.JSONObject;
import javax.crypto.SecretKey;
// import java.net.InetSocketAddress; // No longer needed here
import java.security.*;
import java.security.spec.InvalidKeySpecException; // Added for catch block


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

         // Added InvalidKeySpecException catch which might be thrown by decodePublicKey
         } catch (NoSuchAlgorithmException | InvalidKeyException | InvalidKeySpecException e) {
              System.err.println("[!] Cryptographic error during key exchange: " + e.getMessage());
         } catch (Exception e) {
              System.err.println("[!] Unexpected error during shared key generation: " + e.getMessage());
              e.printStackTrace();
         }
         context.sharedKeyEstablished.set(false); // Ensure flag is false on error
         return false;
     }

    // Called by UI to send a message (only if command is 'chat' or 'c')
    public void sendChatMessage(String message) {
        if (context.currentState.get() != NodeState.CONNECTED_SECURE) {
             // State check is done by the UI before calling this
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
               // Should ideally disconnect here, but rely on ConnectionService/PeerMessageHandler for now
               return;
          }

         CryptoUtils.EncryptedPayload payload = CryptoUtils.EncryptedPayload.fromJson(data);
         if (payload == null) {
              // Added newline
             System.err.println("\n[!] Received invalid encrypted chat payload from " + context.getPeerDisplayName());
             context.redrawPrompt.set(true); // *** Signal redraw even on payload error ***
             return;
         }
         try {
             String decryptedMessage = CryptoUtils.decrypt(payload, sharedKey);

             // Use println to print the message on its own line.
             System.out.println("[" + context.getPeerDisplayName() + "]: " + decryptedMessage);

             // *** Signal the UI thread that the prompt might need redrawing ***
             context.redrawPrompt.set(true);

             if (context.chatHistoryManager != null) {
                 long timestamp = System.currentTimeMillis();
                 String senderId = data.optString("node_id", peerId); // Use sender from packet if available, else assume connected peer
                 context.chatHistoryManager.addMessage(timestamp, senderId, context.connectedPeerUsername.get(), "RECEIVED", decryptedMessage);
             }

         } catch (GeneralSecurityException e) {
              // Added newline for cleaner error printing
              System.err.println("\n[!] Failed to decrypt message from " + context.getPeerDisplayName() + ". " + e.getMessage());
              context.redrawPrompt.set(true); // *** Signal redraw on decryption error ***
         } catch (Exception e) {
              // Added newline
             System.err.println("\n[!] Error handling decrypted message from " + context.getPeerDisplayName() + ": " + e.getMessage());
             context.redrawPrompt.set(true); // *** Signal redraw on other errors ***
         }
     }
}