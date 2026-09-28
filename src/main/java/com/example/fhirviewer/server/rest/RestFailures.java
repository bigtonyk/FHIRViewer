package com.example.fhirviewer.server.rest;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.server.ServerOperationException;

/**
 * Turns what went wrong with a request into the one exception type the UI already handles.
 *
 * <p>The plugin layer already has a single failure type, {@link ServerOperationException},
 * and the UI renders its {@code Kind} without knowing which HTTP library produced it. The
 * REST layer therefore reports failures in that same type rather than introducing a
 * parallel exception hierarchy that every caller would have to learn.
 *
 * <p>The mapping is the useful part: a timeout is not the same thing to a user as a
 * refused connection, and neither is the same as a {@code 409} from a server that refused a
 * conflicting write. Collapsing them into "error" would throw away exactly the information
 * the status bar exists to show.
 */
public final class RestFailures {

    /**
     * Why a request did not produce a response.
     *
     * <p>Separate from {@link ServerOperationException.Kind} because it describes the
     * transport rather than the outcome, and because {@link #NONE} has no meaning at the
     * plugin boundary — a caller never has to handle "no failure".
     */
    public enum RestFailure {

        /** The exchange completed and the server sent a status. */
        NONE,

        /** The request was still outstanding when the deadline passed. */
        TIMEOUT,

        /** The host could not be reached: wrong address, refused, no route, unknown name. */
        NETWORK,

        /** The TLS handshake or certificate check failed. */
        TLS,

        /** A response arrived but could not be read or understood. */
        MALFORMED_RESPONSE,

        /** The client does not implement what the request asked for. */
        UNSUPPORTED
    }

    private RestFailures() {
    }

    /**
     * Classifies a transport-level throwable.
     *
     * <p>Walks the cause chain, because the JDK client wraps a connect refusal in an
     * {@code IOException} whose own message is often less specific than the cause it
     * carries.
     */
    public static RestFailure failureOf(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
            if (current instanceof SSLException) {
                return RestFailure.TLS;
            }
            if (isTimeout(current)) {
                return RestFailure.TIMEOUT;
            }
        }
        return RestFailure.NETWORK;
    }

    /**
     * True for every way a deadline can expire.
     *
     * <p>{@code HttpTimeoutException} is the one the JDK client actually throws, and it is
     * an {@code IOException} rather than a {@code SocketTimeoutException}, so it has to be
     * named explicitly or every timeout would be reported as a network failure.
     */
    private static boolean isTimeout(Throwable failure) {
        return failure instanceof SocketTimeoutException
                || failure instanceof HttpTimeoutException
                || failure instanceof TimeoutException
                || failure instanceof InterruptedIOException;
    }

    /**
     * The plugin-level exception kind a transport failure maps to.
     *
     * <p>Everything is {@link ServerOperationException.Kind#UNREACHABLE} except a timeout,
     * which is reported as such: telling a user to "check the base URL" when the address
     * was right and the server was merely slow sends them off to fix the wrong thing.
     */
    public static ServerOperationException.Kind kindOf(RestFailure failure) {
        return failure == RestFailure.TIMEOUT
                ? ServerOperationException.Kind.TIMEOUT
                : ServerOperationException.Kind.UNREACHABLE;
    }

    /**
     * Builds the exception for a request that never produced a response.
     *
     * @param action  what was being attempted, for the message, e.g. {@code "read Patient/1"}
     * @param failure the classified transport failure
     * @param cause   the underlying throwable, kept for the log
     */
    public static ServerOperationException transportFailure(String action,
            RestFailure failure, String detail, Throwable cause) {
        String message = switch (failure == null ? RestFailure.NETWORK : failure) {
            case TIMEOUT -> action + ": the server did not answer in time.";
            case TLS -> action + ": the secure connection to the server could not be established.";
            case MALFORMED_RESPONSE -> action + ": the server's answer could not be read.";
            case UNSUPPORTED -> action + ": this operation is not supported by the client.";
            default -> action + ": the server could not be reached. Check the base URL.";
        };
        return new ServerOperationException(kindOf(failure), message, null, cause);
    }

    /**
     * Builds the exception for a response that arrived but was not a success.
     *
     * <p>The server's own {@code OperationOutcome} text is carried into the message so the
     * diagnostic reaches the user. The raw body is not: it may echo a submitted resource
     * back, and messages end up in the status bar and in logs.
     */
    public static ServerOperationException httpFailure(String action, RestResponse response) {
        ServerOperationException.Kind kind = kindOfStatus(response.statusCode());
        String message = action + ": " + response.diagnostics();
        return new ServerOperationException(kind, message, response.statusCode());
    }

    /**
     * Maps an HTTP status onto the plugin error kinds the UI already distinguishes.
     *
     * <p>{@code 409} joins {@code 412} as a conflict: servers use both to refuse a write
     * whose precondition did not hold, and the UI already offers reload-or-force for that.
     */
    public static ServerOperationException.Kind kindOfStatus(int statusCode) {
        return switch (statusCode) {
            case 400, 405, 406, 413, 415, 422 -> ServerOperationException.Kind.BAD_REQUEST;
            case 401 -> ServerOperationException.Kind.UNAUTHORIZED;
            case 403 -> ServerOperationException.Kind.FORBIDDEN;
            case 404, 410 -> ServerOperationException.Kind.NOT_FOUND;
            case 409, 412 -> ServerOperationException.Kind.CONFLICT;
            case 501 -> ServerOperationException.Kind.UNSUPPORTED;
            default -> ServerOperationException.Kind.SERVER_ERROR;
        };
    }

    /**
     * True when the body looks like it is worth parsing as a FHIR {@code OperationOutcome}.
     *
     * <p>A cheap substring test rather than a parse attempt, because most responses are
     * resources and parsing every one of them as an error would be both slow and
     * pointless. Both the JSON and XML spellings are accepted, since servers vary.
     */
    public static boolean looksLikeOutcome(String body) {
        return body != null && body.contains(outcomeTypeName());
    }

    /** The resource type name used to recognise a parsed {@code OperationOutcome}. */
    public static String outcomeTypeName() {
        return "OperationOutcome";
    }

    /** True when a parsed resource is a FHIR {@code OperationOutcome}. */
    public static boolean isOutcome(IBaseResource resource) {
        return resource != null
                && outcomeTypeName().equalsIgnoreCase(
                        String.valueOf(resource.fhirType()).toLowerCase(Locale.ROOT));
    }

}
