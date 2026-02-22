import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Crypto {
    private KeyAgreement keyAgreement;
    private PublicKey publicKey;
    private SecretKey aesKey;

    // Reused across every encrypt/decrypt call — avoids repeated
    // OS-entropy seeding (SecureRandom) and provider lookups (Cipher)
    private final SecureRandom secureRandom = new SecureRandom();
    private Cipher encryptCipher;
    private Cipher decryptCipher;

    public Crypto() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("DH");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        publicKey = kp.getPublic();
        keyAgreement = KeyAgreement.getInstance("DH");
        keyAgreement.init(kp.getPrivate());
    }

    public byte[] getPublicKeyBytes() {
        return publicKey.getEncoded();
    }

    public void computeSharedSecret(byte[] peerPubkeyBytes) throws Exception {
        KeyFactory kf           = KeyFactory.getInstance("DH");
        X509EncodedKeySpec spec = new X509EncodedKeySpec(peerPubkeyBytes);
        PublicKey peerPubKey    = kf.generatePublic(spec);

        keyAgreement.doPhase(peerPubKey, true);
        byte[] sharedSecret = keyAgreement.generateSecret();

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] key = sha.digest(sharedSecret);
        aesKey = new SecretKeySpec(key, "AES");

        // Instantiate Cipher objects once after the key is ready
        encryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
        decryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
    }

    public byte[] encrypt(byte[] plaintext) throws Exception {
        byte[] iv = new byte[12]; // GCM IV size
        secureRandom.nextBytes(iv);

        encryptCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] ciphertext = encryptCipher.doFinal(plaintext);

        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);

        return result;
    }

    public byte[] decrypt(byte[] cipherData) throws Exception {
        byte[] iv         = new byte[12];
        byte[] ciphertext = new byte[cipherData.length - 12];

        System.arraycopy(cipherData, 0, iv,         0, 12);
        System.arraycopy(cipherData, 12, ciphertext, 0, ciphertext.length);

        decryptCipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        return decryptCipher.doFinal(ciphertext);
    }
}
