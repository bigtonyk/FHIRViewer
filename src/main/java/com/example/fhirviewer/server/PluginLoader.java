package com.example.fhirviewer.server;

import java.util.List;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a {@link FhirServerPluginRegistry} from a {@link PluginConfig}.
 *
 * <p>Two sources feed the registry, and both are filtered by the configuration's
 * deny list:</p>
 * <ol>
 *   <li>Service-file discovery via {@link ServiceLoader}, which picks up every
 *       implementation on the class path, including third-party ones.</li>
 *   <li>Classes named explicitly in {@link PluginConfig#enabledClasses()}, which are
 *       instantiated by name so a plugin can be loaded purely from configuration
 *       without ever being listed in a service file.</li>
 * </ol>
 *
 * <p>A plugin named in the config that cannot be loaded is logged and skipped rather
 * than thrown: one bad entry in a configuration file must not stop the viewer from
 * starting. Genuinely broken built-in plugins are still reported loudly, because a
 * class listed in the service file failing to instantiate is a packaging bug.</p>
 */
public final class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private PluginLoader() {
    }

    /** Loads the plugins described by the application's resolved configuration. */
    public static FhirServerPluginRegistry load() {
        return load(PluginConfig.resolve());
    }

    /** Loads the plugins described by a given configuration. */
    public static FhirServerPluginRegistry load(PluginConfig config) {
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        if (config == null) {
            config = PluginConfig.defaults();
        }

        for (FhirServerPlugin plugin : ServiceLoader.load(FhirServerPlugin.class)) {
            register(registry, config, plugin);
        }

        for (String className : config.enabledClasses()) {
            if (!config.allows(className)) {
                log.info("plugin {} is enabled but also disabled; skipping", className);
                continue;
            }
            FhirServerPlugin plugin = instantiate(className);
            if (plugin != null) {
                register(registry, config, plugin);
            }
        }

        log.info("loaded {} server plugin(s) from {}", registry.plugins().size(), config.origin());
        for (FhirServerPlugin plugin : registry.plugins()) {
            log.info("  server plugin: {} ({})", plugin.id(), plugin.displayName());
        }
        return registry;
    }

    private static void register(FhirServerPluginRegistry registry, PluginConfig config,
            FhirServerPlugin plugin) {
        if (plugin == null) {
            return;
        }
        if (!config.allows(plugin.getClass().getName())) {
            log.info("server plugin {} is disabled by configuration", plugin.getClass().getName());
            return;
        }
        registry.register(plugin);
    }

    /**
     * Instantiates a plugin class by name, returning {@code null} when it cannot be
     * created. A class that is not on the class path, has no no-argument constructor,
     * or throws while being constructed is a configuration mistake, not a fatal error.
     */
    private static FhirServerPlugin instantiate(String className) {
        try {
            Class<?> type = Class.forName(className);
            if (!FhirServerPlugin.class.isAssignableFrom(type)) {
                log.warn("{} is configured as a server plugin but does not implement {}",
                        className, FhirServerPlugin.class.getName());
                return null;
            }
            Object instance = type.getDeclaredConstructor().newInstance();
            log.info("loaded server plugin {} from configuration", className);
            return (FhirServerPlugin) instance;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("could not load the configured server plugin {}; continuing without it",
                    className, e);
            return null;
        }
    }

    /**
     * The plugin ids a configuration would produce, without instantiating anything
     * that has to be constructed to answer. Useful for diagnostics and tests.
     */
    public static List<String> discoverableIds() {
        return ServiceLoader.load(FhirServerPlugin.class).stream()
                .map(ServiceLoader.Provider::type)
                .map(Class::getName)
                .toList();
    }
}
