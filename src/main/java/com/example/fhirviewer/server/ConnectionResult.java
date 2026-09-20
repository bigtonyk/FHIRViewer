package com.example.fhirviewer.server;

import java.util.Objects;

/**
 * The outcome of testing a server connection.
 *
 * <p>A test never throws: an unreachable server is a failed result with a human readable
 * message, so the dialog can show it directly. Details beyond the message stay in the
 * application log.</p>
 */
public final class ConnectionResult {

    private final boolean reachable;
    private final String message;
    private final ServerCapabilities capabilities;

    private ConnectionResult(boolean reachable, String message, ServerCapabilities capabilities) {
        this.reachable = reachable;
        this.message = Objects.requireNonNull(message, "message");
        this.capabilities = capabilities;
    }

    /** The server answered a metadata request. */
    public static ConnectionResult reachable(String message, ServerCapabilities capabilities) {
        return new ConnectionResult(true, message, capabilities);
    }

    /** The server could not be reached or did not answer usefully. */
    public static ConnectionResult unreachable(String message) {
        return new ConnectionResult(false, message, null);
    }

    /** True when the server answered a metadata request. */
    public boolean isReachable() {
        return reachable;
    }

    /** A one line summary shown in the UI, for example the server and FHIR versions. */
    public String message() {
        return message;
    }

    /** The capabilities read during the test, or {@code null} when unreachable. */
    public ServerCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public String toString() {
        return (reachable ? "Reachable: " : "Unreachable: ") + message;
    }
}
