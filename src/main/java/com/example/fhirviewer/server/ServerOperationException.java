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
 * <p><b>Diagnostics.</b> A FHIR server explains a refusal in its {@code OperationOutcome}
 * body, and that sentence is the only thing that distinguishes "unknown search parameter"
 * from "patient not found" once the status has been reduced to a number. It is carried
 * on the exception as {@link #diagnostics()} and folded into the message, so the status
 * bar can show what the server actually said. The raw body is never kept: it can echo a
 * submitted resource back, and that resource is patient data.</p>
 */
public class ServerOperationException extends Exception {

    /** What went wrong, at the level the UI messages depend on. */
    public enum Kind {
        /** The server could not be reached at all. */
        UNREACHABLE,
        /**
         * The request was still outstanding when its deadline passed.
         *
         * <p>Kept apart from {@link #UNREACHABLE} on purpose. The address may well be
         * correct and the server merely slow, so telling the user to "check the base URL"
         * would send them to fix the wrong thing. Retry later is the useful advice.
         */
        TIMEOUT,
        /** The server rejected the credentials (HTTP 401). */
        UNAUTHORIZED,
        /** The credentials are valid but the operation is not allowed (HTTP 403). */
        FORBIDDEN,
        /** The resource does not exist (HTTP 404). */
        NOT_FOUND,
        /** The request itself was wrong (HTTP 400/405/422 and friends). */
        BAD_REQUEST,
        /**
         * This plugin or this server does not implement the requested operation. Also the
         * kind a read-only server reports for a write, so the UI can offer to export the
         * resource locally instead of showing a generic failure.
         */
        UNSUPPORTED,
        /**
         * The server rejected the write because the resource changed after it was read
         * (HTTP 412). The caller should offer reload-or-force rather than overwriting
         * somebody else's edit.
         */
        CONFLICT,
        /** Anything else, including unexpected failures. */
        SERVER_ERROR
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final String diagnostics;

    public ServerOperationException(Kind kind, String message) {
        this(kind, message, null, null, null);
    }

    public ServerOperationException(Kind kind, String message, Integer httpStatus) {
        this(kind, message, httpStatus, null, null);
    }

    public ServerOperationException(Kind kind, String message, Throwable cause) {
        this(kind, message, null, cause, null);
    }

    public ServerOperationException(Kind kind, String message, Integer httpStatus, Throwable cause) {
        this(kind, message, httpStatus, cause, null);
    }

    /**
     * Builds an exception that also carries what the server said about the refusal.
     *
     * @param kind        what went wrong, at the level the UI messages depend on
     * @param message     the user-facing summary
     * @param httpStatus  the HTTP status, or {@code null} when there was no response
     * @param cause       the underlying throwable, for the log
     * @param diagnostics the server's own words from its {@code OperationOutcome}, or
     *                    {@code null} when it sent none
     */
    public ServerOperationException(Kind kind, String message, Integer httpStatus, Throwable cause,
            String diagnostics) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.httpStatus = httpStatus;
        this.diagnostics = diagnostics == null || diagnostics.isBlank() ? null : diagnostics.trim();
    }

    /** What went wrong, at the level the UI messages depend on. */
    public Kind kind() {
        return kind;
    }

    /** The HTTP status when one was received, or {@code null}. */
    public Integer httpStatus() {
        return httpStatus;
    }

    /**
     * What the server said, in its own words, or {@code null} when it said nothing.
     *
     * <p>Several issues are joined into one line. This is a display string, not a
     * structured value: the code, severity and location of each issue are not modelled
     * here because nothing acts on them — the user either retries with different input
     * or gives up — and keeping a FHIR model object on an exception would drag the FHIR
     * version into every catch block.
     */
    public String diagnostics() {
        return diagnostics;
    }

    /** True when the server explained itself, so a caller can show more than the status. */
    public boolean hasDiagnostics() {
        return diagnostics != null;
    }

    /**
     * The plugin-level kind an HTTP status maps to.
     *
     * <p>Written down here, on the enum, so the two HTTP stacks the project owns cannot
     * drift apart. Before this existed the JDK transport had a status table and the HAPI
     * client had a chain of {@code instanceof} tests, so the same {@code 409} could be
     * reported as a conflict on one path and as a generic error on the other, and the UI
     * would offer reload-or-force only half the time.
     *
     * <p>{@code 409} joins {@code 412} as a conflict: servers use both to refuse a write
     * whose precondition did not hold, and both mean the same thing to the user. A
     * status not listed is a server error, which is the honest default for the
     * {@code 5xx} range and for anything a proxy invented.
     */
    public static Kind kindOfStatus(int statusCode) {
        return switch (statusCode) {
            case 400, 405, 406, 413, 415, 422 -> Kind.BAD_REQUEST;
            case 401 -> Kind.UNAUTHORIZED;
            case 403 -> Kind.FORBIDDEN;
            case 404, 410 -> Kind.NOT_FOUND;
            case 409, 412 -> Kind.CONFLICT;
            case 501 -> Kind.UNSUPPORTED;
            default -> Kind.SERVER_ERROR;
        };
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
