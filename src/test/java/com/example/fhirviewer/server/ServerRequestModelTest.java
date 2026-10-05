package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.rest.RestFailures;

/**
 * The request types Phase 4 added, and the failure rules they depend on.
 *
 * <p>Two of these are security-relevant rather than cosmetic. An operation name is used
 * as a URL path segment, so an unvalidated one could address a different resource
 * entirely; and the HTTP-status table is what both transports now share, so a change
 * that quietly narrowed it would stop the UI offering reload-or-force on a conflicting
 * write.
 */
public class ServerRequestModelTest {

    @Test
    @DisplayName("An operation request builds the URL its level implies")
    void buildsTheUrlItsLevelImplies() {
        assertEquals("$everything", FhirOperationRequest.onServer("everything").path());
        assertEquals("Patient/$everything", FhirOperationRequest.onType("Patient", "everything").path());
        assertEquals("Patient/123/$everything",
                FhirOperationRequest.onInstance("Patient", "123", "everything").path());
    }

    @Test
    @DisplayName("A leading dollar is optional on the way in and always present on the way out")
    void normalisesTheOperationName() {
        assertEquals("Patient/$validate", FhirOperationRequest.onType("Patient", "validate").path());
        assertEquals("Patient/$validate", FhirOperationRequest.onType("Patient", "$validate").path());
        assertEquals("value-set", FhirOperationRequest.onServer("value-set").name(),
                "a hyphenated name is a legal operation name");
    }

    @Test
    @DisplayName("A name that could change the request path is refused before any request is made")
    void refusesANameThatCouldRedirectTheRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> FhirOperationRequest.onType("Patient", "everything/../Observation"));
        assertThrows(IllegalArgumentException.class,
                () -> FhirOperationRequest.onType("Patient", "everything?x=1"));
        assertThrows(IllegalArgumentException.class, () -> FhirOperationRequest.onType("Patient", "$"));
        assertThrows(IllegalArgumentException.class, () -> FhirOperationRequest.onType("Patient", " "));
        assertThrows(IllegalArgumentException.class, () -> FhirOperationRequest.onType("Patient", null));
    }

    @Test
    @DisplayName("A resource type or id that could change the path is refused the same way")
    void refusesASegmentThatCouldRedirectTheRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> FhirOperationRequest.onType("Patient/$x", "everything"));
        assertThrows(IllegalArgumentException.class,
                () -> FhirOperationRequest.onInstance("Patient", "1/Observation/2", "everything"));
        assertThrows(IllegalArgumentException.class, () -> FhirOperationRequest.onType(null, "everything"));
        // A server-level operation needs neither, so neither is invented for it.
        assertNull(FhirOperationRequest.onServer("everything").resourceType());
    }


    @Test
    @DisplayName("Parameters keep the order they were supplied in, and a null map is an empty one")
    void parametersKeepTheirOrder() {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("start", "2020-01-01");
        ordered.put("end", "2020-02-01");
        ordered.put("name", "Smith");

        FhirOperationRequest request = FhirOperationRequest.onType("Patient", "everything", ordered);

        // Asserted on the record's own accessor as well as on orderedParameters():
        // Map.copyOf would have passed the second and failed the first, silently
        // shuffling a Parameters body the caller had assembled on purpose.
        assertEquals(List.of("start", "end", "name"), List.copyOf(request.parameters().keySet()));
        assertEquals(List.of("start", "end", "name"), List.copyOf(request.orderedParameters().keySet()));
        assertEquals("Smith", request.parameters().get("name"));
        assertTrue(FhirOperationRequest.onServer("everything").parameters().isEmpty());
        assertTrue(FhirOperationRequest.onType("Patient", "everything", null).parameters().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> request.parameters().put("extra", "x"),
                "the request is immutable, so a caller cannot add a parameter after building it");
    }

    @Test
    @DisplayName("An operation request never prints its parameter values")
    void neverPrintsParameterValues() {
        FhirOperationRequest request = FhirOperationRequest.onType("Patient", "everything",
                Map.of("name", "Smith Jane"));

        assertEquals("$everything (Patient) with 1 parameters", request.toString());
        assertFalse(request.toString().contains("Smith"));
        assertEquals("$everything (Patient)", request.describe());
    }

    @Test
    @DisplayName("A patch format is the content type the server reads it by")
    void patchFormatIsTheContentType() {
        assertEquals("application/json-patch+json", PatchFormat.JSON_PATCH.contentType());
        assertEquals("application/merge-patch+json", PatchFormat.JSON_MERGE_PATCH.contentType());
        assertEquals("application/xml-patch+xml", PatchFormat.XML_PATCH.contentType());
        assertTrue(PatchFormat.JSON_PATCH.isJson());
        assertFalse(PatchFormat.XML_PATCH.isJson());
    }

    @Test
    @DisplayName("A content type with parameters on it still names its patch format")
    void readsAPatchFormatFromAContentType() {
        assertEquals(PatchFormat.JSON_MERGE_PATCH,
                PatchFormat.forContentType("application/merge-patch+json; charset=utf-8").orElseThrow());
        assertEquals(PatchFormat.XML_PATCH,
                PatchFormat.forContentType("  APPLICATION/XML-PATCH+XML ").orElseThrow());
        assertTrue(PatchFormat.forContentType("text/plain").isEmpty());
        assertTrue(PatchFormat.forContentType(null).isEmpty());
    }

    @Test
    @DisplayName("Both transports read the same meaning out of an HTTP status")
    void oneStatusTableForBothTransports() {
        assertEquals(ServerOperationException.Kind.BAD_REQUEST, RestFailures.kindOfStatus(400));
        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, RestFailures.kindOfStatus(401));
        assertEquals(ServerOperationException.Kind.FORBIDDEN, RestFailures.kindOfStatus(403));
        assertEquals(ServerOperationException.Kind.NOT_FOUND, RestFailures.kindOfStatus(404));
        assertEquals(ServerOperationException.Kind.CONFLICT, RestFailures.kindOfStatus(409));
        assertEquals(ServerOperationException.Kind.CONFLICT, RestFailures.kindOfStatus(412));
        assertEquals(ServerOperationException.Kind.UNSUPPORTED, RestFailures.kindOfStatus(501));
        assertEquals(ServerOperationException.Kind.SERVER_ERROR, RestFailures.kindOfStatus(503));
    }

    @Test
    @DisplayName("A failure keeps the server's own words, and blank is no words")
    void keepsTheServersOwnWords() {
        ServerOperationException withReason = new ServerOperationException(
                ServerOperationException.Kind.BAD_REQUEST, "search: unknown parameter",
                400, null, "Unknown search parameter 'foo'");

        assertEquals("Unknown search parameter 'foo'", withReason.diagnostics());
        assertTrue(withReason.hasDiagnostics());
        assertEquals("search: unknown parameter (HTTP 400)", withReason.displayMessage());

        ServerOperationException silent = new ServerOperationException(
                ServerOperationException.Kind.NOT_FOUND, "read: gone", 404);

        assertNull(silent.diagnostics());
        assertFalse(silent.hasDiagnostics());
    }
}
