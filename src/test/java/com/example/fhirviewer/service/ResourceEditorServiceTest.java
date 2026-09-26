package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.TreeAssert;
import com.example.fhirviewer.fhir.ElementProperty;
import com.example.fhirviewer.model.BundleEntryInfo;
import com.example.fhirviewer.model.ElementInfo;
import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.model.ResourceNode;
import com.example.fhirviewer.pretty.PrettyBlock;
import com.example.fhirviewer.pretty.PrettyDocument;
import com.example.fhirviewer.pretty.PrettyRow;

/**
 * Tests for the editing service: primitive edits, elements that are created on demand,
 * new entries of repeating elements, references and cloning.
 *
 * <p>The paths used here are the ones the resource tree produces (see
 * {@code com.example.fhirviewer.fhir.ResourceTreeBuilder}), so the tests double as a
 * check that a node selected in the tree can be edited directly.</p>
 */
class ResourceEditorServiceTest {

    private final FhirService service = new FhirService();
    private final ResourceEditorService editor = new ResourceEditorService();
    private Patient patient;

    @BeforeEach
    void setUp() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");
        patient = (Patient) loaded.getResource();
    }

    /** FHIR JSON is pretty printed with spaces around the colons; compare without them. */
    private String json() {
        return service.toJson(patient).replaceAll("\\s+", "");
    }

    @Test
    @DisplayName("A primitive element is edited in place and round-trips through JSON")
    void editsPrimitive() {
        editor.setPrimitive(patient, "Patient.name[0].family", "Jones");

        assertEquals("Jones", patient.getNameFirstRep().getFamily());
        assertTrue(json().contains("\"family\":\"Jones\""));
    }

    @Test
    @DisplayName("Editing an element that is not present creates it")
    void createsMissingElement() {
        assertEquals(0, editor.valueCount(patient, "Patient.name[0].prefix"));

        editor.setPrimitive(patient, "Patient.name[0].prefix", "Dr.");

        assertEquals(1, editor.valueCount(patient, "Patient.name[0].prefix"));
        assertEquals("Dr.", patient.getNameFirstRep().getPrefix().get(0).getValue());
        assertTrue(json().contains("\"prefix\":[\"Dr.\"]"));
    }

    @Test
    @DisplayName("A choice element is edited under its concrete element name")
    void editsChoiceElement() {
        editor.setPrimitive(patient, "Patient.deceasedBoolean", "true");

        assertInstanceOf(BooleanType.class, patient.getDeceased());
        assertEquals("true", ((IPrimitiveType<?>) patient.getDeceased()).getValueAsString());
        assertTrue(json().contains("\"deceasedBoolean\":true"));
    }

    @Test
    @DisplayName("A single entry of a repeating primitive element is edited, not the first one")
    void editsAnIndexedPrimitiveEntry() {
        assertEquals("Jacob", patient.getNameFirstRep().getGiven().get(1).getValue());

        editor.setPrimitive(patient, "Patient.name[0].given[1]", "Changed");

        assertEquals("Changed", patient.getNameFirstRep().getGiven().get(1).getValue());
        assertEquals("John", patient.getNameFirstRep().getGiven().get(0).getValue(),
                "the other entries are untouched");
        assertTrue(json().contains("\"given\":[\"John\",\"Changed\"]"));
    }

    @Test
    @DisplayName("A null value and a blank path are rejected")
    void rejectsInvalidPrimitiveEdits() {
        assertThrows(IllegalArgumentException.class,
                () -> editor.setPrimitive(patient, "Patient.name[0].family", null));
        assertThrows(IllegalArgumentException.class,
                () -> editor.setPrimitive(patient, "   ", "value"));
    }

    @Test
    @DisplayName("A value cannot be written to a complex element")
    void rejectsNonPrimitiveTargets() {
        assertThrows(IllegalArgumentException.class,
                () -> editor.setPrimitive(patient, "Patient.name[0]", "value"));
    }

    @Test
    @DisplayName("An unknown element path is reported as an edit failure")
    void rejectsUnknownPath() {
        assertThrows(IllegalArgumentException.class,
                () -> editor.setPrimitive(patient, "Patient.unknownField", "value"));
    }

    @Test
    @DisplayName("A new entry is added to a repeating element")
    void addsRepeatingEntry() {
        IBase added = editor.addElement(patient, "Patient.name");

        assertInstanceOf(HumanName.class, added);
        assertEquals(2, patient.getName().size());
        assertEquals(2, editor.valueCount(patient, "Patient.name"));
        assertFalse(patient.getName().get(1).hasFamily(), "the new entry starts empty");
    }

    @Test
    @DisplayName("The values of a newly added entry can be edited straight away")
    void editsTheAddedEntry() {
        editor.addElement(patient, "Patient.name");

        editor.setPrimitive(patient, "Patient.name[1].family", "NewFamily");

        assertEquals("NewFamily", patient.getName().get(1).getFamily());
        assertTrue(json().contains("\"family\":\"NewFamily\""));
    }

    @Test
    @DisplayName("A new entry is added to a repeating primitive element")
    void addsRepeatingPrimitiveEntry() {
        assertEquals(2, editor.valueCount(patient, "Patient.name[0].given"));

        IBase added = editor.addElement(patient, "Patient.name[0].given");

        assertInstanceOf(IPrimitiveType.class, added);
        assertEquals(3, editor.valueCount(patient, "Patient.name[0].given"));
    }

    @Test
    @DisplayName("An absent element reports a value count of zero and a blank path cannot be added to")
    void reportsValueCounts() {
        assertEquals(0, editor.valueCount(patient, "Patient.telecom"));
        assertThrows(IllegalArgumentException.class, () -> editor.addElement(patient, " "));
    }

    @Test
    @DisplayName("An existing reference element is retargeted")
    void setsReference() {
        editor.setReference(patient, "Patient.managingOrganization", "Organization/999");

        assertEquals("Organization/999", patient.getManagingOrganization().getReference());
        assertTrue(json().contains("\"reference\":\"Organization/999\""));
    }

    @Test
    @DisplayName("A reference that is not present yet is created")
    void createsReference() {
        Patient empty = new Patient();

        editor.setReference(empty, "Patient.managingOrganization", "Organization/1");

        assertEquals("Organization/1", empty.getManagingOrganization().getReference());
    }

    @Test
    @DisplayName("References are rejected for non-reference elements and for blank targets")
    void rejectsInvalidReferences() {
        assertThrows(IllegalArgumentException.class,
                () -> editor.setReference(patient, "Patient.name[0].family", "Practitioner/1"));
        assertThrows(IllegalArgumentException.class,
                () -> editor.setReference(patient, "Patient.managingOrganization", ""));
        assertThrows(IllegalArgumentException.class,
                () -> editor.setReference(patient, "Patient.managingOrganization", null));
    }

    @Test
    @DisplayName("A clone is independent of the resource it was copied from")
    void clonesResource() {
        IBaseResource clone = editor.cloneResource(patient);

        assertInstanceOf(Patient.class, clone);
        editor.setPrimitive(clone, "Patient.name[0].family", "Copied");

        assertEquals("Copied", ((Patient) clone).getNameFirstRep().getFamily());
        assertEquals("Smith", patient.getNameFirstRep().getFamily(), "the original is untouched");
        assertNotEquals(service.toJson(patient), service.toJson(clone));
    }

    @Test
    @DisplayName("A null resource cannot be copied")
    void rejectsNullCloneTarget() {
        assertThrows(IllegalArgumentException.class, () -> editor.cloneResource(null));
    }

    @Test
    @DisplayName("A Bundle entry resource is edited in place inside the Bundle")
    void editsABundleEntry() {
        LoadedResource bundle = service.openSample("/fhir/bundle.json");
        BundleEntryInfo entry = service.bundleEntries(bundle.getResource()).get(0);
        IBaseResource entryResource = entry.resource();

        editor.setPrimitive(entryResource, "Patient.name[0].family", "Edited");

        assertEquals("Edited", ((Patient) entryResource).getNameFirstRep().getFamily());
        // The Bundle holds the same object, so saving the Bundle writes the edit out.
        assertTrue(service.toJson(bundle.getResource()).replaceAll("\\s+", "").contains("\"family\":\"Edited\""));
    }

    @Test
    @DisplayName("Every primitive path the resource tree shows can be edited by the service")
    void everyPrimitivePathFromTheTreeCanBeEdited() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");
        Patient resource = (Patient) loaded.getResource();
        List<ResourceNode> primitives = new ArrayList<>();
        collectPrimitives(service.buildTree(loaded, false), primitives);
        assertTrue(primitives.size() > 5, "the sample patient has several primitive values");

        // Rewriting each value with the value it already has must not fail: it proves the
        // path a tree node shows is a path the editor can resolve, which is what the
        // Details tab relies on.
        List<String> failures = new ArrayList<>();
        for (ResourceNode node : primitives) {
            String path = node.getElementInfo().getPath();
            try {
                editor.setPrimitive(resource, path, node.getElementInfo().getValueText());
            } catch (RuntimeException e) {
                failures.add(path + " -> " + e.getMessage());
            }
        }

        assertTrue(failures.isEmpty(), "editing failed for: " + failures);
    }

    @Test
    @DisplayName("A primitive child element is added under its parent")
    void addsPrimitiveChildElement() {
        IBase added = editor.addNode(patient, "Patient", "multipleBirthBoolean");

        assertInstanceOf(BooleanType.class, added);
        assertEquals(1, editor.valueCount(patient, "Patient.multipleBirthBoolean"));

        editor.setPrimitive(patient, "Patient.multipleBirthBoolean", "true");

        assertInstanceOf(BooleanType.class, patient.getMultipleBirth());
        assertTrue(json().contains("\"multipleBirthBoolean\":true"));
    }

    @Test
    @DisplayName("A complex child element is added under its parent and can be filled in")
    void addsComplexChildElement() {
        IBase added = editor.addNode(patient, "Patient", "maritalStatus");

        assertInstanceOf(CodeableConcept.class, added);
        assertEquals(1, editor.valueCount(patient, "Patient.maritalStatus"));

        editor.setPrimitive(patient, "Patient.maritalStatus.text", "Married");

        assertTrue(json().contains("\"text\":\"Married\""));
    }

    @Test
    @DisplayName("A repeating child element is added as a new entry that can be filled in")
    void addsRepeatingChildElement() {
        assertEquals(1, patient.getName().size());

        IBase added = editor.addNode(patient, "Patient", "name");

        assertInstanceOf(HumanName.class, added);
        assertEquals(2, patient.getName().size());

        editor.setPrimitive(patient, "Patient.name[1].family", "Jones");

        assertEquals("Jones", patient.getName().get(1).getFamily());
        assertTrue(json().contains("\"family\":\"Jones\""));
    }

    @Test
    @DisplayName("An element that already carries a value and may not repeat cannot be added")
    void rejectsAddingPopulatedSingleton() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> editor.addNode(patient, "Patient", "active"));

        assertTrue(failure.getMessage().contains("already has a value"),
                "the failure should explain that the element is populated: " + failure.getMessage());
    }

    @Test
    @DisplayName("An unknown child element name cannot be added")
    void rejectsUnknownChildElement() {
        assertThrows(IllegalArgumentException.class, () -> editor.addNode(patient, "Patient", "unknownField"));
    }

    @Test
    @DisplayName("A primitive element is deleted from the resource")
    void deletesPrimitiveElement() {
        editor.deleteNode(patient, "Patient.name[0].family");

        assertFalse(patient.getNameFirstRep().hasFamily());
        assertFalse(json().contains("\"family\""));
    }

    @Test
    @DisplayName("A single entry of a repeating element is deleted and the others stay")
    void deletesRepeatingEntry() {
        assertEquals(2, patient.getNameFirstRep().getGiven().size());

        editor.deleteNode(patient, "Patient.name[0].given[1]");

        assertEquals(1, patient.getNameFirstRep().getGiven().size());
        assertEquals("John", patient.getNameFirstRep().getGiven().get(0).getValue());
        assertTrue(json().contains("\"given\":[\"John\"]"));
    }

    @Test
    @DisplayName("A complex element is deleted together with everything under it")
    void deletesComplexElement() {
        editor.deleteNode(patient, "Patient.name[0]");

        assertTrue(patient.getName().isEmpty());
        assertFalse(json().contains("\"given\""));
    }

    @Test
    @DisplayName("Every value of a repeating element is deleted at once")
    void deletesAllValues() {
        editor.deleteNode(patient, "Patient.name[0].given");

        assertTrue(patient.getNameFirstRep().getGiven().isEmpty());
        assertFalse(json().contains("\"given\""));
    }

    @Test
    @DisplayName("A choice element is deleted under its concrete name")
    void deletesChoiceElement() {
        editor.setPrimitive(patient, "Patient.deceasedBoolean", "true");
        assertInstanceOf(BooleanType.class, patient.getDeceased());

        editor.deleteNode(patient, "Patient.deceasedBoolean");

        assertFalse(patient.hasDeceased());
        assertFalse(json().contains("\"deceasedBoolean\""));
    }

    @Test
    @DisplayName("The resource itself, unknown paths and empty elements cannot be deleted")
    void rejectsInvalidDeletes() {
        assertThrows(IllegalArgumentException.class, () -> editor.deleteNode(patient, "Patient"));
        assertThrows(IllegalArgumentException.class, () -> editor.deleteNode(patient, "Patient.unknownField"));
        assertThrows(IllegalArgumentException.class, () -> editor.deleteNode(patient, "Patient.telecom"));
    }

        @Test
    @DisplayName("The child elements of an element come from the FHIR resource definition")
    void listsChildElementsFromTheDefinition() {
        List<ElementProperty> children = editor.childElements(patient, "Patient");

        // Primitive, complex and repeating elements are all offered.
        assertTrue(children.stream().anyMatch(child -> child.name().equals("multipleBirthBoolean")));
        assertTrue(children.stream().anyMatch(child -> child.name().equals("maritalStatus")));
        assertTrue(children.stream().anyMatch(child -> child.name().equals("name")));
        // Choice elements are offered under their concrete names, not as value[x].
        assertTrue(children.stream().anyMatch(child -> child.name().equals("deceasedBoolean")));
        assertTrue(children.stream().noneMatch(child -> child.name().endsWith("[x]")));
        // Elements that already carry their single value cannot receive another one.
        assertTrue(children.stream().noneMatch(child -> child.name().equals("active")));
        assertTrue(children.stream().noneMatch(child -> child.name().equals("gender")));
    }

    @Test
    @DisplayName("The child elements of an empty complex element are listed from the definition")
    void listsChildElementsOfAnEmptyElement() {
        editor.addElement(patient, "Patient.name");

        List<ElementProperty> children = editor.childElements(patient, "Patient.name[1]");

        assertTrue(children.stream().anyMatch(child -> child.name().equals("family")));
        assertTrue(children.stream().anyMatch(child -> child.name().equals("given")));
        assertTrue(children.stream().anyMatch(child -> child.name().equals("use")));
    }

    @Test
    @DisplayName("Child elements are not listed under an element without child elements")
    void rejectsChildElementsOfAPrimitive() {
        assertThrows(IllegalArgumentException.class,
                () -> editor.childElements(patient, "Patient.name[0].family"));
    }

    @Test
    @DisplayName("The resource tree no longer shows a deleted element")
    void treeReflectsDeletions() {
        editor.deleteNode(patient, "Patient.name[0].given[1]");

        ResourceNode tree = service.buildTree(loaded(), false);
        assertTrue(TreeAssert.find(tree, "Patient.name[0].given[1]").isEmpty());
        assertTrue(TreeAssert.find(tree, "Patient.name[0].given[0]").isPresent());
    }

    @Test
    @DisplayName("The resource tree shows an element added through the service")
    void treeReflectsAdditions() {
        editor.addNode(patient, "Patient", "maritalStatus");
        editor.setPrimitive(patient, "Patient.maritalStatus.text", "Married");

        ResourceNode tree = service.buildTree(loaded(), false);
        assertTrue(TreeAssert.find(tree, "Patient.maritalStatus.text").isPresent());
    }

    @Test
    @DisplayName("The Pretty View model reflects an edit made through the service")
    void prettyViewModelReflectsEdits() {
        LoadedResource loaded = loaded();
        assertFalse(prettyText(service.buildPrettyView(loaded)).contains("Jones"));

        editor.setPrimitive(patient, "Patient.name[0].family", "Jones");

        assertTrue(prettyText(service.buildPrettyView(loaded)).contains("Jones"));
        assertFalse(prettyText(service.buildPrettyView(loaded)).contains("Smith"));
    }

    @Test
    @DisplayName("The Pretty View model no longer shows a deleted element")
    void prettyViewModelReflectsDeletions() {
        editor.deleteNode(patient, "Patient.name[0].family");

        assertFalse(prettyText(service.buildPrettyView(loaded())).contains("Smith"));
    }

    /** The test resource wrapped the way the application carries a loaded resource. */
    private LoadedResource loaded() {
        return new LoadedResource(patient, ResourceFormat.JSON, "patient.json", null);
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

    private static void collectPrimitives(ResourceNode node, List<ResourceNode> found) {
        ElementInfo info = node.getElementInfo();
        if (info.getKind() == ElementInfo.Kind.PRIMITIVE && info.hasValueText()) {
            found.add(node);
        }
        for (ResourceNode child : node.getChildren()) {
            collectPrimitives(child, found);
        }
    }
}
