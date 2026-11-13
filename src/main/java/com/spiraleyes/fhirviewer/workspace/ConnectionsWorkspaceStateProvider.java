package com.spiraleyes.fhirviewer.workspace;

import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.server.ServerDefinition;
import com.spiraleyes.fhirviewer.server.StandardFhirRestPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Capture/restore boundary for connection state.
 *
 * <p>Serialises profile id/alias, base URL, server type, plugin id, safe options and
 * credential reference only — never secrets or tokens. Restore resolves offline and warns
 * on missing server/plugin; it never auto-connects or writes.</p>
 */
public class ConnectionsWorkspaceStateProvider implements WorkspaceStateProvider {

    private static final Logger LOG =
            LoggerFactory.getLogger(ConnectionsWorkspaceStateProvider.class);

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
        int added = 0;
        for (Map.Entry<String, Object> e : entries.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            String name = text(entry.get("name"), e.getKey());
            String baseUrl = text(entry.get("baseUrl"), "");
            String pluginId = text(entry.get("pluginId"), "");
            if (name.isBlank() || baseUrl.isBlank()) {
                LOG.warn("Workspace restore: skipping a server entry with no name or base URL");
                continue;
            }
            if (manager.servers().stream().anyMatch(server -> name.equals(server.name()))) {
                // Already configured here, possibly edited since the archive was written:
                // restoring must not overwrite what the user has now.
                continue;
            }
            if (registry != null && pluginId != null && !pluginId.isBlank()
                    && registry.plugin(pluginId) == null) {
                // A server whose plugin is absent cannot answer a single request, so adding
                // it would only produce an entry that fails when used.
                LOG.warn("Workspace restore: server '" + name
                        + "' references missing plugin '" + pluginId + "'");
                continue;
            }
            try {
                manager.add(ServerDefinition.named(name, baseUrl)
                        // The id is the credential key, so keeping the archived one is what
                        // lets an already-saved password still resolve for this server.
                        .id(text(entry.get("id"), ""))
                        .fhirVersion(text(entry.get("fhirVersion"), "R4"))
                        .pluginId(pluginId.isBlank()
                                ? StandardFhirRestPlugin.PLUGIN_ID : pluginId)
                        .timeoutMillis(timeoutOf(entry.get("timeoutMillis")))
                        .build());
                added++;
            } catch (IllegalArgumentException invalid) {
                LOG.warn("Workspace restore: server '" + name + "' could not be restored: "
                        + invalid.getMessage());
            }
        }
        if (added > 0) {
            // Added to the list only: no connection is opened and nothing is saved.
            LOG.info("Workspace restore: added " + added + " configured server(s)");
        }
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private static int timeoutOf(Object value) {
        if (value instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        return 0;
    }
}

