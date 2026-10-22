package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.server.smart.SmartAuthorizationSession;
import com.spiraleyes.fhirviewer.server.smart.SmartFhirAccess;
import com.spiraleyes.fhirviewer.server.smart.SmartTokenResponse;

/** Tests {@link SmartApi} with synthetic sessions: no real patient data, no network. */
class SmartApiTest {

    @Test
    void inactiveWhenNoSession() {
        SmartApi api = new SmartApi();
        assertFalse(api.isSessionActive());
    }

    @Test
    void inactiveWhenLoggedOut() {
        SmartAuthorizationSession session = session("tok", "example-1");
        session.logout();
        SmartApi api = new SmartApi(session, null);
        assertFalse(api.isSessionActive());
    }

    @Test
    void inactiveWhenExpired() {
        SmartTokenResponse expired = new SmartTokenResponse(
                "tok-dead", "Bearer", -10L, "patient/*.read", "example-1", null, null, null);
        SmartAuthorizationSession session =
                new SmartAuthorizationSession("http://example.com/fhir", expired, null);
        SmartApi api = new SmartApi(session, null);
        assertFalse(api.isSessionActive());
    }

    @Test
    void activeWhenSessionIsValid() {
        SmartAuthorizationSession session = session("tok-alice", "example-1");
        SmartApi api = new SmartApi(session, null);
        assertTrue(api.isSessionActive());
    }

    @Test
    void grantedScopesEmptyWhenNoSession() {
        SmartApi api = new SmartApi();
        assertTrue(api.grantedScopes().isEmpty());
    }

    @Test
    void grantedScopesParsesSpaceSeparated() {
        SmartAuthorizationSession session = session("tok", "example-1");
        SmartApi api = new SmartApi(session, null);
        assertEquals(List.of("patient/*.read"), api.grantedScopes());
    }

    @Test
    void grantedScopesEmptyWhenScopeIsBlank() {
        SmartTokenResponse tokens = new SmartTokenResponse(
                "tok", "Bearer", 3600L, "   ", "example-1", null, null, null);
        SmartAuthorizationSession session =
                new SmartAuthorizationSession("http://example.com/fhir", tokens, null);
        SmartApi api = new SmartApi(session, null);
        assertTrue(api.grantedScopes().isEmpty());
    }

    @Test
    void launchPatientIdNullWhenNoSession() {
        SmartApi api = new SmartApi();
        assertNull(api.launchPatientId());
    }

    @Test
    void launchPatientIdReturnsPatientFromSession() {
        SmartAuthorizationSession session = session("tok", "example-1");
        SmartApi api = new SmartApi(session, null);
        assertEquals("example-1", api.launchPatientId());
    }

    @Test
    void launchPatientIdNullWhenNoPatientContext() {
        SmartTokenResponse tokens = new SmartTokenResponse(
                "tok-identity", "Bearer", 3600L, "openid profile", null, null, null, null);
        SmartAuthorizationSession session =
                new SmartAuthorizationSession("http://example.com/fhir", tokens, null);
        SmartApi api = new SmartApi(session, null);
        assertNull(api.launchPatientId());
    }

    @Test
    void launchEncounterIdNullWhenNoSession() {
        SmartApi api = new SmartApi();
        assertNull(api.launchEncounterId());
    }

    @Test
    void launchEncounterIdReturnsEncounterFromSession() {
        SmartTokenResponse tokens = new SmartTokenResponse(
                "tok", "Bearer", 3600L, "patient/*.read", "example-1", "enc-42", null, null);
        SmartAuthorizationSession session =
                new SmartAuthorizationSession("http://example.com/fhir", tokens, null);
        SmartApi api = new SmartApi(session, null);
        assertEquals("enc-42", api.launchEncounterId());
    }

    @Test
    void readThrowsWhenNoSession() {
        SmartApi api = new SmartApi();
        assertThrows(ScriptApiException.class, () -> api.read("Patient", "1"));
    }

    @Test
    void readThrowsWhenLoggedOut() {
        SmartAuthorizationSession session = session("tok", "example-1");
        session.logout();
        SmartApi api = new SmartApi(session, null);
        assertThrows(ScriptApiException.class, () -> api.read("Patient", "1"));
    }

    @Test
    void readThrowsWhenExpired() {
        SmartTokenResponse expired = new SmartTokenResponse(
                "tok-dead", "Bearer", -10L, "patient/*.read", "example-1", null, null, null);
        SmartAuthorizationSession session =
                new SmartAuthorizationSession("http://example.com/fhir", expired, null);
        SmartApi api = new SmartApi(session, null);
        assertThrows(ScriptApiException.class, () -> api.read("Patient", "1"));
    }

    @Test
    void toStringNeverExposesTokens() {
        SmartAuthorizationSession session = session("secret-token-value", "example-1");
        SmartApi api = new SmartApi(session, null);
        String text = api.toString();
        assertNotNull(text);
        assertFalse(text.contains("secret-token-value"),
                "toString must not leak the access token: " + text);
        assertFalse(text.contains("Bearer"),
                "toString must not leak the bearer scheme: " + text);
    }

    @Test
    void updateSessionReplacesSession() {
        SmartApi api = new SmartApi();
        assertFalse(api.isSessionActive());

        SmartAuthorizationSession session = session("tok-alice", "example-1");
        api.updateSession(session, null);
        assertTrue(api.isSessionActive());
        assertEquals("example-1", api.launchPatientId());

        api.updateSession(null, null);
        assertFalse(api.isSessionActive());
        assertNull(api.launchPatientId());
    }

    private static SmartAuthorizationSession session(String accessToken, String patientId) {
        SmartTokenResponse tokens = new SmartTokenResponse(
                accessToken, "Bearer", 3600L, "patient/*.read", patientId,
                null, null, null);
        return new SmartAuthorizationSession("http://example.com/fhir", tokens, null);
    }
}