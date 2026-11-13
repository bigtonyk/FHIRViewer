package com.spiraleyes.fhirviewer.server.smart;

/**
 * Why an authorization attempt did not produce a session.
 *
 * <p>Checked, like {@link SmartDiscoveryException}, because authorization is
 * an I/O operation a caller must handle — and the {@link Kind} is what lets
 * the workbench say "the server refused" instead of "something went wrong".
 * Messages are written for a status line and never carry a token, code,
 * verifier, or state value.</p>
 */
public class SmartAuthorizationException extends Exception {

    /** The broad category of failure, each with its own diagnostic. */
    public enum Kind {
        /** The configuration or request could not become an attempt. */
        INVALID_REQUEST,
        /** The system browser could not be opened. */
        BROWSER_UNAVAILABLE,
        /** The browser never returned before the deadline. */
        TIMEOUT,
        /** The user or the application stopped the attempt. */
        CANCELLED,
        /** The OAuth server answered {@code error=...}. */
        OAUTH_ERROR,
        /** The callback failed path/state/code validation. */
        INVALID_CALLBACK,
        /** The token endpoint refused the code or answered unusably. */
        TOKEN_EXCHANGE_FAILED,
        /** An ID token failed validation. */
        ID_TOKEN_INVALID,
        /** No answer: refused, timed out, TLS failed. */
        NETWORK
    }

    private final Kind kind;

    /**
     * @param kind the category of failure
     * @param message a message safe to show a user, free of credentials
     */
    public SmartAuthorizationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /** A failure with the underlying cause kept for the log. */
    public SmartAuthorizationException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
