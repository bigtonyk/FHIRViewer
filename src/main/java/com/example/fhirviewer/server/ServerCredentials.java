package com.example.fhirviewer.server;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the credentials a user saved for a server into a {@link ServerAuthentication}.
 *
 * <p>{@link PluginSettingsStore} has held an encrypted password since before the REST
 * work began, but nothing ever asked it for one: {@link FhirServerService} built every
 * read session anonymous, so a password saved in the plugin manager was never sent
 * anywhere. This class is the missing link, and it is the only place that reads stored
 * credentials for an outgoing request.
 *
 * <p>It is deliberately small and has one job: given a server, work out how to
 * authenticate to <em>that</em> server, or say plainly that it cannot. Everything else —
 * where settings live, how they are encrypted, how a header is built — belongs to
 * {@code PluginSettingsStore} and {@link ServerAuthentication}.
 *
 * <p><b>Nothing here throws.</b> A missing settings file, a wrong passphrase and a
 * damaged file all mean the same thing to a user about to search a server: use
 * anonymous access, and let the server decide. Turning a credential problem into an
 * exception would put a dialog in the middle of every read, and would surface "your
 * passphrase is wrong" in a place the user cannot act on it. The reason is logged
 * instead, without the passphrase or the password.
 *
 * <p><b>Threading.</b> Immutable and stateless apart from the store it is given, and
 * safe to share. The passphrase arrives through a {@link Supplier} rather than a field
 * because it belongs to a UI prompt that happens at a different time from the request.
 */
public final class ServerCredentials {

    private static final Logger log = LoggerFactory.getLogger(ServerCredentials.class);

    private final PluginSettingsStore store;
    private final Supplier<String> passphrase;

    /** A resolver that always answers anonymous, for callers with no settings to offer. */
    public ServerCredentials() {
        this(null, () -> null);
    }

    /**
     * @param store      where saved settings live; {@code null} means nothing is saved
     * @param passphrase supplies the passphrase that unlocks them, or {@code null} when
     *                   the user has not given one this session
     */
    public ServerCredentials(PluginSettingsStore store, Supplier<String> passphrase) {
        this.store = store;
        this.passphrase = passphrase == null ? () -> null : passphrase;
    }

    /**
     * A resolver reading its passphrase from a {@link ServerPassphrase}.
     *
     * <p>The usual construction, because {@link SecretBox} takes a {@code String} while
     * the passphrase is better held as a wipeable {@code char[]}. The temporary
     * {@code String} here is the one unavoidable copy, and it is discarded as soon as
     * the decryption returns.
     */
    public static ServerCredentials from(PluginSettingsStore store, ServerPassphrase passphrase) {
        if (passphrase == null) {
            return new ServerCredentials(store, () -> null);
        }
        return new ServerCredentials(store, () -> {
            char[] copy = passphrase.copy();
            if (copy == null) {
                return null;
            }
            try {
                return new String(copy);
            } finally {
                Arrays.fill(copy, '\0');
            }
        });
    }


    /**
     * How to authenticate to one server.
     *
     * <p>Returns {@link AnonymousServerAuthentication#INSTANCE} whenever the answer is
     * anything other than a usable saved credential: no settings for the server, a
     * passphrase that is absent or wrong, an unreadable settings file, or settings that
     * name a different URL. Each of those is a case where sending a password would be
     * wrong or impossible, and anonymous access is the honest default.
     *
     * @param server the server about to be contacted
     * @return never {@code null}
     */
    public ServerAuthentication forServer(FhirServerConfiguration server) {
        if (server == null || store == null) {
            return AnonymousServerAuthentication.INSTANCE;
        }
        PluginSettings saved;
        try {
            saved = store.readForServer(server);
        } catch (IOException e) {
            log.info("saved credentials for {} could not be read: {}", server.name(), e.toString());
            return AnonymousServerAuthentication.INSTANCE;
        }
        if (saved == null || !saved.hasCredentials()) {
            return AnonymousServerAuthentication.INSTANCE;
        }
        String secret = passphrase.get();
        if (secret == null || secret.isEmpty()) {
            // The user saved a password but has not unlocked it this session. That is a
            // normal state, not an error: the plugin manager clears the passphrase field
            // after saving precisely so it is not left lying around.
            log.info("{} has saved credentials that are locked; using anonymous access",
                    server.name());
            return AnonymousServerAuthentication.INSTANCE;
        }
        if (TransportSecurity.exposesCredentials(server.baseUrl())) {
            // The credential is about to be put on the wire in a form anyone on the path can
            // read. Not refused - http to a non-loopback host is the user's decision, and a
            // check they cannot see past would just be worked around - but recorded, because
            // the save-time warning may have been days and several restarts ago.
            log.warn("using a saved credential for {} over a connection that is not encrypted",
                    server.name());
        }
        try {
            return BasicServerAuthentication.from(store.unlockForServer(server, secret));
        } catch (SecretBoxException e) {
            // The passphrase is wrong or the file is damaged. SecretBox reports both the
            // same way on purpose, and neither the message nor this log line carries the
            // passphrase, so it is safe to record.
            log.info("saved credentials for {} could not be unlocked: {}", server.name(),
                    e.getMessage());
            return AnonymousServerAuthentication.INSTANCE;
        } catch (IOException e) {
            log.info("saved credentials for {} could not be read: {}", server.name(), e.toString());
            return AnonymousServerAuthentication.INSTANCE;
        }
    }

    /**
     * True when this server has a saved password, whether or not it is unlocked.
     *
     * <p>Lets a caller tell "no credentials were ever saved" from "credentials exist but
     * the passphrase has not been entered", which are different things to show a user.
     */
    public boolean hasSavedCredentials(FhirServerConfiguration server) {
        if (server == null || store == null) {
            return false;
        }
        try {
            PluginSettings saved = store.readForServer(server);
            return saved != null && saved.hasCredentials();
        } catch (IOException e) {
            log.info("saved credentials for {} could not be read: {}", server.name(), e.toString());
            return false;
        }
    }

    @Override
    public String toString() {
        return "ServerCredentials[" + (store == null ? "no store" : store.file()) + "]";
    }

    /**
     * The store this resolver reads.
     *
     * <p>Exposed so a caller that already holds a store can hand the same one to the
     * dialog that writes it, and so a test can inspect what was saved.
     */
    public PluginSettingsStore store() {
        return store;
    }
}
