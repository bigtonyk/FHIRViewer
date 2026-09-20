package com.example.fhirviewer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.service.FhirService;
import com.example.fhirviewer.service.ResourceTemplateFactory;

/**
 * Tests the loading and change-tracking state the window uses for Save, Undo, the
 * {@code *} title marker and the discard-changes guard.
 */
class LoadedResourceTest {

    private final FhirService service = new FhirService();

    private LoadedResource samplePatient() {
        return service.openSample("/fhir/patient.json");
    }

    @Test
    @DisplayName("A freshly loaded sample is clean and has no file of its own")
    void loadedResourceStartsClean() {
        LoadedResource loaded = samplePatient();

        assertFalse(loaded.isDirty());
        assertNull(loaded.getSourcePath());
        assertEquals("Patient/example-1", loaded.getDisplayName());
    }

    @Test
    @DisplayName("markDirty and markClean follow the editing state")
    void tracksChanges() {
        LoadedResource loaded = samplePatient();

        loaded.markDirty();
        assertTrue(loaded.isDirty());

        loaded.markClean();
        assertFalse(loaded.isDirty());
    }

    @Test
    @DisplayName("Save As rebases a resource on another file and format")
    void rebasesOnAnotherFile() {
        LoadedResource loaded = samplePatient();
        Path target = Paths.get("target", "saved-patient.xml");

        loaded.setSourcePath(target);
        loaded.setFormat(ResourceFormat.XML);
        loaded.setSourceName("saved-patient.xml");

        assertEquals(target, loaded.getSourcePath());
        assertEquals(ResourceFormat.XML, loaded.getFormat());
        assertEquals("saved-patient.xml", loaded.getSourceName());
    }

    @Test
    @DisplayName("A new resource has no id, so it is labelled with its type alone")
    void labelsNewResourcesWithTheirType() {
        IBaseResource resource = ResourceTemplateFactory.r4().createEmpty("Observation");

        LoadedResource created = new LoadedResource(resource, ResourceFormat.JSON, "Observation", null);

        assertEquals("Observation", created.getDisplayName());
        assertNull(created.getResourceId());
        assertEquals("Observation", created.getSourceName());
        assertNull(created.getSourcePath(), "a new resource is not backed by a file yet");
    }
}
