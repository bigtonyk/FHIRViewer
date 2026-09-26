package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that server plugins are loaded from configuration, not just from the service
 * file: the bundled config enables the Firely plugin, the deny list is honoured, and
 * a broken entry degrades to a warning instead of stopping the application.
 */
public class PluginLoaderTest {

    private static List<String> idsOf(FhirServerPluginRegistry registry) {
        return registry.plugins().stream().map(FhirServerPlugin::id).toList();
    }

    @Test
    @DisplayName("The bundled configuration enables the Firely plugin")
    void bundledConfigEnablesFirely() {
        PluginConfig config = PluginConfig.defaults();
        assertTrue(config.enabledClasses().contains(FirelyPlugin.class.getName()),
                "the bundled config should enable Firely, found: " + config.enabledClasses());
    }

    @Test
    @DisplayName("Loading with the bundled configuration registers standard, Smile and Firely")
    void bundledConfigurationLoadsEveryPlugin() {
        List<String> ids = idsOf(PluginLoader.load(PluginConfig.defaults()));
        assertTrue(ids.contains(StandardFhirRestPlugin.PLUGIN_ID), "ids were " + ids);
        assertTrue(ids.contains(SmileCdrPlugin.PLUGIN_ID), "ids were " + ids);
        assertTrue(ids.contains(FirelyPlugin.PLUGIN_ID), "ids were " + ids);
        assertEquals(3, ids.size(), "each plugin should be registered exactly once: " + ids);
    }

    @Test
    @DisplayName("Firely is loaded even though it is not in the service file")
    void firelyIsNotInTheServiceFile() {
        List<String> discovered = PluginLoader.discoverableIds();
        assertFalse(discovered.contains(FirelyPlugin.class.getName()),
                "Firely should come from the config, not service discovery; found: " + discovered);
    }

    @Test
    @DisplayName("The deny list removes a plugin however it was loaded")
    void disabledPluginsAreDropped() {
        FhirServerPluginRegistry registry = PluginLoader.load(PluginConfig.of(
                Set.of(FirelyPlugin.class.getName()),
                Set.of(SmileCdrPlugin.class.getName(), FirelyPlugin.class.getName())));
        List<String> ids = idsOf(registry);
        assertFalse(ids.contains(SmileCdrPlugin.PLUGIN_ID), "Smile was disabled: " + ids);
        assertFalse(ids.contains(FirelyPlugin.PLUGIN_ID), "Firely was disabled: " + ids);
        assertTrue(ids.contains(StandardFhirRestPlugin.PLUGIN_ID), "standard should survive: " + ids);
    }

    @Test
    @DisplayName("A class that is enabled and also disabled is not loaded")
    void enabledAndDisabledWins() {
        PluginConfig config = PluginConfig.of(
                Set.of(FirelyPlugin.class.getName()),
                Set.of(FirelyPlugin.class.getName()));
        assertFalse(config.allows(FirelyPlugin.class.getName()));
        assertTrue(PluginLoader.load(config).plugin(FirelyPlugin.PLUGIN_ID) == null,
                "a denied plugin must not be registered");
    }

    @Test
    @DisplayName("A plugin named in the config is instantiated by class name")
    void enablesByClassName() {
        FhirServerPluginRegistry registry = PluginLoader.load(PluginConfig.of(
                Set.of(FirelyPlugin.class.getName()), Set.of()));
        FhirServerPlugin firely = registry.plugin(FirelyPlugin.PLUGIN_ID);
        assertNotNull(firely, "the config-named plugin should be registered");
        assertEquals("Firely Server", firely.displayName());
    }

    @Test
    @DisplayName("A bad config entry is skipped without losing the good plugins")
    void badEntriesAreSkipped() {
        FhirServerPluginRegistry registry = PluginLoader.load(PluginConfig.of(
                Set.of("com.example.no.such.Plugin",
                        "java.lang.String",
                        FirelyPlugin.class.getName()),
                Set.of()));
        assertNotNull(registry.plugin(FirelyPlugin.PLUGIN_ID),
                "a broken entry must not stop the working plugin loading");
        assertNotNull(registry.plugin(StandardFhirRestPlugin.PLUGIN_ID));
    }

    @Test
    @DisplayName("The properties file is parsed, with comments and whitespace ignored")
    void parsesPropertiesFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("plugins.properties");
        Files.writeString(file, """
                # a comment
                fhirviewer.plugins.enabled = com.example.fhirviewer.server.FirelyPlugin, \
                    com.example.fhirviewer.server.SmileCdrPlugin ,
                fhirviewer.plugins.disabled= com.example.fhirviewer.server.SmileCdrPlugin
                unknown.key=ignored
                """, StandardCharsets.UTF_8);

        PluginConfig config = PluginConfig.load(file);
        assertEquals(2, config.enabledClasses().size(), "entries were " + config.enabledClasses());
        assertTrue(config.enabledClasses().contains(FirelyPlugin.class.getName()));
        assertTrue(config.disabledClasses().contains(SmileCdrPlugin.class.getName()));
        assertFalse(config.allows(SmileCdrPlugin.class.getName()));
        assertTrue(config.allows(FirelyPlugin.class.getName()));
    }

    @Test
    @DisplayName("An empty configuration loads no named plugins and never throws")
    void emptyConfigurationIsSafe() {
        PluginConfig config = PluginConfig.of(Set.of(), Set.of());
        assertTrue(config.enabledClasses().isEmpty());
        assertNotNull(PluginLoader.load(config));
    }

    @Test
    @DisplayName("A missing or unreadable config falls back instead of failing")
    void resolveNeverThrows(@TempDir Path dir) {
        String previous = System.getProperty(PluginConfig.CONFIG_PROPERTY);
        try {
            System.setProperty(PluginConfig.CONFIG_PROPERTY,
                    dir.resolve("does-not-exist.properties").toString());
            assertNotNull(PluginConfig.resolve(), "a bad override must still resolve");
        } finally {
            if (previous == null) {
                System.clearProperty(PluginConfig.CONFIG_PROPERTY);
            } else {
                System.setProperty(PluginConfig.CONFIG_PROPERTY, previous);
            }
        }
    }
}
