package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PKCE and state generation: S256 vectors, entropy, and secret hygiene.
 */
class PkceTest {

    @Test
    @DisplayName("The RFC 7636 appendix B vector produces its challenge")
    void rfcVectorProducesKnownChallenge() {
        // RFC 7636 §4.2 / Appendix B: verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        // must challenge to "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM".
        Pkce pkce = Pkce.ofVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge());
        assertEquals("S256", pkce.method());
    }

    @Test
    @DisplayName("Generated verifiers are high-entropy base64url of the right length")
    void generatedVerifiersAreHighEntropy() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            Pkce pkce = Pkce.generate();
            assertTrue(pkce.verifier().matches("[A-Za-z0-9\\-_]+"),
                    "verifier must be base64url: " + pkce.verifier());
            assertEquals(43, pkce.verifier().length(),
                    "32 bytes base64url-encode to 43 chars");
            assertTrue(pkce.challenge().matches("[A-Za-z0-9\\-_]+"),
                    "challenge must be base64url: " + pkce.challenge());
            seen.add(pkce.verifier());
        }
        assertEquals(50, seen.size(), "every verifier must be unique");
    }

    @Test
    @DisplayName("The challenge is stable for one verifier")
    void challengeIsStable() {
        Pkce first = Pkce.ofVerifier("some-verifier-value-0123456789abcdef");
        Pkce second = Pkce.ofVerifier("some-verifier-value-0123456789abcdef");

        assertEquals(first.challenge(), second.challenge());
    }

    @Test
    @DisplayName("A blank verifier is refused before any network")
    void blankVerifierIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Pkce.ofVerifier(null));
        assertThrows(IllegalArgumentException.class, () -> Pkce.ofVerifier("  "));
    }

    @Test
    @DisplayName("State values are unique, opaque, and compared exactly")
    void stateValuesAreUniqueAndExact() {
        SmartAuthState first = SmartAuthState.generate();
        SmartAuthState second = SmartAuthState.generate();

        assertNotEquals(first.state(), second.state(), "states must differ");
        assertNotEquals(first.nonce(), second.nonce(), "nonces must differ");
        assertTrue(first.matchesState(first.state()), "an exact state matches");
        assertFalse(first.matchesState(second.state()), "another attempt's state does not");
        assertFalse(first.matchesState(null), "a missing state does not match");
        assertFalse(first.matchesState(first.state() + "x"), "a longer state does not match");
    }

    @Test
    @DisplayName("A seeded generator still produces well-formed values")
    void seededGeneratorProducesWellFormedValues() {
        SmartAuthState state = SmartAuthState.generate(new SecureRandom(new byte[] {1, 2, 3}));

        assertTrue(state.state().matches("[A-Za-z0-9\\-_]+"));
        assertTrue(state.nonce().matches("[A-Za-z0-9\\-_]+"));
    }

    @Test
    @DisplayName("No secret reaches toString")
    void noSecretReachesToString() {
        Pkce pkce = Pkce.ofVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        SmartAuthState state = SmartAuthState.generate();

        assertFalse(pkce.toString().contains(pkce.verifier()), pkce.toString());
        assertFalse(pkce.toString().contains(pkce.challenge()), pkce.toString());
        assertFalse(state.toString().contains(state.state()), state.toString());
        assertFalse(state.toString().contains(state.nonce()), state.toString());
    }
}
