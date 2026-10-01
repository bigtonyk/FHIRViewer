package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.rest.JdkHttpRestClient;
import com.example.fhirviewer.server.rest.RestMethod;
import com.example.fhirviewer.server.rest.RestRequest;

/**
 * Phase 9 — a vendor's administration API on a different origin than its FHIR endpoint.
 *
 * <p>Two stub servers, not one, on purpose. A single stub can only show that a request
 * arrived; it cannot show <em>which</em> of two possible destinations it went to, which is
 * the only thing this feature exists to change. So the FHIR endpoint and the admin API are
 * separate servers here, and each test asserts which one was actually contacted.</p>
 */
class AdministrationBaseUrlTest {

    private OriginStub fhir;
    private OriginStub admin;

    @BeforeEach
    void startBoth() throws IOException {
        fhir = new OriginStub("/fhir");
        admin = new OriginStub("");
        fhir.start();
        admin.start();
    }

    @AfterEach
    void stopBoth() {
        fhir.stop();
        admin.stop();
    }

    private ServerDefinition definition(String adminUrl) {
        ServerDefinition.Builder builder =
                ServerDefinition.named("Server", fhir.baseUrl()).pluginId("smile-cdr");
        if (adminUrl != null) {
            builder.administrationBaseUrl(adminUrl);
        }
        return builder.build();
    }

    private static ServerSession sessionFor(ServerDefinition definition) {
        return new ServerSession(definition, AnonymousServerAuthentication.INSTANCE);
    }

    @Test
    @DisplayName("An administration call goes to the administration origin")
    void administrationCallUsesTheAdministrationOrigin() throws ServerOperationException {
        ServerDefinition definition = definition(admin.baseUrl());
        try (JdkHttpRestClient client = JdkHttpRestClient.forSession(sessionFor(definition))) {
            client.execute(RestRequest.builder(RestMethod.GET, "version/").administration().build());
        }
        assertEquals(1, admin.requests(),
                "the administration operation did not reach the administration server");
        assertEquals(0, fhir.requests(),
                "the administration operation went to the FHIR endpoint instead, which would "
                        + "send it to port 8000 and fail against every real Smile server");
    }

    @Test
    @DisplayName("An ordinary FHIR call still goes to the FHIR endpoint")
    void ordinaryCallStaysOnTheFhirEndpoint() throws ServerOperationException {
        // The other half, and the one that protects everybody who does not use this. A server
        // with an administration URL configured must not start sending its reads there.
        ServerDefinition definition = definition(admin.baseUrl());
        try (JdkHttpRestClient client = JdkHttpRestClient.forSession(sessionFor(definition))) {
            client.execute(RestRequest.builder(RestMethod.GET, "Patient").parameter("_count","1").build());
        }
        assertEquals(1, fhir.requests(),
                "a FHIR read was sent to the administration origin; the two must not be "
                        + "confused just because one server has both configured");
        assertEquals(0, admin.requests(),
                "a FHIR read reached the administration server");
    }

    @Test
    void administrationCallFallsBackToTheBaseUrl() throws ServerOperationException {
        // Firely's case. Its administration API is a branch of the same origin, so its
        // operations are flagged and the flag must be harmless rather than fatal.
        ServerDefinition definition = definition(null);
        try (JdkHttpRestClient client = JdkHttpRestClient.forSession(sessionFor(definition))) {
            client.execute(RestRequest.builder(RestMethod.GET, "administration/$reindex").administration().build());
        }
        assertEquals(1, fhir.requests(),
                "with no administration URL configured, a flagged operation must go to the "
                        + "base URL; failing here would break every Firely server");
        assertEquals(0, admin.requests());
    }

    @Test
    @DisplayName("A blank administration URL is treated as not set")
    void blankAdministrationUrlIsNotSet() throws ServerOperationException {
        // The user leaves the optional field empty. That must not become an empty base URL,
        // which would produce a request to nowhere.
        ServerDefinition definition = ServerDefinition.named("Server", fhir.baseUrl())
                .administrationBaseUrl("   ")
                .build();
        assertNull(definition.administrationBaseUrl(),
                "a blank field should leave no administration URL, not an empty string");

        try (JdkHttpRestClient client = JdkHttpRestClient.forSession(sessionFor(definition))) {
            client.execute(RestRequest.builder(RestMethod.GET, "version/").administration().build());
        }
        assertEquals(1, fhir.requests(),
                "a blank administration URL should fall back to the base URL");
    }

    @Test
    @DisplayName("The plugin's flag, not the path, decides where a call goes")
    void pluginFlagDecidesWhereTheCallGoes() {
        // The plugin states where an operation belongs rather than the transport inferring it
        // from the path. A path-based guess would misroute anything that looks like an admin
        // path, and misrouting a call that carries credentials is the failure to avoid.
        ServerOperation flagged = ServerOperation.builder("version", RestMethod.GET, "version/")
                .administration()
                .build();
        assertTrue(flagged.isAdministration(),
                "the builder's administration() must reach the descriptor");

        ServerOperation plain = ServerOperation
                .builder("validate", RestMethod.POST, "Patient/$validate")
                .build();
        assertTrue(!plain.isAdministration(),
                "an operation that says nothing about administration must not inherit the flag");
    }

    /** One listening server standing in for an origin. */
    private static final class OriginStub {

        private final com.sun.net.httpserver.HttpServer http;
        private final String prefix;
        private final AtomicInteger requests = new AtomicInteger();

        private OriginStub(String prefix) throws IOException {
            this.prefix = prefix;
            this.http = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 0);
            this.http.createContext("/", this::handle);
        }

        private void start() {
            http.start();
        }

        private void stop() {
            http.stop(0);
        }

        /** This origin's root, the form a user would type into the field. */
        private String baseUrl() {
            return "http://127.0.0.1:" + http.getAddress().getPort() + prefix;
        }

        private int requests() {
            return requests.get();
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
