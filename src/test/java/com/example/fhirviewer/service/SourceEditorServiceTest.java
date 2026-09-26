package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.TreeAssert;
import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.model.ResourceNode;
import com.example.fhirviewer.pretty.PrettyBlock;
import com.example.fhirviewer.pretty.PrettyDocument;
import com.example.fhirviewer.pretty.PrettyRow;
import com.example.fhirviewer.service.FHIRPathService.Status;
import com.example.fhirviewer.service.SourceEditorService.ApplyException;
import com.example.fhirviewer.service.SourceEditorService.FailureKind;
import com.example.fhirviewer.util.FileSupport;

/**
 * Tests for editing the JSON and XML representation of a resource in place: a valid
 * document replaces the resource, a broken one leaves it untouched, and every view
 * is built from the replacement resource.
 */
class SourceEditorServiceTest {

    private final FhirService service = new FhirService();
    private final SourceEditorService sourceEditor = new SourceEditorService();
    private Patient patient;

    @BeforeEach
    void setUp() {
        patient = (Patient) service.openSample("/fhir/patient.json").getResource();
    }

    private static String sample(String fileName) throws IOException {
        return FileSupport.readClasspathText("/fhir/" + fileName);
    }

    /** The sample Patient with its family name replaced, as edited JSON. */
    private static String editedJson(String familyName) throws IOException {
        return sample("patient.json").replace("\"Smith\"", "\"" + familyName + "\"");
    }

    /** The sample Patient with its family name replaced, as edited XML. */
    private static String editedXml(String familyName) throws IOException {
        return sample("patient.xml").replace("value=\"Smith\"", "value=\"" + familyName + "\"");
    }

    @Test
    @DisplayName("Edited JSON is parsed, validated and handed back as a new resource")
    void appliesEditedJson() throws IOException {
        SourceEditorService.AppliedSource applied =
                sourceEditor.apply(editedJson("Jones"), ResourceFormat.JSON, "patient.json");

        assertEquals(ResourceFormat.JSON, applied.format());
        assertEquals("Patient", applied.resource().fhirType());
        assertEquals("Jones", ((Patient) applied.resource()).getNameFirstRep().getFamily());
        assertTrue(applied.report().isValid(), applied.report().getSummary());
    }

    @Test
    @DisplayName("Edited XML is parsed, validated and handed back as a new resource")
    void appliesEditedXml() throws IOException {
        SourceEditorService.AppliedSource applied =
                sourceEditor.apply(editedXml("Jones"), ResourceFormat.XML, "patient.xml");

        assertEquals(ResourceFormat.XML, applied.format());
        assertEquals("Jones", ((Patient) applied.resource()).getNameFirstRep().getFamily());
        assertTrue(applied.report().isValid(), applied.report().getSummary());
    }

    @Test
    @DisplayName("Applying source text never mutates the resource that was displayed")
    void leavesTheOriginalResourceAlone() throws IOException {
        String before = service.toJson(patient);

        SourceEditorService.AppliedSource applied =
                sourceEditor.apply(editedJson("Jones"), ResourceFormat.JSON, "patient.json");

        assertNotSame(patient, applied.resource());
        assertEquals(before, service.toJson(patient));
        assertEquals("Smith", patient.getNameFirstRep().getFamily());
    }

    @Test
    @DisplayName("Malformed JSON is rejected and the original resource is unchanged")
    void rejectsMalformedJson() throws IOException {
        String broken = "{ \"resourceType\": \"Patient\", \"name\": [ { \"family\": \"Jones\" }";
        String before = service.toJson(patient);

        ApplyException failure = assertThrows(ApplyException.class,
                () -> sourceEditor.apply(broken, ResourceFormat.JSON, "patient.json"));

        assertEquals(FailureKind.PARSE, failure.kind());
        // The message names the document, the format and, where HAPI reports one,
        // the approximate location of the problem.
        assertTrue(failure.getMessage().contains("patient.json"), failure.getMessage());
        assertTrue(failure.getMessage().contains("JSON"), failure.getMessage());
        assertEquals(before, service.toJson(patient));
    }

    @Test
    @DisplayName("Malformed XML is rejected and the original resource is unchanged")
    void rejectsMalformedXml() throws IOException {
        String broken = "<Patient xmlns=\"http://hl7.org/fhir\"><name><family value=\"Jones\"/></Patient>";
        String before = service.toJson(patient);

        ApplyException failure = assertThrows(ApplyException.class,
                () -> sourceEditor.apply(broken, ResourceFormat.XML, "patient.xml"));

        assertEquals(FailureKind.PARSE, failure.kind());
        assertTrue(failure.getMessage().contains("patient.xml"), failure.getMessage());
        assertTrue(failure.getMessage().contains("XML"), failure.getMessage());
        assertEquals(before, service.toJson(patient));
    }

    @Test
    @DisplayName("A document that parses but fails validation is rejected")
    void rejectsInvalidResource() throws IOException {
        String invalid = sample("observation-missing-required.json");
        String before = service.toJson(patient);

        ApplyException failure = assertThrows(ApplyException.class,
                () -> sourceEditor.apply(invalid, ResourceFormat.JSON, "observation-missing-required.json"));

        assertEquals(FailureKind.PARSE, failure.kind());
        assertTrue(failure.getMessage().contains("not valid FHIR"), failure.getMessage());
        assertEquals(before, service.toJson(patient));
    }

    @Test
    @DisplayName("An empty document is rejected instead of replacing the resource")
    void rejectsEmptyDocument() {
        ApplyException failure = assertThrows(ApplyException.class,
                () -> sourceEditor.apply("   \n  ", ResourceFormat.JSON, "patient.json"));

        assertEquals(FailureKind.EMPTY, failure.kind());
    }

    @Test
    @DisplayName("The replacement resource is what the tree, Pretty View and FHIRPath show")
    void everyViewFollowsTheReplacement() throws IOException {
        LoadedResource replacement = replacementOf(editedJson("Jones"), ResourceFormat.JSON);

        ResourceNode tree = service.buildTree(replacement, false);
        assertTrue(TreeAssert.paths(tree).contains("Patient.name[0].family"));
        assertEquals("Jones", TreeAssert.require(tree, "Patient.name[0].family").getValueText());

        String pretty = prettyText(service.buildPrettyView(replacement));
        assertTrue(pretty.contains("Jones"), pretty);
        assertFalse(pretty.contains("Smith"), pretty);

        assertEquals("Jones", service.evaluateFHIRPath(replacement.getResource(), "Patient.name[0].family")
                .resultText());
    }

    @Test
    @DisplayName("JSON and XML stay in sync with the replacement resource")
    void serializedViewsFollowTheReplacement() throws IOException {
        LoadedResource replacement = replacementOf(editedXml("Jones"), ResourceFormat.XML);

        assertTrue(service.toJson(replacement.getResource()).contains("Jones"));
        assertTrue(service.toXml(replacement.getResource()).contains("Jones"));
        assertFalse(service.toJson(replacement.getResource()).contains("Smith"));
        assertFalse(service.toXml(replacement.getResource()).contains("Smith"));
    }

    @Test
    @DisplayName("A deleted element is gone from every view of the replacement")
    void deletionThroughSourceTextIsVisibleEverywhere() throws IOException {
        // Removing the whole name array leaves the resource valid but nameless.
        String withoutName = sample("patient.json")
                .replaceAll("(?s)\\s*\"name\":\\s*\\[.*?\\],\\s*", "\n");
        LoadedResource replacement = replacementOf(withoutName, ResourceFormat.JSON);

        assertTrue(TreeAssert.paths(service.buildTree(replacement, false)).stream()
                .noneMatch(path -> path.startsWith("Patient.name")));
        assertFalse(prettyText(service.buildPrettyView(replacement)).contains("Smith"));
        assertEquals(Status.EMPTY,
                service.evaluateFHIRPath(replacement.getResource(), "Patient.name[0].family").status());
    }

    /**
     * Wraps an applied document the way the window does: the parsed resource becomes
     * the loaded resource that every view is built from.
     */
    private LoadedResource replacementOf(String text, ResourceFormat format) {
        SourceEditorService.AppliedSource applied = sourceEditor.apply(text, format, "patient");
        LoadedResource loaded = new LoadedResource(
                applied.resource(), applied.format(), "patient", text, null);
        loaded.markDirty();
        return loaded;
    }

    /** Every label and value of a Pretty View model, flattened for assertions. */
    private static String prettyText(PrettyDocument document) {
        StringBuilder text = new StringBuilder();
        document.headerRows().forEach(row -> text.append(row.label()).append(' ').append(row.value()).append(' '));
        document.summaryRows().forEach(row -> text.append(row.label()).append(' ').append(row.value()).append(' '));
        for (PrettyBlock section : document.sections()) {
            appendBlock(section, text);
        }
        return text.toString();
    }

    private static void appendBlock(PrettyBlock block, StringBuilder text) {
        for (PrettyRow row : block.rows()) {
            text.append(row.label()).append(' ').append(row.value()).append(' ');
        }
        for (PrettyBlock child : block.children()) {
            appendBlock(child, text);
        }
    }
}
