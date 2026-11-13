package com.spiraleyes.fhirviewer.server.smart;

/**
 * Why a SMART discovery attempt did not produce a usable configuration.
 *
 * <p>Checked, like {@code ServerOperationException}, because discovery is an I/O operation
 * a caller must handle — and the {@link Kind} is what lets the workbench say "this server
 * is not SMART" instead of "something went wrong". A non-SMART server is a normal,
 * expected answer, not a crash, and the diagnostic a user needs ("this FHIR server does
 * not publish a SMART configuration") is only possible when the failure is typed.</p>
 *
 * <p>The message is written for a user reading a status line, and is already redacted of
 * anything resembling a credential before it reaches one — see {@code ui.ServerErrors}.</p>
 */
public class SmartDiscoveryException extends Exception {

    /** The broad category of failure, each with its own diagnostic. */
    public enum Kind {
        /** The URL the user gave could not become a request at all. */
        INVALID_URL,
        /** The server answered, but not with a SMART discovery document we can parse. */
        NOT_SMART,
        /** The document parsed, but a required field is missing or malformed. */
        INVALID_CONFIGURATION,
        /** No answer: refused, timed out, TLS failed, or the host does not exist. */
        NETWORK
    }

    private final Kind kind;

    /**
     * @param kind    the category of failure
     * @param message a message safe to show a user, already free of credentials and of
     *                URL query strings, which can carry search parameters about a patient
     */
    public SmartDiscoveryException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /** A failure with the underlying cause kept for the log. */
    public SmartDiscoveryException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** True when the failure means "this server is not SMART", rather than an error. */
    public boolean isNotSmart() {
        return kind == Kind.NOT_SMART;
    }
}
