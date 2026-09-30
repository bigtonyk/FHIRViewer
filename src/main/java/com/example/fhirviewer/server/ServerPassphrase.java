package com.example.fhirviewer.server;

import java.util.Arrays;

/**
 * The passphrase that unlocks saved credentials for the current application session.
 *
 * <p>{@link SecretBox} needs the passphrase to decrypt a stored password, and the user
 * types it into the plugin manager rather than the application storing it. That leaves a
 * gap: something has to remember what they typed for as long as the session lasts,
 * because a search an hour later still needs the password. This is that something.
 *
 * <p>It holds the value in a {@code char[]} and overwrites the array on
 * {@link #clear()} and on every {@link #set(String)}, so the passphrase is not left
 * sitting in an immutable {@code String} that cannot be wiped. It is a
 * {@code volatile} reference with synchronised access, because the UI thread sets it
 * while a background thread may be resolving a credential for a request.
 *
 * <p><b>Nothing here reveals it.</b> {@link #toString()} says only whether a passphrase
 * is present, and there is no getter that returns a {@code String}, so the value cannot
 * reach a log line, a status bar or a message by accident. Call {@link #copy()} and
 * clear the result when done with it.
 */
public final class ServerPassphrase {

    private volatile char[] value;

    /**
     * Remembers the passphrase for the rest of the session.
     *
     * <p>A {@code null} or empty passphrase means "no passphrase", which is the state a
     * user is in before they have unlocked anything, and which {@link ServerCredentials}
     * reads as "use anonymous access".
     */
    public synchronized void set(String passphrase) {
        clear();
        this.value = (passphrase == null || passphrase.isEmpty()) ? null : passphrase.toCharArray();
    }

    /**
     * The passphrase as a fresh copy, or {@code null} when none has been set.
     *
     * <p>The caller owns the result and should clear it once the operation that needed it
     * is done, which is why it is a copy rather than the live array.
     */
    public synchronized char[] copy() {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    /** True when a passphrase is available, without revealing it. */
    public boolean isPresent() {
        return value != null;
    }

    /**
     * Forgets the passphrase, overwriting what was held.
     *
     * <p>Called when the window closes, and whenever a new passphrase is set. Locking
     * the application again should not require the old value to remain in memory.
     */
    public synchronized void clear() {
        if (value != null) {
            Arrays.fill(value, '\0');
            value = null;
        }
    }

    @Override
    public String toString() {
        return "ServerPassphrase[" + (isPresent() ? "<set>" : "<none>") + "]";
    }
}
