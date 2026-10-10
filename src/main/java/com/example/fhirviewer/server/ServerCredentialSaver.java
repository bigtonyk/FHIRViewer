package com.example.fhirviewer.server;

import java.io.IOException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a server's credentials to the encrypted settings store, asking for a passphrase
 * when one is needed.
 *
 * <p>Exists so the server dialog can offer authentication without holding a secret, a
 * passphrase, or any knowledge of how the store encrypts anything. The dialog says what
 * kind of authentication and hands over the values; this decides whether a passphrase is
 * needed and reports the outcome in words the dialog can show.</p>
 *
 * <p><b>Why a passphrase is asked for here.</b> {@link PluginSettingsStore} encrypts with
 * a passphrase the user chooses, and that choice already happens in
 * {@code Tools > Server Plugins}. Reusing it rather than inventing a second one means a
 * user has one secret to remember, and an existing encrypted setting is readable without
 * re-keying anything. When no passphrase has been set this session, the caller is told
 * rather than being allowed to store a password nobody will ever be able to unlock.</p>
 */
public final class ServerCredentialSaver {

    /** What happened, in words a dialog can put in a status line. */
    public enum Outcome {
        /** Credentials were written. */
        SAVED("Credentials saved."),
        /** The kind is anonymous, so any stored secret was cleared. */
        CLEARED("Saved as anonymous; any stored password was removed."),
        /** A secret was given but no passphrase is set, so it could not be stored. */
        NEEDS_PASSPHRASE(
                "Type a passphrase in the Passphrase field and save again. It encrypts"
                        + " the password, and is remembered for this session."),
        /** The store refused the write. */
        FAILED("The credentials could not be saved."),

        /**
         * A secret was given but no passphrase is set, so the choice itself was stored
         * while the secret waits for one.
         */
        SAVED_NEEDS_PASSPHRASE(
                "The user name and authentication choice were saved, but the password"
                        + " was not: type a passphrase in the Passphrase field and save"
                        + " again to keep it.");

        private final String message;

        Outcome(String message) {
            this.message = message;
        }

        /** The sentence to show the user. */
        public String message() {
            return message;
        }

        /** True when the caller can carry on as though it had worked. */
        public boolean isSuccess() {
            return this == SAVED || this == CLEARED || this == SAVED_NEEDS_PASSPHRASE;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ServerCredentialSaver.class);

    private final PluginSettingsStore store;
    private final ServerPassphrase passphrase;

    /**
     * @param store      where encrypted settings are kept; {@code null} disables saving
     * @param passphrase the passphrase already set this session, or an empty one
     */
    public ServerCredentialSaver(PluginSettingsStore store, ServerPassphrase passphrase) {
        this.store = store;
        this.passphrase = passphrase == null ? new ServerPassphrase() : passphrase;
    }

    /**
     * Stores, or clears, the credentials for one server.
     *
     * @param server the server the credentials belong to
     * @param kind   which kind of authentication was chosen
     * @param user   the user name, or {@code null}; a blank secret with a user name is
     *               still saved, so a user can type a name now and the password later
     * @param secret the password or token, or {@code null} to keep whatever is stored
     * @return what happened, never {@code null}
     */
    public Outcome save(FhirServerConfiguration server, ServerAuthKind kind, String user,
            String secret) {
        Objects.requireNonNull(server, "server");
        if (store == null) {
            return Outcome.FAILED;
        }
        String effectiveUser = user == null || user.isBlank() ? null : user.trim();
        boolean wantsSecret = kind != null && !kind.isAnonymous();
        boolean hasSecret = secret != null && !secret.isBlank();
        if (wantsSecret && hasSecret && TransportSecurity.exposesCredentials(server.baseUrl())) {
            // Said out loud before the password is written, not discovered later when a
            // request fails. The user is not stopped: http to a non-loopback host is their
            // call, and a warning they can see is better than a refusal they would work
            // around by turning the check off.
            log.warn("credentials for {} would be sent over a connection that is not"
                    + " encrypted; loopback is exempt", server.name());
        }
        // The server's own key, not its plugin id: one plugin can serve several servers, and
        // saving by plugin id meant each server's password replaced the previous one's.
        String key = server.credentialKey();
        try {
            if (!wantsSecret) {
                // Anonymous: keep the URL and the user name for reference, drop the secret,
                // and record the choice explicitly. Without the kind a server reopened
                // later has nothing to read back, and silently comes up as anonymous
                // even when it was saved as Basic — issue 1 in the desync report.
                store.save(new PluginSettings(server.pluginId(), server.baseUrl(),
                        effectiveUser, null, key, ServerAuthKind.ANONYMOUS), secretOf());
                return Outcome.CLEARED;
            }
            if (!hasSecret) {
                // No new secret typed. Leave the stored one exactly as it is rather than
                // overwriting it with nothing, which would silently sign the user out.
                PluginSettings existing = store.readForServer(server);
                if (existing == null || !existing.hasCredentials()) {
                    if (kind == ServerAuthKind.BASIC && effectiveUser != null) {
                        // The name and the choice are still worth keeping: without them a
                        // later passphrase cannot complete the login, and the server
                        // comes back as anonymous even though Basic was chosen.
                        store.save(new PluginSettings(server.pluginId(), server.baseUrl(),
                                effectiveUser, null, key, kind), secretOf());
                        return Outcome.SAVED_NEEDS_PASSPHRASE;
                    }
                    return kind == ServerAuthKind.BASIC && effectiveUser == null
                            ? Outcome.NEEDS_PASSPHRASE
                            : Outcome.SAVED;
                }
                // The choice may still have changed (Basic to Bearer, say) while the
                // secret stayed blank meaning "keep it". Re-file the entry under the new
                // kind so reopening shows what was chosen. The secret carries over
                // decrypted, never re-encrypted: store.save() encrypts whatever it is
                // given, so handing it the ciphertext would double-encrypt it into
                // something no passphrase can unlock. A locked store cannot be read,
                // so there the save reports a failure rather than inventing credentials.
                if (existing.authKind() != kind) {
                    try {
                        PluginSettings unlocked =
                                store.unlockForServer(server, secretOf());
                        store.save(new PluginSettings(server.pluginId(), server.baseUrl(),
                                effectiveUser != null ? effectiveUser : unlocked.userName(),
                                unlocked.password(), key, kind), secretOf());
                    } catch (SecretBoxException alreadyLocked) {
                        return Outcome.FAILED;
                    }
                }
                return Outcome.SAVED;
            }
            String secretKey = secretOf();
            if (secretKey == null || secretKey.isEmpty()) {
                // A password nobody can decrypt is worse than none, so the secret waits
                // — but the choice and the user name are kept, so adding the passphrase
                // later completes the login instead of starting over.
                store.save(new PluginSettings(server.pluginId(), server.baseUrl(),
                        effectiveUser, null, key, kind), null);
                return Outcome.SAVED_NEEDS_PASSPHRASE;
            }
            store.save(new PluginSettings(server.pluginId(), server.baseUrl(),
                    effectiveUser, secret, key, kind), secretKey);
            return Outcome.SAVED;
        } catch (IOException | SecretBoxException e) {
            return Outcome.FAILED;
        }
    }

    /**
     * Stores the credentials for one server under a passphrase typed in the server
     * dialog, remembering that passphrase for the session when it works.
     *
     * <p>This is the overload the server dialog uses: without it the user had to open
     * {@code Tools > Server Plugins}, set the passphrase there, then come back and
     * type the password again, with nothing on the server screen saying so. A blank
     * or failed passphrase leaves the session passphrase untouched, so a mistyped
     * confirmation cannot lock out credentials that already worked.</p>
     *
     * @param server the server the credentials belong to
     * @param kind which kind of authentication was chosen
     * @param user the user name, or {@code null}
     * @param secret the password or token, or {@code null} to keep what is stored
     * @param passphrase the passphrase just typed; blank means "no passphrase"
     * @return what happened, never {@code null}
     */
    public Outcome saveWithPassphrase(FhirServerConfiguration server, ServerAuthKind kind,
            String user, String secret, String passphrase) {
        Objects.requireNonNull(server, "server");
        boolean hasSecret = secret != null && !secret.isBlank();
        boolean wantsSecret = kind != null && !kind.isAnonymous();
        if (!wantsSecret || !hasSecret) {
            return save(server, kind, user, secret);
        }
        if (passphrase == null || passphrase.isBlank()) {
            // No inline passphrase typed: fall back to the session's remembered one
            // rather than refusing. The form used to have nowhere to type it, so the
            // only path was NEEDS_PASSPHRASE with instructions to find another dialog.
            // A session that already unlocked once must not be asked to retype on
            // every save; a session that never unlocked still reports NEEDS_PASSPHRASE
            // from inside save(), with the choice preserved.
            return save(server, kind, user, secret);
        }
        char[] copy = passphrase.toCharArray();
        try {
            String secretKey = new String(copy);
            String effectiveUser = user == null || user.isBlank() ? null : user.trim();
            String key = server.credentialKey();
            if (TransportSecurity.exposesCredentials(server.baseUrl())) {
                log.warn("credentials for {} would be sent over a connection that is not"
                        + " encrypted; loopback is exempt", server.name());
            }
            try {
                store.save(new PluginSettings(server.pluginId(), server.baseUrl(),
                        effectiveUser, secret, key, kind), secretKey);
            } catch (IOException | SecretBoxException e) {
                return Outcome.FAILED;
            }
            // Only the working passphrase is kept: the save above is what proved it.
            this.passphrase.set(secretKey);
            return Outcome.SAVED;
        } finally {
            java.util.Arrays.fill(copy, '\0');
        }
    }

    private String secretOf() {
        char[] copy = passphrase.copy();
        if (copy == null) {
            return null;
        }
        try {
            return new String(copy);
        } finally {
            java.util.Arrays.fill(copy, '\0');
        }
    }

    /**
     * The store this saver writes.
     *
     * <p>Exposed so the server dialog can read back the saved authentication choice
     * when a server is reopened — without it the form could write credentials but
     * never prefill them, which is why everything came back as anonymous.</p>
     */
    public PluginSettingsStore store() {
        return store;
    }
}
