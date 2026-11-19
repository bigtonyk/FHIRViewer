package com.spiraleyes.fhirviewer.service.scripting.api;

import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;

/**
 * A write timed out after it may have been sent, so the server may have applied it.
 *
 * <p>This is deliberately distinct from a normal {@link ScriptApiException}: retrying
 * a create here can create a duplicate, and retrying an update or delete can apply the
 * change twice. Scripts should read or search first.</p>
 */
public final class WriteOutcomeUnknownException extends ScriptApiException {

    private final String operation;
    private final String serverName;
    private final String serverBaseUrl;

    public WriteOutcomeUnknownException(String operation, FhirServerConfiguration server,
            Throwable cause) {
        this(operation, server.name(), server.baseUrl(), cause);
    }

    /**
     * As above for a server with no configured entry, such as a SMART session, which is
     * named by its own URL.
     */
    public WriteOutcomeUnknownException(String operation, String serverName,
            String serverBaseUrl, Throwable cause) {
        super(operation + " request to " + serverName + " (" + serverBaseUrl
                + ") timed out after it may have been sent, so its outcome is unknown."
                + " Read or search before retrying; repeating it could apply the change twice.",
                cause);
        this.operation = operation;
        this.serverName = serverName;
        this.serverBaseUrl = serverBaseUrl;
    }

    /** The operation whose result is unknown, for example {@code create}. */
    public String operation() {
        return operation;
    }

    /** The server the request was sent to. */
    public String serverName() {
        return serverName;
    }

    /** The server's base URL, for a follow-up read or search. */
    public String serverBaseUrl() {
        return serverBaseUrl;
    }
}