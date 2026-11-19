package com.spiraleyes.fhirviewer.service.scripting.api;

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
import java.util.concurrent.atomic.AtomicReference;

import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.server.PatchFormat;
import com.spiraleyes.fhirviewer.server.StandardFhirRestPlugin;
import com.spiraleyes.fhirviewer.server.smart.SmartAuthorizationSession;
import com.spiraleyes.fhirviewer.server.smart.SmartFhirAccess;
import com.spiraleyes.fhirviewer.server.smart.SmartTokenResponse;

/**
 * Tests {@link SmartApi} writes end to end against a local canned server: the session's
 * bearer is what authorizes the request, the console's grant decides whether a script may
 * try, every attempt reaches the journal, and a refused or cancelled write sends nothing.
 */
class SmartApiWriteTest {

    private static final String OPERATION_OUTCOME =
            "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                    + "\"diagnostics\":\"not allowed\"}]}";

    /** HAPI reads the capability statement before its first write, so one must be served. */
    private static final String CAPABILITY_JSON =
            "{\"resourceType\":\"CapabilityStatement\",\"status\":\"active\","
                    + "\"date\":\"2024-01-01\",\"kind\":\"instance\",\"fhirVersion\":\"4.0.1\","
                    + "\"format\":[\"json\"],\"rest\":[{\"mode\":\"server\","
                    + "\"resource\":[{\"type\":\"Patient\"}]}]}";

    private HttpServer server;
    private String baseUrl;
    private SmartAuthorizationSession session;
    private SmartApi api;

    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastIfMatch = new AtomicReference<>();
    private final AtomicReference<String> lastIfNoneMatch = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile boolean rejectWrites;
    private volatile boolean hideBeforeImage;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(new StandardFhirRestPlugin(FhirContext.forR4()));
        SmartFhirAccess access = new SmartFhirAccess(registry);
        session = new SmartAuthorizationSession(baseUrl, new SmartTokenResponse(
                "tok-write", "Bearer", 3600L, "patient/*.write", "example-1", null, null, null),
                null);
        api = new SmartApi(session, access);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastMethod.set(exchange.getRequestMethod());
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastIfMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
        lastIfNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        int status;
        String body = "";
        if (exchange.getRequestURI().getPath().endsWith("/metadata")) {
            status = 200;
            body = CAPABILITY_JSON;
        } else if ("GET".equals(exchange.getRequestMethod())) {
            if (hideBeforeImage) {
                status = 500;
                body = OPERATION_OUTCOME;
            } else {
                status = 200;
                body = "{\"resourceType\":\"Patient\",\"id\":\"example-1\","
                        + "\"meta\":{\"versionId\":\"1\"},\"active\":true}";
            }
        } else if (rejectWrites) {
            status = 403;
            body = OPERATION_OUTCOME;
        } else if ("POST".equals(exchange.getRequestMethod())) {
            status = 201;
            exchange.getResponseHeaders().set("Location", baseUrl + "/Patient/new-1");
            exchange.getResponseHeaders().set("ETag", "W/\"1\"");
            body = "{\"resourceType\":\"Patient\",\"id\":\"new-1\","
                    + "\"meta\":{\"versionId\":\"1\"}}";
        } else if ("PUT".equals(exchange.getRequestMethod())) {
            status = 200;
            exchange.getResponseHeaders().set("ETag", "W/\"2\"");
            body = "{\"resourceType\":\"Patient\",\"id\":\"example-1\","
                    + "\"meta\":{\"versionId\":\"2\"}}";
        } else if ("PATCH".equals(exchange.getRequestMethod())) {
            status = 200;
            body = "{\"resourceType\":\"Patient\",\"id\":\"example-1\","
                    + "\"meta\":{\"versionId\":\"3\"}}";
        } else if ("DELETE".equals(exchange.getRequestMethod())) {
            status = 204;
        } else {
            status = 200;
            body = "{}";
        }
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private SmartApi api(WritePolicy policy) {
        api.useWritePolicy(policy);
        return api;
    }

    private static Patient patient(String id) {
        Patient patient = new Patient();
        patient.setId(id);
        return patient;
    }

    @Test
    @DisplayName("A create through the session sends the bearer and is journalled")
    void createIsSentWithTheBearerAndJournalled() {
        WriteJournal journal = new WriteJournal();
        SmartApi writing = api(WritePolicy.enabled(null, null, journal, 0, null, null));

        assertEquals("new-1", writing.create(new Patient()).resourceId());

        assertEquals("POST", lastMethod.get());
        assertTrue(lastAuthorization.get().contains("tok-write"),
                "the session's token must authorize the create: " + lastAuthorization.get());
        assertTrue(lastBody.get().contains("\"resourceType\":\"Patient\""),
                "the resource itself must be the request body: " + lastBody.get());
        assertEquals("SMART session", journal.entries().get(0).serverName());
        assertEquals(baseUrl, journal.entries().get(0).serverBaseUrl());
        assertEquals(WriteJournal.Status.SUCCEEDED, journal.entries().get(0).status());
        assertEquals("new-1", journal.entries().get(0).resourceId());
    }

    @Test
    @DisplayName("An update sends the version it knows as If-Match")
    void updateSendsIfMatch() {
        Patient patient = new Patient();
        patient.setId("example-1");
        patient.getMeta().setVersionId("7");
        SmartApi writing = api(WritePolicy.enabled());

        writing.update(patient);

        assertEquals("PUT", lastMethod.get());
        assertTrue(lastIfMatch.get() != null && lastIfMatch.get().contains("7"),
                "a known version must travel as If-Match: " + lastIfMatch.get());
    }

    @Test
    @DisplayName("A patch and a delete reach the session's server")
    void patchAndDeleteReachTheServer() {
        SmartApi writing = api(WritePolicy.enabled());

        writing.patch("Patient", "example-1", "{}", PatchFormat.JSON_PATCH);
        assertEquals("PATCH", lastMethod.get());

        writing.delete("Patient", "example-1");
        assertEquals("DELETE", lastMethod.get());
    }

    @Test
    @DisplayName("With writes off a script is refused before any request")
    void writesOffSendsNothing() {
        SmartApi readOnly = api(WritePolicy.READ_ONLY);

        assertFalse(readOnly.isWriteEnabled());
        assertThrows(WriteDeniedException.class, () -> readOnly.create(new Patient()));
        assertNull(lastMethod.get(), "no request may be sent with writes off");
    }

    @Test
    @DisplayName("A logged-out session is refused before any request")
    void loggedOutSessionSendsNothing() {
        session.logout();
        SmartApi writing = api(WritePolicy.enabled());

        assertThrows(ScriptApiException.class, () -> writing.create(new Patient()));
        assertNull(lastMethod.get(), "no request may be sent without a session");
    }

    @Test
    @DisplayName("A cancelled delete sends nothing and is journalled as cancelled")
    void cancelledDeleteSendsNothing() {
        WriteJournal journal = new WriteJournal();
        SmartApi writing = api(WritePolicy.enabled(summary -> false, null, journal, 0, null, null));

        assertThrows(WriteDeniedException.class, () -> writing.delete("Patient", "example-1"));

        assertNull(lastMethod.get(), "cancelling must prevent the request");
        assertEquals(WriteJournal.Status.CANCELLED, journal.entries().get(0).status());
    }

    @Test
    @DisplayName("A write the session's scopes refuse is reported and journalled as failed")
    void refusedWriteIsReported() {
        rejectWrites = true;
        WriteJournal journal = new WriteJournal();
        SmartApi writing = api(WritePolicy.enabled(null, null, journal, 0, null, null));

        ScriptApiException failure = assertThrows(ScriptApiException.class,
                () -> writing.create(new Patient()));

        assertTrue(failure.getMessage().contains("through the SMART session"),
                failure.getMessage());
        assertEquals(WriteJournal.Status.FAILED, journal.entries().get(0).status());
        assertEquals("FORBIDDEN", journal.entries().get(0).detail());
    }

    @Test
    @DisplayName("An update captures the prior resource and undo is offered for it")
    void updateCapturesBeforeImageAndUndoIsOffered() {
        WriteJournal journal = new WriteJournal();
        SmartApi writing = api(WritePolicy.enabled(null, null, journal, 0, null, null));

        writing.update(patient("example-1"));

        WriteJournal.Entry entry = journal.entries().get(0);
        WriteJournal.Undo plan = journal.undoFor(entry).orElseThrow();
        assertTrue(plan.canUndo(), "an update with a before-image must be undoable");
        assertEquals("example-1", plan.before().getIdElement().getIdPart());
        assertEquals(entry, journal.lastUndoable().orElseThrow(),
                "the console must offer undo for a SMART write that captured a before-image");
    }

    @Test
    @DisplayName("Undo of a SMART update restores the before-image through the session")
    void undoRestoresTheSmartBeforeImage() {
        WriteJournal journal = new WriteJournal();
        SmartApi writing = api(WritePolicy.enabled(null, null, journal, 0, null, null));
        writing.update(patient("example-1"));
        WriteJournal.Undo plan = journal.undoFor(journal.entries().get(0)).orElseThrow();

        writing.restoreBeforeImage(baseUrl, plan);

        assertEquals("PUT", lastMethod.get());
        assertTrue(lastAuthorization.get().contains("tok-write"),
                "the reversal must carry the session's bearer: " + lastAuthorization.get());
        assertTrue(lastIfMatch.get() != null && lastIfMatch.get().contains("2"),
                "the undo must name the version the reverted write produced: "
                        + lastIfMatch.get());
        assertTrue(lastBody.get().contains("\"active\":true"),
                "the body must be the captured before-image: " + lastBody.get());
    }

    @Test
    @DisplayName("No undo is offered, and none is sent, when no before-image could be captured")
    void undoIsRefusedWithoutABeforeImage() {
        hideBeforeImage = true;
        WriteJournal journal = new WriteJournal();
        SmartApi writing = api(WritePolicy.enabled(null, null, journal, 0, null, null));

        writing.update(patient("example-1"));

        WriteJournal.Entry entry = journal.entries().get(0);
        assertEquals(WriteJournal.Status.SUCCEEDED, entry.status(),
                "an unreadable prior resource must not block the write");
        assertFalse(journal.undoFor(entry).orElseThrow().canUndo(),
                "there is nothing to restore");
        assertTrue(journal.lastUndoable().isEmpty(),
                "Undo Last Write must not offer a write with no before-image");

        ScriptApiException refused = assertThrows(ScriptApiException.class,
                () -> writing.restoreBeforeImage(baseUrl, new WriteJournal.Undo("update",
                        "Patient", "example-1", "2", null)));
        assertTrue(refused.getMessage().contains("No before-image"), refused.getMessage());
    }

    @Test
    @DisplayName("Undoing a SMART delete writes nothing when the id has been taken again")
    void undoOfDeleteRefusesWhenTheIdIsOccupied() {
        WriteJournal journal = new WriteJournal();
        SmartApi writing = api(WritePolicy.enabled(null, null, journal, 0, null, null));

        writing.delete("Patient", "example-1");
        WriteJournal.Undo plan = journal.undoFor(journal.entries().get(0)).orElseThrow();
        assertEquals("delete", plan.operation());
        assertNotNull(plan.before(), "a delete must capture the resource it removes");

        ScriptApiException refused = assertThrows(ScriptApiException.class,
                () -> writing.restoreBeforeImage(baseUrl, plan));
        assertTrue(refused.getMessage().contains("exists again"), refused.getMessage());
        assertEquals("GET", lastMethod.get(), "nothing may be written when the id is occupied");
    }

}
