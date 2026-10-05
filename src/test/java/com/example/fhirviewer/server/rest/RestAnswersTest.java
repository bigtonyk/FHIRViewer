package com.example.fhirviewer.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Classifying whatever a server sent back.
 *
 * <p>The cases that matter most are the awkward ones: a body that is not what its content
 * type claimed, a malformed document on a 500, and a refusal that arrived as a
 * {@code 200}. Each has to produce a readable answer rather than an exception, because the
 * exception would replace the text the user needed with a parse error.
 */
class RestAnswersTest {

    private static final String PATIENT_JSON =
            "{ \"resourceType\": \"Patient\", \"id\": \"example\", \"name\": [ { \"family\": \"Smith\" } ] }";

    private static final String BUNDLE_JSON =
            "{ \"resourceType\": \"Bundle\", \"type\": \"searchset\", \"entry\": [] }";

    private static final String OUTCOME_JSON =
            "{ \"resourceType\": \"OperationOutcome\", \"issue\": [ "
                    + "{ \"severity\": \"error\", \"code\": \"unknown\", "
                    + "\"diagnostics\": \"Unknown search parameter 'foo'\" } ] }";

    private static RestAnswer classify(String contentType, String body, int status) {
        return RestAnswers.classify(
                RestResponse.of(status, RestHeaders.builder().contentType(contentType).build(),
                        body, List.of()));
    }

    @Test
    @DisplayName("A Patient is a resource, and is parsed")
    void patientIsAFhirResource() {
        RestAnswer answer = classify("application/fhir+json", PATIENT_JSON, 200);

        assertEquals(RestAnswer.Kind.FHIR_RESOURCE, answer.kind());
        assertNotNull(answer.resource(), "the resource is what the Open button needs");
        assertEquals("Patient", answer.resource().fhirType());
    }

    @Test
    @DisplayName("A Bundle is told apart from a single resource")
    void bundleIsItsOwnKind() {
        assertEquals(RestAnswer.Kind.BUNDLE, classify("application/fhir+json", BUNDLE_JSON, 200).kind());
    }

    @Test
    @DisplayName("An OperationOutcome is diagnostics, not a resource to open")
    void operationOutcomeIsDiagnosed() {
        RestAnswer answer = classify("application/fhir+json", OUTCOME_JSON, 400);

        assertEquals(RestAnswer.Kind.OPERATION_OUTCOME, answer.kind());
        assertFalse(answer.isSuccess());
        assertTrue(RestAnswers.diagnostics(answer).get(0).contains("Unknown search parameter"),
                "the server's own words reach the user: " + RestAnswers.diagnostics(answer));
    }

    @Test
    @DisplayName("A FHIR resource sent as XML is still a resource")
    void xmlResourceIsParsed() {
        String xml = "<Patient xmlns=\"http://hl7.org/fhir\"><id value=\"example\"/></Patient>";

        RestAnswer answer = classify("application/fhir+xml", xml, 200);

        assertEquals(RestAnswer.Kind.FHIR_RESOURCE, answer.kind());
        assertNotNull(answer.resource());
    }

    @Test
    @DisplayName("Plain JSON that is not FHIR is still JSON, with nothing to open")
    void vendorJsonIsJson() {
        RestAnswer answer = classify("application/json", "{ \"jobId\": 42 }", 200);

        assertEquals(RestAnswer.Kind.JSON, answer.kind());
        assertNull(answer.resource(), "there is nothing here to open in the viewer");
    }

    @Test
    @DisplayName("A log extract served as text is text, even when it starts with a brace")
    void textIsNotGuessedAsFhir() {
        // The case the content type exists to settle: a multi-line log whose first line is
        // a JSON fragment would otherwise be parsed as FHIR.
        RestAnswer answer = classify("text/plain", "{ not really json at all", 200);

        assertEquals(RestAnswer.Kind.TEXT, answer.kind());
        assertNull(answer.resource());
    }

    @Test
    @DisplayName("A malformed body is classified, never thrown over")
    void malformedJsonDoesNotThrow() {
        RestAnswer answer = classify("application/fhir+json", "{ \"resourceType\": ", 500);

        assertEquals(RestAnswer.Kind.JSON, answer.kind());
        assertEquals(500, answer.statusCode());
        assertFalse(answer.isSuccess());
    }

@Test
    @DisplayName("An empty body is EMPTY whatever the content type says")
    void noBodyIsEmpty() {
        assertEquals(RestAnswer.Kind.EMPTY, classify("application/fhir+json", "", 204).kind());
        assertEquals(RestAnswer.Kind.EMPTY, classify("application/fhir+json", null, 204).kind());
        assertEquals(0, classify("application/fhir+json", null, 204).byteCount());
    }

    @Test
    @DisplayName("The raw body is kept even when a resource was parsed out of it")
    void rawBodyIsKeptAlongsideTheResource() {
        RestAnswer answer = classify("application/fhir+json", PATIENT_JSON, 200);

        assertEquals(PATIENT_JSON, answer.body(),
                "a user debugging a REST call needs the bytes, not a re-serialisation");
    }

    @Test
    @DisplayName("Size is counted in bytes, not characters")
    void sizeIsInBytes() {
        String nonAscii = "{ \"name\": \"Bäcker\" }";

        assertEquals(nonAscii.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                classify("application/json", nonAscii, 200).byteCount());
    }

    @Test
    @DisplayName("The summary carries kind, status, size and duration")
    void summaryIsInformative() {
        String summary = RestAnswers.summary(classify("application/fhir+json", PATIENT_JSON, 200), 87);

        assertTrue(summary.contains("HTTP 200"), summary);
        assertTrue(summary.contains("87 ms"), summary);
        assertTrue(summary.contains("B"), summary);
    }

    @Test
    @DisplayName("A refusal says why, using the server's own words")
    void summaryExplainsAFailure() {
        String summary = RestAnswers.summary(classify("application/fhir+json", OUTCOME_JSON, 400));

        assertTrue(summary.contains("HTTP 400"), summary);
        assertTrue(summary.contains("Unknown search parameter"), summary);
    }

    @Test
    @DisplayName("A null response is an empty answer, not a crash")
    void nullResponseIsSafe() {
        assertEquals(RestAnswer.Kind.EMPTY, RestAnswers.classify(null).kind());
        assertEquals("The request produced no answer.", RestAnswers.summary(null));
        assertTrue(RestAnswers.diagnostics(null).isEmpty());
    }

    @Test
    @DisplayName("toString carries no body")
    void toStringOmitsTheBody() {
        assertFalse(classify("application/fhir+json", PATIENT_JSON, 200).toString().contains("Smith"),
                "a response body is patient data");
    }

    @Test
    @DisplayName("Byte counts are humanised")
    void byteCountsAreReadable() {
        assertEquals("0 B", RestAnswers.formatBytes(0));
        assertEquals("812 B", RestAnswers.formatBytes(812));
        assertEquals("4.0 KB", RestAnswers.formatBytes(4096));
        assertEquals("1.5 MB", RestAnswers.formatBytes(1024 * 1024 * 3 / 2));
    }
}