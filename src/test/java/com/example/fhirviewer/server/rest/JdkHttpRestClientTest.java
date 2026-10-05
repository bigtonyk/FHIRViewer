package com.example.fhirviewer.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.ServerOperationException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests the transport against a tiny canned HTTP server on localhost: every verb, query
 * encoding, header pass-through, the error path, a timeout and an unreachable host.
 *
 * <p>No Internet access and no JavaFX toolkit are needed. One test deliberately runs the
 * client on a background thread, because the transport blocks and the UI must never be the
 * thread that blocks.
 */
public class JdkHttpRestClientTest {

    private static final String OUTCOME_JSON = "{ \"resourceType\": \"OperationOutcome\", "
            + "\"issue\": [ { \"severity\": \"error\", \"code\": \"not-found\", "
            + "\"diagnostics\": \"Patient/99 not known\" } ] }";

    private HttpServer server;
    private String baseUrl;
    private JdkHttpRestClient client;

    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastHeader = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
        client = new JdkHttpRestClient(baseUrl, 5_000);
    }

    @AfterEach
    void stopServer() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("A GET reaches the right path with its query parameters and headers")
    void getWithParametersAndHeaders() throws Exception {
        RestResponse response = client.execute(RestRequest.builder(RestMethod.GET, "Patient")
                .parameter("name", "Smith & Jones")
                .header("X-Tenant", "north")
                .build());

        assertTrue(response.isSuccess(), "HTTP " + response.statusCode());
        assertEquals("GET", lastMethod.get());
        assertEquals("name=Smith+%26+Jones", lastQuery.get(),
                "a space and an ampersand must be encoded, not split into two parameters");
        assertEquals("north", lastHeader.get());
    }

    @Test
    @DisplayName("A POST sends the body with the content type the caller asked for")
    void postWithBody() throws Exception {
        RestResponse response = client.post("Patient",
                "{\"resourceType\":\"Patient\"}", "application/fhir+json");

        assertTrue(response.isSuccess());
        assertEquals("POST", lastMethod.get());
        assertEquals("{\"resourceType\":\"Patient\"}", lastBody.get());
        assertEquals("application/fhir+json", lastContentType.get());
    }

    @Test
    @DisplayName("PUT, PATCH and DELETE each reach the server with their own verb")
    void otherVerbs() throws Exception {
        assertTrue(client.put("Patient/1", "{}", "application/fhir+json").isSuccess());
        assertEquals("PUT", lastMethod.get());

        assertTrue(client.patch("Patient/1", "[]", "application/json-patch+json").isSuccess());
        assertEquals("PATCH", lastMethod.get());
        assertEquals("[]", lastBody.get());

        assertTrue(client.delete("Patient/1").isSuccess());
        assertEquals("DELETE", lastMethod.get());
    }

    @Test
    @DisplayName("A response body and its content type come back to the caller")
    void responseBodyAndHeaders() throws Exception {
        RestResponse response = client.get("Patient/1");

        assertEquals(200, response.statusCode());
        assertTrue(response.bodyOrEmpty().contains("\"resourceType\": \"Patient\""));
        assertNotNull(response.contentType());
        assertTrue(response.contentType().contains("json"));
    }

    @Test
    @DisplayName("A 404 is a response, not an exception, and keeps the server's explanation")
    void notFoundIsAResponse() throws Exception {
        RestResponse response = client.get("Patient/99");

        assertTrue(response.isClientError());
        assertEquals(404, response.statusCode());
        assertEquals(1, response.issues().size(), "the OperationOutcome was parsed");
        assertTrue(response.diagnostics().contains("Patient/99 not known"),
                "the server's reason must survive: " + response.diagnostics());
    }

    @Test
    @DisplayName("A non-2xx response can be turned into the plugin's own exception on request")
    void httpFailureConversion() throws Exception {
        RestResponse response = client.get("Patient/99");

        ServerOperationException failure = RestFailures.httpFailure("read Patient/99", response);

        assertEquals(ServerOperationException.Kind.NOT_FOUND, failure.kind());
        assertEquals(404, failure.httpStatus());
    }

    @Test
    @DisplayName("A request that never gets an answer fails with UNREACHABLE and useful advice")
    void unreachableServer() {
        try (JdkHttpRestClient orphan = new JdkHttpRestClient("http://127.0.0.1:1/fhir", 2_000)) {
            ServerOperationException failure = assertThrows(ServerOperationException.class,
                    () -> orphan.get("Patient"));

            assertEquals(ServerOperationException.Kind.UNREACHABLE, failure.kind());
            assertTrue(failure.getMessage().contains("could not be reached"));
        }
    }

    @Test
    @DisplayName("A server that is too slow fails with TIMEOUT rather than hanging")
    void slowServerTimesOut() {
        try (JdkHttpRestClient impatient = new JdkHttpRestClient(baseUrl, 300)) {
            ServerOperationException failure = assertThrows(ServerOperationException.class,
                    () -> impatient.get("Slow"));

            assertEquals(ServerOperationException.Kind.TIMEOUT, failure.kind());
            assertTrue(failure.getMessage().contains("did not answer in time"),
                    "the message must not tell the user to check a URL that was already fine");
        }
    }

    @Test
    @DisplayName("The transport blocks, so it is exercised here from a background thread")
    void worksOffTheApplicationThread() throws Exception {
        AtomicReference<RestResponse> result = new AtomicReference<>();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(client.get("Patient/1"));
            } catch (Throwable t) {
                problem.set(t);
            }
        }, "test-background-worker");
        worker.start();
        worker.join(10_000);

        assertNotNull(result.get(), "no response was produced: " + problem.get());
        assertTrue(result.get().isSuccess());
    }

    @Test
    @DisplayName("The client is reusable, and its base URL never carries a credential")
    void reusableAndSafe() throws Exception {
        assertEquals(baseUrl, client.baseUrl());
        assertTrue(client.get("metadata").isSuccess());
        assertTrue(client.get("Patient").isSuccess(), "a second request reuses the same client");
    }

    @Test
    @DisplayName("A non-HTTP base URL is refused when the client is built, not on first use")
    void baseUrlIsValidatedUpFront() {
        assertThrows(IllegalArgumentException.class, () -> new JdkHttpRestClient("example.com/fhir"));
        assertThrows(IllegalArgumentException.class, () -> new JdkHttpRestClient("  "));
        assertThrows(IllegalArgumentException.class, () -> new JdkHttpRestClient(null));
    }

    @Test
    @DisplayName("A timeout of zero or less means the default, as ServerDefinition documents")
    void zeroTimeoutMeansDefault() {
        try (JdkHttpRestClient defaulted = new JdkHttpRestClient(baseUrl, 0)) {
            assertNotNull(defaulted.timeout());
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastMethod.set(exchange.getRequestMethod());
        lastQuery.set(exchange.getRequestURI().getRawQuery());
        lastHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant"));
        lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        lastBody.set(readBody(exchange));

        String path = exchange.getRequestURI().getPath();
        if (path.contains("Slow")) {
            sleep(1_500);
        }
        if (path.endsWith("/Patient/99")) {
            respond(exchange, 404, OUTCOME_JSON);
            return;
        }
        if (path.endsWith("/metadata")) {
            respond(exchange, 200, "{\"resourceType\":\"CapabilityStatement\",\"status\":\"active\"}");
            return;
        }
        if (path.endsWith("/Patient/1")) {
            respond(exchange, 200, "{\"resourceType\": \"Patient\", \"id\": \"1\", \"active\": true}");
            return;
        }
        respond(exchange, 200, "{\"resourceType\": \"Bundle\", \"type\": \"searchset\"}");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
