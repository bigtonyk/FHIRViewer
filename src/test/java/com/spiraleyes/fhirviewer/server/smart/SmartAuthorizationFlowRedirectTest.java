package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole authorization flow with a registered redirect URI: the listener binds the
 * registered port, and the same URI is sent in the authorization request and the token POST,
 * which is what an authorization server with exact redirect matching (Smile CDR) requires.
 */
class SmartAuthorizationFlowRedirectTest {

    private static final String GRANT = """
            {"access_token": "abc.def.ghi", "token_type": "Bearer", "expires_in": 60,
             "scope": "patient/*.read"}
            """;

    private HttpServer tokenStub;
    private final AtomicReference<String> tokenBody = new AtomicReference<>("");

    @BeforeEach
    void startTokenStub() throws IOException {
        tokenStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenStub.createContext("/token", exchange -> {
            try (java.io.InputStream in = exchange.getRequestBody()) {
                tokenBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] bytes = GRANT.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        tokenStub.start();
    }

    @AfterEach
    void stopTokenStub() {
        tokenStub.stop(0);
    }

    @Test
    @DisplayName("A registered redirect URI is bound, sent to authorize, and sent to the token endpoint")
    void registeredRedirectIsUsedThroughout() throws Exception {
        String registered = "http://localhost:" + LoopbackCallbackServerTest.freePort()
                + "/smart/callback";
        AtomicReference<String> sentRedirect = new AtomicReference<>();
        BrowserLauncher browser = url -> {
            Map<String, String> query = LoopbackCallbackServer.queryParams(url.getRawQuery());
            sentRedirect.set(query.get("redirect_uri"));
            // Stand in for the browser returning from the authorization server. The
            // listener binds 127.0.0.1, so call that rather than whatever localhost resolves
            // to on the test machine.
            String callback = query.get("redirect_uri").replace("localhost", "127.0.0.1")
                    + "?code=the-code&state=" + query.get("state");
            CompletableFuture.runAsync(() -> get(callback));
            return new BrowserLauncher.LaunchResult.Opened();
        };
        SmartAuthorizationFlow flow = new SmartAuthorizationFlow(
                browser, new SmartTokenExchangeService(), new SmartJwksFetcher());

        SmartAuthorizationSession session = flow.authorize(config(registered), discovered());

        assertEquals(registered, sentRedirect.get());
        Map<String, String> form = LoopbackCallbackServer.queryParams(tokenBody.get());
        assertEquals(registered, form.get("redirect_uri"));
        assertEquals("the-code", form.get("code"));
        assertTrue(session.expiresAt().isPresent());
    }

    @Test
    @DisplayName("A registered port already in use is reported by number")
    void portInUseIsReported() throws Exception {
        try (java.net.ServerSocket taken = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            String registered = "http://127.0.0.1:" + taken.getLocalPort() + "/callback";
            SmartAuthorizationFlow flow = new SmartAuthorizationFlow(
                    url -> new BrowserLauncher.LaunchResult.Opened(),
                    new SmartTokenExchangeService(), new SmartJwksFetcher());

            SmartAuthorizationException failure = assertThrows(
                    SmartAuthorizationException.class,
                    () -> flow.authorize(config(registered), discovered()));

            assertEquals(SmartAuthorizationException.Kind.NETWORK, failure.kind());
            assertTrue(failure.getMessage().contains(String.valueOf(taken.getLocalPort())),
                    failure.getMessage());
        }
    }

    private static SmartLaunchConfig config(String redirectUri) {
        return SmartLaunchConfig.builder()
                .id("id-fixed")
                .name("Smile CDR federated")
                .issuer("https://example.org/fhir")
                .mode(SmartLaunchMode.STANDALONE)
                .clientId("public-client")
                .redirectUri(redirectUri)
                .scopes("patient/*.read")
                .build();
    }

    private SmartConfiguration discovered() {
        String base = "http://127.0.0.1:" + tokenStub.getAddress().getPort();
        return SmartConfiguration.builder()
                .issuer("https://example.org/fhir")
                .authorizationEndpoint(base + "/authorize")
                .tokenEndpoint(base + "/token")
                .codeChallengeMethodsSupported(List.of("S256"))
                .build();
    }

    private static void get(String url) {
        try {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
