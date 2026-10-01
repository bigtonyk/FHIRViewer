package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 29 — does the REST work break anything that worked before it?
 *
 * <p>Five checks, matching the phase's list: plugins still load, discovery still finds
 * them, existing functionality still works, configuration files written earlier still
 * load, and the interfaces a plugin implements have not gained abstract members.</p>
 *
 * <p>Most of the weight is in {@link LegacyPlugin}, which implements only what the
 * interface required before any of this work. It is a compile-time proof: if a method added
 * since loses its default, that file stops compiling and the build fails here rather than in
 * somebody's plugin.</p>
 */
class BackwardCompatibilityTest {

    /** A configuration implementing only the members it had before this work. */
    private static FhirServerConfiguration server(String baseUrl) {
        return new FhirServerConfiguration() {
            @Override
            public String name() {
                return "Old";
            }

            @Override
            public String baseUrl() {
                return baseUrl;
            }

            @Override
            public String fhirVersion() {
                return "R4";
            }

            @Override
            public String pluginId() {
                return LegacyPlugin.PLUGIN_ID;
            }

            @Override
            public int timeoutMillis() {
                return 30_000;
            }

            @Override
            public Map<String, String> extraHeaders() {
                return Map.of();
            }
        };
    }

    private static ServerSession session() {
        return new ServerSession(
                ServerDefinition.named("Old", "https://old.example.com/fhir")
                        .pluginId(LegacyPlugin.PLUGIN_ID)
                        .build(),
                AnonymousServerAuthentication.INSTANCE);
    }

    @Test
    @DisplayName("A plugin written before the REST work still loads and is discovered")
    void legacyPluginIsDiscovered() {
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(new LegacyPlugin());

        FhirServerPlugin found =
                registry.pluginFor(server("https://old.example.com/fhir"));
        assertNotNull(found,
                "a plugin implementing only the pre-REST interface was not discovered; "
                        + "something added since is no longer defaulted");
        assertEquals(LegacyPlugin.PLUGIN_ID, found.id());
assertEquals("Legacy Plugin", found.displayName());
    }

    @Test
    @DisplayName("Existing functionality still works on a pre-REST plugin")
    void legacyPluginStillReadsAndSearches() throws ServerOperationException {
        FhirServerPlugin plugin = new LegacyPlugin();
        ServerSession session = session();

        IBaseResource read = plugin.read(session, "Patient", "legacy-1");
        assertNotNull(read, "read() stopped working on an existing plugin");
        assertEquals("Patient", read.fhirType());

        SearchResultPage page =
                plugin.search(session, new SearchRequest("Patient", List.of(), 20));
        assertEquals(1, page.resources().size(),
                "search() stopped working on an existing plugin");
    }

    @Test
    @DisplayName("Operations added after a plugin was written fail cleanly, not at link time")
    void operationsAddedLaterFailCleanly() throws ServerOperationException {
        // The shape that matters in a default: a reported exception, not an
        // AbstractMethodError, so the UI has something it can show the user.
        FhirServerPlugin plugin = new LegacyPlugin();
        ServerSession session = session();
        IBaseResource resource = plugin.read(session, "Patient", "legacy-1");

        assertThrows(ServerOperationException.class, () -> plugin.create(session, resource),
                "create() on a plugin that predates it must raise a reported failure");
        assertThrows(ServerOperationException.class,
                () -> plugin.delete(session,
                        ServerOrigin.of(LegacyPlugin.PLUGIN_ID, "https://old.example.com/fhir",
                                "Patient", "legacy-1", null)),
                "delete() on a plugin that predates it must raise a reported failure");

        // And the defaults that mean "nothing to show" rather than "broken".
        assertTrue(plugin.availableOperations().isEmpty(),
                "a plugin that declares no operations should offer none, not throw");
        assertFalse(plugin.supportsWrite(),
                "a plugin that predates writes cannot support them, and must say so");
    }

    @Test
    @DisplayName("A credential file keyed by plugin id still loads after per-server keys")
    void pluginKeyedCredentialFileStillLoads(@TempDir Path dir) throws Exception {
        // Credentials used to be keyed by plugin id. That fallback exists in the store but
        // had no test, so it could have rotted unnoticed. Written by hand rather than by
        // the store, so it is the old format and not whatever the writer produces today.
        Path file = dir.resolve("plugin-settings.properties");
        Files.writeString(file, String.join("\n",
                LegacyPlugin.PLUGIN_ID + ".baseUrl=https://old.example.com/fhir",
                LegacyPlugin.PLUGIN_ID + ".userName=old-user",
                ""), StandardCharsets.UTF_8);

        PluginSettingsStore store = new PluginSettingsStore(file);
        PluginSettings found = store.readForServer(server("https://old.example.com/fhir"));

        assertNotNull(found,
                "a credential file written before per-server keys no longer loads, so every "
                        + "existing user silently loses their password");
        assertEquals("old-user", found.userName());
    }

    @Test
    @DisplayName("A plugin-keyed credential is not handed to a different server")
    void pluginKeyedCredentialIsNotGivenElsewhere(@TempDir Path dir) throws Exception {
        // The other half of that fallback. Relaxing it to "any plugin-keyed entry will do"
        // would send one server's password to a different host.
        Path file = dir.resolve("plugin-settings.properties");
        Files.writeString(file, String.join("\n",
                LegacyPlugin.PLUGIN_ID + ".baseUrl=https://old.example.com/fhir",
                LegacyPlugin.PLUGIN_ID + ".userName=old-user",
                ""), StandardCharsets.UTF_8);

        PluginSettingsStore store = new PluginSettingsStore(file);
        assertNull(store.readForServer(server("https://elsewhere.example.com/fhir")),
                "a credential saved for one host was offered to another; the base URL check "
                        + "is the only thing stopping a password going to the wrong server");
    }

    @Test
    @DisplayName("A configuration written before credentialKey existed still resolves one")
    void configurationWithoutCredentialKeyStillResolves() {
        // credentialKey() was added during this work. The fixture implements only the older
        // members, so the inherited default has to be doing the work.
        FhirServerConfiguration first = server("https://a.example.com/fhir");
        FhirServerConfiguration second = server("https://b.example.com/fhir");

        assertEquals(first.baseUrl(), first.credentialKey(),
                "the inherited default should key a credential by base URL");
        assertFalse(first.credentialKey().equals(second.credentialKey()),
                "two servers must not share a credential slot, or saving one signs the other "
                        + "in as nobody");
    }

    @Test
    @DisplayName("A missing configuration file is still not an error")
    void missingConfigurationFileIsNotAnError(@TempDir Path dir) throws IOException {
        // Trivially true, and true before this work. Listed because "existing configuration
        // still works" includes the case where there is none.
        PluginSettingsStore store =
                new PluginSettingsStore(dir.resolve("never-written.properties"));
        assertNull(store.readForServer(server("https://x.example.com/fhir")),
                "a first run with no settings file must not fail");
    }
}
