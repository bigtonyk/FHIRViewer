package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.TreeAssert;
import com.example.fhirviewer.model.BundleEntryInfo;
import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.model.ResourceNode;
import com.example.fhirviewer.util.FileSupport;

/**
 * End to end tests for the service layer: loading, tree building, serialization and
 * Bundle navigation through a single entry point.
 */
class FhirServiceTest {

    private static FhirService service;

    @BeforeAll
    static void createService() {
        service = new FhirService();
    }

    @Test
    @DisplayName("A loaded resource can be displayed as a tree, as JSON and as XML")
    void providesEveryViewForALoadedResource() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");

        ResourceNode tree = service.buildTree(loaded, false);
        String json = service.toJson(loaded.getResource());
        String xml = service.toXml(loaded.getResource());

        assertEquals("Patient", tree.getLabel());
        assertTrue(TreeAssert.paths(tree).contains("Patient.name[0].family"));
        assertFalse(json.isBlank());
        assertTrue(xml.contains("<Patient"));
        assertEquals("R4", service.fhirVersion());
    }

    @Test
    @DisplayName("Bundle entries are listed in order")
    void listsBundleEntries() {
        LoadedResource loaded = service.openSample("/fhir/bundle.json");

        List<BundleEntryInfo> entries = service.bundleEntries(loaded.getResource());

        assertEquals(3, entries.size());
        assertEquals(0, entries.get(0).index());
        assertEquals("Patient/patient-a", entries.get(0).displayName());
        assertEquals("Organization/organization-a", entries.get(1).displayName());
        assertEquals("Observation/observation-a", entries.get(2).displayName());
        assertTrue(entries.get(0).hasResource());
        assertTrue(entries.get(0).getDisplayText().startsWith("[0] Patient/patient-a"));
    }

    @Test
    @DisplayName("A tree can be built for a single Bundle entry")
    void buildsTreeForOneBundleEntry() {
        LoadedResource loaded = service.openSample("/fhir/bundle.json");
        BundleEntryInfo entry = service.bundleEntries(loaded.getResource()).get(0);

        ResourceNode tree = service.buildTree(entry, false);

        assertEquals("Patient/patient-a", tree.getLabel());
        assertEquals("Patient", tree.getPath());
        assertTrue(TreeAssert.paths(tree).contains("Patient.name[0].family"));
    }

    @Test
    @DisplayName("Non Bundle resources report no Bundle entries")
    void nonBundlesHaveNoEntries() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");

        assertTrue(service.bundleEntries(loaded.getResource()).isEmpty());
        assertFalse(service.isBundle(loaded.getResource()));
    }

    @Test
    @DisplayName("Bundles are recognised")
    void recognisesBundles() {
        LoadedResource loaded = service.openSample("/fhir/bundle.json");

        assertTrue(service.isBundle(loaded.getResource()));
        assertTrue(loaded.isBundle());
    }

    @Test
    @DisplayName("Text can be opened directly, with the format detected")
    void opensText() throws IOException {
        String json = FileSupport.readClasspathText("/fhir/observation.json");

        LoadedResource loaded = service.openText(json, "pasted.json");

        assertEquals(ResourceFormat.JSON, loaded.getFormat());
        assertEquals("Observation", loaded.getResourceType());
        assertNotNull(loaded.getRawText());
        assertEquals("Observation", service.buildTree(loaded, false).getLabel());
    }

    @Test
    @DisplayName("Opening an unknown sample reports a clear failure")
    void reportsUnknownSamples() {
        assertThrows(ResourceLoadException.class, () -> service.openSample("/samples/nope.json"));
    }
}