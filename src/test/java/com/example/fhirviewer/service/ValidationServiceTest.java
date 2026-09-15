package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.model.ValidationIssue;
import com.example.fhirviewer.model.ValidationReport;

/** Tests FHIR validation, including the reporting of required elements. */
class ValidationServiceTest {

    private static FhirService service;

    @BeforeAll
    static void createService() {
        service = new FhirService();
    }

    private static String describe(ValidationReport report) {
        return report.getSummary() + "\n" + report.getIssues().stream()
                .map(ValidationIssue::getDisplayText)
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("A valid Patient validates without errors")
    void validPatientHasNoErrors() {
        ValidationReport report = service.validate(service.openSample("/fhir/patient.json").getResource());

        assertTrue(report.isValid(), describe(report));
    }

    @Test
    @DisplayName("A missing required element is reported as an error")
    void missingRequiredElementsAreReported() {
        ValidationReport report = service.validate(
                service.openSample("/fhir/observation-missing-required.json").getResource());

        assertFalse(report.isValid(), describe(report));
        long problems = report.count(ValidationIssue.Severity.ERROR)
                + report.count(ValidationIssue.Severity.FATAL);
        assertTrue(problems > 0, describe(report));
        assertTrue(report.hasIssues());
    }

    @Test
    @DisplayName("Validation returns a report for any resource and never throws")
    void neverThrows() {
        List<ValidationReport> reports = List.of(
                service.validate(service.openSample("/fhir/bundle.json").getResource()),
                service.validate(service.openSample("/fhir/patient-contained.json").getResource()),
                service.validate(service.openSample("/fhir/observation.json").getResource()));

        for (ValidationReport report : reports) {
            assertNotNull(report);
            assertNotNull(report.getIssues());
        }
    }

    @Test
    @DisplayName("Validating nothing produces a harmless empty report")
    void handlesNullResource() {
        ValidationReport report = service.validate(null);

        assertNotNull(report);
        assertTrue(report.isValid());
        assertFalse(report.hasIssues());
    }
}