package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The authorization URL: required parameters, PKCE, audience, nonce rules,
 * and redirect-URI validation.
 */
class SmartAuthorizationRequestTest {

    private static final String ENDPOINT = "https://auth.example.com/authorize";
    private static final String REDIRECT = "http://127.0.0.1:54321/callback";

    @Test
    @DisplayName("The URL carries every required parameter")
    void urlCarriesRequiredParameters() {
        Pkce pkce = Pkce.ofVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        SmartAuthState state = SmartAuthState.of("state-abc", "nonce-xyz");

        URI uri = SmartAuthorizationRequest.to(ENDPOINT)
                .clientId("public-client")
                .redirectUri(REDIRECT)
                .scopes("launch/patient patient/*.read")
                .audience("https://fhir.example.com")
                .authState(state)
                .pkce(pkce)
                .build()
                .toUri();

        assertEquals("https", uri.getScheme());
        assertEquals("auth.example.com", uri.getHost());
        Map<String, String> params = paramsOf(uri);
        assertEquals("code", params.get("response_type"));
        assertEquals("public-client", params.get("client_id"));
        assertEquals(REDIRECT, params.get("redirect_uri"));
        assertEquals("state-abc", params.get("state"));
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                params.get("code_challenge"));
        assertEquals("S256", params.get("code_challenge_method"));
        assertEquals("https://fhir.example.com", params.get("aud"));
        // No OIDC scope here, so no nonce.
        assertFalse(params.containsKey("nonce"), "no OIDC scope means no nonce: " + params);
    }

    @Test
    @DisplayName("OIDC scopes add a nonce; plain scopes do not")
    void nonceFollowsOidcScopes() {
        assertTrue(SmartAuthorizationRequest.needsNonce("openid fhirUser launch/patient"));
        assertTrue(SmartAuthorizationRequest.needsNonce("patient/*.read openid"));
        assertFalse(SmartAuthorizationRequest.needsNonce("launch/patient patient/*.read"));
        assertFalse(SmartAuthorizationRequest.needsNonce(""));
        assertFalse(SmartAuthorizationRequest.needsNonce(null));

        URI withOpenid = SmartAuthorizationRequest.to(ENDPOINT)
                .clientId("c").redirectUri(REDIRECT).scopes("openid launch/patient")
                .audience("https://fhir.example.com").build().toUri();
        assertTrue(paramsOf(withOpenid).containsKey("nonce"), "openid needs a nonce");
    }

    @Test
    @DisplayName("A non-loopback redirect URI is refused before any browser opens")
    void nonLoopbackRedirectIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SmartAuthorizationRequest.to(ENDPOINT)
                .clientId("c").redirectUri("https://evil.example.com/callback").build());
        assertThrows(IllegalArgumentException.class, () -> SmartAuthorizationRequest.to(ENDPOINT)
                .clientId("c").redirectUri("custom-scheme:/callback").build());
        assertThrows(IllegalArgumentException.class, () -> SmartAuthorizationRequest.to(ENDPOINT)
                .clientId("c").redirectUri("").build());
    }

    @Test
    @DisplayName("A missing client id or endpoint is refused")
    void missingFieldsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SmartAuthorizationRequest.to(ENDPOINT)
                .redirectUri(REDIRECT).build());
        assertThrows(IllegalArgumentException.class,
                () -> SmartAuthorizationRequest.to("not a url")
                        .clientId("c").redirectUri(REDIRECT).build());
        assertThrows(IllegalArgumentException.class,
                () -> SmartAuthorizationRequest.to("http://remote.example.com/authorize")
                        .clientId("c").redirectUri(REDIRECT).build());
    }

    @Test
    @DisplayName("toString never prints state, nonce, verifier, or challenge")
    void toStringHidesSecrets() {
        SmartAuthorizationRequest request = SmartAuthorizationRequest.to(ENDPOINT)
                .clientId("c").redirectUri(REDIRECT).scopes("openid").build();
        String text = request.toString();

        assertFalse(text.contains(request.authState().state()), text);
        assertFalse(text.contains(request.authState().nonce()), text);
        assertFalse(text.contains(request.pkce().verifier()), text);
        assertFalse(text.contains(request.pkce().challenge()), text);
    }

    private static Map<String, String> paramsOf(URI uri) {
        Map<String, String> params = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int at = pair.indexOf('=');
            try {
                String name = java.net.URLDecoder.decode(pair.substring(0, at), "UTF-8");
                String value = java.net.URLDecoder.decode(pair.substring(at + 1), "UTF-8");
                params.put(name, value);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return params;
    }
}
