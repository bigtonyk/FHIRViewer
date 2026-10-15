package com.spiraleyes.fhirviewer.server.smart;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Opaque per-attempt OAuth values: {@code state} (CSRF protection, always)
 * and {@code nonce} (replay protection for OIDC identity requests).
 *
 * <p>Both are 256-bit values from the platform CSPRNG, base64url-encoded —
 * the same entropy idiom as {@link Pkce}. Opaque means opaque: neither
 * carries meaning, and the callback compares {@code state} with
 * {@link MessageDigest#isEqual} so the check runs in constant time.</p>
 *
 * <p>Kept separate from {@link Pkce} because the two have different jobs and
 * different lifetimes: PKCE binds the code to the exchange, while state binds
 * the callback to the request that opened the browser.</p>
 */
public final class SmartAuthState {

    /** Entropy bytes: 256 bits, matching the PKCE verifier strength. */
    static final int ENTROPY_BYTES = 32;

    private final String state;
    private final String nonce;

    private SmartAuthState(String state, String nonce) {
        this.state = state;
        this.nonce = nonce;
    }

    /** @return a fresh state/nonce pair from the platform CSPRNG */
    public static SmartAuthState generate() {
        return generate(new SecureRandom());
    }

    /**
     * @param random the entropy source; exposed so a test can seed it
     * @return a fresh pair
     */
    static SmartAuthState generate(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        return new SmartAuthState(randomString(random), randomString(random));
    }

    /** Rebuilds expected values around a known state, for callback checks. */
    static SmartAuthState of(String state, String nonce) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("A state value is required.");
        }
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("A nonce value is required.");
        }
        return new SmartAuthState(state, nonce);
    }

    private static String randomString(SecureRandom random) {
        byte[] entropy = new byte[ENTROPY_BYTES];
        random.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    /**
     * Constant-time comparison: a callback {@code state} that differs by even
     * one byte — or one length — is a forgery or a crossed request, never a
     * near miss.
     */
    public boolean matchesState(String callbackState) {
        if (callbackState == null) {
            return false;
        }
        byte[] expected = state.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] actual = callbackState.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    /** The CSRF value sent in the authorize URL; never logged. */
    public String state() {
        return state;
    }

    /** The replay value sent when OIDC scopes are requested; never logged. */
    public String nonce() {
        return nonce;
    }

    /** Names the type only; both values are secrets and are never printed. */
    @Override
    public String toString() {
        return "SmartAuthState[present]";
    }
}
