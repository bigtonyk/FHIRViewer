package com.spiraleyes.fhirviewer.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceArchiveRoundTripTest {

    @TempDir Path dir;

    @Test
    void roundTripWithSubsystemState() throws Exception {
        WorkspaceMetadata meta = new WorkspaceMetadata(
                WorkspaceMetadata.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(), "Round Trip");
        Path file = dir.resolve("test.fhirworkspace");
        WorkspaceArchiveWriter.write(file, meta);
        WorkspaceMetadata read = WorkspaceArchiveReader.read(file);
        assertEquals(meta, read);
    }

    @Test
    void subsystemStateRoundTripsThroughArchive() throws Exception {
        Map<String, Object> connections = new LinkedHashMap<>();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", "Local HAPI");
        server.put("baseUrl", "http://localhost:8080/fhir");
        connections.put("Local HAPI", server);

        Map<String, Object> restWorkbench = new LinkedHashMap<>();
        restWorkbench.put("method", "GET");
        restWorkbench.put("path", "/Patient");
        restWorkbench.put("body", "test body content");

        Map<String, Object> treeTabs = new LinkedHashMap<>();
        treeTabs.put("selected", "Patient/123");
        treeTabs.put("tabs", List.of("tree", "details"));

        Map<String, Object> fhirPath = new LinkedHashMap<>();
        fhirPath.put("expressions", List.of("Patient.name", "Observation.value"));

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("packages", List.of(Map.of("name", "core", "version", "4.0.1")));

        WorkspaceMetadata meta = new WorkspaceMetadata(
                WorkspaceMetadata.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(), "Subsystem",
                connections, restWorkbench, treeTabs, fhirPath, validation);

        Path file = dir.resolve("subsystem.fhirworkspace");
        WorkspaceArchiveWriter.write(file, meta);

        WorkspaceMetadata read = WorkspaceArchiveReader.read(file);

        assertEquals("Local HAPI", ((Map) read.connections().get("Local HAPI")).get("name"));
        assertEquals("http://localhost:8080/fhir", ((Map) read.connections().get("Local HAPI")).get("baseUrl"));
        assertEquals("GET", read.restWorkbench().get("method"));
        assertEquals("/Patient", read.restWorkbench().get("path"));
        assertTrue(read.restWorkbench().containsKey("body"));
        assertEquals("test body content", read.restWorkbench().get("body"));
        assertEquals("Patient/123", read.treeTabs().get("selected"));
        assertEquals(List.of("Patient.name", "Observation.value"), read.fhirPath().get("expressions"));
        assertEquals(List.of(Map.of("name", "core", "version", "4.0.1")), read.validation().get("packages"));
    }
}