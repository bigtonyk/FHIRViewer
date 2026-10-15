package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.server.ServerOperationException;
import com.spiraleyes.fhirviewer.server.StandardFhirRestPlugin;

/**
 * Tests {@link SmartFhirAccess} against a local canned HTTP server: the bearer token
 * reaches the intended FHIR request, a granted patient comes back, refusals surface as
 * clear exceptions, and a logged-out or expired session is refused before a socket
 * opens. No Internet access is needed; everything runs on localhost.
 */
class SmartFhirAccessTest {

    private static final String PATIENT_JSON =
            "{\"resourceType\":\"Patient\",\"id\":\"example-1\",\"name\":[{\"family\":\"Smith\",\"given\":[\"John\"]}]}";

    private static final String CAPABILITY_JSON =
            "{\"resourceType\":\"CapabilityStatement\",\"status\":\"active\",\"date\":\"2024-01-01\","
                    + "\"kind\":\"instance\",\"fhirVersion\":\"4.0.1\",\"format\":[\"json\"],"
                    + "\"rest\":[{\"mode\":\"server\",\"resource\":[{\"type\":\"Patient\"}]}]}";

    private static final String OPERATION_OUTCOME_404 =
            "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                    + "\"diagnostics\":\"not found\"}]}";

    private HttpServer server;
    private String baseUrl;
    private SmartFhirAccess access;
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(new StandardFhirRestPlugin(FhirContext.forR4()));
        access = new SmartFhirAccess(registry);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private SmartAuthorizationSession session(String accessToken, String patientId) {
        SmartTokenResponse tokens = new SmartTokenResponse(
                accessToken, "Bearer", 3600L, "patient/*.read", patientId,
                null, null, null);
        return new SmartAuthorizationSession(baseUrl, tokens, null);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastPath.set(exchange.getRequestURI().getPath());
        String path = exchange.getRequestURI().getPath();
        int status = 200;
        String body;
        if (path.endsWith("/metadata")) {
            body = CAPABILITY_JSON;
        } else if (path.endsWith("/Patient/example-1")) {
            body = PATIENT_JSON;
        } else if (path.endsWith("/Patient/does-not-exist")) {
            status = 404;
            body = OPERATION_OUTCOME_404;
        } else if (path.endsWith("/Patient/forbidden")) {
            status = 403;
            body = OPERATION_OUTCOME_404;
        } else if (path.endsWith("/Patient/unauthorized")) {
            status = 401;
            body = OPERATION_OUTCOME_404;
        } else {
            status = 404;
            body = OPERATION_OUTCOME_404;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    @DisplayName("An authorized read sends the bearer token and returns the patient")
    void authorizedReadReturnsPatient() throws Exception {
        SmartAuthorizationSession session = session("tok-alice", "example-1");

        Optional<IBaseResource> result = access.patient(session);

        assertTrue(result.isPresent());
        IBaseResource resource = result.get();
        assertEquals("Patient", resource.fhirType());
        assertNotNull(resource.getIdElement().getIdPart());
        assertEquals("example-1", resource.getIdElement().getIdPart());
        assertTrue(lastAuthorization.get().startsWith("Bearer "),
                "expected a bearer header, got: " + lastAuthorization.get());
        assertTrue(lastAuthorization.get().contains("tok-alice"),
                "the session's token must be attached: " + lastAuthorization.get());
        assertTrue(lastPath.get().endsWith("/Patient/example-1"),
                "the read must target the session's patient: " + lastPath.get());
    }

    @Test
    @DisplayName("A patient the server does not serve yields empty, not a failure")
    void unknownPatientReturnsEmpty() throws Exception {
        SmartAuthorizationSession session = session("tok-bob", "does-not-exist");

        Optional<IBaseResource> result = access.patient(session);

        assertTrue(result.isEmpty());
        assertTrue(lastAuthorization.get().contains("tok-bob"));
    }

    @Test
    @DisplayName("A rejected token (HTTP 401) surfaces as UNAUTHORIZED")
    void rejectedTokenIsUnauthorized() {
        SmartAuthorizationSession session = session("tok-revoked", "unauthorized");

        ServerOperationException failure = assertThrows(
                ServerOperationException.class, () -> access.patient(session));
        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, failure.kind());
    }

    @Test
    @DisplayName("A token the scopes do not cover (HTTP 403) surfaces as FORBIDDEN")
    void forbiddenTokenIsForbidden() {
        SmartAuthorizationSession session = session("tok-narrow", "forbidden");

        ServerOperationException failure = assertThrows(
                ServerOperationException.class, () -> access.patient(session));
        assertEquals(ServerOperationException.Kind.FORBIDDEN, failure.kind());
    }

    @Test
    @DisplayName("A logged-out session is refused before a socket opens")
    void loggedOutSessionIsRefused() {
        SmartAuthorizationSession session = session("tok-any", "example-1");
        session.logout();

        ServerOperationException failure = assertThrows(
                ServerOperationException.class, () -> access.patient(session));
        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, failure.kind());
        assertNull(lastAuthorization.get(), "no request may be sent after logout");
    }

    @Test
    @DisplayName("An expired session is refused before a socket opens")
    void expiredSessionIsRefused() {
        SmartTokenResponse expired = new SmartTokenResponse(
                "tok-dead", "Bearer", -10L, null, "example-1", null, null, null);
        SmartAuthorizationSession session =
                new SmartAuthorizationSession(baseUrl, expired, null);

        ServerOperationException failure = assertThrows(
                ServerOperationException.class, () -> access.patient(session));
        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, failure.kind());
        assertNull(lastAuthorization.get(), "no request may be sent with an expired token");
    }

    @Test
    @DisplayName("A launch with no patient context returns empty without opening a socket")
    void noPatientContextReturnsEmpty() throws Exception {
        SmartTokenResponse tokens = new SmartTokenResponse(
                "tok-identity", "Bearer", 3600L, "openid profile", null,
                null, null, null);
        SmartAuthorizationSession session =
                new SmartAuthorizationSession(baseUrl, tokens, null);

        Optional<IBaseResource> result = access.patient(session);

        assertTrue(result.isEmpty());
        assertNull(lastAuthorization.get(), "no request may be sent when there is no patient");
    }

    @Test
    @DisplayName("Two sessions carry different tokens and never share authorization")
    void sessionsAreIsolated() throws Exception {
        SmartAuthorizationSession first = session("tok-alpha", "example-1");
        SmartAuthorizationSession second = session("tok-beta", "example-1");

        assertDoesNotThrow(() -> access.patient(first));
        String firstHeader = lastAuthorization.get();
        assertTrue(firstHeader.contains("tok-alpha"), firstHeader);

        assertDoesNotThrow(() -> access.patient(second));
        String secondHeader = lastAuthorization.get();
        assertTrue(secondHeader.contains("tok-beta"), secondHeader);
        assertFalse(firstHeader.equals(secondHeader));
    }

    @Test
    @DisplayName("The token is sent only to the session's own server")
    void tokenStaysOnTheIntendedServer() throws Exception {
        SmartAuthorizationSession session = session("tok-only-me", "example-1");

        access.patient(session);

        assertTrue(lastPath.get().endsWith("/Patient/example-1"),
                "the read must target the session's patient: " + lastPath.get());
        assertTrue(lastAuthorization.get().contains("tok-only-me"));
    }
}