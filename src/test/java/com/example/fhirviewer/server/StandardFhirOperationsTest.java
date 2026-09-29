package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

/**
 * The standard operations Phase 4 added, against a canned server on localhost: PATCH,
 * {@code $}-operations, following a paging link the server itself supplied, and the
 * {@code OperationOutcome} a refusal carries.
 *
 * <p>Several of these guard decisions rather than features. The patch body must travel
 * under the content type of the format it was written in, because that content type is
 * what tells the server how to read it. A page must be fetched from the URL the server
 * gave us, not one this application assembled. And a refusal must carry the server's
 * own sentence, which is the thing the previous error mapping threw away.
 */
public class StandardFhirOperationsTest {

    private static final String PATIENT_JSON = "{ \"resourceType\": \"Patient\", \"id\": \"p1\","
            + " \"meta\": { \"versionId\": \"3\" },"
            + " \"name\": [ { \"family\": \"Patched\", \"given\": [ \"Pat\" ] } ] }";

    private HttpServer server;
    private String baseUrl;
    private StandardFhirRestPlugin plugin;
    private ServerDefinition definition;
    private ServerSession session;

    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastIfMatch = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
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

    private ServerSession sessionWith(int timeoutMillis) {
        return new ServerSession(
                ServerDefinition.named("Canned", baseUrl).timeoutMillis(timeoutMillis).build(),
                AnonymousServerAuthentication.INSTANCE);
    }

    private ServerOrigin originOf(String id, String version) {
        return ServerOrigin.of(StandardFhirRestPlugin.PLUGIN_ID, baseUrl, "Patient", id, version);
    }

    private static String outcome(String code, String diagnostics) {
        return "{ \"resourceType\": \"OperationOutcome\", \"issue\": [ { \"severity\": \"error\","
                + " \"code\": \"" + code + "\", \"diagnostics\": \"" + diagnostics + "\" } ] }";
    }

    @Test
    @DisplayName("A patch travels under the content type of the format it was written in")
    void patchCarriesItsFormatAsTheContentType() throws Exception {
        ServerWriteResult result = plugin.patch(session, originOf("p1", "3"),
                "[ { \"op\": \"replace\", \"path\": \"/name/0/family\", \"value\": \"Patched\" } ]",
                PatchFormat.JSON_PATCH);

        assertEquals("PATCH", lastMethod.get());
        assertEquals("/fhir/Patient/p1", lastPath.get());
        assertEquals("application/json-patch+json", lastContentType.get());
        assertEquals("W/3", lastIfMatch.get(), "the version the patch was computed against is sent back");
        assertTrue(lastBody.get().contains("\"op\""), "the body reaches the server unchanged");
        assertEquals("p1", result.resourceId());
        assertEquals("3", result.versionId(), "the patched resource's version is what the caller rebases on");
        assertTrue(!result.created());
    }

    @Test
    @DisplayName("A merge patch is sent as a merge patch, not as a JSON patch")
    void patchFormatsAreNotInterchangeable() throws Exception {
        plugin.patch(session, originOf("p1", "3"), "{ \"active\": false }",
                PatchFormat.JSON_MERGE_PATCH);

        assertEquals("application/merge-patch+json", lastContentType.get());
    }

    @Test
    @DisplayName("A patch with no version to match is sent unconditionally, not with a stale If-Match")
    void patchWithoutAVersionSendsNoPrecondition() throws Exception {
        ServerWriteResult result = plugin.patch(session, originOf("p1", null),
                "{ \"active\": false }", PatchFormat.JSON_MERGE_PATCH);

        assertNull(lastIfMatch.get());
        assertEquals("3", result.versionId(),
                "with no body back, the version the caller already knew is the honest answer");
    }

    @Test
    @DisplayName("A patch refused because the resource changed is a conflict, not a generic error")
    void conflictingPatchIsAConflict() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.patch(session, originOf("stale", "2"),
                        "{ \"active\": false }", PatchFormat.JSON_MERGE_PATCH));

        assertEquals(ServerOperationException.Kind.CONFLICT, failure.kind());
        assertEquals(412, failure.httpStatus());
        assertTrue(failure.hasDiagnostics());
        assertTrue(failure.diagnostics().contains("changed"), "the server said why: " + failure.diagnostics());
    }

    @Test
    @DisplayName("A server that does not accept PATCH says so through the shared status table")
    void unsupportedPatchIsReportedAsUnsupported() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.patch(session,
                        ServerOrigin.of(StandardFhirRestPlugin.PLUGIN_ID, baseUrl, "Device", "d1", "1"),
                        "[]", PatchFormat.JSON_PATCH));

        assertEquals(ServerOperationException.Kind.UNSUPPORTED, failure.kind());
        assertTrue(failure.diagnostics().contains("PATCH is not supported"),
                "the server's own words survive: " + failure.diagnostics());
    }

    @Test
    @DisplayName("Patching something that was never saved is refused before a request is made")
    void patchingAnUnsavedResourceIsRefused() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.patch(session,
                        ServerOrigin.unsaved(StandardFhirRestPlugin.PLUGIN_ID, baseUrl, "Patient"),
                        "[]", PatchFormat.JSON_PATCH));

        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
        assertTrue(failure.getMessage().contains("create"), "the message must say what to do instead");
    }

    @Test
    @DisplayName("A patch with no body is refused rather than sent as an empty document")
    void anEmptyPatchIsRefused() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.patch(session, originOf("p1", "3"), "  ", PatchFormat.JSON_PATCH));

        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
    }


    @Test
    @DisplayName("An operation with no parameters is a GET; one with parameters posts a Parameters body")
    void operationVerbFollowsTheSpecification() throws Exception {
        plugin.invoke(session, FhirOperationRequest.onType("Patient", "everything"));
        assertEquals("GET", lastMethod.get());
        assertEquals("/fhir/Patient/$everything", lastPath.get());

        plugin.invoke(session, FhirOperationRequest.onType("Patient", "everything",
                Map.of("start", "2020-01-01")));
        assertEquals("POST", lastMethod.get());
        assertTrue(lastBody.get().contains("\"name\""), "parameters are sent as a Parameters resource");
        assertTrue(lastBody.get().contains("2020-01-01"));
    }

    @Test
    @DisplayName("An operation's result is parsed as whatever resource the server returned")
    void operationResultIsAParsedResource() throws Exception {
        IBaseResource result = plugin.invoke(session,
                FhirOperationRequest.onType("Patient", "everything"));

        assertNotNull(result);
        assertEquals("Bundle", result.fhirType());
        Bundle bundle = (Bundle) result;
        assertEquals(1, bundle.getEntry().size());
        assertTrue(bundle.getEntryFirstRep().getResource() instanceof Patient);
    }

    @Test
    @DisplayName("An operation the server does not implement is UNSUPPORTED, not a server error")
    void unsupportedOperationIsReportedAsUnsupported() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.invoke(session, FhirOperationRequest.onType("Patient", "not-real")));

        assertEquals(ServerOperationException.Kind.UNSUPPORTED, failure.kind());
        assertEquals(501, failure.httpStatus());
        assertTrue(failure.diagnostics().contains("not a supported operation"),
                "the server's own words survive: " + failure.diagnostics());
    }

    @Test
    @DisplayName("A refused operation carries the server's OperationOutcome, not just its status")
    void refusedOperationCarriesTheOutcome() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.invoke(session, FhirOperationRequest.onType("Patient", "rejected")));

        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
        assertTrue(failure.hasDiagnostics());
        assertTrue(failure.diagnostics().contains("start is required"));
        assertTrue(failure.getMessage().contains("start is required"),
                "and it reaches the status bar, which only ever shows the message");
    }

    @Test
    @DisplayName("A page is fetched from the URL the server supplied, whatever relation it is")
    void followsAServerSuppliedPagingLink() throws Exception {
        SearchResultPage second = plugin.pageAt(session, baseUrl + "/Patient?_page=2");

        assertEquals("GET", lastMethod.get());
        assertEquals("/fhir/Patient", lastPath.get());
        assertEquals("_page=2", lastQuery.get(),
                "the query the server put in its own link is the query that was sent");
        assertEquals(1, second.resources().size());
        assertEquals("3", ((Patient) second.resources().get(0)).getMeta().getVersionId());
        assertTrue(second.links().hasPrevious(), "a middle page can go back");
    }

    @Test
    @DisplayName("nextPage and pageAt are the same call, so the old name keeps working")
    void nextPageIsPageAt() throws Exception {
        SearchResultPage page = plugin.nextPage(session, null, baseUrl + "/Patient?_page=3");

        assertEquals("_page=3", lastQuery.get());
        assertEquals(1, page.resources().size());
    }

    @Test
    @DisplayName("Asking for a page that does not exist is a clear refusal, not a null")
    void aMissingPageTokenIsRefused() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.pageAt(session, "  "));

        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
    }

    @Test
    @DisplayName("A page the server refuses reports the server's own words")
    void aRefusedPageCarriesTheServersWords() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.pageAt(session, baseUrl + "/Patient?_page=broken"));

        assertEquals(ServerOperationException.Kind.NOT_FOUND, failure.kind());
        assertTrue(failure.diagnostics().contains("No such page"),
                "the generic 'the server returned an error' is exactly what this phase removed");
    }

    @Test
    @DisplayName("The configured deadline reaches the HAPI client, and the default is applied when unset")
    void theConfiguredDeadlineIsApplied() {
        // This is the gap Phase 4 closed: timeoutMillis() sat on the configuration and
        // nothing read it, so every read could block for as long as the server liked.
        assertEquals(750, plugin.effectiveTimeoutMillis(sessionWith(750).server()));
        assertEquals(20_000, plugin.effectiveTimeoutMillis(sessionWith(0).server()),
                "zero means the plugin default, exactly as ServerDefinition documents");
        assertEquals(20_000, plugin.effectiveTimeoutMillis(definition));
        assertEquals(20_000, plugin.effectiveTimeoutMillis(null));
    }


    /**
     * A canned server: enough FHIR to exercise each operation, and nothing more.
     *
     * <p>Everything it answers is fixed, so a test asserting on a failure is asserting
     * on the server's words rather than on a network timing. Requests are recorded
     * because several of these tests are about what went on the wire — the patch content
     * type, the {@code If-Match} version, the operation URL — not only about the result.
     */
    private void handle(HttpExchange exchange) throws IOException {
        lastMethod.set(exchange.getRequestMethod());
        lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        lastIfMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        lastPath.set(path);
        lastQuery.set(query);
        lastBody.set(readBody(exchange));

        String body;
        int status = 200;
        if ("PATCH".equalsIgnoreCase(lastMethod.get())) {
            if (path.endsWith("/Device/d1")) {
                status = 501;
                body = outcome("not-supported", "PATCH is not supported on this server");
            } else if (path.endsWith("/Patient/stale")) {
                status = 412;
                body = outcome("conflict", "The resource changed on the server since it was read.");
            } else {
                // A conditional patch: refuse it when the caller sent a version and it
                // is not the one the server holds, which is the precondition every
                // other write in this plugin also uses.
                String ifMatch = lastIfMatch.get();
                if (ifMatch != null && !"W/3".equals(ifMatch)) {
                    status = 412;
                    body = outcome("conflict", "The resource changed on the server since it was read.");
                } else {
                    body = PATIENT_JSON;
                }
            }
        } else if (path.contains("$everything")) {
            body = searchBundle(baseUrl + "/Patient?_page=1");
        } else if (path.contains("$not-real")) {
            status = 501;
            body = outcome("not-supported", "Unknown operation: not a supported operation");
        } else if (path.contains("$rejected")) {
            status = 400;
            body = outcome("invalid", "The parameter start is required for $rejected");
        } else if (query != null && query.contains("broken")) {
            status = 404;
            body = outcome("not-found", "No such page");
        } else if (path.endsWith("/metadata")) {
            body = "{ \"resourceType\": \"CapabilityStatement\", \"status\": \"active\","
                    + " \"kind\": \"instance\", \"fhirVersion\": \"4.0.1\","
                    + " \"rest\": [ { \"mode\": \"server\", \"resource\": ["
                    + " { \"type\": \"Patient\", \"interaction\": [ { \"code\": \"read\" } ] } ] } ] }";
        } else {
            // Anything else that asks for a page of results gets one.
            body = searchBundle(baseUrl + "/Patient?_page=3");
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** A middle page: self, previous and next, but no last, as most servers send. */
    private String searchBundle(String nextUrl) {
        return "{ \"resourceType\": \"Bundle\", \"type\": \"searchset\", \"total\": 3, \"link\": [ "
                + "{ \"relation\": \"self\", \"url\": \"" + nextUrl + "\" },"
                + "{ \"relation\": \"previous\", \"url\": \"" + baseUrl + "/Patient?_page=1\" },"
                + "{ \"relation\": \"next\", \"url\": \"" + nextUrl + "\" }"
                + " ], \"entry\": [ { \"resource\": " + PATIENT_JSON + " } ] }";
    }

    /**
     * Reads the request body, draining it so the client is not left waiting on a stream
     * nobody read.
     */
    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

