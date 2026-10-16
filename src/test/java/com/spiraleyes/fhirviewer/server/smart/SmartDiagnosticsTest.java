package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SmartDiagnostics}: events record what happened, nothing records a secret,
 * clear removes everything, and export produces redacted text.
 */
class SmartDiagnosticsTest {

    @Test
    @DisplayName("Records events and returns them in order")
    void recordsEventsInOrder() {
        SmartDiagnostics diagnostics = new SmartDiagnostics();
        diagnostics.record(SmartDiagnosticsEvent.Phase.DISCOVERY,
                SmartDiagnosticsEvent.Kind.STARTED, "Discovery started", "https://example.org");
        diagnostics.record(SmartDiagnosticsEvent.Phase.TOKEN_EXCHANGE,
                SmartDiagnosticsEvent.Kind.SUCCESS, "Token received", null);

        List<SmartDiagnosticsEvent> events = diagnostics.events();
        assertEquals(2, events.size());
        assertEquals(SmartDiagnosticsEvent.Phase.DISCOVERY, events.get(0).phase());
        assertEquals(SmartDiagnosticsEvent.Kind.STARTED, events.get(0).kind());
        assertEquals("Discovery started", events.get(0).message());
        assertEquals("https://example.org", events.get(0).detail());
        assertEquals(SmartDiagnosticsEvent.Phase.TOKEN_EXCHANGE, events.get(1).phase());
        assertEquals(SmartDiagnosticsEvent.Kind.SUCCESS, events.get(1).kind());
    }

    @Test
    @DisplayName("Every event carries a timestamp")
    void eventsAreTimed() {
        SmartDiagnostics diagnostics = new SmartDiagnostics();
        Instant before = Instant.now();
        diagnostics.record(SmartDiagnosticsEvent.Phase.SESSION,
                SmartDiagnosticsEvent.Kind.CREATED, "Session created", null);
        Instant after = Instant.now();

        SmartDiagnosticsEvent event = diagnostics.events().get(0);
        assertFalse(event.instant().isBefore(before));
        assertFalse(event.instant().isAfter(after.plusNanos(100_000_000)));
    }

    @Test
    @DisplayName("Redacts authorization codes in URLs")
    void redactsAuthorizationCodes() {
        SmartDiagnostics diagnostics = new SmartDiagnostics();
        diagnostics.record(SmartDiagnosticsEvent.Phase.CALLBACK,
                SmartDiagnosticsEvent.Kind.RECEIVED,
                "Callback received", "http://127.0.0.1:54321/callback?code=abc123def456");

        String detail = diagnostics.events().get(0).detail();
        assertFalse(detail.contains("abc123def456"));
        assertTrue(detail.contains("code=[redacted]"));
    }

    @Test
    @DisplayName("Redacts PKCE verifiers in URLs")
    void redactsPkceVerifiers() {
        SmartDiagnostics diagnostics = new SmartDiagnostics();
        diagnostics.record(SmartDiagnosticsEvent.Phase.TOKEN_EXCHANGE,
                SmartDiagnosticsEvent.Kind.REQUEST_INITIATED,
                "Exchanging code", "http://127.0.0.1:54321/callback?code_verifier=dGVzdC12ZXJpZmllcg");

        String detail = diagnostics.events().get(0).detail();
        assertFalse(detail.contains("dGVzdC12ZXJpZmllcg"));
        assertTrue(detail.contains("code_verifier=[redacted]"));
    }

    @Test
    @DisplayName("Redacts patient references in messages and details")
    void redactsPatientReferences() {
        SmartDiagnostics diagnostics = new SmartDiagnostics();
        diagnostics.record(SmartDiagnosticsEvent.Phase.FHIR_ACCESS,
                SmartDiagnosticsEvent.Kind.REQUEST_COMPLETED,
                "Read Patient/12345 successfully", "GET /fhir/Patient/12345 status=200");

        SmartDiagnosticsEvent event = diagnostics.events().get(0);
        assertFalse(event.message().contains("12345"));
        assertFalse(event.detail().contains("12345"));
        assertTrue(event.message().contains("patient/[redacted]"));
        assertTrue(event.detail().contains("patient/[redacted]"));
    }

    @Test
    @DisplayName("Clear removes every event")
    void clearRemovesAllEvents() {
        SmartDiagnostics diagnostics = new SmartDiagnostics();
        diagnostics.record(SmartDiagnosticsEvent.Phase.DISCOVERY,
                SmartDiagnosticsEvent.Kind.STARTED, "Discovery started", null);
        diagnostics.record(SmartDiagnosticsEvent.Phase.SESSION,
                SmartDiagnosticsEvent.Kind.LOGGED_OUT, "Session cleared", null);

        assertEquals(2, diagnostics.events().size());
        diagnostics.clear();
        assertTrue(diagnostics.events().isEmpty());
    }

    @Test
    @DisplayName("Export produces redacted text with a header")
    void exportIsRedactedAndStructured() {
        SmartDiagnostics diagnostics = new SmartDiagnostics();
        diagnostics.record(SmartDiagnosticsEvent.Phase.TOKEN_EXCHANGE,
                SmartDiagnosticsEvent.Kind.FAILED,
                "Authorization: Bearer secret-token-123 failed", null);

        String export = diagnostics.export();
        assertTrue(export.contains("SMART Launch Diagnostics Export"));
        assertTrue(export.contains("Total events: 1"));
        assertFalse(export.contains("secret-token-123"));
        assertTrue(export.contains("Bearer [redacted]"));
    }

    @Test
    @DisplayName("Never crashes on null inputs")
    void handlesNullInputsGracefully() {
        SmartDiagnostics diagnostics = new SmartDiagnostics();
        assertDoesNotThrow(() -> diagnostics.record(null, null, null, null));
        assertDoesNotThrow(diagnostics::export);
        assertDoesNotThrow(diagnostics::clear);
        assertDoesNotThrow(diagnostics::events);
    }
}
