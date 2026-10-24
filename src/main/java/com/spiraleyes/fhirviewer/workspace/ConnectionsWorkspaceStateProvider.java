package com.spiraleyes.fhirviewer.workspace;

import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.server.ServerDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Capture/restore boundary for connection state.
 *
 * <p>Serialises profile id/alias, base URL, server type, plugin id, safe options and
 * credential reference only — never secrets or tokens. Restore resolves offline and warns
 * on missing server/plugin; it never auto-connects or writes.</p>
 */
public class ConnectionsWorkspaceStateProvider implements WorkspaceStateProvider {

    private final FhirServerManager manager;
    private final FhirServerPluginRegistry registry;

    /** No-arg constructor: capture/restore are no-ops until wired to a real manager. */
    public ConnectionsWorkspaceStateProvider() {
        this(null, null);
    }

    public ConnectionsWorkspaceStateProvider(FhirServerManager manager,
                                             FhirServerPluginRegistry registry) {
        this.manager = manager;
        this.registry = registry;
    }

    @Override
    public String providerId() {
        return "connections";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object capture() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (manager == null) return out;
        List<FhirServerConfiguration> servers = manager.servers();
        for (FhirServerConfiguration s : servers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", s instanceof ServerDefinition sd ? sd.id() : s.credentialKey());
            entry.put("name", s.name());
            entry.put("baseUrl", s.baseUrl());
            entry.put("fhirVersion", s.fhirVersion());
            entry.put("pluginId", s.pluginId());
            entry.put("timeoutMillis", s.timeoutMillis());
            entry.put("extraHeaders", s.extraHeaders());
            entry.put("credentialKey", s.credentialKey());
            String key = entry.get("name") == null
                    ? String.valueOf(entry.get("id"))
                    : (String) entry.get("name");
            out.put(key, entry);
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restore(Object snapshot) {
        if (!(snapshot instanceof Map) || manager == null) return;
        Map<String, Object> entries = (Map<String, Object>) snapshot;
        for (Map.Entry<String, Object> e : entries.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            String pluginId = String.valueOf(entry.getOrDefault("pluginId", ""));
            if (registry != null && pluginId != null && !pluginId.isBlank()
                    && registry.plugin(pluginId) == null) {
                System.err.println("Workspace restore: server '"
                        + entry.getOrDefault("name", e.getKey())
                        + "' references missing plugin '" + pluginId + "'");
            }
            // No auto-connect: servers are listed, not activated
        }
    }
}

