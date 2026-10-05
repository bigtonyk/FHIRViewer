package com.example.fhirviewer.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests URL joining, the one place where a configured base URL meets a request path.
 *
 * <p>Getting this wrong does not throw: it produces a URL that 404s, or worse, a request
 * that goes to the wrong host, so the cases are pinned down explicitly.
 */
public class RestUrlsTest {

    @Test
    @DisplayName("A leading slash on the path never doubles the separator")
    void leadingSlash() {
        assertEquals("https://example.com/fhir/metadata",
                RestUrls.join("https://example.com/fhir", "/metadata"));
        assertEquals("https://example.com/fhir/metadata",
                RestUrls.join("https://example.com/fhir", "metadata"));
    }

    @Test
    @DisplayName("A trailing slash on the base URL is tolerated, however many there are")
    void trailingSlashesOnTheBase() {
        assertEquals("https://example.com/fhir/metadata",
                RestUrls.join("https://example.com/fhir", "metadata"));
        assertEquals("https://example.com/fhir/metadata",
                RestUrls.join("https://example.com/fhir/", "metadata"));
        assertEquals("https://example.com/fhir/metadata",
                RestUrls.join("https://example.com/fhir///", "/metadata"));
    }

    @Test
    @DisplayName("A root base URL still produces an absolute URL")
    void rootBaseUrl() {
        assertEquals("https://example.com/metadata",
                RestUrls.join("https://example.com/", "metadata"));
    }

    @Test
    @DisplayName("A query string on the path is preserved")
    void queryOnThePath() {
        assertEquals("https://example.com/fhir/Patient?name=Smith",
                RestUrls.join("https://example.com/fhir", "Patient?name=Smith"));
    }

    @Test
    @DisplayName("An absolute path is passed through, so a vendor plugin may use a full URL")
    void absolutePathPassesThrough() {
        assertEquals("https://other.example.com/admin/status",
                RestUrls.join("https://example.com/fhir", "https://other.example.com/admin/status"));
        assertEquals("http://other.example.com/admin/status",
                RestUrls.join("https://example.com/fhir", "http://other.example.com/admin/status"));
    }

    @Test
    @DisplayName("Surrounding whitespace is trimmed from both halves")
    void whitespaceIsTrimmed() {
        assertEquals("https://example.com/fhir/metadata",
                RestUrls.join("  https://example.com/fhir  ", "  /metadata  "));
    }

    @Test
    @DisplayName("Absolute detection is by scheme, not by a prefix that merely looks like one")
    void absoluteDetection() {
        assertTrue(RestUrls.isAbsolute("https://example.com/fhir"));
        assertTrue(RestUrls.isAbsolute("HTTPS://example.com/fhir"));
        assertFalse(RestUrls.isAbsolute("httpx://example.com/fhir"));
        assertFalse(RestUrls.isAbsolute("/metadata"));
        assertFalse(RestUrls.isAbsolute(null));
    }

    @Test
    @DisplayName("A blank base URL or path is rejected rather than producing a broken URL")
    void blankPartsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RestUrls.join("  ", "metadata"));
        assertThrows(IllegalArgumentException.class, () -> RestUrls.join("https://example.com", "  "));
        assertThrows(NullPointerException.class, () -> RestUrls.join(null, "metadata"));
        assertThrows(NullPointerException.class, () -> RestUrls.join("https://example.com", null));
    }
}
