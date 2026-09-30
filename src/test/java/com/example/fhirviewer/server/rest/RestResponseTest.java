package com.example.fhirviewer.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the response model: how a status is classified, and how the server's own
 * explanation is preferred over a bare HTTP number.
 */
public class RestResponseTest {

    private static final String OUTCOME_JSON = "{ \"resourceType\": \"OperationOutcome\", "
            + "\"issue\": [ { \"severity\": \"error\", \"code\": \"not-found\", "
            + "\"diagnostics\": \"Patient/99 not known\" } ] }";

    @Test
    @DisplayName("Status ranges are classified so a caller does not compare numbers")
    void statusClassification() {
        assertTrue(RestResponse.ofStatus(200, RestHeaders.empty()).isSuccess());
        assertTrue(RestResponse.ofStatus(204, RestHeaders.empty()).isSuccess());
        assertTrue(RestResponse.ofStatus(404, RestHeaders.empty()).isClientError());
        assertTrue(RestResponse.ofStatus(503, RestHeaders.empty()).isServerError());
        assertFalse(RestResponse.ofStatus(404, RestHeaders.empty()).isSuccess());
    }

    @Test
    @DisplayName("A status that is not a real HTTP status is rejected")
    void impossibleStatusIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RestResponse.of(99, RestHeaders.empty(), null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RestResponse.of(600, RestHeaders.empty(), null, List.of()));
    }

    @Test
    @DisplayName("A missing body reads as empty, so a caller can parse without a null check")
    void bodyOrEmpty() {
        assertEquals("", RestResponse.ofStatus(204, RestHeaders.empty()).bodyOrEmpty());
        assertEquals(OUTCOME_JSON,
                RestResponse.of(400, RestHeaders.empty(), OUTCOME_JSON, List.of()).bodyOrEmpty());
    }

    @Test
    @DisplayName("Diagnostics prefer what the server said over what the status implies")
    void diagnosticsUseTheServerText() {
        List<RestOperationOutcome> issues = new RestOutcomeParser().parse(OUTCOME_JSON);
        RestResponse response = RestResponse.of(404, RestHeaders.empty(), OUTCOME_JSON, issues);

        assertTrue(response.diagnostics().contains("Patient/99 not known"),
                "the server's own words must survive: " + response.diagnostics());
        assertEquals("not-found", response.primaryIssue().orElseThrow().code());
    }

    @Test
    @DisplayName("A short plain-text error body is used when it is not an OperationOutcome")
    void plainTextErrorBodyIsUsed() {
        RestResponse response = RestResponse.of(503, RestHeaders.empty(),
                "Service Unavailable - starting up", List.of());

        assertEquals("Service Unavailable - starting up", response.diagnostics());
    }

    @Test
    @DisplayName("Markup is not shown raw, and a body-less failure falls back to the status")
    void unparsableBodiesAreNotShownRaw() {
        RestResponse html = RestResponse.of(500, RestHeaders.empty(),
                "<html><body>Internal Server Error</body></html>", List.of());
        assertFalse(html.diagnostics().contains("<html>"), "markup is noise in a dialog");

        assertEquals("The server answered with HTTP 500.",
                RestResponse.ofStatus(500, RestHeaders.empty()).diagnostics());
    }

    @Test
    @DisplayName("The printed form never includes the body, which can be patient data")
    void toStringNeverPrintsTheBody() {
        RestResponse response = RestResponse.of(200,
                RestHeaders.builder().contentType("application/fhir+json").build(),
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Sensitive\"}]}", List.of());

        String printed = response.toString();

        assertFalse(printed.contains("Sensitive"), "the body must not be printed");
        assertTrue(printed.contains("status=200"));
        assertTrue(printed.contains("application/fhir+json"), "the content type is safe and useful");
    }

    @Test
    @DisplayName("An issue describes itself in one readable line")
    void issueDescription() {
        RestOperationOutcome issue = new RestOperationOutcome("error", "invalid",
                "Invalid parameter", "Patient.name", List.of("Patient.name[0]"));

        String described = issue.describe();

        assertTrue(described.startsWith("error: "));
        assertTrue(described.contains("[invalid]"));
        assertTrue(described.contains("Invalid parameter"));
        assertTrue(described.contains("Patient.name"), "the expression is shown");
    }

    @Test
    @DisplayName("An empty issue still says something rather than rendering as blank")
    void emptyIssueStillDescribesItself() {
        assertTrue(RestOperationOutcome.ofMessage("error", null).describe().length() > 0);
        assertTrue(RestOperationOutcome.ofMessage(null, "Something went wrong").describe()
                .contains("Something went wrong"));
    }

    @Test
    @DisplayName("A warning is not treated as an error, but the data still travels back")
    void warningsAreNotErrors() {
        assertFalse(RestOperationOutcome.ofMessage("warning", "Deprecated").isError());
        assertFalse(RestOperationOutcome.ofMessage("information", "Hint").isError());
        assertTrue(RestOperationOutcome.ofMessage("error", "Refused").isError());
        assertTrue(RestOperationOutcome.ofMessage("fatal", "Refused").isError());
    }

    @Test
    @DisplayName("An issue prints only its shape, never the diagnostics that may name a patient")
    void issueToStringIsSafe() {
        RestOperationOutcome issue = RestOperationOutcome.ofMessage("error", "Patient Smith not found");

        assertFalse(issue.toString().contains("Smith"));
        assertTrue(issue.toString().contains("severity=error"));
    }
}
