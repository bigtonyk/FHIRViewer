package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the origin model the Open-from / Save-to flow depends on.
 *
 * <p>The decisions that matter are which writes become updates and whether a conflict can be
 * detected at all: {@link ServerOrigin#isSaved()} picks create versus update, and
 * {@link ServerOrigin#hasVersion()} decides whether the UI may imply a conflict check
 * happened. Getting either wrong either overwrites a resource or creates a duplicate.</p>
 */
public class ServerOriginTest {

    private static final ServerOrigin SAVED =
            ServerOrigin.of("firely", "https://firely.example.com/fhir", "Patient", "p1", "2");

    @Test
    @DisplayName("A resource the server has seen is an update; one it has not is a create")
    void savedVersusUnsaved() {
        assertTrue(SAVED.isSaved());
        assertFalse(ServerOrigin.unsaved("firely", "https://firely.example.com/fhir", "Patient")
                .isSaved());
    }

    @Test
    @DisplayName("A blank server id is not treated as saved")
    void blankIdIsNotSaved() {
        assertFalse(ServerOrigin.of("firely", "https://x.example/fhir", "Patient", "  ", "2")
                .isSaved());
    }

    @Test
    @DisplayName("hasVersion reports whether a conflict check is actually possible")
    void hasVersionReflectsTheServersAnswer() {
        assertTrue(SAVED.hasVersion());
        assertFalse(ServerOrigin.of("firely", "https://x.example/fhir", "Patient", "p1", null)
                .hasVersion());
        assertFalse(ServerOrigin.unsaved("firely", "https://x.example/fhir", "Patient").hasVersion());
    }

    @Test
    @DisplayName("Dropping the version keeps the id, so a forced write is still an update")
    void withoutVersionKeepsTheResourceId() {
        ServerOrigin forced = SAVED.withoutVersion();

        assertFalse(forced.hasVersion(), "the version must be gone so no If-Match is sent");
        assertTrue(forced.isSaved(), "keeping the id means update, not a second create");
        assertEquals("p1", forced.resourceId());
        assertEquals("Patient", forced.resourceType(), "the type must survive an update");
        assertEquals(SAVED.baseUrl(), forced.baseUrl());
        assertTrue(SAVED.hasVersion(), "the original must not be mutated");
    }

    @Test
    @DisplayName("Rebasing after a create adopts the id the server assigned")
    void rebasedAdoptsTheServerId() {
        ServerOrigin before = ServerOrigin.unsaved("firely", "https://x.example/fhir", "Patient");
        assertFalse(before.isSaved());

        ServerWriteResult created = ServerWriteResult.created("generated-1", "1");
        ServerOrigin after = before.rebased(created);

        assertTrue(after.isSaved(), "a created resource is now updatable");
        assertEquals("generated-1", after.resourceId());
        assertEquals("1", after.versionId());
        assertTrue(after.hasVersion());
    }

    @Test
    @DisplayName("Rebasing carries the resource type over, so a later save can address it")
    void rebasedKeepsTheType() {
        ServerWriteResult updated = ServerWriteResult.updated("p1", "3");
        assertEquals("Patient", SAVED.rebased(updated).resourceType());
    }

    @Test
    @DisplayName("Rebasing against a result with no id leaves the origin unsaved")
    void rebasedWithNoIdStaysUnsaved() {
        // The raw constructor, because the factories both require a non-null id.
        ServerWriteResult noId = new ServerWriteResult(null, null, true);
        assertFalse(ServerOrigin.unsaved("firely", "https://x.example/fhir", "Patient")
                .rebased(noId).isSaved(),
                "inventing an id would make the next save overwrite a resource we never made");
    }

    @Test
    @DisplayName("Two origins from one server are the same server even for different resources")
    void sameServerIgnoresTheResource() {
        ServerOrigin other = ServerOrigin.of("firely", "https://firely.example.com/fhir",
                "Observation", "o1", "1");
        assertTrue(SAVED.isSameServer(other));
        assertFalse(SAVED.isSameServer(ServerOrigin.of("smile-cdr",
                "https://firely.example.com/fhir", "Patient", "p1", "2")));
        assertFalse(SAVED.isSameServer(null));
    }

    @Test
    @DisplayName("An origin with no plugin or url is rejected at construction")
    void requiredFieldsAreEnforced() {
        assertThrows(NullPointerException.class,
                () -> new ServerOrigin(null, "https://x.example/fhir", "Patient", "p1", "1"));
        assertThrows(NullPointerException.class,
                () -> new ServerOrigin("firely", null, "Patient", "p1", "1"));
        assertThrows(NullPointerException.class,
                () -> new ServerOrigin("firely", "https://x.example/fhir", null, "p1", "1"));
    }

    @Test
    @DisplayName("toString never exposes a credential, and marks an unsaved resource")
    void toStringIsSafe() {
        assertTrue(SAVED.toString().contains("p1"));
        assertTrue(ServerOrigin.unsaved("firely", "https://x.example/fhir", "Patient")
                .toString().contains("(unsaved)"));
        assertFalse(SAVED.toString().toLowerCase().contains("password"));
    }

    @Test
    @DisplayName("A result with no version is reported as having no version")
    void writeResultWithoutAVersion() {
        ServerWriteResult noVersion = ServerWriteResult.updated("p1", null);
        assertFalse(SAVED.rebased(noVersion).hasVersion(),
                "the UI must be able to say the conflict check did not happen");
        assertNull(SAVED.rebased(noVersion).versionId());
    }
}
