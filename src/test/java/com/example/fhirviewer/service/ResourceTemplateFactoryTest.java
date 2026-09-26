package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the factory that builds the empty resources behind File &gt; New.
 */
class ResourceTemplateFactoryTest {

    private final ResourceTemplateFactory factory = ResourceTemplateFactory.r4();

    @Test
    @DisplayName("An empty resource of the requested type is created")
    void createsAnEmptyResource() {
        IBaseResource patient = factory.createEmpty("Patient");

        assertNotNull(patient);
        assertEquals("Patient", patient.fhirType());
        assertFalse(patient.getIdElement().hasIdPart(), "a new resource has no id yet");
    }

    @Test
    @DisplayName("Resources of other types are created just as well")
    void createsOtherTypes() {
        assertEquals("Observation", factory.createEmpty("Observation").fhirType());
        assertEquals("Encounter", factory.createEmpty("Encounter").fhirType());
        assertEquals("Bundle", factory.createEmpty("Bundle").fhirType());
    }

    @Test
    @DisplayName("An unknown resource type is reported as an illegal argument")
    void rejectsUnknownTypes() {
        assertThrows(IllegalArgumentException.class, () -> factory.createEmpty("NotAResource"));
    }

    @Test
    @DisplayName("The list of available types covers the common resources and is sorted")
    void listsResourceTypes() {
        List<String> types = factory.resourceTypeNames();

        assertTrue(types.contains("Patient"));
        assertTrue(types.contains("Observation"));
        assertTrue(types.contains("Encounter"));
        assertTrue(types.size() > 50, "the R4 model defines many resource types");
        assertEquals(types.stream().sorted().toList(), types, "the dialog shows them sorted");
    }
}
