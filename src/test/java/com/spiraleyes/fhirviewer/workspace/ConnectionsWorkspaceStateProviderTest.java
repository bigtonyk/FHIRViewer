package com.spiraleyes.fhirviewer.workspace;

import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.server.ServerDefinition;
import com.spiraleyes.fhirviewer.server.StandardFhirRestPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionsWorkspaceStateProviderTest {

    @Test
    void providerIdIsConnections() {
        assertEquals("connections", new ConnectionsWorkspaceStateProvider().providerId());
    }

    @Test
    void captureWithNullManagerReturnsEmptyMap() {
        ConnectionsWorkspaceStateProvider p = new ConnectionsWorkspaceStateProvider(null, null);
        Object snap = p.capture();
        assertTrue(snap instanceof Map);
        assertTrue(((Map<?, ?>) snap).isEmpty());
    }

    @Test
    void captureExcludesSecretsAndIncludesSafeFields() {
        FhirServerManager manager = new FhirServerManager();
        ServerDefinition def = ServerDefinition.named("Test Server", "https://example.org/fhir")
                .pluginId(StandardFhirRestPlugin.PLUGIN_ID)
                .timeoutMillis(5000)
                .build();
        manager.add(def);

        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(new StandardFhirRestPlugin());

        ConnectionsWorkspaceStateProvider p = new ConnectionsWorkspaceStateProvider(manager, registry);
        Object snap = p.capture();
        assertTrue(snap instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) snap;
        assertEquals(1, out.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) out.get("Test Server");
        assertNotNull(entry);
        assertEquals("Test Server", entry.get("name"));
        assertEquals("https://example.org/fhir", entry.get("baseUrl"));
        assertEquals("R4", entry.get("fhirVersion"));
        assertEquals(StandardFhirRestPlugin.PLUGIN_ID, entry.get("pluginId"));
        assertEquals(5000, entry.get("timeoutMillis"));
        // No password/token/secret fields present
        assertFalse(entry.containsKey("password"));
        assertFalse(entry.containsKey("token"));
        assertFalse(entry.containsKey("secret"));
    }

    @Test
    void captureWithMissingPluginStillReturnsServerData() {
        FhirServerManager manager = new FhirServerManager();
        ServerDefinition def = ServerDefinition.named("Orphaned", "https://gone.example.org/fhir")
                .pluginId("missing-plugin")
                .build();
        manager.add(def);

        ConnectionsWorkspaceStateProvider p = new ConnectionsWorkspaceStateProvider(manager, new FhirServerPluginRegistry());
        Object snap = p.capture();
        assertTrue(snap instanceof Map);
        assertFalse(((Map<?, ?>) snap).isEmpty());
    }

    @Test
    void restoreWithMissingPluginWarnsButDoesNotConnect() {
        FhirServerManager manager = new FhirServerManager();
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry(); // empty: no plugins registered

        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("name", "Ghost Server");
        entry.put("baseUrl", "https://ghost.example.org/fhir");
        entry.put("pluginId", "missing-plugin");

        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("Ghost Server", entry);

        ConnectionsWorkspaceStateProvider p = new ConnectionsWorkspaceStateProvider(manager, registry);
        // Should not throw, should not auto-connect (manager.servers() unchanged)
        p.restore(snap);
        assertTrue(manager.servers().isEmpty());
    }

    @Test
    void restoreWithNullManagerIsNoOp() {
        ConnectionsWorkspaceStateProvider p = new ConnectionsWorkspaceStateProvider(null, null);
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        p.restore(snap); // should not throw
    }

    @Test
    void restoreWithNonMapIsNoOp() {
        FhirServerManager manager = new FhirServerManager();
        ConnectionsWorkspaceStateProvider p = new ConnectionsWorkspaceStateProvider(manager, null);
        p.restore("not a map"); // should not throw
        assertTrue(manager.servers().isEmpty());
    }
}
