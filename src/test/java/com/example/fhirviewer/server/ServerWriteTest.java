package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

/**
 * Tests the write verbs (create, update, delete) against a tiny canned HTTP server.
 *
 * <p>No Internet access is needed; everything runs on localhost. The canned server
 * records the method and the {@code If-Match} header so the conditional-write contract
 * can be asserted rather than assumed.</p>
 */
public class ServerWriteTest {

    private static final String PATIENT_JSON = "{ \"resourceType\": \"Patient\", \"id\": \"example-1\", "
            + "\"meta\": { \"versionId\": \"2\" }, "
            + "\"name\": [ { \"family\": \"Server\", \"given\": [ \"Sam\" ] } ] }";

    private HttpServer server;
    private String baseUrl;
    private StandardFhirRestPlugin plugin;
    private ServerSession session;
    private ServerDefinition definition;
    private AtomicReference<String> lastMethod;
    private AtomicReference<String> lastIfMatch;

    @BeforeEach
    void startServer() throws IOException {
        lastMethod = new AtomicReference<>();
        lastIfMatch = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
        plugin = new StandardFhirRestPlugin(FhirContext.forR4());
        definition = ServerDefinition.named("Canned", baseUrl).build();
        session = new ServerSession(definition, AnonymousServerAuthentication.INSTANCE);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("create POSTs and returns the id the server assigned")
    void createReturnsAssignedId() throws Exception {
        Patient patient = new Patient();
        patient.addName().setFamily("New");

        ServerWriteResult result = plugin.create(session, patient);

        assertTrue(result.created());
        assertEquals("new-id", result.resourceId());
        assertEquals("1", result.versionId());
        assertEquals("POST", lastMethod.get());
    }

    @Test
    @DisplayName("update PUTs to the resource address and sends If-Match when a version is known")
    void updateSendsIfMatch() throws Exception {
        Patient patient = new Patient();
        patient.setId("example-1");
        ServerOrigin origin = ServerOrigin.of("standard-rest", baseUrl, "Patient", "example-1", "2");

        ServerWriteResult result = plugin.update(session, patient, origin);

        assertFalse(result.created());
        assertEquals("example-1", result.resourceId());
        assertEquals("PUT", lastMethod.get());
        assertEquals("W/2", lastIfMatch.get(), "a known version must be sent as If-Match");
    }

    @Test
    @DisplayName("update omits If-Match when the server never reported a version")
    void updateWithoutVersionSendsNoHeader() throws Exception {
        Patient patient = new Patient();
        patient.setId("example-1");
        ServerOrigin saved = new ServerOrigin("standard-rest", baseUrl, "Patient", "example-1", null);

        plugin.update(session, patient, saved);

        assertEquals("PUT", lastMethod.get());
        assertNull(lastIfMatch.get(), "no version means no conditional write to claim");
    }

    @Test
    @DisplayName("delete DELETEs the resource and reports a removed origin")
    void deleteRemovesResource() throws Exception {
        ServerOrigin origin = ServerOrigin.of("standard-rest", baseUrl, "Patient", "example-1", "2");

        plugin.delete(session, origin);

        assertEquals("DELETE", lastMethod.get());
        assertEquals("W/2", lastIfMatch.get());
    }

    @Test
    @DisplayName("update refuses an unsaved origin, telling the caller to create instead")
    void updateRefusesUnsavedOrigin() {
        Patient patient = new Patient();
        ServerOrigin origin = ServerOrigin.unsaved("standard-rest", baseUrl, "Patient");

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.update(session, patient, origin));
        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
    }

    @Test
    @DisplayName("The standard plugin advertises write support")
    void advertisesWriteSupport() {
        assertTrue(plugin.supportsWrite());
    }

    @Test
    @DisplayName("A read-only plugin refuses writes with UNSUPPORTED, not a generic error")
    void readOnlyPluginRefusesWrites() {
        FhirServerPlugin readOnly = new ReadOnlyPlugin();
        ServerSession readOnlySession = new ServerSession(
                ServerDefinition.named("RO", baseUrl).pluginId("read-only").build(),
                AnonymousServerAuthentication.INSTANCE);

        assertFalse(readOnly.supportsWrite());

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> readOnly.create(readOnlySession, new Patient()));
        assertEquals(ServerOperationException.Kind.UNSUPPORTED, failure.kind());
        assertTrue(failure.getMessage().contains("create"),
                "the message should name the verb: " + failure.getMessage());

        assertThrows(ServerOperationException.class,
                () -> readOnly.update(readOnlySession, new Patient(), null));
        assertThrows(ServerOperationException.class,
                () -> readOnly.delete(readOnlySession, null));
    }

    @Test
    @DisplayName("A write to the wrong server is refused before any request is made")
    void serviceRefusesCrossServerWrite() throws Exception {
        FhirServerService service = new FhirServerService();
        service.registry().register(plugin);

        ServerOrigin fromElsewhere = ServerOrigin.of("standard-rest", "http://other.example.com/fhir",
                "Patient", "example-1", "2");

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> service.update(definition, new Patient(), fromElsewhere));
        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
        assertTrue(failure.getMessage().contains("other.example.com"),
                "the message should name the origin server: " + failure.getMessage());
        assertNull(lastMethod.get(), "nothing should have been sent to the server");
    }

    @Test
    @DisplayName("The origin rebases onto the id and version the server returned")
    void originRebasesAfterWrite() {
        ServerOrigin unsaved = ServerOrigin.unsaved("firely", baseUrl, "Patient");
        assertFalse(unsaved.isSaved());

        ServerOrigin rebased = unsaved.rebased(ServerWriteResult.created("new-id", "7"));

        assertTrue(rebased.isSaved());
        assertEquals("new-id", rebased.resourceId());
        assertEquals("7", rebased.versionId());
        assertTrue(rebased.hasVersion());
        assertEquals("Patient", rebased.resourceType(), "a write never renames the type");
    }

    @Test
    @DisplayName("Writing to a server with no plugin is a clear failure")
    void unknownServerIsRefused() {
        FhirServerService service = new FhirServerService();
        service.registry().register(plugin);

        ServerDefinition unsupported = ServerDefinition.named("Nope", baseUrl)
                .pluginId("not-registered").build();

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> service.create(unsupported, new Patient()));
        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
    }

    @Test
    @DisplayName("A 412 from the server surfaces as a CONFLICT, not a generic error")
    void preconditionFailureIsAConflict() {
        Patient patient = new Patient();
        patient.setId("stale");
        // The canned server answers 412 for this address.
        ServerOrigin origin = ServerOrigin.of("standard-rest", baseUrl, "Patient", "stale", "9");

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.update(session, patient, origin));

        assertEquals(ServerOperationException.Kind.CONFLICT, failure.kind(),
                "a stale write must be reported as a conflict, not as a server error");
    }

    /** A plugin that advertises no write support, to prove the UNSUPPORTED path. */
    private static final class ReadOnlyPlugin implements FhirServerPlugin {

        @Override
        public String id() {
            return "read-only";
        }

        @Override
        public String displayName() {
            return "Read Only";
        }

        @Override
        public String description() {
            return "A plugin that cannot write.";
        }

        @Override
        public List<String> supportedFhirVersions() {
            return List.of("R4");
        }

        @Override
        public boolean supports(FhirServerConfiguration configuration) {
            return configuration != null && id().equals(configuration.pluginId());
        }

        @Override
        public ServerCapabilities capabilities(ServerSession session) {
            return ServerCapabilities.empty();
        }

        @Override
        public ConnectionResult testConnection(ServerSession session) {
            return ConnectionResult.unreachable("Read only.");
        }

        @Override
        public SearchResultPage search(ServerSession session, SearchRequest request) {
            return SearchResultPage.empty();
        }

        @Override
        public SearchResultPage nextPage(ServerSession session, SearchRequest request, String pageToken) {
            return SearchResultPage.empty();
        }

        @Override
        public org.hl7.fhir.instance.model.api.IBaseResource read(ServerSession session,
                String resourceType, String resourceId) {
            return null;
        }

        @Override
        public boolean supportsWrite() {
            return false;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        lastMethod.set(method);
        lastIfMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
        // Drain the request body so the client is not left waiting on an unread stream.
        try (java.io.InputStream in = exchange.getRequestBody()) {
            while (in.read() != -1) {
                // discard
            }
        }
        String path = exchange.getRequestURI().getPath();
        String body;
        int status = 200;
        if (path.contains("/Patient/stale")) {
            // A conditional write against a version the server no longer has.
            status = 412;
            body = outcome("version conflict");
        } else if (path.contains("/Patient/does-not-exist")) {
            status = 404;
            body = outcome("not found");
        } else if ("POST".equals(method)) {
            // A create: the server assigns the id and the first version.
            status = 201;
            exchange.getResponseHeaders().set("Location",
                    baseUrl + "/Patient/new-id/_history/1");
            body = PATIENT_JSON.replace("\"id\": \"example-1\"", "\"id\": \"new-id\"")
                    .replace("\"versionId\": \"2\"", "\"versionId\": \"1\"");
        } else if ("DELETE".equals(method)) {
            status = 204;
            body = "";
        } else if ("PUT".equals(method)) {
            body = "";
        } else if (path.endsWith("/metadata")) {
            body = "{ \"resourceType\": \"CapabilityStatement\", \"status\": \"active\", "
                    + "\"date\": \"2024-01-01\", \"kind\": \"instance\", \"fhirVersion\": \"4.0.1\", "
                    + "\"format\": [ \"json\" ], \"rest\": [ { \"mode\": \"server\", \"resource\": [ "
                    + "{ \"type\": \"Patient\" } ] } ] }";
        } else if (path.endsWith("/Patient/example-1")) {
            body = PATIENT_JSON;
        } else {
            status = 404;
            body = outcome("unknown");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        // A 204 carries no body, and sendResponseHeaders rejects a positive length for it.
        if (status == 204 || bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String outcome(String diagnostics) {
        return "{ \"resourceType\": \"OperationOutcome\", \"issue\": [ { \"severity\": \"error\", "
                + "\"diagnostics\": \"" + diagnostics + "\" } ] }";
    }
}

