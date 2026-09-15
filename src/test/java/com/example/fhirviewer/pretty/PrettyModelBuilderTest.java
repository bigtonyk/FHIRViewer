package com.example.fhirviewer.pretty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.service.FhirService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the presentation model of the Pretty View: document header, label/value
 * rows, sections for top level complex elements, numbered repeating elements,
 * nested summaries, extensions, Bundle entries and contained resources.
 */
class PrettyModelBuilderTest {

    private static FhirService service;

    @BeforeAll
    static void createService() {
        service = new FhirService();
    }

    private static PrettyDocument document(String fileName) {
        LoadedResource loaded = service.openSample("/fhir/" + fileName);
        return service.buildPrettyView(loaded);
    }

    // ------------------------------------------------------------------
    // Lookup helpers
    // ------------------------------------------------------------------

    private static Optional<PrettyRow> findRow(List<PrettyRow> rows, String label) {
        return rows.stream().filter(row -> row.label().equals(label)).findFirst();
    }

    private static PrettyRow requireRow(List<PrettyRow> rows, String label) {
        return findRow(rows, label).orElseThrow(() -> new AssertionError(
                "No row with label <" + label + ">. Rows are: " + describe(rows)));
    }

    private static Optional<PrettyBlock> findBlock(List<PrettyBlock> blocks, String title) {
        for (PrettyBlock block : blocks) {
            if (block.title().equals(title)) {
                return Optional.of(block);
            }
            Optional<PrettyBlock> nested = findBlock(block.children(), title);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    private static PrettyBlock requireBlock(List<PrettyBlock> blocks, String title) {
        return findBlock(blocks, title).orElseThrow(() -> new AssertionError(
                "No section with title <" + title + ">. Sections are: " + describe(blocks)));
    }

    private static String describe(List<?> items) {
        StringBuilder sb = new StringBuilder();
        for (Object item : items) {
            sb.append("\n  - ").append(item);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Document header
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The document header carries the resource type and id")
    void documentHeaderContainsResourceTypeAndId() {
        PrettyDocument document = document("patient.json");

        assertEquals("Patient", document.resourceType());
        assertEquals("example-1", document.resourceId());
    }

    @Test
    @DisplayName("Resource level primitives become document rows")
    void resourceLevelPrimitivesBecomeDocumentRows() {
        PrettyDocument document = document("patient.json");

        assertEquals("true", requireRow(document.rows(), "Active").value());
        assertEquals("male", requireRow(document.rows(), "Gender").value());
        assertEquals("1974-12-25", requireRow(document.rows(), "Birth Date").value());
    }

    @Test
    @DisplayName("Meta contents are shown as header rows, not as a section")
    void metaProfileAppearsAsHeaderRow() {
        PrettyDocument document = document("patient.json");

        assertEquals("http://hl7.org/fhir/StructureDefinition/Patient",
                requireRow(document.headerRows(), "Profile").value());
    }

    // ------------------------------------------------------------------
    // Sections and rows
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Top level complex elements become titled sections")
    void topLevelComplexElementsBecomeSections() {
        List<PrettyBlock> sections = document("patient.json").sections();

        assertNotNull(requireBlock(sections, "Name 1"));
        assertNotNull(requireBlock(sections, "Address 1"));
        assertNotNull(requireBlock(sections, "Identifier 1"));
        assertNotNull(requireBlock(sections, "General Practitioner 1"));
    }

    @Test
    @DisplayName("Repeating top level elements are numbered sections with their own rows")
    void repeatingTopLevelElementsAreNumberedSections() {
        List<PrettyBlock> sections = document("organization.json").sections();

        PrettyBlock telecom1 = requireBlock(sections, "Telecom 1");
        assertEquals("phone", requireRow(telecom1.rows(), "System").value());
        assertEquals("+1-555-0100", requireRow(telecom1.rows(), "Value").value());
        assertEquals("work", requireRow(telecom1.rows(), "Use").value());

        assertEquals("email", requireRow(requireBlock(sections, "Telecom 2").rows(), "System").value());

        PrettyBlock address1 = requireBlock(sections, "Address 1");
        assertEquals("Springfield", requireRow(address1.rows(), "City").value());
        assertEquals("100 Main Street", requireRow(address1.rows(), "Line").value());
        assertEquals("Shelbyville", requireRow(requireBlock(sections, "Address 2").rows(), "City").value());

        assertEquals("ORG-001", requireRow(requireBlock(sections, "Identifier 1").rows(), "Value").value());
    }

    @Test
    @DisplayName("Nested summarizable values become one line summary rows")
    void nestedSummarizableValuesBecomeSummaryRows() {
        PrettyBlock participant = requireBlock(document("encounter.json").sections(), "Participant 1");

        assertEquals("Dr. Alice Grey (Practitioner/example-gp)",
                requireRow(participant.rows(), "Individual").value());
        assertEquals("Practitioner/interpreter-1",
                requireRow(requireBlock(document("encounter.json").sections(), "Participant 2").rows(),
                        "Individual").value());
    }

    @Test
    @DisplayName("Nested quantities are summarized as value and unit")
    void nestedQuantitiesAreSummarized() {
        PrettyBlock component = requireBlock(document("observation.json").sections(), "Component 1");

        assertEquals("120 mmHg", requireRow(component.rows(), "Value Quantity").value());
        assertEquals("systolic", requireRow(component.rows(), "Code").value());
        assertEquals("80 mmHg", requireRow(
                requireBlock(document("observation.json").sections(), "Component 2").rows(),
                "Value Quantity").value());
    }

    @Test
    @DisplayName("Extensions become sections named after their canonical URL")
    void extensionsUseFriendlyNames() {
        PrettyDocument document = document("encounter.json");
        PrettyBlock extension = requireBlock(document.sections(), "Encounter Priority");

        assertTrue(extension.rows().stream().anyMatch(row -> "routine".equals(row.value())),
                "The extension value should be shown: " + describe(extension.rows()));
        assertTrue(extension.rows().stream().noneMatch(row -> row.value().contains("http://")),
                "The raw extension URL should not be repeated inside its section");
    }

    @Test
    @DisplayName("Bundle entries become nested resource sections")
    void bundleEntriesBecomeNestedResourceSections() {
        List<PrettyBlock> sections = document("bundle.json").sections();

        PrettyBlock entry = requireBlock(sections, "Entry 1");
        assertNotNull(requireBlock(entry.children(), "Patient/patient-a"));
        assertTrue(entry.rows().stream().anyMatch(row -> row.value().startsWith("urn:uuid:")),
                "The entry fullUrl should be shown: " + describe(entry.rows()));
        assertNotNull(requireBlock(sections, "Entry 2"));
    }

    @Test
    @DisplayName("Contained resources become nested resource sections")
    void containedResourcesBecomeNestedResourceSections() {
        List<PrettyBlock> sections = document("patient-contained.json").sections();

        PrettyBlock contained = requireBlock(sections, "Contained 1");
        assertNotNull(requireBlock(contained.children(), "Observation/contained-observation"));
    }

    @Test
    @DisplayName("Nested names and telecoms are summarized inside backbone sections")
    void nestedContactDetailsAreSummarized() {
        PrettyBlock contact = requireBlock(document("organization.json").sections(), "Contact 1");

        assertEquals("Alice Grey", requireRow(contact.rows(), "Name").value());
        assertEquals("+1-555-0111 (phone work)", requireRow(contact.rows(), "Telecom").value());
        assertEquals("Administration", requireRow(contact.rows(), "Purpose").value());
    }

    @Test
    @DisplayName("A minimal resource renders as document rows without sections")
    void minimalResourceHasDocumentRowsOnly() {
        PrettyDocument document = document("patient-minimal.json");

        assertTrue(document.sections().isEmpty());
        assertEquals("unknown", requireRow(document.rows(), "Gender").value());
    }
}
