package com.example.fhirviewer.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provisions servers at start-up for plugins marked "Load on start".
 *
 * <p>The tick box in the plugin dialog was write-only for a long time: the flag was
 * saved and shown back correctly, but nothing ever read it, so a built-in plugin
 * never gained its server. This runs after the saved server list loads and adds one
 * server per flagged plugin that does not already have one, using the URL saved
 * alongside the flag. It never duplicates: a plugin with any server already
 * configured is left alone, because several servers can share one plugin and the
 * second one must not suppress the first's provisioning check.</p>
 */
public final class StartupServers {

    private static final Logger log = LoggerFactory.getLogger(StartupServers.class);

    private StartupServers() {
    }

    /**
     * Adds a server for every load-on-start plugin missing one.
     *
     * @param manager the configured servers to add to
     * @param plugins the loaded plugins, in registration order
     * @param settings where the flags and saved URLs live
     * @return how many servers were added
     */
    public static int provision(FhirServerManager manager, List<FhirServerPlugin> plugins,
            PluginSettingsStore settings) {
        Objects.requireNonNull(manager, "manager");
        if (plugins == null || plugins.isEmpty() || settings == null) {
            return 0;
        }
        int added = 0;
        for (FhirServerPlugin plugin : plugins) {
            if (plugin == null) {
                continue;
            }
            boolean flagged;
            try {
                flagged = settings.loadsOnStart(plugin.id());
            } catch (IOException e) {
                log.info("could not read the load-on-start flag for {}: {}",
                        plugin.id(), e.toString());
                continue;
            }
            if (!flagged) {
                continue;
            }
            if (hasServerFor(manager, plugin)) {
                continue;
            }
            String url = savedUrlFor(settings, plugin.id());
            if (url == null) {
                continue;
            }
            String name = plugin.displayName() == null || plugin.displayName().isBlank()
                    ? plugin.id() : plugin.displayName().trim();
            String unique = name;
            int suffix = 2;
            while (nameInUse(manager, unique)) {
                unique = name + " " + suffix++;
            }
            ServerDefinition.Builder builder = ServerDefinition.named(unique, url)
                    .pluginId(plugin.id());
            String version = plugin.supportedFhirVersions().stream().findFirst().orElse(null);
            if (version != null && !version.isBlank()) {
                builder.fhirVersion(version.trim());
            }
            try {
                if (manager.add(builder.build())) {
                    added++;
                    log.info("added the {} server from its load-on-start settings", plugin.id());
                }
            } catch (IllegalArgumentException | NullPointerException e) {
                log.info("could not add the {} server from its saved settings: {}",
                        plugin.id(), e.toString());
            }
        }
        return added;
    }

    private static boolean hasServerFor(FhirServerManager manager, FhirServerPlugin plugin) {
        for (FhirServerConfiguration server : manager.servers()) {
            if (server == null) {
                continue;
            }
            if (plugin.id().equals(server.pluginId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean nameInUse(FhirServerManager manager, String name) {
        for (FhirServerConfiguration server : manager.servers()) {
            if (server != null && name.equals(server.name())) {
                return true;
            }
        }
        return false;
    }

    private static String savedUrlFor(PluginSettingsStore settings, String pluginId) {
        try {
            List<String> candidates = new ArrayList<>();
            PluginSettings direct = settings.read(pluginId);
            if (direct != null && direct.baseUrl() != null && !direct.baseUrl().isBlank()) {
                candidates.add(direct.baseUrl().trim());
            }
            for (PluginSettings entry : settings.readAll().values()) {
                if (entry != null && pluginId.equals(entry.pluginId())
                        && entry.baseUrl() != null && !entry.baseUrl().isBlank()) {
                    candidates.add(entry.baseUrl().trim());
                }
            }
            return candidates.isEmpty() ? null : candidates.get(0);
        } catch (IOException e) {
            log.info("could not read the saved URL for {}: {}", pluginId, e.toString());
            return null;
        }
    }
}
