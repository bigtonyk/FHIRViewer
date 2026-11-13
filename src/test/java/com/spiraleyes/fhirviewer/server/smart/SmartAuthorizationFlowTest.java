package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Builds the authorization request for both launch modes and asserts what
 * each sends: standalone sends {@code aud} and no {@code launch}; EHR launch
 * sends {@code launch} and no {@code aud}.
 */
class SmartAuthorizationFlowTest {

    private static SmartLaunchConfig standalone() {
        return SmartLaunchConfig.builder()
                .id("id-standalone")
                .name("Standalone clinic")
                .issuer("https://example.org/fhir")
                .mode(SmartLaunchMode.STANDALONE)
                .clientId("public-client")
                .redirectUri("http://127.0.0.1:54321/callback")
                .scopes("openid launch/patient patient/*.read")
                .build();
    }

    private static SmartLaunchConfig ehrLaunch() {
        return SmartLaunchConfig.builder()
                .id("id-ehr")
                .name("Ward EHR launch")
                .issuer("https://example.org/fhir")
                .mode(SmartLaunchMode.EHR_LAUNCH)
                .clientId("public-client")
                .redirectUri("http://127.0.0.1:54321/callback")
                .scopes("openid launch/patient patient/*.read")
                .launchParameter("launch-xyz-789")
                .build();
    }

    private static SmartConfiguration discovered() {
        return SmartConfiguration.builder()
                .issuer("https://example.org/fhir")
                .authorizationEndpoint("https://example.org/fhir/authorize")
                .tokenEndpoint("https://example.org/fhir/token")
                .codeChallengeMethodsSupported(java.util.List.of("S256"))
                .build();
    }

    private static SmartAuthState authState() {
        return SmartAuthState.of("state-abc", "nonce-xyz");
    }

    private static Pkce pkce() {
        return Pkce.ofVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
    }

    @Test
    @DisplayName("Standalone launch sends aud (issuer) and no launch token")
    void standaloneSendsAudienceNotLaunch() throws Exception {
        SmartAuthorizationRequest request = SmartAuthorizationFlow.requestFor(
                standalone(), discovered(), authState(), pkce(),
                "http://127.0.0.1:54321/callback");

        java.net.URI uri = request.toUri();
        java.util.Map<String, String> params = paramsOf(uri);
        assertEquals("https://example.org/fhir", params.get("aud"),
                "standalone sends the FHIR base URL as aud: " + params);
        assertFalse(params.containsKey("launch"),
                "standalone sends no launch token: " + params);
    }

    @Test
    @DisplayName("EHR launch sends launch token and no aud")
    void ehrLaunchSendsLaunchNotAudience() throws Exception {
        SmartAuthorizationRequest request = SmartAuthorizationFlow.requestFor(
                ehrLaunch(), discovered(), authState(), pkce(),
                "http://127.0.0.1:54321/callback");

        java.net.URI uri = request.toUri();
        java.util.Map<String, String> params = paramsOf(uri);
        assertEquals("launch-xyz-789", params.get("launch"),
                "EHR launch sends the EHR launch token: " + params);
        assertFalse(params.containsKey("aud"),
                "EHR launch sends no aud: " + params);
    }

    @Test
    @DisplayName("EHR launch without a launch parameter is refused")
    void ehrLaunchWithoutLaunchParameterIsRefused() {
        SmartLaunchConfig bad = SmartLaunchConfig.builder()
                .id("id-bad")
                .name("EHR without context")
                .issuer("https://example.org/fhir")
                .mode(SmartLaunchMode.EHR_LAUNCH)
                .clientId("public-client")
                .redirectUri("http://127.0.0.1:54321/callback")
                .build();

        assertThrows(SmartAuthorizationException.class, () ->
                SmartAuthorizationFlow.requestFor(
                        bad, discovered(), authState(), pkce(),
                        "http://127.0.0.1:54321/callback"));
    }

    @Test
    @DisplayName("Every request carries response_type, state, PKCE, scopes")
    void everyRequestCarriesRequiredParameters() throws Exception {
        SmartAuthorizationRequest request = SmartAuthorizationFlow.requestFor(
                standalone(), discovered(), authState(), pkce(),
                "http://127.0.0.1:54321/callback");

        java.net.URI uri = request.toUri();
        java.util.Map<String, String> params = paramsOf(uri);
        assertEquals("code", params.get("response_type"));
        assertEquals("public-client", params.get("client_id"));
        assertEquals("http://127.0.0.1:54321/callback", params.get("redirect_uri"));
        assertEquals("state-abc", params.get("state"));
        assertEquals("S256", params.get("code_challenge_method"));
        assertEquals("openid launch/patient patient/*.read", params.get("scope"));
    }

    private static java.util.Map<String, String> paramsOf(java.net.URI uri) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
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
