package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.util.FileSupport;

/** Tests loading resources from the classpath, from files and from text. */
class ResourceLoaderTest {

    private final FhirService service = new FhirService();

    @Test
    @DisplayName("A JSON resource is loaded from the classpath")
    void loadsJsonFromClasspath() {
        LoadedResource loaded = service.openSample("/fhir/patient.json");

        assertEquals("Patient", loaded.getResourceType());
        assertEquals("example-1", loaded.getResourceId());
        assertEquals(ResourceFormat.JSON, loaded.getFormat());
        assertEquals("Patient/example-1", loaded.getDisplayName());
    }

    @Test
    @DisplayName("An XML resource is loaded from the classpath")
    void loadsXmlFromClasspath() {
        LoadedResource loaded = service.openSample("/fhir/patient.xml");

        assertEquals("Patient", loaded.getResourceType());
        assertEquals(ResourceFormat.XML, loaded.getFormat());
    }

    @Test
    @DisplayName("XML content in a .json file is still opened")
    void fallsBackToTheOtherFormat() throws IOException {
        String xml = FileSupport.readClasspathText("/fhir/patient.xml");

        LoadedResource loaded = service.openText(xml, "patient.json");

        assertEquals("Patient", loaded.getResourceType());
        assertEquals(ResourceFormat.XML, loaded.getFormat());
    }

    @Test
    @DisplayName("A leading byte order mark does not break parsing")
    void ignoresByteOrderMark() throws IOException {
        String json = "\uFEFF" + FileSupport.readClasspathText("/fhir/patient.json");

        LoadedResource loaded = service.openText(json, "patient-with-bom.json");

        assertEquals("Patient", loaded.getResourceType());
    }

    @Test
    @DisplayName("A missing file is reported with the file name")
    void reportsMissingFiles() {
        Path missing = Paths.get("does-not-exist", "patient.json");

        ResourceLoadException failure = assertThrows(ResourceLoadException.class, () -> service.openFile(missing));

        assertTrue(failure.getMessage().contains("patient.json"), failure.getMessage());
    }

    @Test
    @DisplayName("A missing sample is reported")
    void reportsMissingSamples() {
        assertThrows(ResourceLoadException.class, () -> service.openSample("/samples/does-not-exist.json"));
    }

    @Test
    @DisplayName("Malformed content is reported instead of producing a resource")
    void reportsMalformedContent() throws IOException {
        String malformed = FileSupport.readClasspathText("/fhir/malformed.json");

        ResourceLoadException failure = assertThrows(ResourceLoadException.class,
                () -> service.openText(malformed, "malformed.json"));

        assertTrue(failure.getMessage().contains("malformed.json"), failure.getMessage());
    }

    @Test
    @DisplayName("A Bundle is recognised as such")
    void recognisesBundles() {
        LoadedResource loaded = service.openSample("/fhir/bundle.json");

        assertTrue(loaded.isBundle());
        assertEquals("Bundle", loaded.getResourceType());
    }

    @Test
    @DisplayName("A resource loaded from a file remembers the file it came from")
    void remembersTheSourceFile() throws IOException {
        Path file = Files.createTempFile("fhir-viewer-patient", ".json");
        try {
            FileSupport.writeText(file, FileSupport.readClasspathText("/fhir/patient.json"));

            LoadedResource loaded = service.openFile(file);

            assertEquals(file, loaded.getSourcePath());
            assertEquals("Patient/example-1", loaded.getDisplayName());
            assertFalse(loaded.isDirty(), "loading a resource does not count as a change");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("A sample or a pasted resource has no file to save back to")
    void resourcesWithoutAFileHaveNoSourcePath() {
        assertNull(service.openSample("/fhir/patient.json").getSourcePath());
        assertNull(service.openText("{\"resourceType\":\"Patient\"}", "pasted.json").getSourcePath());
    }
}