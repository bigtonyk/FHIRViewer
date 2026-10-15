package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

/**
 * Session lifecycle, expiry, logout, isolation, JWKS parsing, and the rule
 * that no token ever reaches logs or diagnostics.
 */
class SmartSessionTest {

    private static SmartTokenResponse tokens(String access) {
        return new SmartTokenResponse(access, "Bearer", 3600L, "patient/*.read", "123",
                null, null, "refresh-1");
    }

    @Test
    @DisplayName("A session reports its grant and context")
    void sessionReportsGrant() {
        SmartAuthorizationSession session =
                new SmartAuthorizationSession("https://fhir.example.com", tokens("tok-1"), "sub-1");

        assertEquals("https://fhir.example.com", session.fhirBaseUrl());
        assertEquals("tok-1", session.accessToken());
        assertEquals("patient/*.read", session.grantedScope());
        assertEquals("123", session.patient());
        assertEquals(Optional.of("sub-1"), session.subject());
        assertEquals(Optional.of("refresh-1"), session.refreshToken());
        assertTrue(session.expiresAt().isPresent());
        assertFalse(session.isExpired());
        assertFalse(session.isLoggedOut());
    }

    @Test
    @DisplayName("Logout clears the session; expiry is by the stated lifetime")
    void logoutAndExpiry() {
        SmartAuthorizationSession session =
                new SmartAuthorizationSession("https://fhir.example.com", tokens("tok-2"), null);
        session.logout();

        assertTrue(session.isLoggedOut());
        assertFalse(session.isExpired(), "a logged-out session reports logged out, not expired");

        SmartTokenResponse expired = new SmartTokenResponse("tok-3", "Bearer", -10L,
                null, null, null, null, null);
        SmartAuthorizationSession old =
                new SmartAuthorizationSession("https://fhir.example.com", expired, null);
        assertTrue(old.isExpired());

        SmartTokenResponse timeless = new SmartTokenResponse("tok-4", "Bearer", null,
                null, null, null, null, null);
        SmartAuthorizationSession noExpiry =
                new SmartAuthorizationSession("https://fhir.example.com", timeless, null);
        assertFalse(noExpiry.isExpired(), "no stated expiry never reports expired");
        assertTrue(noExpiry.expiresAt().isEmpty());
    }

    @Test
    @DisplayName("Sessions are isolated: two grants never share a token")
    void sessionsAreIsolated() {
        SmartAuthorizationSession first =
                new SmartAuthorizationSession("https://a.example.com", tokens("tok-a"), null);
        SmartAuthorizationSession second =
                new SmartAuthorizationSession("https://b.example.com", tokens("tok-b"), null);

        assertEquals("tok-a", first.accessToken());
        assertEquals("tok-b", second.accessToken());
        first.logout();
        assertTrue(first.isLoggedOut());
        assertFalse(second.isLoggedOut());
    }

    @Test
    @DisplayName("No token, subject, or patient reaches toString or describe")
    void sessionTextHidesSecrets() {
        SmartAuthorizationSession session =
                new SmartAuthorizationSession("https://fhir.example.com", tokens("secret-tok"),
                        "secret-sub");

        assertFalse(session.toString().contains("secret-tok"), session.toString());
        assertFalse(session.toString().contains("secret-sub"), session.toString());
        assertFalse(session.toString().contains("refresh-1"), session.toString());
        assertFalse(session.describe().contains("secret-tok"), session.describe());
    }

    @Test
    @DisplayName("Redaction removes bearer tokens from any line bound for a dialog")
    void redactionRemovesTokens() {
        String line = "failed with Bearer secret-tok and code auth-code-1";
        String redacted = com.spiraleyes.fhirviewer.ui.ServerErrors.redact(line);

        assertFalse(redacted.contains("secret-tok"), redacted);
        assertTrue(redacted.contains("[redacted]"), redacted);
    }

    @Test
    @DisplayName("A JWKS document parses; garbage yields no keys")
    void jwksParses() throws Exception {
        String jwks = "{\"keys\":[" + new RSAKeyGenerator(2048).keyID("k1").generate()
                .toPublicJWK().toJSONString() + "]}";
        List<JWK> keys = SmartJwksFetcher.parse(jwks);

        assertEquals(1, keys.size());
        assertTrue(SmartJwksFetcher.parse(null).isEmpty());
        assertTrue(SmartJwksFetcher.parse("not json").isEmpty());
        assertTrue(SmartJwksFetcher.parse("{\"keys\":\"no\"}").isEmpty());
        assertTrue(SmartJwksFetcher.parse("{\"keys\":[{\"kty\":\"RSA\"}]}").isEmpty(),
                "an unparsable key is skipped, not fatal");
    }
}
