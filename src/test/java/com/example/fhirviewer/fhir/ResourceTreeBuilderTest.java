package com.example.fhirviewer.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.fhirviewer.TreeAssert;
import com.example.fhirviewer.model.ElementInfo;
import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceNode;
import com.example.fhirviewer.service.FhirService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that the resource tree is generated dynamically from the FHIR model:
 * nested elements, repeating elements, primitives, extensions, references,
 * contained resources and Bundle entries.
 */
class ResourceTreeBuilderTest {

    private static FhirService service;

    @BeforeAll
    static void createService() {
        service = new FhirService();
    }

    private static ResourceNode tree(String fileName) {
        LoadedResource loaded = service.openSample("/fhir/" + fileName);
        return service.buildTree(loaded, false);
    }

    private static ResourceNode fullTree(String fileName) {
        LoadedResource loaded = service.openSample("/fhir/" + fileName);
        return service.buildTree(loaded, true);
    }

    @Test
    @DisplayName("The root node is the resource itself, with its id as the value")
    void rootNodeDescribesTheResource() {
        ResourceNode root = tree("patient.json");

        assertEquals("Patient", root.getLabel());
        assertEquals("Patient", root.getPath());
        assertEquals("example-1", root.getValueText());
        assertEquals("Patient: example-1", root.getDisplayText());
        assertEquals(ElementInfo.Kind.RESOURCE, root.getElementInfo().getKind());
    }

    @Test
    @DisplayName("Nested elements are expanded to their full path")
    void expandsNestedElements() {
        ResourceNode root = tree("patient.json");

        assertEquals("family: Smith", TreeAssert.require(root, "Patient.name[0].family").getDisplayText());
        assertEquals("city: Springfield", TreeAssert.require(root, "Patient.address[0].city").getDisplayText());
        assertEquals("postalCode: 62701", TreeAssert.require(root, "Patient.address[0].postalCode").getDisplayText());
    }

    @Test
    @DisplayName("Repeating elements are grouped and indexed")
    void indexesRepeatingElements() {
        ResourceNode root = tree("patient.json");

        ResourceNode identifiers = TreeAssert.require(root, "Patient.identifier");
        assertEquals(2, identifiers.getChildren().size());

        assertEquals("system: http://hospital.example.org/mrn",
                TreeAssert.require(root, "Patient.identifier[0].system").getDisplayText());
        assertEquals("value: MRN-12345",
                TreeAssert.require(root, "Patient.identifier[0].value").getDisplayText());
        assertEquals("value: 000-00-0000",
                TreeAssert.require(root, "Patient.identifier[1].value").getDisplayText());

        ResourceNode given = TreeAssert.require(root, "Patient.name[0].given");
        assertEquals(2, given.getChildren().size());
        assertEquals("[0]: John", TreeAssert.require(root, "Patient.name[0].given[0]").getDisplayText());
        assertEquals("[1]: Jacob", TreeAssert.require(root, "Patient.name[0].given[1]").getDisplayText());

        ResourceNode lines = TreeAssert.require(root, "Patient.address[0].line");
        assertEquals(2, lines.getChildren().size());
    }

    @Test
    @DisplayName("Primitive values of each datatype are rendered")
    void rendersPrimitiveValues() {
        ResourceNode root = tree("patient.json");

        assertEquals("gender: male", TreeAssert.require(root, "Patient.gender").getDisplayText());
        assertEquals("active: true", TreeAssert.require(root, "Patient.active").getDisplayText());
        assertEquals("birthDate: 1974-12-25", TreeAssert.require(root, "Patient.birthDate").getDisplayText());
        assertEquals("use: official", TreeAssert.require(root, "Patient.name[0].use").getDisplayText());
    }

    @Test
    @DisplayName("Cardinality and datatype metadata come from the model")
    void exposesCardinalityAndTypeMetadata() {
        ResourceNode root = tree("patient.json");

        ElementInfo given = TreeAssert.require(root, "Patient.name[0].given").getElementInfo();
        assertEquals("0..*", given.getCardinality());
        assertTrue(given.isRepeating());

        ElementInfo gender = TreeAssert.require(root, "Patient.gender").getElementInfo();
        assertEquals("0..1", gender.getCardinality());
        assertFalse(gender.isRepeating());
        assertEquals("code", gender.getTypeCode());

        ElementInfo family = TreeAssert.require(root, "Patient.name[0].family").getElementInfo();
        assertEquals("string", family.getTypeCode());
        assertEquals(ElementInfo.Kind.PRIMITIVE, family.getKind());
    }

    @Test
    @DisplayName("Extensions are identified by their canonical URL")
    void displaysExtensions() {
        ResourceNode root = tree("patient.json");
        String extensionPath = "Patient.extension[0]";
        ResourceNode extension = TreeAssert.require(root, extensionPath);

        assertEquals("http://example.org/fhir/StructureDefinition/patient-race", extension.getLabel());
        assertEquals(ElementInfo.Kind.EXTENSION, extension.getElementInfo().getKind());
        assertEquals("url: http://example.org/fhir/StructureDefinition/patient-race",
                TreeAssert.require(root, extensionPath + ".url").getDisplayText());
        assertTrue(TreeAssert.displayTexts(extension).stream().anyMatch(text -> text.endsWith(": white")),
                "The extension value should be rendered: " + TreeAssert.displayTexts(extension));
    }

    @Test
    @DisplayName("References show their target and stay expandable")
    void displaysReferences() {
        ResourceNode root = tree("patient.json");

        ResourceNode managingOrganization = TreeAssert.require(root, "Patient.managingOrganization");
        assertEquals(ElementInfo.Kind.REFERENCE, managingOrganization.getElementInfo().getKind());
        assertEquals("managingOrganization: Organization/example-org", managingOrganization.getDisplayText());
        assertEquals("reference: Organization/example-org",
                TreeAssert.require(root, "Patient.managingOrganization.reference").getDisplayText());

        ResourceNode practitioner = TreeAssert.require(root, "Patient.generalPractitioner[0]");
        assertTrue(practitioner.getDisplayText().contains("Practitioner/example-gp"), practitioner.getDisplayText());
    }

    @Test
    @DisplayName("Contained resources are shown as nested resources")
    void displaysContainedResources() {
        ResourceNode root = tree("patient-contained.json");
        ResourceNode contained = TreeAssert.require(root, "Patient.contained[0]");

        assertEquals(ElementInfo.Kind.NESTED_RESOURCE, contained.getElementInfo().getKind());
        assertEquals("[0]: Observation/contained-observation", contained.getDisplayText());
        assertEquals("status: final", TreeAssert.require(root, "Patient.contained[0].status").getDisplayText());
    }

    @Test
    @DisplayName("Bundle entries expose the entry resources")
    void expandsBundleEntries() {
        ResourceNode root = tree("bundle.json");

        assertEquals("Bundle", root.getLabel());
        assertEquals("type: collection", TreeAssert.require(root, "Bundle.type").getDisplayText());
        assertEquals(3, TreeAssert.require(root, "Bundle.entry").getChildren().size());

        ResourceNode firstEntryResource = TreeAssert.require(root, "Bundle.entry[0].resource");
        assertEquals("resource: Patient/patient-a", firstEntryResource.getDisplayText());
        assertEquals("family: Doe",
                TreeAssert.require(root, "Bundle.entry[0].resource.name[0].family").getDisplayText());
        assertEquals("Organization/organization-a",
                TreeAssert.require(root, "Bundle.entry[1].resource").getValueText());
    }

    @Test
    @DisplayName("Elements of any resource type are expanded without resource specific code")
    void expandsAnyResourceType() {
        ResourceNode root = tree("observation.json");

        assertEquals("Observation", root.getLabel());
        assertEquals("status: final", TreeAssert.require(root, "Observation.status").getDisplayText());
        assertEquals("text: Heart rate", TreeAssert.require(root, "Observation.code.text").getDisplayText());
        assertEquals("display: Heart rate",
                TreeAssert.require(root, "Observation.code.coding[0].display").getDisplayText());
        assertEquals("effectiveDateTime: 2024-05-01T10:15:00Z",
                TreeAssert.require(root, "Observation.effectiveDateTime").getDisplayText());
        assertEquals(2, TreeAssert.require(root, "Observation.component").getChildren().size());
    }

    @Test
    @DisplayName("Unpopulated elements are excluded by default and included on request")
    void canIncludeUnpopulatedElements() {
        ResourceNode compact = tree("observation.json");
        ResourceNode complete = fullTree("observation.json");

        assertTrue(TreeAssert.countNodes(complete) > TreeAssert.countNodes(compact));
        assertTrue(TreeAssert.paths(complete).contains("Observation.category"));
        assertFalse(TreeAssert.paths(compact).contains("Observation.category"));
    }

    @Test
    @DisplayName("Choice type values such as valueQuantity are expanded")
    void rendersChoiceTypeValues() {
        ResourceNode root = tree("observation.json");

        ResourceNode valueQuantity = TreeAssert.require(root, "Observation.valueQuantity");
        assertEquals(ElementInfo.Kind.COMPLEX, valueQuantity.getElementInfo().getKind());
        assertNotEquals(0, valueQuantity.getChildren().size());
        assertTrue(TreeAssert.displayTexts(valueQuantity).stream().anyMatch(text -> text.endsWith(": 72")),
                "valueQuantity should expose its decimal value: " + TreeAssert.displayTexts(valueQuantity));
    }
}