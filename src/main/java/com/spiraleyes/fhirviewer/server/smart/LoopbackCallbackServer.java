package com.spiraleyes.fhirviewer.server.smart;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The loopback redirect target for one authorization attempt.
 *
 * <p>Bound to {@code 127.0.0.1} on an ephemeral port, never to a
 * network-accessible interface, with the real port carried in the redirect
 * URI the authorization request sends. Follows the test {@code StubServer}
 * idiom: {@code HttpServer.create(new InetSocketAddress("127.0.0.1", 0),
 * 0)}.</p>
 *
 * <p>One callback, then done. The first request to the expected path is
 * validated (path, {@code state}, success vs. OAuth error) and becomes the
 * outcome; later requests are refused without becoming a second outcome.
 * The server stops on success, error, cancellation, or timeout.</p>
 */
public final class LoopbackCallbackServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LoopbackCallbackServer.class);

    /** How long {@link #await} waits when the caller names no deadline. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 300;

    /** The callback path; fixed so the redirect URI is predictable. */
    public static final String CALLBACK_PATH = "/callback";

    private final HttpServer http;
    private final String expectedState;
    private final BlockingQueue<CallbackOutcome> outcomes = new LinkedBlockingQueue<>(1);
    private volatile boolean consumed;

    /**
     * @param expectedState the {@code state} the callback must echo; never blank
     * @throws IOException when the loopback socket cannot be bound
     */
    public LoopbackCallbackServer(String expectedState) throws IOException {
        if (expectedState == null || expectedState.isBlank()) {
            throw new IllegalArgumentException("An expected state value is required.");
        }
        this.expectedState = expectedState;
        this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.http.createContext(CALLBACK_PATH, this::handle);
        this.http.setExecutor(null);
    }

    /** Starts listening; the redirect URI is usable only after this returns. */
    public void start() {
        http.start();
    }

    /** @return the redirect URI carrying this server's real port */
    public String redirectUri() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + CALLBACK_PATH;
    }

    /** @return the port this server is listening on */
    public int port() {
        return http.getAddress().getPort();
    }

    /**
     * Waits for the one callback.
     *
     * @param timeout how long to wait; never negative
     * @param unit the timeout unit; never {@code null}
     * @return the validated outcome
     * @throws SmartAuthorizationException on timeout, cancellation, OAuth
     *         refusal, or validation failure
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public CallbackOutcome await(long timeout, TimeUnit unit)
            throws SmartAuthorizationException, InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) {
            throw new IllegalArgumentException("A timeout cannot be negative.");
        }
        CallbackOutcome outcome = outcomes.poll(timeout, unit);
        if (outcome == null) {
            stop();
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.TIMEOUT,
                    "The browser did not return in time. The authorization was cancelled.");
        }
        if (outcome instanceof CallbackOutcome.Cancelled) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.CANCELLED,
                    "The authorization was cancelled.");
        }
        if (outcome instanceof CallbackOutcome.Error error) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.OAUTH_ERROR, error.message());
        }
        if (outcome instanceof CallbackOutcome.Invalid invalid) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.INVALID_CALLBACK, invalid.message());
        }
        return outcome;
    }

    /** Waits with the default deadline (five minutes). */
    public CallbackOutcome await() throws SmartAuthorizationException, InterruptedException {
        return await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Cancels the wait: the outcome becomes cancelled. */
    public void cancel() {
        outcomes.offer(new CallbackOutcome.Cancelled());
        stop();
    }

    /** Stops listening; safe to call more than once. */
    public void stop() {
        try {
            http.stop(0);
        } catch (RuntimeException e) {
            log.debug("Loopback callback server stop reported: {}", e.toString());
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void handle(HttpExchange exchange) throws IOException {
        boolean first;
        synchronized (this) {
            first = !consumed;
            consumed = true;
        }
        try {
            if (!first) {
                respond(exchange, 429, "This authorization has already completed. "
                        + "Close this tab and return to the application.");
                return;
            }
            URI request = exchange.getRequestURI();
            String rawPath = request.getRawPath();
            if (!CALLBACK_PATH.equals(rawPath)) {
                offer(new CallbackOutcome.Invalid(
                        "The browser returned to an unexpected address. Close this tab and "
                                + "start the authorization again."));
                respond(exchange, 404, "Not the authorization callback. "
                        + "Close this tab and return to the application.");
                return;
            }
            Map<String, String> params = queryParams(request.getRawQuery());
            String error = params.get("error");
            if (error != null && !error.isBlank()) {
                String description = params.get("error_description");
                String message = "The authorization server refused the request (" + error
                        + (description == null || description.isBlank() ? "" : ": " + description)
                        + ").";
                offer(new CallbackOutcome.Error(message));
                respond(exchange, 200, "The authorization was not granted (" + error + "). "
                        + "Close this tab and return to the application.");
                return;
            }
            String code = params.get("code");
            String state = params.get("state");
            if (code == null || code.isBlank()) {
                offer(new CallbackOutcome.Invalid(
                        "The browser returned without an authorization code. Close this tab "
                                + "and start the authorization again."));
                respond(exchange, 400, "The authorization response was incomplete. "
                        + "Close this tab and return to the application.");
                return;
            }
            if (!expectedState.equals(state)) {
                offer(new CallbackOutcome.Invalid(
                        "The authorization response did not match this request "
                                + "(the state value differs). It may belong to another "
                                + "attempt. Close this tab and start again."));
                respond(exchange, 400, "The authorization response did not match. "
                        + "Close this tab and return to the application.");
                return;
            }
            offer(new CallbackOutcome.Success(code));
            respond(exchange, 200, "Authorization received. "
                    + "Close this tab and return to the application.");
        } finally {
            exchange.close();
            stop();
        }
    }

    private void offer(CallbackOutcome outcome) {
        // offer, not put: a second outcome (cancel racing success) is dropped
        // rather than blocking the HTTP handler thread forever.
        outcomes.offer(outcome);
    }

    static void respond(HttpExchange exchange, int status, String text)
            throws IOException {
        // Plain text, not HTML: nothing here needs markup, and markup would be
        // a place for a reflected parameter to hide in.
        byte[] body = ("FHIRViewer SMART authorization\n\n" + text + "\n")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int at = pair.indexOf('=');
            String name = at < 0 ? pair : pair.substring(0, at);
            String value = at < 0 ? "" : pair.substring(at + 1);
            params.putIfAbsent(decode(name), decode(value));
        }
        return params;
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException bad) {
            return value;
        }
    }

    /** The one validated outcome of the callback. */
    public sealed interface CallbackOutcome
            permits CallbackOutcome.Success, CallbackOutcome.Error,
            CallbackOutcome.Invalid, CallbackOutcome.Cancelled {

        /** The authorization code; only present on success. */
        record Success(String code) implements CallbackOutcome {
        }

        /** The OAuth server refused ({@code error=...}). */
        record Error(String message) implements CallbackOutcome {
        }

        /** The callback failed validation (path, state, missing code). */
        record Invalid(String message) implements CallbackOutcome {
        }

        /** The wait was cancelled before any callback arrived. */
        record Cancelled() implements CallbackOutcome {
        }
    }
}

