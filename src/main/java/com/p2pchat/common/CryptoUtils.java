package main.java.com.p2pchat.common;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
// Removed unused PKCS8EncodedKeySpec import
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.json.JSONObject;


public class CryptoUtils {

    private static final String ASYMMETRIC_ALGORITHM = "EC";
    private static final int EC_KEY_SIZE = 256;
    private static final String KEY_AGREEMENT_ALGORITHM = "ECDH";

    private static final String SYMMETRIC_ALGORITHM = "AES";
    private static final String SYMMETRIC_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE = 256;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

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

    public static EncryptedPayload encrypt(String plaintext, SecretKey key) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(SYMMETRIC_TRANSFORMATION);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

        byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        return new EncryptedPayload(
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(cipherText)
        );
    }

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

    public static class EncryptedPayload {
        public final String ivBase64;
        public final String ciphertextBase64;

        public EncryptedPayload(String ivBase64, String ciphertextBase64) {
            this.ivBase64 = ivBase64;
            this.ciphertextBase64 = ciphertextBase64;
        }

        public static EncryptedPayload fromJson(JSONObject json) {
            if (json == null || !json.has("iv") || !json.has("e_payload")) {
                return null;
            }
            return new EncryptedPayload(json.getString("iv"), json.getString("e_payload"));
        }

        public JSONObject toJson() {
             JSONObject json = new JSONObject();
             json.put("iv", this.ivBase64);
             json.put("e_payload", this.ciphertextBase64);
             return json;
        }
    }
}