package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.rest.RestMethod;

/**
 * The claim this phase makes, tested: a plugin can expose a vendor REST endpoint, and the
 * application can discover and run it without changing a line of core.
 *
 * <p>{@link MockVendorPlugin} is the plugin, and it is deliberately a stranger — it is not
 * a subclass of anything the application ships, it is not registered in
 * {@code META-INF/services}, and it overrides nothing but the discovery method. Everything
 * else it relies on is inherited. So if these tests pass, the abstraction is real; if they
 * only passed because core knew about a vendor, that knowledge would have to exist in a
 * class this test does not touch, and it does not.</p>
 *
 * <p>Two things are asserted throughout rather than assumed. First, that the request
 * actually carried what the descriptor promised — the verb, the resolved path, the query
 * parameters, the declared header, the body and its content type — because a discovery
 * mechanism that silently drops a parameter is worse than none. Second, that the refusals
 * happen <em>before</em> a request is sent, since a check performed after the round trip
 * has already leaked whatever it was meant to protect.</p>
 */
public class PluginCustomOperationsTest {

    private HttpServerStub server;
    private MockVendorPlugin plugin;
    private ServerDefinition definition;
    private ServerSession session;
    private FhirServerService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new HttpServerStub();
        server.start();
        plugin = new MockVendorPlugin();
        definition = ServerDefinition.named("Mock", server.baseUrl())
                .pluginId(MockVendorPlugin.PLUGIN_ID)
                .build();
        session = new ServerSession(definition, AnonymousServerAuthentication.INSTANCE);

        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(plugin);
        service = new FhirServerService(registry);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    /**
     * A session carrying a bearer token.
     *
     * <p>Used by the tests that run the export operation, which the plugin declares as
     * requiring credentials — so the "it needs authentication" rule is exercised by the
     * same tests that exercise everything else, rather than by a special case.</p>
     */
    private ServerSession authenticatedSession() {
        return new ServerSession(definition, new BearerServerAuthentication("token-123"));
    }

    @Test
    @DisplayName("A plugin's operations are discoverable, with their parameters intact")
    void discoversDeclaredOperations() {
        List<ServerOperation> operations = plugin.availableOperations();

        assertEquals(4, operations.size());

        ServerOperation export = plugin.operation("export-jobs").orElseThrow();
        assertEquals("Export Job", export.displayName());
        assertEquals(RestMethod.POST, export.method());
        assertEquals("admin/export/{jobId}", export.pathTemplate());
        assertEquals(ServerOperation.Category.ADMINISTRATION, export.category());
        assertEquals(ServerOperation.ResultKind.JSON, export.expectedResult());
        assertTrue(export.requiresAuthentication());

        assertEquals(1, export.pathParameters().size());
        assertEquals(2, export.parametersAt(ServerOperationParameter.Location.QUERY).size());
        assertEquals("jobId", export.pathParameters().get(0).name());
        assertTrue(export.parameter("jobId").orElseThrow().isRequired());
    }

    @Test
    @DisplayName("A standard and a vendor operation travel the same discovery list")
    void standardAndVendorOperationsShareOneList() {
        List<ServerOperation> operations = plugin.availableOperations();

        assertTrue(operations.stream()
                        .anyMatch(operation -> operation.category() == ServerOperation.Category.STANDARD),
                "a plugin declares its standard interactions through the same seam as its vendor ones");
        assertTrue(operations.stream()
                        .anyMatch(operation -> operation.category() == ServerOperation.Category.VENDOR
                                || operation.category() == ServerOperation.Category.ADMINISTRATION));
    }

    @Test
    @DisplayName("An unknown id is a clear refusal, not a null")
    void unknownOperationIsRefused() throws Exception {
        assertTrue(plugin.operation("no-such-thing").isEmpty());

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.executeOperation(session, ServerOperationInvocation.of("no-such-thing")));
        assertEquals(ServerOperationException.Kind.UNSUPPORTED, failure.kind());
        assertTrue(failure.getMessage().contains("no-such-thing"),
                "the message names what was asked for: " + failure.getMessage());
    }

    @Test
    @DisplayName("A vendor operation runs end to end, and the core learned nothing about it")
    void runsAVendorOperationEndToEnd() throws Exception {
        ServerOperationResult result = plugin.executeOperation(authenticatedSession(),
                ServerOperationInvocation.invocation("export-jobs")
                        .pathParameter("jobId", "job-42")
                        .queryParameter("format", "ndjson")
                        .queryParameter("since", "2026-01-01")
                        .headerParameter("X-Report-Mode", "full")
                        .body("{ \"trigger\": true }")
                        .build());

        assertTrue(result.isSuccess());
        assertEquals(200, result.statusCode());
        assertEquals(ServerOperation.ResultKind.JSON, result.kind());
        assertEquals("{\"jobId\":\"job-42\",\"format\":\"ndjson\"}", result.bodyOrEmpty());

        // What went on the wire, which is the part a discovery mechanism can quietly get wrong.
        assertEquals("POST", server.lastMethod());
        assertEquals("/fhir/admin/export/job-42", server.lastPath(),
                "the path parameter was substituted into the template, not sent as a literal");
        assertEquals("format=ndjson&since=2026-01-01", server.lastQuery());
        assertEquals("full", server.lastHeader("X-Report-Mode"),
                "a header the operation declared is sent, because the plugin asked for it");
        assertEquals("application/json", server.lastContentType(),
                "the declared body content type is the one the server is asked to read");
        assertTrue(server.lastBody().contains("trigger"), "the body arrives unchanged");
    }

    @Test
    @DisplayName("A path parameter is percent-encoded, so it cannot escape the path the plugin declared")
    void pathParametersCannotEscapeThePath() throws Exception {
        plugin.executeOperation(authenticatedSession(),
                ServerOperationInvocation.invocation("export-jobs")
                        .pathParameter("jobId", "../../admin/secret")
                        .body("{}")
                        .build());

        // The dots are encoded too, not just the slashes. A value of ".." left intact would
        // walk up a directory even though every slash around it were escaped, so the
        // encoder excludes "." from the unreserved set for exactly this reason.
        assertEquals("/fhir/admin/export/%2E%2E%2F%2E%2E%2Fadmin%2Fsecret", server.lastPath(),
                "one opaque segment: the caller cannot walk out of the declared path");
    }

    @Test
    @DisplayName("A standard FHIR operation declared by a plugin runs through the same mechanism")
    void runsAStandardOperationThroughTheSameSeam() throws Exception {
        server.answerWith("application/fhir+json",
                "{ \"resourceType\": \"OperationOutcome\", \"issue\": [ "
                        + "{ \"severity\": \"information\", \"code\": \"informational\","
                        + " \"diagnostics\": \"All issues were detected\" } ] }");

        ServerOperationResult result = plugin.executeOperation(session,
                ServerOperationInvocation.invocation("validate-patient")
                        .body("{ \"resourceType\": \"Patient\", \"id\": \"p1\" }")
                        .build());

        assertTrue(result.isSuccess());
        assertEquals(ServerOperation.ResultKind.OPERATION_OUTCOME, result.kind(),
                "an OperationOutcome is recognised from what it is, not from what was declared");
        assertTrue(result.resource().isPresent(), "it parsed as a FHIR resource");
        assertEquals("POST", server.lastMethod());
        assertEquals("/fhir/Patient/$validate", server.lastPath());
    }

    @Test
    @DisplayName("A plain-text answer comes back as text, unparsed and intact")
    void returnsTextUnchanged() throws Exception {
        server.answerWith("text/plain", "2026-01-01 started\n2026-01-01 finished\n");

        ServerOperationResult result = plugin.executeOperation(session,
                ServerOperationInvocation.of("server-log"));

        assertEquals(ServerOperation.ResultKind.TEXT, result.kind());
        assertTrue(result.bodyOrEmpty().contains("finished"));
        assertTrue(result.resource().isEmpty(), "a log extract is not a FHIR resource");
    }

    @Test
    @DisplayName("A vendor Bundle is recognised as one, and its resources are available")
    void recognisesAVendorBundle() throws Exception {
        server.answerWith("application/fhir+json",
                "{ \"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [ "
                        + "{ \"resource\": { \"resourceType\": \"Patient\", \"id\": \"p1\" } } ] }");

        ServerOperationResult result = plugin.executeOperation(session,
                ServerOperationInvocation.of("server-log"));

        assertEquals(ServerOperation.ResultKind.BUNDLE, result.kind());
        assertTrue(result.resource().isPresent());
        assertEquals("Bundle", result.resource().orElseThrow().fhirType());
    }

    @Test
    @DisplayName("An operation that needs credentials is refused locally, before any request")
    void refusesAnUnauthenticatedCallerWithoutContactingTheServer() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.executeOperation(session, ServerOperationInvocation.invocation("export-jobs")
                        .pathParameter("jobId", "job-1")
                        .body("{}")
                        .build()));

        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, failure.kind());
        assertTrue(server.requestCount() == 0,
                "nothing was sent: the refusal happened before a socket was opened");
    }

    @Test
    @DisplayName("The same operation runs for a caller that does have credentials")
    void runsForAnAuthenticatedCaller() throws Exception {
        ServerOperationResult result = plugin.executeOperation(authenticatedSession(),
                ServerOperationInvocation.invocation("export-jobs")
                        .pathParameter("jobId", "job-1")
                        .body("{}")
                        .build());

        assertTrue(result.isSuccess());
        assertEquals("Bearer token-123", server.lastHeader("Authorization"),
                "the credential comes from the session, exactly as for every other request");
    }

    @Test
    @DisplayName("A caller cannot set a credential header, however it spells it")
    void refusesCallerSuppliedCredentialHeaders() {
        for (String name : List.of("Authorization", "authorization", "X-Api-Key", "Cookie")) {
            ServerOperationException failure = assertThrows(ServerOperationException.class,
                    () -> plugin.executeOperation(session,
                            ServerOperationInvocation.invocation("cache-status")
                                    .headerParameter(name, "Bearer stolen")
                                    .build()),
                    "the header " + name + " must not be settable by a caller");
            assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
        }
        assertEquals(0, server.requestCount(), "no attempt reached the server");
    }

    @Test
    @DisplayName("A header the operation never declared is refused, not quietly sent")
    void refusesUndeclaredHeaders() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.executeOperation(session,
                        ServerOperationInvocation.invocation("cache-status")
                                .headerParameter("X-Evil", "1")
                                .build()));

        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
        assertTrue(failure.getMessage().contains("X-Evil"));
        assertEquals(0, server.requestCount());
    }

    @Test
    @DisplayName("A header value carrying a line break is refused, because it is request splitting")
    void refusesHeaderInjection() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.executeOperation(session,
                        ServerOperationInvocation.invocation("cache-status")
                                .headerParameter("X-Report-Mode", "full\r\nX-Injected: yes")
                                .build()));

        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
        assertEquals(0, server.requestCount());
    }

    @Test
    @DisplayName("A required path parameter left empty is a clear message, not a malformed URL")
    void refusesAMissingRequiredParameter() {
        // Authenticated, so the refusal under test is the missing field rather than the
        // credential check, which has its own test above.
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.executeOperation(authenticatedSession(),
                        ServerOperationInvocation.invocation("export-jobs").body("{}").build()));

        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
        assertTrue(failure.getMessage().contains("jobId"),
                "the message names the field the user has to fill in: " + failure.getMessage());
        assertEquals(0, server.requestCount());
    }

    @Test
    @DisplayName("A body the operation did not ask for is refused rather than sent and ignored")
    void refusesAnUnwantedBody() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.executeOperation(session, ServerOperationInvocation.invocation("server-log")
                        .body("surprise")
                        .build()));

        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
        assertEquals(0, server.requestCount());
    }

    @Test
    @DisplayName("A missing required body is refused, rather than sent empty")
    void refusesAMissingRequiredBody() {
        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.executeOperation(session, ServerOperationInvocation.of("validate-patient")));

        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
        assertTrue(failure.getMessage().contains("body"));
        assertEquals(0, server.requestCount());
    }

    @Test
    @DisplayName("A refusal is a result carrying the server's own words, not an exception")
    void aRefusalIsAResultNotAnException() throws Exception {
        server.answerWithStatus(403, "application/fhir+json",
                "{ \"resourceType\": \"OperationOutcome\", \"issue\": [ { \"severity\": \"error\","
                        + " \"code\": \"forbidden\", \"diagnostics\": \"The export job is not yours.\" } ] }");

        ServerOperationResult result = plugin.executeOperation(session,
                ServerOperationInvocation.of("server-log"));

        assertFalse(result.isSuccess());
        assertEquals(403, result.statusCode());
        assertEquals(ServerOperation.ResultKind.OPERATION_OUTCOME, result.kind());
        assertTrue(result.hasIssues());
        assertTrue(result.diagnostics().contains("not yours"),
                "the server's sentence survives, which is the whole point of keeping the body: "
                        + result.diagnostics());
    }

    @Test
    @DisplayName("A refusal that is not FHIR still keeps its body, so the caller can show it")
    void keepsANonFhirRefusalBody() throws Exception {
        server.answerWithStatus(500, "text/plain", "the export worker died at 03:14");

        ServerOperationResult result = plugin.executeOperation(session,
                ServerOperationInvocation.of("server-log"));

        assertFalse(result.isSuccess());
        assertEquals(ServerOperation.ResultKind.TEXT, result.kind());
        assertTrue(result.diagnostics().contains("export worker died"),
                "a plain-text error is the only diagnostic there is: " + result.diagnostics());
    }

    @Test
    @DisplayName("An HTML error page is reported by its status, not as a parse failure")
    void toleratesAnHtmlErrorPage() throws Exception {
        server.answerWithStatus(502, "text/html", "<html><body>Bad Gateway</body></html>");

        ServerOperationResult result = plugin.executeOperation(session,
                ServerOperationInvocation.of("server-log"));

        assertEquals(502, result.statusCode());
        assertTrue(result.resource().isEmpty());
        assertEquals(ServerOperation.ResultKind.TEXT, result.kind());
    }

    @Test
    @DisplayName("The service exposes discovery and execution, so a UI needs no plugin")
    void theServiceIsTheOnlyThingAUILikesToCall() throws Exception {
        List<ServerOperation> declared = service.availableOperations(definition);
        assertEquals(4, declared.size());

        // Anonymous: the credential-requiring operation is not offered, because the plugin
        // narrowed its own list rather than the service deciding on its behalf.
        assertEquals(3, service.supportedOperations(definition).size());

        ServerOperationResult result = service.executeOperation(definition,
                ServerOperationInvocation.of("server-log"));
        assertTrue(result.isSuccess());

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> service.executeOperation(definition, ServerOperationInvocation.of("export-jobs")));
        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, failure.kind());
    }

    @Test
    @DisplayName("A query parameter with several values is sent once per value, in order")
    void sendsRepeatableQueryParameters() throws Exception {
        // "format" is a declared query parameter, so a caller may legitimately supply it
        // more than once and expect all the values to reach the server.
        plugin.executeOperation(authenticatedSession(),
                ServerOperationInvocation.invocation("export-jobs")
                        .pathParameter("jobId", "job-1")
                        .queryParameter("format", "ndjson")
                        .queryParameter("format", "csv")
                        .body("{}")
                        .build());

        assertEquals("format=ndjson&format=csv", server.lastQuery(),
                "both values, in the order the caller gave them");
    }

    @Test
    @DisplayName("Nothing the invocation carried appears in its own log line")
    void doesNotLogValuesOrBodies() {
        ServerOperationInvocation invocation = ServerOperationInvocation.invocation("export-jobs")
                .pathParameter("jobId", "job-42")
                .queryParameter("format", "ndjson")
                .body("{ \"patient\": \"Jane Doe\" }")
                .build();

        String described = invocation.toString();
        assertFalse(described.contains("job-42"), described);
        assertFalse(described.contains("ndjson"), described);
        assertFalse(described.contains("Jane Doe"), described);
        assertTrue(described.contains("1 path"), "the shape is still described: " + described);
    }

    /**
     * A canned server on localhost that records what it was sent.
     *
     * <p>Its default answer is the small JSON document the vendor-operation tests expect,
     * echoing the two values that prove the path and query reached the server. Recording
     * the request matters as much as answering it: these tests are largely about whether
     * the descriptor was honoured on the wire, and a mechanism that dropped a parameter
     * would otherwise look identical to one that worked.
     */
    private static final class HttpServerStub {

        private final com.sun.net.httpserver.HttpServer http;
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<String> path = new AtomicReference<>();
        private final AtomicReference<String> query = new AtomicReference<>();
        private final AtomicReference<String> contentType = new AtomicReference<>();
        private final AtomicReference<String> body = new AtomicReference<>();
        private final Map<String, String> headers = new ConcurrentHashMap<>();
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<String> answer = new AtomicReference<>(
                "{\"jobId\":\"job-42\",\"format\":\"ndjson\"}");
        private final AtomicReference<String> answerType = new AtomicReference<>("application/json");
        private final AtomicInteger answerStatus = new AtomicInteger(200);

        private HttpServerStub() throws IOException {
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

        private String baseUrl() {
            return "http://127.0.0.1:" + http.getAddress().getPort() + "/fhir";
        }

        private void answerWith(String contentType, String body) {
            answerWithStatus(200, contentType, body);
        }

        private void answerWithStatus(int status, String contentType, String body) {
            answerStatus.set(status);
            answerType.set(contentType);
            answer.set(body);
        }

        private String lastMethod() {
            return method.get();
        }

        private String lastPath() {
            return path.get();
        }

        private String lastQuery() {
            return query.get();
        }

        private String lastContentType() {
            return contentType.get();
        }

        private String lastBody() {
            return body.get();
        }

        private String lastHeader(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        private int requestCount() {
            return requests.get();
        }

        /** Records the request, then answers with whatever the test last configured. */
        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            method.set(exchange.getRequestMethod());
            // The *raw* path and query, not the decoded ones. A path parameter the client
            // percent-encoded arrives here as %2F; reading the decoded form would hide
            // whether the encoding happened at all, which is the whole point of the
            // traversal test.
            path.set(exchange.getRequestURI().getRawPath());
            query.set(exchange.getRequestURI().getRawQuery());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
                }
            });
            try (InputStream in = exchange.getRequestBody()) {
                // Drained even when a test does not assert on it, so the client is never
                // left waiting on a stream nobody read.
                body.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", answerType.get());
            exchange.sendResponseHeaders(answerStatus.get(), bytes.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}





