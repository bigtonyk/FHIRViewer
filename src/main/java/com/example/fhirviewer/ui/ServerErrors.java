package com.example.fhirviewer.ui;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

import com.example.fhirviewer.server.ServerOperationException;

/**
 * Turns a failed server call into one line a user can act on.
 *
 * <p>The plan asks for useful information for a specific list of failures — 401, 403, 404,
 * 409, 422, 429, 5xx, timeout, network error, TLS error and {@code OperationOutcome} — and
 * this class is where that list lives. Writing it once matters for two reasons: the four
 * screens that talk to a server would otherwise each word the same status differently, and
 * a message is the only part of a REST failure a user ever sees, so getting it right is not
 * cosmetic.</p>
 *
 * <p><b>What the server said beats what the status implies.</b> A {@code 400} carrying
 * {@code "Unknown search parameter 'foo'"} is diagnosed by those words, not by the number.
 * So the server's own diagnostics are used when there are any, and the status table below
 * supplies only the explanation the status alone justifies.</p>
 *
 * <p><b>No credentials, ever.</b> Two rules apply to everything this class returns. A
 * message never quotes an {@code Authorization} header, a token or a password, and any text
 * passing through is run past {@link #redact(String)} first, which removes a bearer token or
 * basic-auth pair that a lower layer managed to put into an exception message. The rule
 * exists because the alternative is a screenshot of this window in a bug report containing
 * a live credential.</p>
 */
public final class ServerErrors {

    private ServerErrors() {
    }

    /** A bearer token in an {@code Authorization}-shaped value. */
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");

    /** A basic-auth pair: the base64 of {@code user:password}. */
    private static final Pattern BASIC = Pattern.compile("(?i)basic\\s+[A-Za-z0-9+/=]{8,}");

    /**
     * Describes any failure for display.
     *
     * @param failure the throwable; {@code null} yields a neutral message rather than
     *                {@code "null"}, so a caller cannot show the word "null" to a user
     */
    public static String describe(Throwable failure) {
        if (failure == null) {
            return "The operation failed for an unknown reason.";
        }
        if (failure instanceof ServerOperationException known) {
            return describe(known);
        }
        return redact(underlying(failure));
    }

    /**
     * Describes a plugin's own failure, using the kind it reported and what the server said.
     *
     * <p>Separate from {@link #describe(Throwable)} because this type is the one that knows
     * the HTTP status and the {@code OperationOutcome} diagnostics, and because it is the
     * case where a good message already exists and must not be replaced by a generic one.</p>
     */
    public static String describe(ServerOperationException failure) {
        if (failure == null) {
            return "The operation failed for an unknown reason.";
        }
        String advice = adviceFor(failure.kind(), failure.httpStatus());
        StringBuilder message = new StringBuilder(redact(failure.getMessage()));
        if (failure.hasDiagnostics()) {
            // The server's own words go in brackets rather than replacing the sentence:
            // both are wanted, and the order keeps the summary readable when the
            // diagnostics run to several lines.
            if (!message.isEmpty()) {
                message.append(' ');
            }
            message.append(failure.diagnostics());
        }
        if (failure.httpStatus() != null) {
            message.append(" (HTTP ").append(failure.httpStatus()).append(')');
        }
        if (advice != null && !advice.isEmpty()) {
            message.append(' ').append(advice);
        }
        return redact(message.toString());
    }

    /**
     * The advice a status justifies on its own, or {@code null} when there is nothing
     * useful to add.
     *
     * <p>Each line says what to do next rather than repeating the status, because the
     * status is already in the message. The one deliberate exception is
     * {@link ServerOperationException.Kind#UNSUPPORTED}, where the useful thing to say is
     * that the answer came from the server rather than from this build — a user who is told
     * "unsupported" and then finds the operation missing from the menu has been told the
     * truth by the wrong source.</p>
     */
    static String adviceFor(ServerOperationException.Kind kind, Integer status) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case UNAUTHORIZED ->
                    "Check the credentials saved for this server in Tools > Server Plugins.";
            case FORBIDDEN ->
                    "The credentials are valid but are not allowed to do this.";
            case NOT_FOUND ->
                    "Check the resource type, the id and the base URL.";
            case CONFLICT ->
                    "The server copy changed since it was read. Reload it, or overwrite it deliberately.";
            case BAD_REQUEST ->
                    status != null && status == 422
                            ? "The server understood the request but the content is not valid."
                            : null;
            case UNSUPPORTED ->
                    status != null && status == 501
                            ? "The server says it does not implement this operation."
                            : "This server or plugin does not offer that.";
            case TIMEOUT ->
                    "The server did not answer in time. It may be busy; try again.";
            case UNREACHABLE ->
                    "The server could not be reached. Check the base URL, the network and any firewall.";
            case SERVER_ERROR ->
                    rateLimited(status)
                            ? "The server is rate limiting requests. Wait a moment and try again."
                            : "The server reported a problem. Its own log will have the detail.";
        };
    }

    /** True for 429, which means "slow down" rather than "something is broken". */
    private static boolean rateLimited(Integer status) {
        return status != null && status == 429;
    }

    /**
     * The message for a throwable that never reached the plugin layer.
     *
     * <p>The plugin maps HTTP failures onto {@link ServerOperationException}, so anything
     * arriving here failed below that: a DNS lookup, a socket connect, a TLS handshake or a
     * deadline. Those are distinguished by cause because each has a different fix, and
     * lumping them under "network error" would send a user to check the wrong thing.</p>
     */
    private static String underlying(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String specific = describeCause(current);
            if (specific != null) {
                return specific;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        String message = messageOf(failure);
        return message.isEmpty() ? "The operation failed unexpectedly." : message;
    }

    /** One recognised transport failure, or {@code null} when it is not one of these. */
    private static String describeCause(Throwable cause) {
        if (cause instanceof UnknownHostException) {
            return "The host name could not be resolved. Check the base URL.";
        }
        if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
            return "The server did not answer in time. It may be busy; try again.";
        }
        if (cause instanceof SSLException) {
            return "The TLS certificate could not be validated. "
                    + "Check the certificate, or the trust store this machine uses.";
        }
        if (cause instanceof ConnectException) {
            return "The connection was refused. Check that the server is running and that the base URL is right.";
        }
        return null;
    }

    /**
     * Removes anything shaped like a credential from text bound for a dialog.
     *
     * <p>The transport is not supposed to put a credential in an exception message, and
     * {@link com.example.fhirviewer.server.rest.RestHeaders} redacts response headers for
     * the same reason. This is the belt to that braces: a message is the one thing a user
     * will copy into a bug report, and an {@code Authorization} value reaching one is a
     * credential leak that no later stage can undo. Cheap to apply, so it is applied to
     * every string this class returns rather than to the cases currently known.</p>
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String cleaned = BEARER.matcher(text).replaceAll("Bearer [redacted]");
        return BASIC.matcher(cleaned).replaceAll("Basic [redacted]");
    }

    /**
     * The first non-blank message in a cause chain, or the empty string.
     *
     * <p>Walked rather than read off the outermost throwable because a wrapper such as
     * {@code CompletionException} routinely has a null message of its own, and showing
     * "null" — or the wrapper's class name — is how a real explanation gets lost.</p>
     */
    private static String messageOf(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message.trim();
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return "";
    }

    /** True when the text still names a credential shape; used by the tests. */
    static boolean looksCredentialBearing(String text) {
        return text != null && (BEARER.matcher(text).find() || BASIC.matcher(text).find());
    }
}
