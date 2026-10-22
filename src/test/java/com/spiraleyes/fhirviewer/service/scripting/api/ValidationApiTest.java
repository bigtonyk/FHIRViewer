package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.model.ValidationReport;
import com.spiraleyes.fhirviewer.service.FhirService;

class ValidationApiTest {

    private final FhirService fhirService = new FhirService();
    private final ValidationApi validation = new ValidationApi(fhirService);

    @Test
    void validateReturnsAStructuredReport() {
        Patient patient = new Patient();
        patient.setId("1");
        patient.addName().setFamily("Smith");

        ValidationReport report = validation.validate(patient);
        assertNotNull(report);
        assertEquals("Patient/1", report.getResourceName());
        assertTrue(report.isValid());
        assertEquals(0, report.getIssues().size());
        assertFalse(report.hasIssues());
        assertTrue(report.getSummary().contains("valid"));
    }

    @Test
    void scratchShowIssues() {
        Patient patient = new Patient();
        patient.setId("1");
        patient.addName().setFamily("Smith");
        ValidationReport report = validation.validate(patient);
        System.out.println("SCRATCH name=" + report.getResourceName() + " valid=" + report.isValid());
        report.getIssues().forEach(i -> System.out.println("SCRATCH issue: " + i.getDisplayText()));
        System.out.println("SCRATCH summary: " + report.getSummary());
        ValidationReport missing = validation.validate(patient, "http://example.org/does-not-exist");
        System.out.println("SCRATCH missing valid=" + missing.isValid());
        missing.getIssues().forEach(i -> System.out.println("SCRATCH missing issue: " + i.getDisplayText()));
        System.out.println("SCRATCH missing summary: " + missing.getSummary());
    }

    @Test
    void validateAgainstAMissingProfileReportsAnIssue() {
        Patient patient = new Patient();
        patient.setId("1");

        ValidationReport report = validation.validate(patient, "http://example.org/does-not-exist");
        assertNotNull(report);
        assertFalse(report.isValid());
        assertTrue(report.getIssues().size() > 0);
        assertTrue(report.getSummary().toLowerCase().contains("error")
                || report.getSummary().toLowerCase().contains("not available"));
    }
}
