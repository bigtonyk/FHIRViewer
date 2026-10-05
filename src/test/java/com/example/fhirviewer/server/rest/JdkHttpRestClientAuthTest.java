package com.example.fhirviewer.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import com.example.fhirviewer.server.AnonymousServerAuthentication;
import com.example.fhirviewer.server.BasicServerAuthentication;
import com.example.fhirviewer.server.BearerServerAuthentication;
import com.example.fhirviewer.server.RequestHeaders;
import com.example.fhirviewer.server.ServerAuthentication;
import com.example.fhirviewer.server.ServerDefinition;
import com.example.fhirviewer.server.ServerSession;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests that credentials actually reach the wire, and that nothing else does.
 *
 * <p>Phase 2's transport sent no {@code Authorization} header at all, and the only
 * authentication the HAPI client knew about was basic. These tests exercise the
 * {@code JdkHttpRestClient} end to end against a canned server that records the header
 * it received, because "the header was built" and "the header was sent" are different
 * claims and only the second one matters to a user whose search is failing.
 */
public class JdkHttpRestClientAuthTest {

    private HttpServer server;
    private String baseUrl;

    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastTenant = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Basic credentials are sent as an Authorization header")
    void basicIsSent() throws Exception {
        try (JdkHttpRestClient client = new JdkHttpRestClient(baseUrl, 5_000,
                new BasicServerAuthentication("alice", "s3cret").requestHeaders())) {

            assertTrue(client.get("Patient").isSuccess());
        }

        assertEquals("Basic YWxpY2U6czNjcmV0", lastAuthorization.get());
    }

    @Test
    @DisplayName("A bearer token is sent as an Authorization header")
    void bearerIsSent() throws Exception {
        try (JdkHttpRestClient client = new JdkHttpRestClient(baseUrl, 5_000,
                new BearerServerAuthentication("abc.def.ghi").requestHeaders())) {

            assertTrue(client.get("Patient").isSuccess());
        }

        assertEquals("Bearer abc.def.ghi", lastAuthorization.get());
    }

    @Test
    @DisplayName("Anonymous access sends no Authorization header at all")
    void anonymousSendsNothing() throws Exception {
        try (JdkHttpRestClient client = new JdkHttpRestClient(baseUrl, 5_000)) {
            assertTrue(client.get("Patient").isSuccess());
        }

        assertNull(lastAuthorization.get());
    }

    @Test
    @DisplayName("forSession takes the URL, timeout and credentials from the session")
    void forSessionUsesTheLiveConfiguration() throws Exception {
        // The point of the factory: a plugin cannot drift from the server definition,
        // because it does not get to choose the URL or the timeout.
        ServerDefinition definition = ServerDefinition.named("Canned", baseUrl)
                .timeoutMillis(7_000)
                .build();
        ServerSession session = new ServerSession(definition,
                new BearerServerAuthentication("session.token"));

        try (JdkHttpRestClient client = JdkHttpRestClient.forSession(session)) {
            assertEquals(baseUrl, client.baseUrl());
            assertEquals(7_000, client.timeout().toMillis());
            assertEquals("Bearer session.token", client.authentication().first("Authorization"));

            assertTrue(client.get("Patient").isSuccess());
        }

        assertEquals("Bearer session.token", lastAuthorization.get());
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastTenant.set(exchange.getRequestHeaders().getFirst("X-Tenant"));
        try (java.io.InputStream in = exchange.getRequestBody()) {
            while (in.read() != -1) {
                // Drain, so the client is not left waiting on an unread stream.
            }
        }
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    @DisplayName("forSession on an anonymous session sends no credentials")
    void forSessionToleratesAnonymous() throws Exception {
        ServerDefinition definition = ServerDefinition.named("Canned", baseUrl).build();
        ServerSession session =
                new ServerSession(definition, AnonymousServerAuthentication.INSTANCE);

        try (JdkHttpRestClient client = JdkHttpRestClient.forSession(session)) {
            assertTrue(client.authentication().isEmpty());
            assertTrue(client.get("Patient").isSuccess());
        }

        assertNull(lastAuthorization.get());
    }

    @Test
    @DisplayName("A header the caller sets on the request wins over the session's")
    void perRequestHeaderOverridesTheClient() throws Exception {
        // A caller overriding Authorization for one call must not be overruled by the
        // session's credentials; the alternative is a silent, undebuggable surprise.
        try (JdkHttpRestClient client = new JdkHttpRestClient(baseUrl, 5_000,
                new BearerServerAuthentication("session.token").requestHeaders())) {

            RestResponse response = client.execute(RestRequest.builder(RestMethod.GET, "Patient")
                    .header("Authorization", "Bearer per-request")
                    .build());

            assertTrue(response.isSuccess());
        }

        assertEquals("Bearer per-request", lastAuthorization.get());
    }

    @Test
    @DisplayName("A mechanism the project has never heard of reaches the wire unchanged")
    void anUnknownMechanismIsSentAsWell() throws Exception {
        // The regression guard for the whole phase: no instanceof list anywhere can
        // exclude a mechanism that implements the seam.
        ServerAuthentication vendorKey = new ServerAuthentication() {
            @Override
            public String type() {
                return "vendor-key";
            }

            @Override
            public String displayName() {
                return "Vendor API key";
            }

            @Override
            public boolean isAnonymous() {
                return false;
            }

            @Override
            public RequestHeaders requestHeaders() {
                return RequestHeaders.builder()
                        .set("X-Vendor-Key", "key-12345")
                        .set("X-Tenant", "north")
                        .build();
            }
        };

        try (JdkHttpRestClient client = JdkHttpRestClient.forSession(
                new ServerSession(ServerDefinition.named("Canned", baseUrl).build(), vendorKey))) {

            assertTrue(client.get("Patient").isSuccess());
        }

        assertEquals("north", lastTenant.get());
    }
    @Test
    @DisplayName("Credentials are applied to every verb, not just GET")
    void credentialsApplyToEveryVerb() throws Exception {
        try (JdkHttpRestClient client = new JdkHttpRestClient(baseUrl, 5_000,
                new BearerServerAuthentication("tok").requestHeaders())) {

            assertTrue(client.post("Patient", "{}", "application/fhir+json").isSuccess());
            assertEquals("Bearer tok", lastAuthorization.get(), "after POST");

            assertTrue(client.put("Patient/1", "{}", "application/fhir+json").isSuccess());
            assertEquals("Bearer tok", lastAuthorization.get(), "after PUT");

            assertTrue(client.delete("Patient/1").isSuccess());
            assertEquals("Bearer tok", lastAuthorization.get(), "after DELETE");
        }
    }

    @Test
    @DisplayName("Nothing printable about a client carries the token")
    void nothingPrintableCarriesTheToken() throws Exception {
        String token = "a-very-recognisable-token";
        try (JdkHttpRestClient client = JdkHttpRestClient.forSession(
                new ServerSession(ServerDefinition.named("Canned", baseUrl).build(),
                        new BearerServerAuthentication(token)))) {

            assertTrue(client.get("Patient").isSuccess());

            // A failure message and a log line are where a token would leak; both are
            // built from these, so both are checked here.
            String printed = client.baseUrl() + " " + client.authentication() + " "
                    + client.toString();
            assertTrue(printed.indexOf(token) < 0, "a token reached a printable form: " + printed);
            assertNotNull(client.authentication().first("Authorization"),
                    "it is still sent; it is only the printed form that is safe");
        }
    }
}


