package main.java.com.p2pchat.common; // Place in common or a new util package

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.json.JSONObject;


public class CryptoUtils {

    private static final String ASYMMETRIC_ALGORITHM = "EC"; // Elliptic Curve
    private static final int EC_KEY_SIZE = 256; // Using a standard curve size implicitly (like Curve25519 often used with X25519 KeyAgreement)
    private static final String KEY_AGREEMENT_ALGORITHM = "ECDH"; // EC Diffie-Hellman

    private static final String SYMMETRIC_ALGORITHM = "AES";
    private static final String SYMMETRIC_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE = 256; // bits
    private static final int GCM_IV_LENGTH = 12; // bytes (96 bits recommended for GCM)
    private static final int GCM_TAG_LENGTH = 128; // bits

    /**
     * Generates an Elliptic Curve KeyPair.
     * @return A KeyPair object.
     * @throws NoSuchAlgorithmException If EC algorithm is not supported.
     */
    public static KeyPair generateECKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance(ASYMMETRIC_ALGORITHM);
        // For EC, key size is often determined by the curve parameters implicitly selected
        // or explicitly via AlgorithmParameterSpec. Standard JCE providers handle this.
        // keyPairGen.initialize(EC_KEY_SIZE); // Size may not be directly settable like RSA
        keyPairGen.initialize(EC_KEY_SIZE, new SecureRandom()); // Use SecureRandom
        return keyPairGen.generateKeyPair();
    }

    /**
     * Encodes a PublicKey to a Base64 String.
     * @param publicKey The PublicKey to encode.
     * @return Base64 encoded string of the public key.
     */
    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * Decodes a Base64 String to a PublicKey.
     * @param keyStr Base64 encoded public key string.
     * @return PublicKey object.
     * @throws NoSuchAlgorithmException If EC algorithm is not supported.
     * @throws InvalidKeySpecException If the key specification is invalid.
     */
    public static PublicKey decodePublicKey(String keyStr) throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] keyBytes = Base64.getDecoder().decode(keyStr);
        KeyFactory keyFactory = KeyFactory.getInstance(ASYMMETRIC_ALGORITHM);
        return keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
    }


    /**
     * Performs ECDH key agreement to generate a shared secret.
     * @param privateKey Our private key.
     * @param peerPublicKey The peer's public key.
     * @return The raw shared secret byte array.
     * @throws NoSuchAlgorithmException If ECDH algorithm is not supported.
     * @throws InvalidKeyException If a key is invalid.
     */
    public static byte[] generateSharedSecret(PrivateKey privateKey, PublicKey peerPublicKey)
            throws NoSuchAlgorithmException, InvalidKeyException {
        KeyAgreement keyAgree = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM);
        keyAgree.init(privateKey);
        keyAgree.doPhase(peerPublicKey, true);
        return keyAgree.generateSecret();
    }

     /**
     * Derives a symmetric AES key from a shared secret using SHA-256.
     * NOTE: Using a proper KDF like HKDF is recommended in production.
     * @param sharedSecret The raw shared secret from key agreement.
     * @return A SecretKeySpec suitable for AES.
     * @throws NoSuchAlgorithmException If SHA-256 is not supported.
     */
    public static SecretKey deriveSymmetricKey(byte[] sharedSecret) throws NoSuchAlgorithmException {
         MessageDigest hash = MessageDigest.getInstance("SHA-256");
         byte[] derivedKey = hash.digest(sharedSecret);
         // Use only the first 32 bytes (256 bits) for the AES key
         byte[] aesKeyBytes = new byte[AES_KEY_SIZE / 8];
         System.arraycopy(derivedKey, 0, aesKeyBytes, 0, aesKeyBytes.length);
         return new SecretKeySpec(aesKeyBytes, SYMMETRIC_ALGORITHM);
    }

    /**
     * Encrypts plaintext using AES/GCM.
     * @param plaintext The message to encrypt.
     * @param key The shared symmetric AES key.
     * @return An EncryptedPayload object containing IV and ciphertext.
     * @throws GeneralSecurityException For various crypto errors.
     */
    public static EncryptedPayload encrypt(String plaintext, SecretKey key) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(iv); // Generate a random IV

        Cipher cipher = Cipher.getInstance(SYMMETRIC_TRANSFORMATION);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

        byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        return new EncryptedPayload(
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(cipherText)
        );
    }

    /**
     * Decrypts ciphertext using AES/GCM.
     * @param payload The EncryptedPayload containing IV and ciphertext.
     * @param key The shared symmetric AES key.
     * @return The original plaintext string.
     * @throws GeneralSecurityException If decryption fails (e.g., bad key, tampered data).
     */
    public static String decrypt(EncryptedPayload payload, SecretKey key) throws GeneralSecurityException {
        if (payload == null || payload.ivBase64 == null || payload.ciphertextBase64 == null) {
            throw new IllegalArgumentException("Invalid encrypted payload");
        }
        byte[] iv = Base64.getDecoder().decode(payload.ivBase64);
        byte[] cipherText = Base64.getDecoder().decode(payload.ciphertextBase64);

        if (iv.length != GCM_IV_LENGTH) {
            throw new GeneralSecurityException("Invalid IV length");
        }

        Cipher cipher = Cipher.getInstance(SYMMETRIC_TRANSFORMATION);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

        byte[] decryptedText = cipher.doFinal(cipherText);

        return new String(decryptedText, StandardCharsets.UTF_8);
    }

    /**
     * Simple container for IV and Ciphertext (Base64 encoded).
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
            if (json == null || !json.has("iv") || !json.has("e_payload")) {
                return null;
            }
            return new EncryptedPayload(json.getString("iv"), json.getString("e_payload"));
        }

        // Convenience method to convert to JSON
        public JSONObject toJson() {
             JSONObject json = new JSONObject();
             json.put("iv", this.ivBase64);
             json.put("e_payload", this.ciphertextBase64);
             return json;
        }
    }
}