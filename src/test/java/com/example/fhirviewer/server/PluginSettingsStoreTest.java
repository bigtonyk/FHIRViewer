package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the settings store contract that the plugin manager dialog calls.
 *
 * <p>The dialog could not open at all for a while because it was written against an API
 * that did not exist: a {@code saveAnonymous} method, and a {@code save} with the plugin
 * id as its first argument. Nothing caught that at build time, so these tests pin the
 * real signatures and the anonymous/credential round trips the dialog depends on.</p>
 */
public class PluginSettingsStoreTest {

    private static final String FIRELY = FirelyPlugin.PLUGIN_ID;
    private static final String URL = "https://firely.example.com/fhir";

    @Test
    @DisplayName("Anonymous settings save with save(settings, \"\") and store no secret")
    void anonymousSettingsRoundTrip() throws Exception {
        Path file = tempSettings();
        PluginSettingsStore store = new PluginSettingsStore(file);

        store.save(new PluginSettings(FIRELY, URL, "", null), "");

        PluginSettings read = store.read(FIRELY);
        assertEquals(URL, read.baseUrl());
        // The store normalizes a blank user name to null rather than storing an empty string.
        assertNull(read.userName());
        assertNull(read.password(), "an anonymous save must not store a secret");
    }

    @Test
    @DisplayName("Credentials round-trip through the passphrase and decrypt back")
    void credentialsRoundTrip() throws Exception {
        Path file = tempSettings();
        PluginSettingsStore store = new PluginSettingsStore(file);

        store.save(new PluginSettings(FIRELY, URL, "alice", "s3cret"), "pass phrase");
        store.save(new PluginSettings(SmileCdrPlugin.PLUGIN_ID, URL, "bob", "hunter2"), "pass phrase");

        // At rest the secret is ciphertext, not the plaintext.
        PluginSettings locked = store.read(FIRELY);
        assertFalse("s3cret".equals(locked.password()), "the password must not be stored in clear");

        PluginSettings unlocked = store.unlock(FIRELY, "pass phrase");
        assertEquals("alice", unlocked.userName());
        assertEquals("s3cret", unlocked.password());
        assertEquals("bob", store.unlock(SmileCdrPlugin.PLUGIN_ID, "pass phrase").userName());
    }

    @Test
    @DisplayName("Saving anonymously after a password removes the stored secret")
    void anonymousSaveClearsAPreviousPassword() throws Exception {
        Path file = tempSettings();
        PluginSettingsStore store = new PluginSettingsStore(file);

        store.save(new PluginSettings(FIRELY, URL, "alice", "s3cret"), "pass phrase");
        assertTrue(store.read(FIRELY).password() != null);

        // This is the exact call the dialog makes when the user clears the password field.
        store.save(new PluginSettings(FIRELY, URL, "alice", null), "");

        assertNull(store.read(FIRELY).password(),
                "going back to anonymous must remove the previously stored password");
        assertEquals(URL, store.read(FIRELY).baseUrl(), "the URL should survive");
        assertEquals("alice", store.read(FIRELY).userName(), "the user name should survive");
    }

    @Test
    @DisplayName("The wrong passphrase fails instead of returning garbage")
    void wrongPassphraseIsRejected() throws Exception {
        Path file = tempSettings();
        PluginSettingsStore store = new PluginSettingsStore(file);
        store.save(new PluginSettings(FIRELY, URL, "alice", "s3cret"), "right");

        assertThrows(SecretBoxException.class, () -> store.unlock(FIRELY, "wrong"));
    }

    @Test
    @DisplayName("Load-on-start and remove work per plugin")
    void loadOnStartAndRemove() throws Exception {
        Path file = tempSettings();
        PluginSettingsStore store = new PluginSettingsStore(file);

        assertFalse(store.loadsOnStart(FIRELY), "a new plugin defaults to not loading on start");
        store.setLoadsOnStart(FIRELY, true);
        assertTrue(store.loadsOnStart(FIRELY));
        assertTrue(store.exists());

        store.remove(FIRELY);
        assertNull(store.read(FIRELY), "remove should delete the settings");
        // Regression: the load-on-start flag used to survive remove(), which left the id in
        // the file and made the plugin reappear as an empty entry.
        assertFalse(store.loadsOnStart(FIRELY), "the load-on-start flag must be removed too");
        assertTrue(store.readAll().isEmpty(), "no keys should remain for the removed plugin");
    }

    @TempDir
    Path tempDir;

    private Path tempSettings() {
        return tempDir.resolve("plugin-settings.properties");
    }

    @Test
    @DisplayName("A missing settings file reads as empty rather than failing")
    void missingFileIsEmpty() throws IOException {
        PluginSettingsStore store = new PluginSettingsStore(tempDir.resolve("absent.properties"));
        assertFalse(store.exists());
        assertTrue(store.readAll().isEmpty());
        assertNull(store.read(FIRELY));
    }
}
