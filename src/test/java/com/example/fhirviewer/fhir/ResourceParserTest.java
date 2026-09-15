package com.example.fhirviewer.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.util.FileSupport;

/** Tests JSON/XML parsing, resource type detection and error reporting. */
class ResourceParserTest {

    private static ResourceParser parser;

    @BeforeAll
    static void createParser() {
        parser = new ResourceParser(FhirContextFactory.r4());
    }

    private static String testResource(String fileName) throws IOException {
        return FileSupport.readClasspathText("/fhir/" + fileName);
    }

    @Test
    @DisplayName("A JSON Patient is parsed and its resource type detected")
    void parsesJsonResource() throws IOException {
        IBaseResource resource = parser.parse(testResource("patient.json"), "patient.json");

        assertEquals("Patient", resource.fhirType());
        assertNotNull(resource.getIdElement());
        assertEquals("example-1", resource.getIdElement().getIdPart());
    }

    @Test
    @DisplayName("An XML Patient is parsed and its resource type detected")
    void parsesXmlResource() throws IOException {
        IBaseResource resource = parser.parse(testResource("patient.xml"), "patient.xml");

        assertEquals("Patient", resource.fhirType());
        assertEquals("example-1", resource.getIdElement().getIdPart());
    }

    @Test
    @DisplayName("An Observation using a choice element (value[x]) is parsed")
    void parsesChoiceElements() throws IOException {
        IBaseResource resource = parser.parse(testResource("observation.json"), "observation.json");

        assertEquals("Observation", resource.fhirType());
        assertEquals("observation-1", resource.getIdElement().getIdPart());
    }

    @Test
    @DisplayName("A Bundle is parsed with all of its entries")
    void parsesBundle() throws IOException {
        IBaseResource resource = parser.parse(testResource("bundle.json"), "bundle.json");

        assertEquals("Bundle", resource.fhirType());
        assertEquals(3, new R4ModelAdapter().entriesOf(resource).size());
    }

    @Test
    @DisplayName("A resource nested in another resource is parsed")
    void parsesContainedResources() throws IOException {
        String content = testResource("patient-contained.json");
        IBaseResource resource = parser.parse(content, "patient-contained.json");

        assertEquals("Patient", resource.fhirType());
        String json = new ResourceSerializer(FhirContextFactory.r4()).toJson(resource);
        assertTrue(json.contains("contained-observation"), "The contained Observation should survive parsing");
    }

    @Test
    @DisplayName("Malformed JSON is reported with a useful message")
    void reportsMalformedJson() throws IOException {
        String content = testResource("malformed.json");

        FhirParseException failure = assertThrows(FhirParseException.class,
                () -> parser.parse(content, ResourceFormat.JSON, "malformed.json"));

        assertTrue(failure.getMessage().contains("malformed.json"), failure.getMessage());
        assertTrue(failure.getMessage().contains("JSON"), failure.getMessage());
    }

    @Test
    @DisplayName("JSON that is not a FHIR resource is rejected")
    void rejectsJsonWithoutResourceType() throws IOException {
        String content = testResource("not-a-resource.json");

        assertThrows(FhirParseException.class, () -> parser.parse(content, "not-a-resource.json"));
    }

    @Test
    @DisplayName("An empty document is rejected instead of producing an empty resource")
    void rejectsEmptyInput() {
        assertThrows(FhirParseException.class, () -> parser.parse("   \n  ", "empty.json"));
    }

    @Test
    @DisplayName("XML text is still parsed when the file name says JSON")
    void usesContentRatherThanTheFileName() throws IOException {
        String xml = testResource("patient.xml");

        IBaseResource resource = parser.parse(xml, "misleading-name.json");

        assertEquals("Patient", resource.fhirType());
    }
}