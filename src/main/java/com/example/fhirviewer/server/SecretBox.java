package com.example.fhirviewer.server;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Passphrase-based encryption for the credentials a plugin stores on disk.
 *
 * <p>Credentials are encrypted with PBKDF2-HMAC-SHA256 (per-encryption random salt,
 * 256-bit key) and AES-GCM (per-encryption random 96-bit IV), then written as one
 * self-describing line so a later version can still read older files:</p>
 *
 * <pre>v1$&lt;base64 salt&gt;$&lt;base64 iv&gt;$&lt;base64 ciphertext&gt;</pre>
 *
 * <p><b>What this protects against:</b> someone reading the settings file without the
 * passphrase. <b>What it does not:</b> it cannot stop a user who has the passphrase, and
 * it does not defend against an attacker who has the file <i>and</i> the passphrase. A
 * weak passphrase is still guessable offline, which is why the UI never stores the
 * passphrase itself and asks for it once per session.</p>
 *
 * <p>AES-GCM is authenticated: a wrong passphrase or a tampered file fails to decrypt
 * rather than returning garbage, and the two cases are reported as the same
 * {@link SecretBoxException} on purpose, so the failure does not leak which one it was.</p>
 */
public final class SecretBox {

    /** The format marker written at the start of every stored value. */
    public static final String VERSION = "v1";

    /** PBKDF2 rounds. High enough to be slow, low enough to keep start-up responsive. */
    private static final int ITERATIONS = 210_000;

    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String KDF = "PBKDF2WithHmacSHA256";

    private final SecureRandom random = new SecureRandom();

    /** Encrypts a secret under the given passphrase, returning the stored line. */
    public String encrypt(String passphrase, String secret) throws SecretBoxException {
        requirePassphrase(passphrase);
        if (secret == null) {
            throw new SecretBoxException("There is no secret to encrypt.");
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(salt);
        random.nextBytes(iv);
        try {
            SecretKey key = deriveKey(passphrase, salt);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return VERSION + '$' + encode(salt) + '$' + encode(iv) + '$' + encode(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new SecretBoxException("The secret could not be encrypted.", e);
        }
    }

    /**
     * Decrypts a stored line produced by {@link #encrypt}.
     *
     * @throws SecretBoxException when the passphrase is wrong or the value is not a
     *                            well-formed, unmodified value this class produced
     */
    public String decrypt(String passphrase, String stored) throws SecretBoxException {
        requirePassphrase(passphrase);
        if (stored == null || stored.isBlank()) {
            throw new SecretBoxException("There is no stored secret to decrypt.");
        }
        String[] parts = stored.trim().split("\\$", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new SecretBoxException("The stored secret is not in a format this build understands.");
        }
        try {
            byte[] salt = decode(parts[1]);
            byte[] iv = decode(parts[2]);
            byte[] ciphertext = decode(parts[3]);
            SecretKey key = deriveKey(passphrase, salt);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Deliberately the same message for a wrong passphrase and a corrupt value.
            throw new SecretBoxException(
                    "The passphrase is wrong, or the stored secret has been altered.", e);
        }
    }

    /** True when the value looks like something this class wrote. */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(VERSION + '$') && value.split("\\$", -1).length == 4;
    }

    private static SecretKey deriveKey(String passphrase, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            byte[] derived = SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
            return new SecretKeySpec(derived, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static void requirePassphrase(String passphrase) throws SecretBoxException {
        if (passphrase == null || passphrase.isEmpty()) {
            throw new SecretBoxException("A passphrase is required.");
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        // Base64 is case-sensitive, so the value must be decoded exactly as written.
        // Trimming is safe; changing case is not.
        return Base64.getDecoder().decode(value.trim());
    }

    @Override
    public String toString() {
        return "SecretBox[" + VERSION + "]";
    }
}
