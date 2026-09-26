package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.model.ValidationIssue;
import com.example.fhirviewer.model.ValidationReport;

/**
 * Tests validation with IG package support.
 */
class ValidationServiceIgTest {

    private static FhirService service;

    @BeforeAll
    static void createService() {
        service = new FhirService();
    }

    @Test
    @DisplayName("Validation service has package manager")
    void validationServiceHasPackageManager() {
        ValidationService validationService = service.validationService();
        assertNotNull(validationService.getPackageManager());
    }

    @Test
    @DisplayName("Package manager starts empty")
    void packageManagerStartsEmpty() {
        assertTrue(service.validationService().getPackageManager().getLoadedPackages().isEmpty());
    }

    @Test
    @DisplayName("Validation works without IG packages loaded")
    void validationWorksWithoutPackages() {
        ValidationReport report = service.validate(
                service.openSample("/fhir/patient.json").getResource());
        assertNotNull(report);
        assertTrue(report.getIssues() != null);
    }
}