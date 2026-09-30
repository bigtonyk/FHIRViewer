package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that configured servers survive a restart, and that nothing secret does.
 *
 * <p>Offline throughout: a {@code @TempDir} and the real {@link ServerDefinitionStore},
 * following the conventions in {@code PluginSettingsStoreTest}. The property that matters
 * is the one a user would call a bug - a server they had to type in once should not have
 * to be typed again - so each test writes through one manager and reads through
 * another, the way a restart does.</p>
 */
class ServerDefinitionPersistenceTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("Editing a server keeps it in the same place in the list")
    void replaceKeepsPosition() {
        // Editing by delete-then-add would reorder the list, and would briefly leave the
        // server unconfigured. A user with three servers would lose track of which is which.
        FhirServerManager manager = new FhirServerManager();
        manager.add(ServerDefinition.named("First", "https://one.example.org/fhir").build());
        ServerDefinition second = ServerDefinition.named("Second",
                "https://two.example.org/fhir").build();
        manager.add(second);
        manager.add(ServerDefinition.named("Third", "https://three.example.org/fhir").build());

        assertTrue(manager.replace(second,
                ServerDefinition.named("Second", "https://moved.example.org/fhir").build()));

        assertEquals(List.of("First", "Second", "Third"),
                manager.servers().stream().map(FhirServerConfiguration::name).toList());
        assertEquals("https://moved.example.org/fhir", manager.servers().get(1).baseUrl());
    }

    @Test
    @DisplayName("Editing the active server keeps it active")
    void replaceKeepsTheActiveServerActive() {
        // Otherwise the menu would report the old server's name while requests went to the
        // new URL - the worst kind of silent mismatch.
        FhirServerManager manager = new FhirServerManager();
        ServerDefinition active = ServerDefinition.named("Active",
                "https://old.example.org/fhir").build();
        manager.add(active);
        manager.setActive(active);

        ServerDefinition edited = ServerDefinition.named("Renamed",
                "https://new.example.org/fhir").build();
        manager.replace(active, edited);

        assertTrue(manager.active().isPresent(), "the active server must still be set");
        assertEquals("Renamed", manager.active().get().name());
        assertEquals("https://new.example.org/fhir", manager.active().get().baseUrl());
    }

    @Test
    @DisplayName("Replacing a server that is not configured changes nothing")
    void replaceIgnoresAnUnknownServer() {
        FhirServerManager manager = new FhirServerManager();
        manager.add(ServerDefinition.named("Real", "https://real.example.org/fhir").build());

        boolean replaced = manager.replace(
                ServerDefinition.named("Ghost", "https://ghost.example.org/fhir").build(),
                ServerDefinition.named("Other", "https://other.example.org/fhir").build());

        assertFalse(replaced, "an unknown server must not be replaced");
        assertEquals(1, manager.servers().size());
        assertEquals("Real", manager.servers().get(0).name());
    }

    @Test
    @DisplayName("An edited server survives a save and a load")
    void editedServerSurvivesARestart() throws IOException {
        Path file = directory.resolve("server-definitions.properties");
        FhirServerManager before = new FhirServerManager();
        ServerDefinition original = ServerDefinition.named("Before",
                "https://old.example.org/fhir").build();
        before.add(original);
        before.replace(original, ServerDefinition.named("After",
                "https://new.example.org/fhir").build());
        before.save(file);

        FhirServerManager after = new FhirServerManager();
        assertEquals(1, after.load(file));

        assertEquals("After", after.servers().get(0).name());
        assertEquals("https://new.example.org/fhir", after.servers().get(0).baseUrl());
    }

    @Test
    @DisplayName("A configured server survives a save and a load")
    void serverSurvivesARestart() throws IOException {
        Path file = directory.resolve("server-definitions.properties");

        FhirServerManager before = new FhirServerManager();
        before.add(ServerDefinition.named("Local HAPI", "http://localhost:8080/fhir")
                .pluginId(StandardFhirRestPlugin.PLUGIN_ID)
                .fhirVersion("R4")
                .timeoutMillis(1500)
                .build());
        before.save(file);

        FhirServerManager after = new FhirServerManager();
        int loaded = after.load(file);

        assertEquals(1, loaded, "the saved server should come back");
        assertEquals(1, after.servers().size());
        ServerDefinition restored = (ServerDefinition) after.servers().get(0);
        assertEquals("Local HAPI", restored.name());
        assertEquals("http://localhost:8080/fhir", restored.baseUrl());
        assertEquals(StandardFhirRestPlugin.PLUGIN_ID, restored.pluginId());
        assertEquals("R4", restored.fhirVersion());
        assertEquals(1500, restored.timeoutMillis(),
                "a configured timeout is part of the definition and must be restored");
    }

    @Test
    @DisplayName("The order servers were added in is preserved")
    void orderIsPreserved() throws IOException {
        Path file = directory.resolve("servers.properties");
        FhirServerManager before = new FhirServerManager();
        before.add(ServerDefinition.named("First", "http://one.example/fhir").build());
        before.add(ServerDefinition.named("Second", "http://two.example/fhir").build());
        before.add(ServerDefinition.named("Third", "http://three.example/fhir").build());
        before.save(file);

        FhirServerManager after = new FhirServerManager();
        after.load(file);

        assertEquals(List.of("First", "Second", "Third"),
                after.servers().stream().map(FhirServerConfiguration::name).toList());
    }

    @Test
    @DisplayName("The active server is restored by name")
    void activeServerIsRestored() throws IOException {
        Path file = directory.resolve("servers.properties");
        FhirServerManager before = new FhirServerManager();
        ServerDefinition second =
                ServerDefinition.named("Second", "http://two.example/fhir").build();
        before.add(ServerDefinition.named("First", "http://one.example/fhir").build());
        before.add(second);
        before.setActive(second);
        before.save(file);

        FhirServerManager after = new FhirServerManager();
        after.load(file);

        assertTrue(after.isActive(after.servers().get(1)),
                "the previously active server should be the active one again");
    }

    @Test
    @DisplayName("A saved active name that no longer exists is ignored")
    void unknownActiveNameIsIgnored() throws IOException {
        Path file = directory.resolve("servers.properties");
        FhirServerManager before = new FhirServerManager();
        before.add(ServerDefinition.named("Kept", "http://kept.example/fhir").build());
        before.save(file);
        // Point the file at a server that is not in the list, as if the user had removed
        // it by hand since the last save.
        Files.writeString(file, Files.readString(file) + "active=Deleted\n");

        FhirServerManager after = new FhirServerManager();
        after.load(file);

        assertEquals(1, after.servers().size(), "the surviving server still loads");
        assertTrue(after.active().isPresent(),
                "loading the first server makes it active, which is the documented behaviour");
        assertTrue(after.isActive(after.servers().get(0)));
    }

    @Test
    @DisplayName("Loading a file that does not exist adds nothing and does not throw")
    void missingFileIsNotAnError() {
        FhirServerManager manager = new FhirServerManager();

        assertEquals(0, manager.load(directory.resolve("never-written.properties")));
        assertTrue(manager.servers().isEmpty());
    }

    @Test
    @DisplayName("A definition already in this session is not duplicated by a load")
    void loadDoesNotDuplicate() throws IOException {
        Path file = directory.resolve("servers.properties");
        FhirServerManager manager = new FhirServerManager();
        manager.add(ServerDefinition.named("Twice", "http://twice.example/fhir").build());
        manager.save(file);

        assertEquals(0, manager.load(file), "the name is already configured");
        assertEquals(1, manager.servers().size(),
                "two entries with one name would make 'which server?' unanswerable");
    }

    @Test
    @DisplayName("An unreadable entry is skipped without losing the others")
    void malformedEntryIsSkipped() throws IOException {
        Path file = directory.resolve("servers.properties");
        // Block 2 has a base URL that is no longer a URL, which the builder refuses.
        Files.writeString(file, """
                server.1.name=Good
                server.1.baseUrl=http://good.example/fhir
                server.2.name=Broken
                server.2.baseUrl=not-a-url
                server.3.name=Also good
                server.3.baseUrl=http://also.example/fhir
                """);

        FhirServerManager manager = new FhirServerManager();

        assertEquals(2, manager.load(file));
        assertEquals(List.of("Good", "Also good"),
                manager.servers().stream().map(FhirServerConfiguration::name).toList());
    }

    @Test
    @DisplayName("The definitions file never contains a password")
    void definitionsFileHoldsNoSecret() throws IOException, SecretBoxException {
        Path file = directory.resolve("server-definitions.properties");
        PluginSettingsStore pluginSettings =
                new PluginSettingsStore(directory.resolve("plugin-settings.properties"));
        // A password saved for this server, encrypted, in the file that owns it.
        pluginSettings.save(new PluginSettings(StandardFhirRestPlugin.PLUGIN_ID,
                "http://localhost:8080/fhir", "alice", "hunter2-secret"), "passphrase");

        FhirServerManager manager = new FhirServerManager();
        manager.add(ServerDefinition.named("Local HAPI", "http://localhost:8080/fhir").build());
        manager.save(file);

        String written = Files.readString(file);
        assertFalse(written.contains("hunter2-secret"),
                "the password must not reach the definitions file in any form");
        // Checked as parsed keys rather than as raw text, because the file's own header
        // comment legitimately says where passwords do live. What must not exist is a
        // setting that holds one.
        assertTrue(new ServerDefinitionStore(file).read().stream()
                        .noneMatch(server -> server.name().contains("hunter2")),
                "no stored field should carry the secret");
        // The non-secret fields are the ones that do belong there, and a user has to be
        // able to read and edit them. Asserted by reading back rather than by matching
        // raw text, because Properties escapes the colons in a URL when it writes.
        assertEquals("http://localhost:8080/fhir",
                new ServerDefinitionStore(file).read().get(0).baseUrl());
    }

    @Test
    @DisplayName("A saved password stays encrypted at rest")
    void savedPasswordStaysEncrypted() throws Exception {
        Path settingsFile = directory.resolve("plugin-settings.properties");
        PluginSettingsStore store = new PluginSettingsStore(settingsFile);
        store.save(new PluginSettings(StandardFhirRestPlugin.PLUGIN_ID,
                "http://localhost:8080/fhir", "alice", "hunter2-secret"), "passphrase");

        String raw = Files.readString(settingsFile);
        assertFalse(raw.contains("hunter2-secret"), "the password must not be on disk in the clear");
        assertTrue(raw.contains("v1$"), "it should be there in the SecretBox envelope");

        // Still readable with the right passphrase, which is what makes the check above a
        // real restriction rather than data loss.
        PluginSettings unlocked = store.unlock(StandardFhirRestPlugin.PLUGIN_ID, "passphrase");
        assertNotNull(unlocked);
        assertEquals("hunter2-secret", unlocked.password());
    }

    @Test
    @DisplayName("A wrong passphrase yields an error, never a wrong password")
    void wrongPassphraseIsRefused() throws IOException, SecretBoxException {
        Path settingsFile = directory.resolve("plugin-settings.properties");
        PluginSettingsStore store = new PluginSettingsStore(settingsFile);
        store.save(new PluginSettings(StandardFhirRestPlugin.PLUGIN_ID,
                "http://localhost:8080/fhir", "alice", "hunter2-secret"), "passphrase");

        assertThrows(SecretBoxException.class,
                () -> store.unlock(StandardFhirRestPlugin.PLUGIN_ID, "the wrong passphrase"));
    }

    @Test
    @DisplayName("A file with no active key loads without one")
    void missingActiveKeyIsTolerated() throws IOException {
        Path file = directory.resolve("servers.properties");
        Files.writeString(file, """
                server.1.name=Only
                server.1.baseUrl=http://only.example/fhir
                """);

        FhirServerManager manager = new FhirServerManager();
        manager.load(file);

        assertEquals(1, manager.servers().size());
        assertNull(new ServerDefinitionStore(file).readActiveName(),
                "nothing was saved as active, so nothing is claimed to be");
    }
}
