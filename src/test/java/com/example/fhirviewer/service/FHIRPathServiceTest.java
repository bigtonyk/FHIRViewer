package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.service.FHIRPathService.Result;
import com.example.fhirviewer.service.FHIRPathService.Status;

/**
 * Tests the FHIRPath service: valid expressions with their rendered result,
 * invalid expressions with their error, empty results and the recalculation of
 * an expression against an edited resource.
 */
class FHIRPathServiceTest {

    private final FhirService service = new FhirService();
    private final ResourceEditorService editor = new ResourceEditorService();
    private final FHIRPathService fhirPath = new FHIRPathService();
    private Patient patient;

    @BeforeEach
    void setUp() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");
        patient = (Patient) loaded.getResource();
    }

    @Test
    @DisplayName("A valid expression returns the value it selects")
    void evaluatesValidExpression() {
        Result result = fhirPath.evaluate(patient, "Patient.name[0].family");

        assertEquals(Status.VALID, result.status());
        assertTrue(result.hasResult());
        assertEquals("Smith", result.resultText());
    }

    @Test
    @DisplayName("Every result of an expression is rendered, in order")
    void rendersEveryResult() {
        Result result = fhirPath.evaluate(patient, "Patient.name[0].given");

        assertEquals(Status.VALID, result.status());
        assertEquals("John, Jacob", result.resultText());
    }

    @Test
    @DisplayName("A complex result is rendered with its data")
    void rendersComplexResult() {
        Result result = fhirPath.evaluate(patient, "Patient.name[0]");

        assertEquals(Status.VALID, result.status());
        assertTrue(result.resultText().contains("Smith"), "the HumanName data is rendered: " + result.resultText());
    }

    @Test
    @DisplayName("An expression without results is reported as empty")
    void reportsEmptyResult() {
        Result result = fhirPath.evaluate(patient, "Patient.contact");

        assertEquals(Status.EMPTY, result.status());
        assertFalse(result.hasResult());
    }

    @Test
    @DisplayName("A broken expression is reported as invalid with its error")
    void reportsInvalidSyntax() {
        Result result = fhirPath.evaluate(patient, "Patient.name[0].");

        assertEquals(Status.INVALID, result.status());
        assertFalse(result.error().isBlank());
    }

    @Test
    @DisplayName("An expression that fails at evaluation time is reported as invalid")
    void reportsInvalidEvaluation() {
        Result result = fhirPath.evaluate(patient, "Patient.name.unknownFunction()");

        assertEquals(Status.INVALID, result.status());
        assertFalse(result.error().isBlank());
    }

    @Test
    @DisplayName("A blank expression and a missing resource are reported as invalid")
    void rejectsBlankExpressionAndMissingResource() {
        assertEquals(Status.INVALID, fhirPath.evaluate(patient, "   ").status());
        assertEquals(Status.INVALID, fhirPath.evaluate(null, "Patient.gender").status());
    }

    @Test
    @DisplayName("Recalculating an expression returns the value after an edit")
    void recalculatesAfterEdit() {
        assertEquals("Smith", fhirPath.evaluate(patient, "Patient.name[0].family").resultText());

        editor.setPrimitive(patient, "Patient.name[0].family", "Jones");

        Result recalculated = fhirPath.evaluate(patient, "Patient.name[0].family");
        assertEquals(Status.VALID, recalculated.status());
        assertEquals("Jones", recalculated.resultText());
    }

    @Test
    @DisplayName("An expression becomes empty when its element is deleted")
    void recalculatesAfterDelete() {
        editor.deleteNode(patient, "Patient.name[0].family");

        assertEquals(Status.EMPTY, fhirPath.evaluate(patient, "Patient.name[0].family").status());
    }

    @Test
    @DisplayName("FhirService exposes the FHIRPath evaluation")
    void evaluatesThroughFhirService() {
        Result result = service.evaluateFHIRPath(patient, "Patient.gender");

        assertEquals(Status.VALID, result.status());
        assertEquals("male", result.resultText());
    }
}