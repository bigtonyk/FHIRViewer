package com.spiraleyes.fhirviewer.workspace;

import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.server.ServerDefinition;
import com.spiraleyes.fhirviewer.server.StandardFhirRestPlugin;
import com.spiraleyes.fhirviewer.service.IgPackageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceServiceIntegrationTest {

    @TempDir Path dir;

    private WorkspaceService newService() {
        FhirServerManager manager = new FhirServerManager();
        manager.add(ServerDefinition.named("Local HAPI", "http://localhost:8080/fhir")
                .pluginId(StandardFhirRestPlugin.PLUGIN_ID)
                .build());
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(new StandardFhirRestPlugin());
        IgPackageManager packageManager = new IgPackageManager();
        return new WorkspaceService(manager, registry, packageManager);
    }

    @Test
    void roundTripCaptureSaveOpenPreservesMetadata() throws Exception {
        WorkspaceService service = newService();
        service.newWorkspace("Round Trip");
        service.captureAll();

        Path file = dir.resolve("workspace.fhirworkspace");
        service.saveAs(file);

        // Simulate a fresh app start: new service instance opens the saved file
        WorkspaceService restored = newService();
        restored.open(file);

        WorkspaceMetadata meta = restored.getCurrent();
        assertNotNull(meta);
        assertEquals("Round Trip", meta.name());
        assertNotNull(meta.connections());
        assertFalse(meta.connections().isEmpty(), "connections should be captured");

        // Connections data should contain the server
        @SuppressWarnings("unchecked")
        Map<String, Object> connections = (Map<String, Object>) meta.connections();
        assertTrue(connections.containsKey("Local HAPI"));
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) connections.get("Local HAPI");
        assertEquals("Local HAPI", entry.get("name"));
        assertEquals("http://localhost:8080/fhir", entry.get("baseUrl"));
    }

    @Test
    void captureExcludesSecrets() throws Exception {
        WorkspaceService service = newService();
        service.newWorkspace("No Secrets");
        service.captureAll();

        WorkspaceMetadata meta = service.getCurrent();
        @SuppressWarnings("unchecked")
        Map<String, Object> connections = (Map<String, Object>) meta.connections();
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) connections.get("Local HAPI");

        // No password, token, or secret fields should ever be captured
        assertFalse(entry.containsKey("password"));
        assertFalse(entry.containsKey("token"));
        assertFalse(entry.containsKey("secret"));
        assertFalse(entry.containsKey("credential"));
    }

    @Test
    void restoreWithMissingPluginWarnsButDoesNotFail() throws Exception {
        // Service that saves: has the plugin registered
        WorkspaceService saving = newService();
        saving.newWorkspace("Missing Plugin");
        saving.captureAll();
        Path file = dir.resolve("missing-plugin.fhirworkspace");
        saving.saveAs(file);

        // Service that restores: NO plugins registered → plugin is "missing"
        FhirServerManager manager = new FhirServerManager();
        FhirServerPluginRegistry emptyRegistry = new FhirServerPluginRegistry();
        IgPackageManager packageManager = new IgPackageManager();
        WorkspaceService restoring = new WorkspaceService(manager, emptyRegistry, packageManager);

        // Should not throw even though plugin is missing
        assertDoesNotThrow(() -> restoring.open(file));
    }

    @Test
    void restoreWithMissingPackageWarnsButDoesNotFail() throws Exception {
        // Service that saves: empty package manager
        WorkspaceService saving = newService();
        saving.newWorkspace("Missing Package");
        saving.captureAll();
        Path file = dir.resolve("missing-package.fhirworkspace");
        saving.saveAs(file);

        // Service that restores: same empty manager → package is "missing"
        WorkspaceService restoring = newService();
        assertDoesNotThrow(() -> restoring.open(file));
    }

    @Test
    void captureAndRestoreIsIdempotent() throws Exception {
        WorkspaceService service = newService();
        service.newWorkspace("Idempotent");
        service.captureAll();

        Path file = dir.resolve("idempotent.fhirworkspace");
        service.saveAs(file);

        WorkspaceService restored1 = newService();
        restored1.open(file);

        WorkspaceMetadata first = restored1.getCurrent();

        // Save again from the restored service and re-open
        restored1.saveAs(dir.resolve("second.fhirworkspace"));
        WorkspaceService restored2 = newService();
        restored2.open(dir.resolve("second.fhirworkspace"));

        WorkspaceMetadata second = restored2.getCurrent();
        assertEquals(first.name(), second.name());
        // IDs are random per save, so compare connection data without the id field
        @SuppressWarnings("unchecked")
        Map<String, Object> firstConns = (Map<String, Object>) first.connections();
        @SuppressWarnings("unchecked")
        Map<String, Object> secondConns = (Map<String, Object>) second.connections();
        @SuppressWarnings("unchecked")
        Map<String, Object> firstEntry = (Map<String, Object>) firstConns.get("Local HAPI");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondEntry = (Map<String, Object>) secondConns.get("Local HAPI");
        assertEquals(firstEntry.get("name"), secondEntry.get("name"));
        assertEquals(firstEntry.get("baseUrl"), secondEntry.get("baseUrl"));
    }

    @Test
    void noArgConstructorStillWorks() {
        WorkspaceService service = new WorkspaceService();
        service.newWorkspace("Untitled");
        service.captureAll();
        assertNotNull(service.getCurrent());
        assertEquals("Untitled", service.getCurrent().name());
    }
}
