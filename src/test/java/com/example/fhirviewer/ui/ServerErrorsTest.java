package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.ServerOperationException;

/**
 * The claim of Phase 6 about failures, tested: every status the plan lists produces a
 * message a user can act on, and none of them can leak a credential.
 *
 * <p>This class is deliberately JavaFX-free. It is the one part of the UI work that can be
 * asserted without a toolkit, and it is the part where being wrong is worst — a user
 * staring at "Something went wrong" with no idea whether to fix their credentials or wait
 * for a busy server.</p>
 *
 * <p>The redaction cases are the reason this is a test and not a code review: a bearer
 * token reaching a status bar is copied into bug reports, and the test is what keeps a
 * future message-formatting change from quietly undoing the guarantee.</p>
 */
class ServerErrorsTest {

    @Test
    @DisplayName("Every status the plan names produces its own message")
    void mapsEveryListedStatus() {
        assertMentions("credential", described(401));
        assertMentions("not allowed", described(403));
        assertMentions("resource type", described(404));
        assertMentions("changed since it was read", described(409));
        assertMentions("not valid", described(422));
        assertMentions("rate limiting", described(429));
        assertMentions("log will have the detail", described(500));
    }

    /** Every status is also reported as the number, which support will ask for. */
    @Test
    @DisplayName("The HTTP status is included in the message")
    void alwaysCarriesTheStatus() {
        for (int status : List.of(401, 403, 404, 409, 422, 429, 500, 503)) {
            assertTrue(described(status).contains("HTTP " + status),
                    "Status " + status + " is missing from: " + described(status));
        }
    }

    /** A 412 is the conflict a conditional write produces; it must read as one. */
    @Test
    @DisplayName("A version conflict offers reload or overwrite, not a generic error")
    void conflictIsActionable() {
        String message = described(412);
        assertMentions("Reload it", message);
        assertMentions("overwrite", message);
    }

    /** The server's own words must survive: they are the actual diagnosis. */
    @Test
    @DisplayName("OperationOutcome diagnostics are kept in the message")
    void keepsServerDiagnostics() {
        ServerOperationException failure = new ServerOperationException(
                ServerOperationException.Kind.BAD_REQUEST,
                "The search failed.", 400, null,
                "Unknown search parameter 'foo'");
        String message = ServerErrors.describe(failure);
        assertMentions("The search failed.", message);
        assertMentions("Unknown search parameter 'foo'", message);
        assertMentions("HTTP 400", message);
    }

    /** Several issues are joined rather than showing only the first. */
    @Test
    @DisplayName("Every reported issue is shown, not just the first")
    void keepsAllDiagnostics() {
        ServerOperationException failure = new ServerOperationException(
                ServerOperationException.Kind.SERVER_ERROR,
                "The batch failed.", 500, null,
                "entry 1: unknown; entry 2: invalid");
        assertMentions("entry 1", ServerErrors.describe(failure));
        assertMentions("entry 2", ServerErrors.describe(failure));
    }

    /** Transport failures are told apart, because each has a different fix. */
    @Test
    @DisplayName("DNS, timeout and TLS failures each get their own message")
    void distinguishesTransportFailures() {
        assertMentions("host name could not be resolved",
                ServerErrors.describe(new UnknownHostException("hapi.invalid")));
        assertMentions("did not answer in time",
                ServerErrors.describe(new SocketTimeoutException("read timed out")));
        assertMentions("TLS certificate",
                ServerErrors.describe(new SSLHandshakeException("handshake failed")));
    }

    /** A plugin timeout and a raw socket timeout must be worded the same way. */
    @Test
    @DisplayName("A timeout reported by the plugin reads like a transport timeout")
    void pluginTimeoutMatchesTransportTimeout() {
        String message = ServerErrors.describe(new ServerOperationException(
                ServerOperationException.Kind.TIMEOUT, "The request timed out."));
        assertMentions("did not answer in time", message);
        assertMentions("try again", message);
    }

    /** The rule the plan states twice: never display credentials or tokens. */
    @Test
    @DisplayName("A token in a failure message is redacted, not shown")
    void redactsBearerTokens() {
        String raw = "Request failed with header Authorization: Bearer eyJhbGciOi.secret-value";
        String message = ServerErrors.describe(new IllegalStateException(raw));
        assertFalse(ServerErrors.looksCredentialBearing(message),
                "A bearer token survived redaction in: " + message);
        assertMentions("redacted", message);
        assertFalse(message.contains("eyJhbGciOi"), "The token itself is still in: " + message);
    }

    /** The same rule for the basic-auth shape. */
    @Test
    @DisplayName("A basic-auth value in a failure message is redacted")
    void redactsBasicCredentials() {
        String raw = "Could not authenticate: Basic dXNlcjpwYXNzd29yZA==";
        String message = ServerErrors.describe(new IllegalStateException(raw));
        assertFalse(message.contains("dXNlcjpwYXNzd29yZA=="),
                "The basic-auth value is still in: " + message);
        assertMentions("redacted", message);
    }

    /**
     * A credential can also reach a dialog inside a plugin message, so the redaction is
     * applied to that path and not only to raw transport exceptions.
     */
    @Test
    @DisplayName("A token inside a plugin failure's diagnostics is redacted too")
    void redactsInsidePluginDiagnostics() {
        ServerOperationException failure = new ServerOperationException(
                ServerOperationException.Kind.UNAUTHORIZED,
                "Rejected.", 401, null, "Token Bearer abc.def-ghi was not accepted");
        String message = ServerErrors.describe(failure);
        assertFalse(message.contains("abc.def-ghi"), "The token is still in: " + message);
    }

    /** A null failure must not render as the word "null" in a dialog. */
    @Test
    @DisplayName("A missing failure still produces a sentence")
    void handlesNullFailure() {
        assertFalse(ServerErrors.describe(null).isBlank());
        assertFalse(ServerErrors.describe((ServerOperationException) null).isBlank());
    }

    /** Redaction must not mangle ordinary text that merely mentions a header name. */
    @Test
    @DisplayName("Ordinary text passes through redaction unchanged")
    void leavesOrdinaryTextAlone() {
        String message = ServerErrors.describe(new IllegalStateException(
                "The Authorization header was rejected by the proxy."));
        assertMentions("rejected by the proxy", message);
    }

    private static String described(int status) {
        return ServerErrors.describe(new ServerOperationException(
                ServerOperationException.kindOfStatus(status), "The request failed.", status));
    }

    private static void assertMentions(String needle, String text) {
        assertTrue(text.contains(needle),
                "Expected <" + needle + "> in: " + text);
    }
}
