package com.example.fhirviewer.server;

import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * What a plugin reports when a single operation fails.
 *
 * <p>Network work always surfaces through this type, never as a raw stack trace: it
 * distinguishes what the user can act on (unreachable server, bad credentials,
 * forbidden, missing resource, bad request) from unexpected failures that are only
 * logged. Messages are written for the status bar and dialogs; technical detail
 * belongs in the log.</p>
 */
public class ServerOperationException extends Exception {

    /** What went wrong, at the level the UI messages depend on. */
    public enum Kind {
        /** The server could not be reached at all. */
        UNREACHABLE,
        /** The server rejected the credentials (HTTP 401). */
        UNAUTHORIZED,
        /** The credentials are valid but the operation is not allowed (HTTP 403). */
        FORBIDDEN,
        /** The resource does not exist (HTTP 404). */
        NOT_FOUND,
        /** The request itself was wrong (HTTP 400/405/422 and friends). */
        BAD_REQUEST,
        /** Anything else, including unexpected failures. */
        SERVER_ERROR
    }

    private final Kind kind;
    private final Integer httpStatus;

    public ServerOperationException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    public ServerOperationException(Kind kind, String message, Integer httpStatus) {
        this(kind, message, httpStatus, null);
    }

    public ServerOperationException(Kind kind, String message, Throwable cause) {
        this(kind, message, null, cause);
    }

    public ServerOperationException(Kind kind, String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.httpStatus = httpStatus;
    }

    /** What went wrong, at the level the UI messages depend on. */
    public Kind kind() {
        return kind;
    }

    /** The HTTP status when one was received, or {@code null}. */
    public Integer httpStatus() {
        return httpStatus;
    }

    /** A short user facing summary including the HTTP status when there is one. */
    public String displayMessage() {
        if (httpStatus == null) {
            return getMessage();
        }
        return getMessage() + " (HTTP " + httpStatus + ")";
    }

    /**
     * Reads a resource from a search result directly instead of asking the server again.
     * The standard plugin returns full resources inside search Bundles whenever the
     * server includes them, and the UI reuses a resource that is already complete.
     */
    public static IBaseResource localCopy(IBaseResource resource) {
        return Objects.requireNonNull(resource, "resource");
    }
}
