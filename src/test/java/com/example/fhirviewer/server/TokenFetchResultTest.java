package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading an OAuth grant document, and — more importantly — refusing everything else.
 *
 * <p>The refusal is the property with teeth. A token client that accepted an error body as
 * if it were a token would leave the user with a blank {@code Authorization} header and a
 * {@code 401} that looks like a permissions problem rather than a credential one.
 */
class TokenFetchResultTest {

    private static final String GRANT =
            "{ \"access_token\": \"abc.def.ghi\", \"token_type\": \"Bearer\", \"expires_in\": 3600 }";

    @Test
    @DisplayName("A real grant is read, with its type and lifetime")
    void readsAGrant() {
        TokenFetchResult result = TokenFetchResult.from(GRANT).orElseThrow();

        assertEquals("abc.def.ghi", result.accessToken());
        assertEquals("Bearer", result.tokenType());
        assertEquals(3600L, result.expiresInSeconds());
        assertTrue(result.hasExpiry());
    }

    @Test
    @DisplayName("A grant with no expiry is still a grant")
    void grantWithoutExpiry() {
        TokenFetchResult result =
                TokenFetchResult.from("{ \"access_token\": \"abc\" }").orElseThrow();

        assertEquals("abc", result.accessToken());
        assertNull(result.expiresInSeconds());
        assertFalse(result.hasExpiry());
        assertFalse(result.describe().contains("expires"), result.describe());
    }

    @Test
    @DisplayName("An OAuth error body is not a token")
    void errorBodyIsNotAToken() {
        assertTrue(TokenFetchResult.from(
                "{ \"error\": \"invalid_client\", \"error_description\": \"bad secret\" }").isEmpty());
    }

    @Test
    @DisplayName("A missing access_token is not a token")
    void missingAccessTokenIsNotAToken() {
        assertTrue(TokenFetchResult.from("{ \"token_type\": \"Bearer\" }").isEmpty());
        assertTrue(TokenFetchResult.from("{ \"access_token\": \"\" }").isEmpty());
        assertTrue(TokenFetchResult.from("{ \"access_token\": 42 }").isEmpty());
    }

    @Test
    @DisplayName("An HTML login page is not a token")
    void htmlPageIsNotAToken() {
        assertTrue(TokenFetchResult.from("<html><body>Sign in</body></html>").isEmpty());
    }

    @Test
    @DisplayName("Nothing at all is not a token")
    void nothingIsNotAToken() {
        assertTrue(TokenFetchResult.from(null).isEmpty());
        assertTrue(TokenFetchResult.from("").isEmpty());
        assertTrue(TokenFetchResult.from("   ").isEmpty());
        assertTrue(TokenFetchResult.from("[1,2,3]").isEmpty());
    }

    @Test
    @DisplayName("Truncated output does not throw")
    void truncatedJsonDoesNotThrow() {
        assertTrue(TokenFetchResult.from("{ \"access_token\": \"abc").isEmpty());
    }

    @Test
    @DisplayName("toString never prints the token")
    void toStringHidesTheToken() {
        String described = TokenFetchResult.from(GRANT).orElseThrow().toString();

        assertFalse(described.contains("abc.def.ghi"), described);
        assertTrue(described.contains("expires in 3600s"), described);
    }

    @Test
    @DisplayName("describe() reports the lifetime without revealing the token")
    void describeReportsExpiryOnly() {
        Optional<TokenFetchResult> result = TokenFetchResult.from(GRANT);

        assertTrue(result.isPresent());
        assertTrue(result.get().describe().contains("Bearer"), result.get().describe());
        assertFalse(result.get().describe().contains("abc.def.ghi"), result.get().describe());
    }

    @Test
    @DisplayName("An expires_in that is not a number is ignored rather than crashing")
    void nonNumericExpiryIsIgnored() {
        TokenFetchResult result = TokenFetchResult
                .from("{ \"access_token\": \"abc\", \"expires_in\": \"soon\" }").orElseThrow();

        assertNull(result.expiresInSeconds());
        assertFalse(result.hasExpiry());
    }

    @Test
    @DisplayName("The client refuses an endpoint URL it cannot use, before any network")
    void unusableEndpointsAreRefused() {
        TokenEndpointClient client = new TokenEndpointClient();

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> client.fetch("", "client", "s".toCharArray(), null))
                .getMessage().contains("token endpoint"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> client.fetch("not a url", "client", "s".toCharArray(), null))
                .getMessage().contains("token endpoint"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> client.fetch("/relative", "client", "s".toCharArray(), null))
                .getMessage().contains("http"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> client.fetch("https://auth.example.com/token", "", "s".toCharArray(), null))
                .getMessage().contains("client id"));
    }
}