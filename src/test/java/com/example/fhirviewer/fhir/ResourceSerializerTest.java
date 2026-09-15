package com.example.fhirviewer.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.service.FhirService;

/** Tests JSON and XML serialization, including round trips. */
class ResourceSerializerTest {

    private static FhirService service;
    private static ResourceParser parser;

    @BeforeAll
    static void createService() {
        service = new FhirService();
        parser = new ResourceParser(FhirContextFactory.r4());
    }

    private static String withoutWhitespace(String text) {
        return text.replaceAll("\\s+", "");
    }

    @Test
    @DisplayName("JSON output is pretty printed and contains the resource contents")
    void rendersJson() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");

        String json = service.toJson(loaded.getResource());

        assertTrue(json.contains("\n"), "Pretty printing should produce multiple lines");
        assertTrue(withoutWhitespace(json).contains("\"resourceType\":\"Patient\""));
        assertTrue(withoutWhitespace(json).contains("\"family\":\"Smith\""));
        assertTrue(withoutWhitespace(json).contains("\"birthDate\":\"1974-12-25\""));
    }

    @Test
    @DisplayName("XML output uses the FHIR namespace and the resource name as root element")
    void rendersXml() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");

        String xml = service.toXml(loaded.getResource());

        assertTrue(xml.contains("xmlns=\"http://hl7.org/fhir\""), xml.substring(0, Math.min(400, xml.length())));
        assertTrue(xml.contains("<Patient"));
        assertTrue(xml.contains("<birthDate value=\"1974-12-25\""));
    }

    @Test
    @DisplayName("A JSON resource survives a JSON -> XML -> JSON round trip")
    void xmlRoundTripPreservesValues() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");
        String xml = service.toXml(loaded.getResource());

        IBaseResource reparsed = parser.parse(xml, ResourceFormat.XML, "round-trip.xml");
        String json = service.toJson(reparsed);

        assertEquals("Patient", reparsed.fhirType());
        assertTrue(withoutWhitespace(json).contains("\"family\":\"Smith\""));
        assertTrue(withoutWhitespace(json).contains("\"birthDate\":\"1974-12-25\""));
        assertTrue(withoutWhitespace(json).contains("\"reference\":\"Organization/example-org\""));
        assertTrue(withoutWhitespace(json).contains("\"url\":\"http://example.org/fhir/StructureDefinition/patient-race\""));
    }

    @Test
    @DisplayName("A Bundle survives an XML round trip with all entries")
    void bundleRoundTripKeepsEntries() {
        LoadedResource loaded = service.openSample("/fhir/bundle.json");
        String xml = service.toXml(loaded.getResource());

        IBaseResource reparsed = parser.parse(xml, ResourceFormat.XML, "bundle-round-trip.xml");

        assertEquals("Bundle", reparsed.fhirType());
        assertEquals(3, new R4ModelAdapter().entriesOf(reparsed).size());
    }

    @Test
    @DisplayName("The requested format is used by serialize()")
    void honoursTheRequestedFormat() {
        LoadedResource loaded = service.openSample("/fhir/observation.json");

        String json = service.serialize(loaded.getResource(), ResourceFormat.JSON);
        String xml = service.serialize(loaded.getResource(), ResourceFormat.XML);

        assertTrue(withoutWhitespace(json).startsWith("{\"resourceType\":\"Observation\""));
        assertTrue(xml.contains("<Observation"));
    }
}