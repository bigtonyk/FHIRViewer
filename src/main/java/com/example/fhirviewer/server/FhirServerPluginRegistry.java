package com.example.fhirviewer.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Discovers and holds the available {@link FhirServerPlugin} implementations.
 *
 * <p>Plugins register in two ways: implementations on the class path (or a future plugin
 * directory) are found through {@link ServiceLoader}, and tests or bootstrapping code
 * can register instances directly. The registry itself has no JavaFX in it, so it stays
 * usable from any thread.</p>
 */
public final class FhirServerPluginRegistry {

    private final Map<String, FhirServerPlugin> plugins = new LinkedHashMap<>();

    /** Discovers plugins through {@link ServiceLoader} and registers them. */
    public static FhirServerPluginRegistry discover() {
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        for (FhirServerPlugin plugin : ServiceLoader.load(FhirServerPlugin.class)) {
            registry.register(plugin);
        }
        return registry;
    }

    /**
     * Registers a plugin, replacing any plugin with the same id. Later registrations win
     * so a vendor plugin on the class path can take precedence over a built-in one.
     */
    public void register(FhirServerPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        plugins.put(plugin.id(), plugin);
    }

    /** Every registered plugin, in registration order. */
    public List<FhirServerPlugin> plugins() {
        return new ArrayList<>(plugins.values());
    }

    /** The plugin with the given id, or {@code null} when none is registered. */
    public FhirServerPlugin plugin(String id) {
        return id == null ? null : plugins.get(id);
    }

    /**
     * The first plugin that {@link FhirServerPlugin#supports supports} the configuration,
     * preferring an exact id match when the configuration names one. Returns
     * {@code null} when nothing supports it.
     */
    public FhirServerPlugin pluginFor(FhirServerConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        FhirServerPlugin named = plugin(configuration.pluginId());
        if (named != null && named.supports(configuration)) {
            return named;
        }
        for (FhirServerPlugin plugin : plugins.values()) {
            if (plugin.supports(configuration)) {
                return plugin;
            }
        }
        return null;
    }
}
