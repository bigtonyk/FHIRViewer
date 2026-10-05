package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a credential the user saved is actually used, and — the more important
 * half — that one saved for a different server is not.
 *
 * <p>Before this phase, {@link PluginSettingsStore} had held an encrypted password for
 * some time and nothing ever read it: every session was built anonymous, so a password
 * saved in the plugin manager was written, encrypted and then never sent. These tests
 * pin the behaviour that fixes that.
 */
public class ServerCredentialsTest {

    private static final String URL = "https://firely.example.com/fhir";
    private static final String PASSPHRASE = "correct horse";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("A saved password is decrypted and used when the passphrase is available")
    void savedCredentialsAreUsed() throws Exception {
        ServerCredentials credentials = credentialsWithPassphrase();
        savePassword(credentials);

        ServerAuthentication resolved = credentials.forServer(firely(URL));

        assertEquals("basic", resolved.type());
        assertFalse(resolved.isAnonymous());
        assertNotNull(resolved.requestHeaders().first("Authorization"),
                "the resolved mechanism must be able to send its credential");
    }

    @Test
    @DisplayName("Anonymous is used when the user has not unlocked anything this session")
    void lockedCredentialsFallBackToAnonymous() throws Exception {
        ServerCredentials credentials = credentialsWithPassphrase();
        savePassword(credentials);

        // The passphrase exists but has not been typed yet, which is the state the
        // application is in on every launch.
        ServerCredentials locked = new ServerCredentials(credentials.store(), () -> null);

        assertTrue(locked.hasSavedCredentials(firely(URL)),
                "the caller can still tell a password exists");
        assertTrue(locked.forServer(firely(URL)).isAnonymous());
    }

    @Test
    @DisplayName("A wrong passphrase falls back to anonymous instead of throwing")
    void wrongPassphraseFallsBackToAnonymous() throws Exception {
        ServerCredentials credentials = credentialsWithPassphrase();
        savePassword(credentials);

        ServerCredentials wrong = new ServerCredentials(credentials.store(), () -> "not it");

        // A credential problem must not become an exception on every read: the honest
        // outcome is an anonymous request, and a 401 from the server if it needs auth.
        assertTrue(wrong.forServer(firely(URL)).isAnonymous());
    }

    @Test
    @DisplayName("A password saved for one server is never sent to a different one")
    void credentialsAreNotSentToAnotherServer() throws Exception {
        ServerCredentials credentials = credentialsWithPassphrase();
        savePassword(credentials);

        // Same plugin, different host. Settings are keyed by plugin id, so without the
        // URL check this password would go to a server that never earned it, and a
        // credential leaked to the wrong host is not recoverable.
        ServerDefinition other = ServerDefinition.forFirely("Elsewhere",
                "https://someone-else.example.com/fhir").build();

        assertTrue(credentials.forServer(other).isAnonymous());
        assertFalse(credentials.hasSavedCredentials(other));
    }

    @Test
    @DisplayName("A changed URL stops the saved password from being reused")
    void changedUrlStopsReuse() throws Exception {
        ServerCredentials credentials = credentialsWithPassphrase();
        savePassword(credentials);

        // The user edited the server to point somewhere else. The old password belongs
        // to the old server and must not follow the new one.
        assertTrue(credentials.forServer(
                ServerDefinition.forFirely("Moved", "https://firely.example.com/fhir2").build())
                .isAnonymous());
    }

    @Test
    @DisplayName("A trailing slash does not count as a different server")
    void trailingSlashIsNotADifferentServer() throws Exception {
        ServerCredentials credentials = credentialsWithPassphrase();
        savePassword(credentials);

        // ServerDefinition strips trailing slashes, but the store may hold one the user
        // typed by hand, and refusing on that technicality would look like a bug.
        assertFalse(credentials.forServer(
                ServerDefinition.forFirely("Same", URL + "/").build()).isAnonymous());
    }

    @Test
    @DisplayName("Nothing saved means anonymous, and no settings file is not an error")
    void nothingSavedIsAnonymous() throws Exception {
        ServerCredentials credentials = credentialsWithPassphrase();

        assertFalse(credentials.hasSavedCredentials(firely(URL)));
        assertTrue(credentials.forServer(firely(URL)).isAnonymous());
        assertTrue(credentials.forServer(null).isAnonymous());
    }

    @Test
    @DisplayName("A resolver with no store at all still answers, so callers need no null checks")
    void noStoreIsSafe() {
        ServerCredentials empty = new ServerCredentials();

        assertTrue(empty.forServer(firely(URL)).isAnonymous());
        assertFalse(empty.hasSavedCredentials(firely(URL)));
        assertTrue(ServerCredentials.from(null, null).forServer(firely(URL)).isAnonymous());
    }

    @Test
    @DisplayName("The session passphrase is held, reported without revealing, and wiped")
    void passphraseIsNeverPrintedAndCanBeCleared() {
        ServerPassphrase passphrase = new ServerPassphrase();

        assertFalse(passphrase.isPresent());
        assertTrue(passphrase.toString().contains("<none>"));

        passphrase.set("hunter2");
        assertTrue(passphrase.isPresent());
        assertTrue(passphrase.toString().contains("<set>"));
        assertFalse(passphrase.toString().contains("hunter2"),
                "a passphrase must never be printed");

        char[] copied = passphrase.copy();
        assertEquals("hunter2", new String(copied));

        // The copy belongs to the caller, so clearing the holder leaves it alone; what
        // gets overwritten is the holder's own array.
        passphrase.clear();
        assertFalse(passphrase.isPresent());
        assertEquals("hunter2", new String(copied));
    }

    @Test
    @DisplayName("A passphrase set from a ServerPassphrase unlocks the saved password")
    void passphraseHolderFeedsTheResolver() throws Exception {
        ServerPassphrase holder = new ServerPassphrase();
        ServerCredentials credentials = ServerCredentials.from(
                new PluginSettingsStore(settingsFile()), holder);
        credentials.store().save(new PluginSettings(FirelyPlugin.PLUGIN_ID, URL, "alice", "s3cret"),
                PASSPHRASE);

        assertTrue(credentials.forServer(firely(URL)).isAnonymous(), "nothing typed yet");

        holder.set(PASSPHRASE);
        assertEquals("basic", credentials.forServer(firely(URL)).type());

        holder.set("wrong");
        assertTrue(credentials.forServer(firely(URL)).isAnonymous());
    }

    private void savePassword(ServerCredentials credentials) throws Exception {
        credentials.store().save(new PluginSettings(FirelyPlugin.PLUGIN_ID, URL, "alice", "s3cret"),
                PASSPHRASE);
    }

    private ServerCredentials credentialsWithPassphrase() {
        return new ServerCredentials(new PluginSettingsStore(settingsFile()), () -> PASSPHRASE);
    }

    private ServerDefinition firely(String url) {
        return ServerDefinition.forFirely("Firely", url).build();
    }

    private Path settingsFile() {
        return tempDir.resolve("plugin-settings.properties");
    }
}
