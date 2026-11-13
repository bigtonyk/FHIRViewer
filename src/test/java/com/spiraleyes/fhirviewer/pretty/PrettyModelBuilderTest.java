package com.spiraleyes.fhirviewer.pretty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.spiraleyes.fhirviewer.TreeAssert;
import com.spiraleyes.fhirviewer.fhir.FhirContextFactory;
import com.spiraleyes.fhirviewer.model.ElementInfo;
import com.spiraleyes.fhirviewer.model.LoadedResource;
import com.spiraleyes.fhirviewer.model.ResourceNode;
import com.spiraleyes.fhirviewer.service.FhirService;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
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
        assertEquals("Administration (terminology.hl7.org/CodeSystem/v2-0131 ADMIN)",
                requireRow(contact.rows(), "Purpose").value());
    }

    @Test
    @DisplayName("An extension with sub-extensions shows its children instead of only its title")
    void nestedExtensionShowsItsChildren() {
        HumanName name = new HumanName().setFamily("Smith");
        Extension race = new Extension("http://example.org/fhir/StructureDefinition/patient-race");
        race.addExtension("race", new Coding("http://example.org/race", "white", "White"));
        race.addExtension("text", new StringType("White"));
        name.addExtension(race);
        Patient patient = new Patient();
        patient.addName(name);

        PrettyDocument document =
                new PrettyModelBuilder(FhirContextFactory.r4ModelAdapter()).build(patient);

        PrettyBlock section = requireBlock(document.sections(), "Patient Race");
        assertEquals("Race = White (example.org/race white)",
                requireRow(section.rows(), "Extension 1").value());
        assertEquals("Text = White", requireRow(section.rows(), "Extension 2").value());
    }

    @Test
    @DisplayName("A minimal resource renders as document rows without sections")
    void minimalResourceHasDocumentRowsOnly() {
        PrettyDocument document = document("patient-minimal.json");

        assertTrue(document.sections().isEmpty());
        assertEquals("unknown", requireRow(document.rows(), "Gender").value());
    }

    // ------------------------------------------------------------------
    // Focused documents (resource tree selection)
    // ------------------------------------------------------------------

    private static PrettyDocument focusedDocument(String fileName, String nodePath) {
        LoadedResource loaded = service.openSample("/fhir/" + fileName);
        ResourceNode node = TreeAssert.require(service.buildTree(loaded, false), nodePath);
        return service.buildPrettyView(loaded.getResource(), node);
    }

    @Test
    @DisplayName("A primitive tree node resolves to a single row")
    void primitiveNodeBecomesSingleRow() {
        PrettyDocument document = focusedDocument("patient.json", "Patient.name[0].family");

        assertEquals("Family", document.resourceType());
        assertEquals("Smith", requireRow(document.rows(), "Family").value());
    }

    @Test
    @DisplayName("A focused primitive is titled with the element name, not the datatype")
    void primitiveNodeIsTitledWithItsElementName() {
        PrettyDocument document = focusedDocument("patient.json", "Patient.birthDate");

        assertEquals("Birth Date", document.resourceType());
        assertEquals("1974-12-25", requireRow(document.rows(), "Birth Date").value());
    }

    @Test
    @DisplayName("A path naming a value that is not there falls back to the whole document")
    void outOfRangePathShowsTheWholeDocument() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");
        ResourceNode root = new ResourceNode("Patient", ElementInfo.builder()
                .path("Patient")
                .name("Patient")
                .kind(ElementInfo.Kind.RESOURCE)
                .build());
        ResourceNode stale = new ResourceNode("[4]", ElementInfo.builder()
                .path("Patient.identifier[4]")
                .name("identifier")
                .typeCode("Identifier")
                .kind(ElementInfo.Kind.COMPLEX)
                .build());
        root.addChild(stale);

        PrettyDocument document = service.buildPrettyView(loaded.getResource(), stale);

        assertEquals("Patient", document.resourceType(),
                "an unresolvable path must not render another identifier as if it matched");
        assertEquals("000-00-0000", requireRow(
                requireBlock(document.sections(), "Identifier 2").rows(), "Value").value());
        assertEquals("MRN-12345", requireRow(
                requireBlock(document.sections(), "Identifier 1").rows(), "Value").value());
    }

    @Test
    @DisplayName("A group node with an out-of-range parent index falls back to the whole document")
    void outOfRangeGroupPathShowsTheWholeDocument() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");
        ResourceNode root = new ResourceNode("Patient", ElementInfo.builder()
                .path("Patient")
                .name("Patient")
                .kind(ElementInfo.Kind.RESOURCE)
                .build());
        // A group node for a repeating primitive, whose parent index names no name.
        ResourceNode group = new ResourceNode("given", ElementInfo.builder()
                .path("Patient.name[7].given")
                .name("given")
                .typeCode("string")
                .cardinality(0, Integer.MAX_VALUE)
                .kind(ElementInfo.Kind.PRIMITIVE)
                .build());
        group.addChild(new ResourceNode("[0]", ElementInfo.builder()
                .path("Patient.name[7].given[0]")
                .name("given")
                .typeCode("string")
                .kind(ElementInfo.Kind.PRIMITIVE)
                .build()));
        group.addChild(new ResourceNode("[1]", ElementInfo.builder()
                .path("Patient.name[7].given[1]")
                .name("given")
                .typeCode("string")
                .kind(ElementInfo.Kind.PRIMITIVE)
                .build()));
        root.addChild(group);

        PrettyDocument document = service.buildPrettyView(loaded.getResource(), group);

        assertEquals("Patient", document.resourceType(),
                "a group path whose parent index does not exist must not resolve another name");
        assertEquals("Smith", requireRow(
                requireBlock(document.sections(), "Name 1").rows(), "Family").value());
    }

    @Test
    @DisplayName("A complex tree node shows the element's own detail")
    void complexNodeShowsElementDetail() {
        PrettyDocument document = focusedDocument("patient.json", "Patient.name[0]");

        assertEquals("HumanName", document.resourceType());
        assertEquals("Smith", requireRow(document.rows(), "Family").value());
        assertEquals("John, Jacob", requireRow(document.rows(), "Given").value());
        assertEquals("official", requireRow(document.rows(), "Use").value());
    }

    @Test
    @DisplayName("A repeating group node shows numbered sections for every value")
    void groupNodeShowsNumberedSections() {
        PrettyDocument document = focusedDocument("patient.json", "Patient.identifier");

        assertEquals("Identifier", document.resourceType());
        PrettyBlock first = requireBlock(document.sections(), "Identifier 1");
        assertEquals("http://hospital.example.org/mrn", requireRow(first.rows(), "System").value());
        assertEquals("MRN-12345", requireRow(first.rows(), "Value").value());
        assertEquals("000-00-0000", requireRow(
                requireBlock(document.sections(), "Identifier 2").rows(), "Value").value());
    }

    @Test
    @DisplayName("A repeating primitive group node joins its values into one row")
    void primitiveGroupNodeJoinsValues() {
        PrettyDocument document = focusedDocument("patient.json", "Patient.name[0].given");

        assertEquals("Given", document.resourceType());
        assertEquals("John, Jacob", requireRow(document.rows(), "Given").value());
    }

    @Test
    @DisplayName("The root tree node shows the full resource pretty view")
    void rootNodeShowsFullPrettyView() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");
        PrettyDocument document =
                service.buildPrettyView(loaded.getResource(), service.buildTree(loaded, false));

        assertEquals("Patient", document.resourceType());
        assertEquals("example-1", document.resourceId());
        assertEquals("true", requireRow(document.rows(), "Active").value());
        assertNotNull(requireBlock(document.sections(), "Name 1"));
    }

    @Test
    @DisplayName("A single reference node is resolved and rendered")
    void referenceNodeIsResolved() {
        PrettyDocument document = focusedDocument("patient.json", "Patient.managingOrganization");

        assertEquals("Reference", document.resourceType());
        assertEquals("Organization/example-org", requireRow(document.rows(), "Reference").value());
    }

    @Test
    @DisplayName("A repeating reference group shows numbered sections like the full view")
    void referenceGroupShowsNumberedSections() {
        PrettyDocument document = focusedDocument("patient.json", "Patient.generalPractitioner");

        PrettyBlock section = requireBlock(document.sections(), "General Practitioner 1");
        assertEquals("Practitioner/example-gp", requireRow(section.rows(), "Reference").value());
        assertEquals("Dr. Alice Grey", requireRow(section.rows(), "Display").value());
    }
}
