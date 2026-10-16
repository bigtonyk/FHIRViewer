package com.spiraleyes.fhirviewer.server.smart;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * PKCE (RFC 7636) for the native public client: a high-entropy verifier and
 * its S256 challenge.
 *
 * <p>This is the one cryptographic piece SMART needs, and it is a
 * hash-and-base64 of random bytes rather than a protocol implementation
 * written from scratch: {@link SecureRandom} for entropy, {@link MessageDigest}
 * {@code SHA-256} for the challenge, {@link Base64} url-encoding without
 * padding. The pattern follows {@code server.SecretBox}, which already
 * establishes {@code SecureRandom} as the entropy source.</p>
 *
 * <p><b>Only S256.</b> The {@code plain} method offers no protection and this
 * application never sends it; a server advertising only {@code plain} is
 * reported by {@link SmartConfiguration#problems()}, not worked around here.</p>
 *
 * <p>Instances are values: the verifier is created once per authorization
 * attempt and must be kept in memory until the token exchange sends it.
 * It is never logged — {@link #toString()} names the method only.</p>
 */
public final class Pkce {

    /** The challenge method this application sends; the only one it supports. */
    public static final String METHOD_S256 = "S256";

    /** Entropy bytes per RFC 7636 §4.1 (32 octets &rarr; 43 base64url chars). */
    static final int VERIFIER_BYTES = 32;

    private final String verifier;
    private final String challenge;

    private Pkce(String verifier, String challenge) {
        this.verifier = verifier;
        this.challenge = challenge;
    }

    /** @return a fresh verifier/challenge pair from the platform CSPRNG */
    public static Pkce generate() {
        return generate(new SecureRandom());
    }

    /**
     * @param random the entropy source; exposed so a test can seed it
     * @return a fresh pair
     */
    static Pkce generate(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        byte[] entropy = new byte[VERIFIER_BYTES];
        random.nextBytes(entropy);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        return ofVerifier(verifier);
    }

    /**
     * Rebuilds a pair from a known verifier, for tests and for sending a
     * stored verifier to the token endpoint.
     *
     * @param verifier the original verifier string; never blank
     */
    public static Pkce ofVerifier(String verifier) {
        if (verifier == null || verifier.isBlank()) {
            throw new IllegalArgumentException("A PKCE verifier is required.");
        }
        return new Pkce(verifier, challengeFor(verifier));
    }

    /** The secret sent (once) to the token endpoint; never logged. */
    public String verifier() {
        return verifier;
    }

    /** {@code BASE64URL-ENCODE(SHA256(verifier))}, sent in the authorize URL. */
    public String challenge() {
        return challenge;
    }

    /** The only method sent: {@value #METHOD_S256}. */
    public String method() {
        return METHOD_S256;
    }

    /**
     * Derives the S256 challenge for a verifier.
     *
     * @param verifier the verifier; never {@code null}
     * @return the challenge string
     */
    public static String challengeFor(String verifier) {
        Objects.requireNonNull(verifier, "verifier");
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(
                    verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandatory in every Java SE implementation.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Names the method only; the verifier is a secret and is never printed. */
    @Override
    public String toString() {
        return "Pkce[method=" + METHOD_S256 + "]";
    }
}
