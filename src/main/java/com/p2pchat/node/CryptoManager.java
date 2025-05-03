package main.java.com.p2pchat.node; // Or main.java.com.p2pchat.util

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Handles cryptographic operations: Diffie-Hellman key exchange and AES/GCM encryption/decryption.
 */
public class CryptoManager {

    private KeyPair dhKeyPair;
    private KeyAgreement keyAgreement;

    private static final String DH_ALGORITHM = "DH";
    private static final int DH_KEY_SIZE = 2048; // Or 3072 for higher security
    private static final String AES_ALGORITHM = "AES";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE_BITS = 256; // For AES-256
    private static final int GCM_IV_LENGTH_BYTES = 12; // 96 bits is recommended for GCM
    private static final int GCM_TAG_LENGTH_BITS = 128; // Authentication tag size

    public CryptoManager() throws NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException {
        generateDhKeyPair();
        initializeKeyAgreement();
    }

    private void generateDhKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance(DH_ALGORITHM);
        // Note: For standard DH, parameters might be needed, but Java often uses defaults.
        // For real-world apps, consider specifying parameters explicitly (e.g., RFC 3526 groups).
        keyPairGen.initialize(DH_KEY_SIZE);
        this.dhKeyPair = keyPairGen.generateKeyPair();
    }

    private void initializeKeyAgreement() throws NoSuchAlgorithmException, InvalidKeyException {
        this.keyAgreement = KeyAgreement.getInstance(DH_ALGORITHM);
        this.keyAgreement.init(this.dhKeyPair.getPrivate());
    }

    public byte[] getDhPublicKeyEncoded() {
        return this.dhKeyPair.getPublic().getEncoded();
    }

    public byte[] computeSharedSecret(byte[] peerPublicKeyBytes) throws InvalidKeyException, NoSuchAlgorithmException, java.security.spec.InvalidKeySpecException {
        KeyFactory keyFactory = KeyFactory.getInstance(DH_ALGORITHM);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(peerPublicKeyBytes);
        PublicKey peerPublicKey = keyFactory.generatePublic(keySpec);

        this.keyAgreement.doPhase(peerPublicKey, true);
        return this.keyAgreement.generateSecret();
    }

    /**
     * Derives a fixed-size AES key from the raw shared secret using SHA-256.
     * WARNING: This is a basic KDF. HKDF is generally preferred for better security properties.
     */
    public SecretKey deriveAesKeyFromSharedSecret(byte[] sharedSecret) throws NoSuchAlgorithmException {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] derivedKey = hash.digest(sharedSecret);
        // Truncate or pad if necessary (SHA-256 output is 256 bits, matching AES_KEY_SIZE_BITS)
        byte[] aesKeyBytes = new byte[AES_KEY_SIZE_BITS / 8];
        System.arraycopy(derivedKey, 0, aesKeyBytes, 0, aesKeyBytes.length);
        return new SecretKeySpec(aesKeyBytes, AES_ALGORITHM);
    }

    /**
     * Encrypts plaintext using AES/GCM with the provided key.
     * Prepends a unique nonce/IV to the ciphertext.
     *
     * @param plaintext The data to encrypt.
     * @param aesKey    The shared AES secret key.
     * @return A byte array containing: 12-byte nonce + ciphertext + 16-byte GCM tag.
     * @throws GeneralSecurityException If encryption fails.
     */
    public byte[] encrypt(byte[] plaintext, SecretKey aesKey) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        SecureRandom random = SecureRandom.getInstanceStrong(); // Use a strong random source
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, parameterSpec);

        byte[] cipherText = cipher.doFinal(plaintext);

        // Prepend IV to ciphertext for transmission: IV (12 bytes) + Ciphertext + Tag (16 bytes)
        ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
        byteBuffer.put(iv);
        byteBuffer.put(cipherText);
        return byteBuffer.array();
    }

    /**
     * Decrypts data encrypted with AES/GCM.
     * Assumes the input format is: 12-byte nonce + ciphertext + 16-byte GCM tag.
     *
     * @param ivCiphertextTag The combined nonce, ciphertext, and tag.
     * @param aesKey          The shared AES secret key.
     * @return The original plaintext byte array.
     * @throws GeneralSecurityException If decryption or authentication fails (e.g., bad tag).
     */
    public byte[] decrypt(byte[] ivCiphertextTag, SecretKey aesKey) throws GeneralSecurityException {
        if (ivCiphertextTag == null || ivCiphertextTag.length < GCM_IV_LENGTH_BYTES) {
             throw new IllegalArgumentException("Invalid ciphertext input length");
        }
        // Extract IV (first 12 bytes) and the ciphertext+tag
        ByteBuffer byteBuffer = ByteBuffer.wrap(ivCiphertextTag);
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        byteBuffer.get(iv);
        byte[] cipherTextWithTag = new byte[byteBuffer.remaining()];
        byteBuffer.get(cipherTextWithTag);

        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, parameterSpec);

        return cipher.doFinal(cipherTextWithTag); // Will throw AEADBadTagException if authentication fails
    }

     // Helper to encode byte[] to Base64 String
     public static String encodeBase64(byte[] data) {
         return Base64.getEncoder().encodeToString(data);
     }

     // Helper to decode Base64 String to byte[]
     public static byte[] decodeBase64(String data) {
         return Base64.getDecoder().decode(data);
     }
}