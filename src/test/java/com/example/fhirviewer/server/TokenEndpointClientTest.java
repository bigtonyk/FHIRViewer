package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code client_credentials} grant, end to end against a local token endpoint.
 *
 * <p>Against a stub rather than a real identity provider, for the reason every other
 * transport test here does the same: the offline suite stays offline, and the thing worth
 * proving is that the request is well-formed and that a refusal is reported as a refusal.
 * The one property that genuinely needs the wire is that the form body really arrives, and
 * that is what {@link #theGrantIsFormEncodedAndCarriesTheSecret()} checks.
 */
class TokenEndpointClientTest {

    private com.sun.net.httpserver.HttpServer tokenEndpoint;
    private final AtomicReference<String> lastMethod = new AtomicReference<>("");
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastContentType = new AtomicReference<>("");
    private final AtomicReference<String> answer = new AtomicReference<>("");
    private final AtomicReference<Integer> answerStatus = new AtomicReference<>(200);

    @BeforeEach
    void startStub() throws IOException {
        tokenEndpoint = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        tokenEndpoint.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastContentType.set(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answerStatus.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        tokenEndpoint.start();
    }

    @AfterEach
    void stopStub() {
        tokenEndpoint.stop(0);
    }

    private String endpointUrl() {
        return "http://127.0.0.1:" + tokenEndpoint.getAddress().getPort() + "/oauth/token";
    }

    @Test
    @DisplayName("A real grant comes back as a token")
    void obtainsAToken() throws Exception {
        answer.set("{ \"access_token\": \"abc.def.ghi\", \"token_type\": \"Bearer\","
                + " \"expires_in\": 3600 }");

        TokenFetchResult result = new TokenEndpointClient()
                .fetch(endpointUrl(), "my-client", "s3cret".toCharArray(), null);

        assertEquals("abc.def.ghi", result.accessToken());
        assertEquals(3600L, result.expiresInSeconds());
    }

    @Test
    @DisplayName("The grant is a form-encoded POST carrying the secret")
    void theGrantIsFormEncodedAndCarriesTheSecret() throws Exception {
        answer.set("{ \"access_token\": \"abc\" }");

        new TokenEndpointClient().fetch(endpointUrl(), "my-client", "s3cret".toCharArray(), "read");

        assertEquals("POST", lastMethod.get());
        assertTrue(lastContentType.get().startsWith("application/x-www-form-urlencoded"),
                lastContentType.get());
        assertTrue(lastBody.get().contains("grant_type=client_credentials"), lastBody.get());
        assertTrue(lastBody.get().contains("client_id=my-client"), lastBody.get());
        assertTrue(lastBody.get().contains("client_secret=s3cret"), lastBody.get());
        assertTrue(lastBody.get().contains("scope=read"), lastBody.get());
    }

    @Test
    @DisplayName("A scope is left out entirely when none was asked for")
    void noScopeMeansNoScopeParameter() throws Exception {
        answer.set("{ \"access_token\": \"abc\" }");

        new TokenEndpointClient().fetch(endpointUrl(), "my-client", "s3cret".toCharArray(), null);

        assertTrue(!lastBody.get().contains("scope="), lastBody.get());
    }

@Test
    @DisplayName("A wrong secret comes back as a refusal with the server's own words")
    void refusalIsReportedAsARefusal() {
        answer.set("{ \"error\": \"invalid_client\", \"error_description\": \"bad secret\" }");
        answerStatus.set(401);

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> new TokenEndpointClient().fetch(endpointUrl(), "my-client",
                        "wrong".toCharArray(), null));

        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, failure.kind());
        assertEquals(401, failure.httpStatus());
    }

    @Test
    @DisplayName("A 200 that is not a token is refused rather than passed on as one")
    void aNonTokenBodyIsNotAToken() {
        answer.set("<html><body>Sign in to continue</body></html>");

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> new TokenEndpointClient().fetch(endpointUrl(), "my-client",
                        "s3cret".toCharArray(), null));

        assertTrue(failure.getMessage().contains("access_token"), failure.getMessage());
    }

    @Test
    @DisplayName("An unreachable endpoint is a transport failure, not a null token")
    void unreachableEndpointFails() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> new TokenEndpointClient().fetch("http://127.0.0.1:1/token", "c",
                        "s".toCharArray(), null));

        assertEquals(ServerOperationException.Kind.UNREACHABLE, failure.kind());
    }

    @Test
    @DisplayName("A plain-http endpoint off loopback warns, exactly as a password does")
    void plainHttpEndpointWarns() {
        assertNull(TokenEndpointClient.transportWarning("https://auth.example.com/token"));
        assertNull(TokenEndpointClient.transportWarning("http://localhost:8080/token"));
        assertTrue(TokenEndpointClient.transportWarning("http://auth.example.com/token") != null,
                "a client secret in the clear is the same risk as a password in the clear");
    }
}