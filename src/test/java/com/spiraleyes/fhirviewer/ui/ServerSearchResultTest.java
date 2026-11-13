package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.server.ServerDefinition;

/**
 * Checks what "Open in viewer" hands the window after a search.
 *
 * <p>The search dialog used to return a bare {@code LoadedResource}, which cannot say which
 * server produced it: a resource found by search could be viewed but never refreshed, patched,
 * deleted or saved back, because every server action needs an origin. The result now carries
 * one, and these tests pin the two cases that matter - a normal result, and a server that sent
 * no id (where an origin would silently turn Save into a create).</p>
 */
class ServerSearchResultTest {

    @Test
    @DisplayName("A search result keeps the server, type, id and version a later save needs")
    void searchResultCarriesItsOrigin() {
        Patient patient = new Patient();
        patient.setId("patient-a");
        patient.getMeta().setVersionId("3");
        ServerDefinition server = ServerDefinition.named("Rest", "https://example.test/fhir").build();

        ServerSearchDialog.Picked picked = ServerSearchDialog.pickedFor(patient, server);

        assertNotNull(picked.origin(), "a result with an id must know where it came from");
        assertEquals(server.pluginId(), picked.origin().pluginId());
        assertEquals(server.baseUrl(), picked.origin().baseUrl());
        assertEquals("Patient", picked.origin().resourceType());
        assertEquals("patient-a", picked.origin().resourceId());
        assertEquals("3", picked.origin().versionId());
        assertTrue(picked.resource().getSourceName().contains("Rest"),
                "the label must say which server the resource came from");
    }

    @Test
    @DisplayName("A result without an id is shown but not made writable")
    void resultWithoutIdHasNoOrigin() {
        Patient patient = new Patient();
        ServerDefinition server = ServerDefinition.named("Rest", "https://example.test/fhir").build();

        ServerSearchDialog.Picked picked = ServerSearchDialog.pickedFor(patient, server);

        assertNull(picked.origin(),
                "an origin with no id means create, so a search hit must never get one");
        assertTrue(picked.resource().getSourceName().contains("(from Rest)"),
                "the resource must still say where it came from");
    }
}
