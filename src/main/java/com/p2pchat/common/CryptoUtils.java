package main.java.com.p2pchat.common; // Place in common or a new util package

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
// No changes needed for these imports for byte handling, but keep them:
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.json.JSONObject;

public class CryptoUtils {

    private static final String ASYMMETRIC_ALGORITHM = "EC"; // Elliptic Curve
    private static final int EC_KEY_SIZE = 256;
    private static final String KEY_AGREEMENT_ALGORITHM = "ECDH"; // EC Diffie-Hellman

    private static final String SYMMETRIC_ALGORITHM = "AES";
    private static final String SYMMETRIC_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE = 256; // bits
    private static final int GCM_IV_LENGTH = 12; // bytes (96 bits recommended for GCM)
    private static final int GCM_TAG_LENGTH = 128; // bits

    // --- Key Generation & Agreement (Unchanged) ---

    public static KeyPair generateECKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance(ASYMMETRIC_ALGORITHM);
        keyPairGen.initialize(EC_KEY_SIZE, new SecureRandom());
        return keyPairGen.generateKeyPair();
    }

    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public static PublicKey decodePublicKey(String keyStr) throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] keyBytes = Base64.getDecoder().decode(keyStr);
        KeyFactory keyFactory = KeyFactory.getInstance(ASYMMETRIC_ALGORITHM);
        return keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    public static byte[] generateSharedSecret(PrivateKey privateKey, PublicKey peerPublicKey)
            throws NoSuchAlgorithmException, InvalidKeyException {
        KeyAgreement keyAgree = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM);
        keyAgree.init(privateKey);
        keyAgree.doPhase(peerPublicKey, true);
        return keyAgree.generateSecret();
    }

    public static SecretKey deriveSymmetricKey(byte[] sharedSecret) throws NoSuchAlgorithmException {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] derivedKey = hash.digest(sharedSecret);
        byte[] aesKeyBytes = new byte[AES_KEY_SIZE / 8];
        System.arraycopy(derivedKey, 0, aesKeyBytes, 0, aesKeyBytes.length);
        return new SecretKeySpec(aesKeyBytes, SYMMETRIC_ALGORITHM);
    }

    // --- Symmetric Encryption/Decryption ---

    /**
     * Encrypts a String using AES/GCM.
     * @param plaintext The String message to encrypt.
     * @param key The shared symmetric AES key.
     * @return An EncryptedPayload object containing IV and ciphertext (Base64 encoded).
     * @throws GeneralSecurityException For various crypto errors.
     */
    public static EncryptedPayload encrypt(String plaintext, SecretKey key) throws GeneralSecurityException {
        return encryptBytes(plaintext.getBytes(StandardCharsets.UTF_8), key); // Delegate to byte version
    }

    /**
     * Decrypts ciphertext (from an EncryptedPayload) using AES/GCM to a String.
     * @param payload The EncryptedPayload containing IV and ciphertext (Base64 encoded).
     * @param key The shared symmetric AES key.
     * @return The original plaintext string.
     * @throws GeneralSecurityException If decryption fails (e.g., bad key, tampered data).
     */
    public static String decrypt(EncryptedPayload payload, SecretKey key) throws GeneralSecurityException {
        byte[] decryptedBytes = decryptBytes(payload, key); // Delegate to byte version
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    /**
     * Encrypts raw bytes using AES/GCM.
     * @param plainBytes The raw byte array to encrypt.
     * @param key The shared symmetric AES key.
     * @return An EncryptedPayload object containing IV and ciphertext (Base64 encoded).
     * @throws GeneralSecurityException For various crypto errors.
     */
    public static EncryptedPayload encryptBytes(byte[] plainBytes, SecretKey key) throws GeneralSecurityException {
        if (plainBytes == null || key == null) {
            throw new IllegalArgumentException("Input bytes and key cannot be null");
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(iv); // Generate a random IV

        Cipher cipher = Cipher.getInstance(SYMMETRIC_TRANSFORMATION);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

        byte[] cipherText = cipher.doFinal(plainBytes);

        return new EncryptedPayload(
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(cipherText)
        );
    }

    /**
     * Decrypts ciphertext (from an EncryptedPayload) using AES/GCM to raw bytes.
     * @param payload The EncryptedPayload containing IV and ciphertext (Base64 encoded).
     * @param key The shared symmetric AES key.
     * @return The original byte array.
     * @throws GeneralSecurityException If decryption fails (e.g., bad key, tampered data).
     */
    public static byte[] decryptBytes(EncryptedPayload payload, SecretKey key) throws GeneralSecurityException {
        if (payload == null || payload.ivBase64 == null || payload.ciphertextBase64 == null || key == null) {
            throw new IllegalArgumentException("Invalid encrypted payload or key is null");
        }
        byte[] iv = Base64.getDecoder().decode(payload.ivBase64);
        byte[] cipherText = Base64.getDecoder().decode(payload.ciphertextBase64);

        if (iv.length != GCM_IV_LENGTH) {
            throw new GeneralSecurityException("Invalid IV length: " + iv.length + ", expected " + GCM_IV_LENGTH);
        }

        Cipher cipher = Cipher.getInstance(SYMMETRIC_TRANSFORMATION);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

        return cipher.doFinal(cipherText);
    }


    /**
     * Simple container for IV and Ciphertext (Base64 encoded). (Unchanged)
     */
    public static class EncryptedPayload {
        public final String ivBase64;
        public final String ciphertextBase64;

        public EncryptedPayload(String ivBase64, String ciphertextBase64) {
            this.ivBase64 = ivBase64;
            this.ciphertextBase64 = ciphertextBase64;
        }

        // Convenience method to create from JSON
        public static EncryptedPayload fromJson(JSONObject json) {
            if (json == null) return null;
            // Adjust keys to match file_chunk format
            String ivKey = "iv";
            String payloadKey = json.has("e_chunk") ? "e_chunk" : "e_payload"; // Check for file or chat payload key
            if (!json.has(ivKey) || !json.has(payloadKey)) {
                 // System.err.println("[Crypto] Missing '"+ivKey+"' or '"+payloadKey+"' in JSON for EncryptedPayload: " + json.toString());
                return null;
            }
            return new EncryptedPayload(json.getString(ivKey), json.getString(payloadKey));
        }

        // Convenience method to convert to JSON (Unchanged)
        public JSONObject toJson() {
             JSONObject json = new JSONObject();
             json.put("iv", this.ivBase64);
             // Use generic key, let caller decide which context (chat/file)
             json.put("e_payload", this.ciphertextBase64);
             return json;
        }
    }
}