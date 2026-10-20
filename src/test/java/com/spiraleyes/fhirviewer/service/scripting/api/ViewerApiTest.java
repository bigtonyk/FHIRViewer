package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.service.FhirService;

class ViewerApiTest {

    private final FhirService fhirService = new FhirService();
    private final ViewerApi viewer = new ViewerApi(fhirService);
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final PrintStream original = System.out;

    @BeforeEach
    void captureOutput() {
        System.setOut(new PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(original);
    }

    @Test
    void showResourceRendersSummaryAndJson() {
        Patient patient = new Patient();
        patient.setId("1");
        patient.addName().setFamily("Smith");

        viewer.showResource(patient);

        String text = out.toString();
        assertTrue(text.contains("[viewer]"), "expected a viewer headline: " + text);
        assertTrue(text.contains("{"), "expected JSON in the output: " + text);
    }

    @Test
    void showSearchResultsListsEachResource() {
        Patient patient = new Patient();
        patient.setId("1");
        patient.addName().setFamily("Smith");

        SearchResults results = new SearchResults(
                java.util.List.of(patient), 1, null, null);
        viewer.showSearchResults(results);

        String text = out.toString();
        assertTrue(text.contains("[viewer] 1 resource"), "expected a count line: " + text);
        assertTrue(text.contains("- "), "expected one line per resource: " + text);
    }

    @Test
    void showTextWritesArbitraryText() {
        viewer.showText("A headline from the script.");
        assertTrue(out.toString().contains("A headline from the script."));
    }

    @Test
    void showResourceWithNullIsSafe() {
        viewer.showResource(null);
        assertTrue(out.toString().contains("No resource to show."));
    }
}
