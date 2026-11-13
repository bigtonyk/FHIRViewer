package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The URL rules discovery depends on. No sockets: every rule here is pure, and that is the
 * point of having them in one place.
 */
class SmartUrlsTest {

    @Test
    @DisplayName("A base URL keeps its path — only trailing slashes go")
    void basePathsSurviveNormalization() {
        assertEquals("https://example.com/fhir",
                SmartUrls.normalizeFhirBase("https://example.com/fhir"));
        assertEquals("https://example.com/fhir",
                SmartUrls.normalizeFhirBase("  https://example.com/fhir/  "));
        // The mounted-path case this rule exists for: a server below /dc/fhir/R4 would be
        // sent to the wrong document if normalization stripped to the origin.
        assertEquals("https://ehr.example.org/dc/fhir/R4",
                SmartUrls.normalizeFhirBase("https://ehr.example.org/dc/fhir/R4/"));
    }

    @Test
    @DisplayName("A URL with no scheme gets one — https, or http on this machine only")
    void schemeIsAddedDeliberately() {
        assertEquals("https://example.com/fhir", SmartUrls.normalizeFhirBase("example.com/fhir"));
        assertEquals("http://localhost:8080/fhir",
                SmartUrls.normalizeFhirBase("localhost:8080/fhir"));
        assertEquals("http://127.0.0.1/fhir", SmartUrls.normalizeFhirBase("127.0.0.1/fhir"));
    }

    @Test
    @DisplayName("An explicit scheme is never changed — no silent downgrade")
    void explicitSchemesAreNeverRewritten() {
        assertEquals("http://example.com/fhir",
                SmartUrls.normalizeFhirBase("http://example.com/fhir"),
                "the user asked for http; second-guessing them would be a silent change");
        assertEquals("https://127.0.0.1/fhir",
                SmartUrls.normalizeFhirBase("https://127.0.0.1/fhir"),
                "and the loopback exception only ever permits http, never forces it");
    }

    @Test
    @DisplayName("A URL that cannot become a request is refused, with a reason")
    void unusableUrlsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> SmartUrls.normalizeFhirBase(null));
        assertThrows(IllegalArgumentException.class,
                () -> SmartUrls.normalizeFhirBase("   "));
        assertThrows(IllegalArgumentException.class,
                () -> SmartUrls.normalizeFhirBase("https://example.com/fhir#section"),
                "a fragment is never sent to a server, so keeping it would silently drop it");
    }

    @Test
    @DisplayName("The discovery URL is the base plus the one well-known path")
    void discoveryUrlIsBuiltFromTheBase() {
        assertEquals("https://example.com/fhir/.well-known/smart-configuration",
                SmartUrls.discoveryUrl("https://example.com/fhir"));
        assertEquals("https://example.com/fhir/.well-known/smart-configuration",
                SmartUrls.discoveryUrl("https://example.com/fhir/"),
                "a trailing slash must not become a doubled separator");
        // Idempotence: a caller that already holds the full URL is not made to unwrap it.
        assertEquals("https://example.com/fhir/.well-known/smart-configuration",
                SmartUrls.discoveryUrl("https://example.com/fhir/.well-known/smart-configuration"));
    }

    @Test
    @DisplayName("Loopback means this machine, and nothing else")
    void loopbackIsNarrow() {
        assertTrue(SmartUrls.isLoopback("localhost"));
        assertTrue(SmartUrls.isLoopback("127.0.0.1"));
        assertTrue(SmartUrls.isLoopback("127.1.2.3"));
        assertTrue(SmartUrls.isLoopback("[::1]"), "an IPv6 literal arrives bracketed");
        assertFalse(SmartUrls.isLoopback("example.com"));
        assertFalse(SmartUrls.isLoopback("1270.example.com"),
                "a host that merely starts with the digits is not this machine");
        assertFalse(SmartUrls.isLoopback(null));
    }
}
