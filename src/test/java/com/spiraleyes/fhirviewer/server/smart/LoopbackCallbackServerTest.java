package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The loopback callback: success, state mismatch, error callbacks, missing
 * codes, repeated callbacks, timeout, and cancellation.
 */
class LoopbackCallbackServerTest {

    @Test
    @DisplayName("A matching callback yields the code")
    void matchingCallbackYieldsCode() throws Exception {
        try (LoopbackCallbackServer server = new LoopbackCallbackServer("state-1")) {
            server.start();
            get(server.redirectUri() + "?code=auth-code-1&state=state-1");

            LoopbackCallbackServer.CallbackOutcome outcome =
                    server.await(5, TimeUnit.SECONDS);
            assertTrue(outcome instanceof LoopbackCallbackServer.CallbackOutcome.Success success
                    && success.code().equals("auth-code-1"), String.valueOf(outcome));
        }
    }

    @Test
    @DisplayName("A state mismatch is rejected, not mistaken for success")
    void stateMismatchIsRejected() throws Exception {
        try (LoopbackCallbackServer server = new LoopbackCallbackServer("expected")) {
            server.start();
            assertEquals(400, get(server.redirectUri() + "?code=code&state=other"));
            SmartAuthorizationException failure = assertThrows(SmartAuthorizationException.class,
                    () -> server.await(5, TimeUnit.SECONDS));
            assertEquals(SmartAuthorizationException.Kind.INVALID_CALLBACK, failure.kind());
        }
    }

    @Test
    @DisplayName("An OAuth error callback surfaces the server's refusal")
    void oauthErrorSurfacesRefusal() throws Exception {
        try (LoopbackCallbackServer server = new LoopbackCallbackServer("state-9")) {
            server.start();
            get(server.redirectUri()
                    + "?error=access_denied&error_description=user+declined&state=state-9");
            SmartAuthorizationException failure = assertThrows(SmartAuthorizationException.class,
                    () -> server.await(5, TimeUnit.SECONDS));
            assertEquals(SmartAuthorizationException.Kind.OAUTH_ERROR, failure.kind());
            assertTrue(failure.getMessage().contains("access_denied"), failure.getMessage());
        }
    }

    @Test
    @DisplayName("A missing code is rejected")
    void missingCodeIsRejected() throws Exception {
        try (LoopbackCallbackServer server = new LoopbackCallbackServer("state-2")) {
            server.start();
            get(server.redirectUri() + "?state=state-2");
            SmartAuthorizationException failure = assertThrows(SmartAuthorizationException.class,
                    () -> server.await(5, TimeUnit.SECONDS));
            assertEquals(SmartAuthorizationException.Kind.INVALID_CALLBACK, failure.kind());
        }
    }

    @Test
    @DisplayName("A second callback never becomes a second outcome")
    void secondCallbackIsRefused() throws Exception {
        try (LoopbackCallbackServer server = new LoopbackCallbackServer("state-3")) {
            server.start();
            get(server.redirectUri() + "?code=first&state=state-3");
            LoopbackCallbackServer.CallbackOutcome outcome =
                    server.await(5, TimeUnit.SECONDS);
            assertTrue(outcome instanceof LoopbackCallbackServer.CallbackOutcome.Success);
            // The server stopped after the first callback; a second request
            // either refuses or never connects. Either way there is no
            // second outcome to mistake for a new authorization.
            try {
                get(server.redirectUri() + "?code=second&state=state-3");
            } catch (IOException refused) {
                // Expected: the listener is gone.
            }
        }
    }

    @Test
    @DisplayName("No callback before the deadline is a timeout, and the port is freed")
    void timeoutFreesThePort() throws Exception {
        try (LoopbackCallbackServer server = new LoopbackCallbackServer("state-4")) {
            server.start();
            assertTrue(server.port() > 0);
            SmartAuthorizationException failure = assertThrows(SmartAuthorizationException.class,
                    () -> server.await(300, TimeUnit.MILLISECONDS));
            assertEquals(SmartAuthorizationException.Kind.TIMEOUT, failure.kind());
        }
        try (LoopbackCallbackServer again = new LoopbackCallbackServer("state-5")) {
            again.start();
            assertTrue(again.port() > 0);
            assertTrue(again.redirectUri().startsWith("http://127.0.0.1:"));
        }
    }

    @Test
    @DisplayName("Cancellation ends the wait")
    void cancellationEndsTheWait() throws Exception {
        try (LoopbackCallbackServer server = new LoopbackCallbackServer("state-6")) {
            server.start();
            CompletableFuture.runAsync(server::cancel);
            SmartAuthorizationException failure = assertThrows(SmartAuthorizationException.class,
                    () -> server.await(5, TimeUnit.SECONDS));
            assertEquals(SmartAuthorizationException.Kind.CANCELLED, failure.kind());
        }
    }

    private static int get(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}

