package com.example.fhirviewer.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the parser that keeps a server's {@code OperationOutcome} usable without the
 * caller owning a FHIR parser — and, just as importantly, that it never throws.
 */
public class RestOutcomeParserTest {

    private final RestOutcomeParser parser = new RestOutcomeParser();

    @Test
    @DisplayName("A JSON OperationOutcome becomes plain issues")
    void parsesJson() {
        String body = "{ \"resourceType\": \"OperationOutcome\", \"issue\": [ "
                + "{ \"severity\": \"error\", \"code\": \"not-found\", "
                + "\"diagnostics\": \"Patient/99 not known\", "
                + "\"expression\": \"Patient.name\", "
                + "\"location\": [ \"Patient.name[0]\" ] }, "
                + "{ \"severity\": \"information\", \"code\": \"informational\", "
                + "\"diagnostics\": \"Retrying\" } ] }";

        List<RestOperationOutcome> issues = parser.parse(body);

        assertEquals(2, issues.size());
        RestOperationOutcome first = issues.get(0);
        assertEquals("error", first.severity());
        assertEquals("not-found", first.code());
        assertEquals("Patient/99 not known", first.diagnostics());
        assertEquals("Patient.name", first.expression());
        assertEquals(List.of("Patient.name[0]"), first.location());
        assertTrue(first.isError());
        assertFalse(issues.get(1).isError(), "an informational issue is not an error");
    }

    @Test
    @DisplayName("An XML OperationOutcome is parsed too, since servers serve both")
    void parsesXml() {
        String body = "<OperationOutcome xmlns=\"http://hl7.org/fhir\">"
                + "<issue><severity value=\"error\"/><code value=\"processing\"/>"
                + "<diagnostics value=\"Unable to process\"/></issue></OperationOutcome>";

        List<RestOperationOutcome> issues = parser.parse(body);

        assertEquals(1, issues.size());
        assertEquals("processing", issues.get(0).code());
        assertEquals("Unable to process", issues.get(0).diagnostics());
    }

    @Test
    @DisplayName("A body that is not an outcome yields no issues and never throws")
    void toleratesNonOutcomes() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("Service Unavailable").isEmpty());
        assertTrue(parser.parse("<html><body>Gateway Timeout</body></html>").isEmpty());
        assertTrue(parser.parse("{\"resourceType\":\"OperationOutcome\", truncated").isEmpty());
    }

    @Test
    @DisplayName("A successful resource body is not parsed as an error, even if it says Outcome")
    void ordinaryResourcesAreSkipped() {
        assertTrue(parser.parse("{\"resourceType\":\"Patient\",\"id\":\"1\"}").isEmpty());
    }

    @Test
    @DisplayName("An outcome with no issue array parses to an empty list, not an error")
    void outcomeWithoutIssues() {
        assertTrue(parser.parse("{\"resourceType\":\"OperationOutcome\"}").isEmpty());
    }

    @Test
    @DisplayName("An already-parsed resource is accepted, and other resource types are ignored")
    void fromResource() {
        var context = com.example.fhirviewer.fhir.FhirContextFactory.r4();
        var outcome = (org.hl7.fhir.r4.model.OperationOutcome) context.newJsonParser()
                .parseResource("{ \"resourceType\": \"OperationOutcome\", \"issue\": [ "
                        + "{ \"severity\": \"error\", \"code\": \"forbidden\", "
                        + "\"diagnostics\": \"Access denied\" } ] }");

        assertEquals(1, parser.fromResource(outcome).size());
        assertTrue(parser.fromResource(null).isEmpty());
        assertTrue(parser.fromResource(new org.hl7.fhir.r4.model.Patient()).isEmpty());
    }

    @Test
    @DisplayName("The details coding is surfaced for servers that put the text there")
    void detailsText() {
        var context = com.example.fhirviewer.fhir.FhirContextFactory.r4();
        var outcome = (org.hl7.fhir.r4.model.OperationOutcome) context.newJsonParser()
                .parseResource("{ \"resourceType\": \"OperationOutcome\", \"issue\": [ "
                        + "{ \"severity\": \"error\", \"code\": \"invalid\", "
                        + "\"details\": { \"text\": \"The identifier is already in use\" } } ] }");

        assertEquals("The identifier is already in use", RestOutcomeParser.detailsText(outcome));
    }

    @Test
    @DisplayName("The cheap pre-check only accepts a body worth parsing")
    void looksLikeOutcome() {
        assertTrue(RestFailures.looksLikeOutcome("{\"resourceType\":\"OperationOutcome\"}"));
        assertTrue(RestFailures.looksLikeOutcome("<OperationOutcome/>"));
        assertFalse(RestFailures.looksLikeOutcome("{\"resourceType\":\"Patient\"}"));
        assertFalse(RestFailures.looksLikeOutcome(null));
        assertNotNull(RestFailures.outcomeTypeName());
    }
}
