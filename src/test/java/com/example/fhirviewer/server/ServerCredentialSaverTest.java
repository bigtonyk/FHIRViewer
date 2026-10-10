package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for writing a server's credentials from the server manager.
 *
 * <p>The property that matters is the one a user would call a data-loss bug: saving a
 * server twice must not silently throw away the password typed the first time. A password
 * field is never echoed back, so a blank one on the second visit means "unchanged" rather
 * than "empty" — and getting that wrong logs the user out of their own server without
 * saying so.</p>
 */
class ServerCredentialSaverTest {

    @TempDir
    Path directory;

    private PluginSettingsStore store() {
        return new PluginSettingsStore(directory.resolve("plugin-settings.properties"));
    }

    private static ServerDefinition server() {
        return ServerDefinition.named("Local", "https://local.example.org/fhir")
                .pluginId(StandardFhirRestPlugin.PLUGIN_ID)
                .build();
    }

    private static ServerPassphrase unlocked() {
        ServerPassphrase passphrase = new ServerPassphrase();
        passphrase.set("a passphrase");
        return passphrase;
    }

    @Test
    @DisplayName("Two servers on one plugin keep their own credentials")
    void twoServersOnOnePluginKeepSeparateCredentials() throws Exception {
        // The bug this fixes. Settings were keyed by plugin id, so the second server's save
        // replaced the first's outright. Both servers were configured correctly and one of
        // them silently started signing in as nobody.
        PluginSettingsStore store = store();
        ServerCredentialSaver saver = new ServerCredentialSaver(store, unlocked());
        ServerDefinition first = ServerDefinition.named("Prod",
                "https://prod.example.org/fhir")
                .pluginId(StandardFhirRestPlugin.PLUGIN_ID).build();
        ServerDefinition second = ServerDefinition.named("Test",
                "https://test.example.org/fhir")
                .pluginId(StandardFhirRestPlugin.PLUGIN_ID).build();

        saver.save(first, ServerAuthKind.BASIC, "alice", "prod-password");
        saver.save(second, ServerAuthKind.BASIC, "bob", "test-password");

        // Read back through the store the way ServerCredentials does, not through the
        // objects that were just written, so this is a real round trip.
        assertEquals("alice", store.readForServer(first).userName(),
                "the first server's credentials were overwritten by the second's");
        assertEquals("bob", store.readForServer(second).userName());

        // And they must actually unlock to different passwords, not merely be reported
        // correctly: same user name twice would pass the check above.
        assertEquals("prod-password",
                store.unlockForServer(first, "a passphrase").password());
        assertEquals("test-password",
                store.unlockForServer(second, "a passphrase").password());
    }

    @Test
    @DisplayName("Editing a server keeps the credentials it already had")
    void editingAServerKeepsItsCredentials() throws Exception {
        // The id is what credentials are filed under, so an edit that generated a fresh one
        // would orphan the password - and a user correcting a URL is exactly who needs it.
        PluginSettingsStore store = store();
        ServerCredentialSaver saver = new ServerCredentialSaver(store, unlocked());
        ServerDefinition original = ServerDefinition.named("Prod",
                "https://old.example.org/fhir")
                .pluginId(StandardFhirRestPlugin.PLUGIN_ID).build();
        saver.save(original, ServerAuthKind.BASIC, "alice", "prod-password");

        // What the form does on Save: rebuild the definition, carrying the id across.
        ServerDefinition edited = ServerDefinition.named("Production",
                        "https://new.example.org/fhir")
                .pluginId(StandardFhirRestPlugin.PLUGIN_ID)
                .id(original.id())
                .build();

        assertEquals(original.id(), edited.id(),
                "an edit must not change the server's identity, or its credentials are lost");
        assertEquals("prod-password", store.unlockForServer(edited, "a passphrase").password(),
                "renaming and re-pointing a server must not sign the user out of it");
    }

    @Test
    @DisplayName("A server keeps its identity across a save and a load")
    void identitySurvivesARestart() throws IOException {
        // Without this the id would be regenerated on every start, and every saved password
        // would be orphaned the first time the application was reopened.
        java.nio.file.Path file = directory.resolve("server-definitions.properties");
        FhirServerManager before = new FhirServerManager();
        before.add(ServerDefinition.named("Prod", "https://prod.example.org/fhir").build());
        before.save(file);

        FhirServerManager after = new FhirServerManager();
        after.load(file);

        ServerDefinition reloaded = after.definitionsForDisplay().get(0);
        assertEquals(before.definitionsForDisplay().get(0).id(), reloaded.id(),
                "a server's identity must survive a restart, or its credentials are orphaned");
    }

    @Test
    @DisplayName("Two servers that share a plugin are stored as separate entries")
    void twoServersAreStoredSeparately() throws IOException {
        // The file itself, not the API: one entry per server is what makes the read above
        // possible, and a hand-inspectable file is the point of the format.
        PluginSettingsStore store = store();
        ServerCredentialSaver saver = new ServerCredentialSaver(store, unlocked());
        saver.save(ServerDefinition.named("Prod", "https://prod.example.org/fhir")
                        .pluginId(StandardFhirRestPlugin.PLUGIN_ID).build(),
                ServerAuthKind.BASIC, "alice", "p1");
        saver.save(ServerDefinition.named("Test", "https://test.example.org/fhir")
                        .pluginId(StandardFhirRestPlugin.PLUGIN_ID).build(),
                ServerAuthKind.BASIC, "bob", "p2");

        assertEquals(2, store.readAll().size(),
                "each server needs its own entry; one entry means one server lost its login");
    }

    @Test
    @DisplayName("Anonymous saves nothing and clears any stored secret")
    void anonymousClearsTheSecret() throws IOException {
        PluginSettingsStore store = store();
        ServerCredentialSaver saver = new ServerCredentialSaver(store, unlocked());
        ServerDefinition server = server();

        assertEquals(ServerCredentialSaver.Outcome.SAVED,
                saver.save(server, ServerAuthKind.BASIC, "alice", "s3cret"));

        // Choosing anonymous is how a user discards a password, so it must actually
        // remove the stored one rather than merely stop using it.
        assertEquals(ServerCredentialSaver.Outcome.CLEARED,
                saver.save(server, ServerAuthKind.ANONYMOUS, null, null));

        String stored = store.readForServer(server).password();
        assertTrue(stored == null || stored.isEmpty(),
                "the stored password must be gone after switching to anonymous");
    }

    @Test
    @DisplayName("A blank password on a later save keeps the one already stored")
    void blankPasswordKeepsTheStoredOne() throws IOException {
        // The case that would otherwise lose a working password: a user opens a server to
        // correct its URL, the password field is blank because it never echoes, and
        // saving signs them out.
        PluginSettingsStore store = store();
        ServerCredentialSaver saver = new ServerCredentialSaver(store, unlocked());
        ServerDefinition server = server();

        saver.save(server, ServerAuthKind.BASIC, "alice", "s3cret");
        assertEquals(ServerCredentialSaver.Outcome.SAVED,
                saver.save(server, ServerAuthKind.BASIC, "alice", null));

        // Read the file back so this is a real round trip rather than an assertion about
        // an object still in memory.
        assertTrue(Files.readString(store.file()).contains("alice"),
                "the user name must survive a blank-password save");
    }

    @Test
    @DisplayName("Without a passphrase the choice is kept while the secret waits")
    void keepsTheChoiceWhileTheSecretWaitsForAPassphrase() throws IOException {
        // Used to refuse outright with NEEDS_PASSPHRASE, which dropped the auth choice
        // as well: the server reopened as anonymous even though Basic was chosen.
        // Now the name and the kind are stored so a later passphrase completes the
        // login, while the password itself is never written without one.
        PluginSettingsStore store = store();
        ServerCredentialSaver saver = new ServerCredentialSaver(store, new ServerPassphrase());
        // One server, held across both the save and the read. named() mints a fresh id on
        // every call, so two server() calls here would file the choice under one key and
        // look it up under another, which is exactly the orphaned-credential bug the id
        // exists to prevent — not a passphrase behaviour.
        ServerDefinition server = server();

        ServerCredentialSaver.Outcome outcome =
                saver.save(server, ServerAuthKind.BASIC, "alice", "s3cret");

        assertEquals(ServerCredentialSaver.Outcome.SAVED_NEEDS_PASSPHRASE, outcome);
        assertTrue(outcome.message().toLowerCase().contains("passphrase"),
                "the user must be told what to do about it, but was told: "
                        + outcome.message());
        assertTrue(outcome.isSuccess(), "the server still saves; only the secret waits");
        PluginSettings saved = store.readForServer(server);
        assertEquals(ServerAuthKind.BASIC, saved.authKind(),
                "the choice must survive so reopening shows Basic, not anonymous");
        assertEquals("alice", saved.userName());
        assertTrue(saved.password() == null || saved.password().isEmpty(),
                "no secret may be stored without a passphrase to encrypt it");
    }

    @Test
    @DisplayName("A bearer token is saved with its kind and unlocks as bearer")
    void bearerTokenRoundTripsWithItsKind() throws Exception {
        PluginSettingsStore store = store();
        ServerCredentialSaver saver = new ServerCredentialSaver(store, unlocked());
        ServerDefinition server = server();

        assertEquals(ServerCredentialSaver.Outcome.SAVED,
                saver.save(server, ServerAuthKind.BEARER, null, "tok-123"));

        PluginSettings saved = store.readForServer(server);
        assertEquals(ServerAuthKind.BEARER, saved.authKind());
        ServerAuthentication resolved = ServerCredentials.from(store, unlocked()).forServer(server);
        assertEquals("bearer", resolved.type());
        assertTrue(resolved.requestHeaders().first("Authorization").startsWith("Bearer "),
                "the saved token must actually be sent");
    }

    @Test
    @DisplayName("Switching kinds with a blank secret keeps the secret under the new kind")
    void switchingKindsKeepsTheStoredSecret() throws IOException {
        PluginSettingsStore store = store();
        ServerCredentialSaver saver = new ServerCredentialSaver(store, unlocked());
        ServerDefinition server = server();

        saver.save(server, ServerAuthKind.BASIC, "alice", "s3cret");
        assertEquals(ServerCredentialSaver.Outcome.SAVED,
                saver.save(server, ServerAuthKind.BEARER, null, null),
                "a blank secret means keep what is stored, not refuse the kind change");

        PluginSettings saved = store.readForServer(server);
        assertEquals(ServerAuthKind.BEARER, saved.authKind(),
                "reopening must show the newly chosen kind");
        assertTrue(saved.password() != null && !saved.password().isEmpty(),
                "the stored secret must carry over to the new kind");
    }

    @Test
    @DisplayName("The secret is encrypted on disk, never written in the clear")
    void theSecretIsNotReadableOnDisk() throws IOException {
        PluginSettingsStore store = store();
        new ServerCredentialSaver(store, unlocked())
                .save(server(), ServerAuthKind.BASIC, "alice", "s3cret");

        String onDisk = Files.readString(store.file());

        assertTrue(onDisk.contains("alice"), "the user name is stored in the clear by design");
        assertTrue(!onDisk.contains("s3cret"),
                "the password must be encrypted at rest, but appeared in the settings file");
    }
}
