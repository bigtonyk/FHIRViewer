package com.example.fhirviewer.server;

import java.io.IOException;
import java.util.Objects;

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
                "Set a passphrase in Tools > Server Plugins... first, then enter the"
                        + " password again. It cannot be saved without one."),
        /** The store refused the write. */
        FAILED("The credentials could not be saved.");

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
            return this == SAVED || this == CLEARED;
        }
    }

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
        // The server's own key, not its plugin id: one plugin can serve several servers, and
        // saving by plugin id meant each server's password replaced the previous one's.
        String key = server.credentialKey();
        try {
            if (!wantsSecret) {
                // Anonymous: keep the URL and the user name for reference, drop the secret.
                // This is also how a user clears a password they no longer want stored.
                store.save(new PluginSettings(server.pluginId(), server.baseUrl(),
                        effectiveUser, null, key), secretOf());
                return Outcome.CLEARED;
            }
            if (!hasSecret) {
                // No new secret typed. Leave the stored one exactly as it is rather than
                // overwriting it with nothing, which would silently sign the user out.
                PluginSettings existing = store.readForServer(server);
                if (existing == null || !existing.hasCredentials()) {
                    return kind == ServerAuthKind.BASIC && effectiveUser == null
                            ? Outcome.NEEDS_PASSPHRASE
                            : Outcome.SAVED;
                }
                return Outcome.SAVED;
            }
            String secretKey = secretOf();
            if (secretKey == null || secretKey.isEmpty()) {
                // A password nobody can decrypt is worse than none: it would be stored,
                // look saved, and never work.
                return Outcome.NEEDS_PASSPHRASE;
            }
            store.save(new PluginSettings(server.pluginId(), server.baseUrl(),
                    effectiveUser, secret, key), secretKey);
            return Outcome.SAVED;
        } catch (IOException | SecretBoxException e) {
            return Outcome.FAILED;
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
}
