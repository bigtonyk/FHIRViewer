package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Token parsing and the code-for-token exchange against a stub endpoint.
 */
class SmartTokenExchangeTest {

    static final String GRANT = """
            {"access_token": "abc.def.ghi", "token_type": "Bearer", "expires_in": 3600,
             "scope": "launch/patient patient/*.read", "patient": "123",
             "id_token": "header.payload.sig", "refresh_token": "refresh-1"}
            """;

    private com.sun.net.httpserver.HttpServer stub;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> answer = new AtomicReference<>(GRANT);
    private volatile int status = 200;

    @BeforeEach
    void startStub() throws IOException {
        stub = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/token", exchange -> {
            try (java.io.InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        stub.start();
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    String tokenUrl() {
        return "http://127.0.0.1:" + stub.getAddress().getPort() + "/token";
    }

    @Test
    @DisplayName("A well-formed grant parses with scope, patient, and refresh token")
    void grantParses() {
        SmartTokenResponse response = SmartTokenResponse.from(GRANT).orElseThrow();

        assertEquals("abc.def.ghi", response.accessToken());
        assertEquals("launch/patient patient/*.read", response.grantedScope());
        assertEquals("123", response.patient());
        assertTrue(response.hasRefreshToken());
        assertTrue(response.hasExpiry());
        assertTrue(response.expiresAt().isPresent());
    }

    @Test
    @DisplayName("Error documents, HTML, and blanks are not grants")
    void malformedBodiesAreNotGrants() {
        assertTrue(SmartTokenResponse.from(null).isEmpty());
        assertTrue(SmartTokenResponse.from("").isEmpty());
        assertTrue(SmartTokenResponse.from("{\"error\":\"invalid_grant\"}").isEmpty());
        assertTrue(SmartTokenResponse.from("<html>login</html>").isEmpty());
        assertTrue(SmartTokenResponse.from("{\"access_token\":\"\"}").isEmpty());
        assertTrue(SmartTokenResponse.from("{ \"access_token\": \"abc").isEmpty());
    }

    @Test
    @DisplayName("The exchange posts the code, redirect, and verifier without a secret")
    void exchangePostsPkceFields() throws Exception {
        SmartTokenExchangeService service = new SmartTokenExchangeService(
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        SmartTokenResponse response = service.exchange(tokenUrl(), "public-client",
                "code-1", "http://127.0.0.1:9/callback", "verifier-abc");

        assertEquals("abc.def.ghi", response.accessToken());
        String body = lastBody.get();
        assertTrue(body.contains("grant_type=authorization_code"), body);
        assertTrue(body.contains("code=code-1"), body);
        assertTrue(body.contains("code_verifier=verifier-abc"), body);
        assertTrue(body.contains("client_id=public-client"), body);
        assertTrue(body.contains("redirect_uri="), body);
        assertFalse(body.contains("client_secret"), "public clients send no secret: " + body);
    }

    @Test
    @DisplayName("A refused exchange names the server's error")
    void refusedExchangeNamesError() {
        status = 400;
        answer.set("{\"error\":\"invalid_grant\",\"error_description\":\"code expired\"}");
        SmartTokenExchangeService service = new SmartTokenExchangeService(
                Duration.ofSeconds(5), Duration.ofSeconds(5));

        SmartAuthorizationException failure = assertThrows(SmartAuthorizationException.class,
                () -> service.exchange(tokenUrl(), "c", "code", "http://127.0.0.1:9/cb", "v"));
        assertEquals(SmartAuthorizationException.Kind.TOKEN_EXCHANGE_FAILED, failure.kind());
        assertTrue(failure.getMessage().contains("invalid_grant"), failure.getMessage());
    }

    @Test
    @DisplayName("A 200 without a token is a failure, not a blank session")
    void tokenlessSuccessIsFailure() {
        answer.set("{\"ok\":true}");
        SmartTokenExchangeService service = new SmartTokenExchangeService(
                Duration.ofSeconds(5), Duration.ofSeconds(5));

        assertThrows(SmartAuthorizationException.class, () -> service.exchange(
                tokenUrl(), "c", "code", "http://127.0.0.1:9/cb", "v"));
    }

    @Test
    @DisplayName("No token reaches toString or describe")
    void noTokenReachesText() {
        SmartTokenResponse response = SmartTokenResponse.from(GRANT).orElseThrow();

        assertFalse(response.toString().contains("abc.def.ghi"), response.toString());
        assertFalse(response.toString().contains("refresh-1"), response.toString());
        assertFalse(response.describe().contains("abc.def.ghi"), response.describe());
        assertFalse(response.describe().contains("refresh-1"), response.describe());
        assertTrue(response.describe().contains("3600"), response.describe());
    }
}

