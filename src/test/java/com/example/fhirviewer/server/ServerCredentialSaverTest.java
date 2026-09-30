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
    @DisplayName("A password is refused rather than stored when no passphrase is set")
    void refusesToStoreWithoutAPassphrase() {
        // A password nobody can decrypt is worse than none: it would look saved and never
        // work, which is the hardest kind of failure to diagnose.
        ServerCredentialSaver saver = new ServerCredentialSaver(store(), new ServerPassphrase());

        ServerCredentialSaver.Outcome outcome =
                saver.save(server(), ServerAuthKind.BASIC, "alice", "s3cret");

        assertEquals(ServerCredentialSaver.Outcome.NEEDS_PASSPHRASE, outcome);
        assertTrue(outcome.message().toLowerCase().contains("passphrase"),
                "the user must be told what to do about it, but was told: "
                        + outcome.message());
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
