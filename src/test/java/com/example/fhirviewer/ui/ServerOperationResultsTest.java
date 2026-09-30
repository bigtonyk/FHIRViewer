package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.FhirServerPluginRegistry;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.ServerDefinition;
import com.example.fhirviewer.server.ServerOperationInvocation;
import com.example.fhirviewer.server.ServerOperationResult;

/**
 * The claim of plan section 7, tested: whatever an operation answers with, the UI knows
 * which of the existing views to put it in.
 *
 * <p>Run through the real transport against a local HTTP stub rather than through a
 * hand-built result. {@code ServerOperationResult}'s factory is package-private precisely so
 * that a result cannot be conjured up outside the code that produces it, and a test that
 * constructed one anyway would be testing a shape the transport never emits. Going over the
 * wire also means the classification is proven against the content type, the status and the
 * body exactly as a server sends them.</p>
 *
 * <p>Every case here is a shape a real vendor endpoint returns. The point is that the
 * classification is driven by what actually arrived rather than by what the operation
 * claimed it would return, because a caller cannot know in advance which of these it is
 * about to get — that is the whole reason the result type is structured the way it is.</p>
 */
class ServerOperationResultsTest {

    private StubServer server;
    private FhirServerService service;
    private FhirServerConfiguration configuration;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubServer();
        server.start();
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(new ShapePlugin());
        service = new FhirServerService(registry);
        configuration = ServerDefinition.named("Shapes", server.baseUrl())
                .pluginId(ShapePlugin.PLUGIN_ID)
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    /** Runs an operation against the stub and classifies what came back. */
    private ServerOperationResults.Presentation run(String operationId, int status,
            String contentType, String body) {
        server.answerWith(status, contentType, body);
        return ServerOperationResults.classify(call(operationId));
    }

    /** Runs an operation and returns the raw result, for the summary assertions. */
    private ServerOperationResult call(String operationId) {
        try {
            ServerOperationResult result = service.executeOperation(configuration,
                    ServerOperationInvocation.of(operationId));
            assertNotNull(result, "The operation '" + operationId + "' produced no result");
            return result;
        } catch (com.example.fhirviewer.server.ServerOperationException e) {
            throw new AssertionError("The operation '" + operationId + "' failed: "
                    + e.getMessage(), e);
        }
    }

    @Test
    @DisplayName("A single FHIR resource is shown in the resource viewer")
    void showsAFhirResource() {
        ServerOperationResults.Presentation presentation = run("patient", 200,
                "application/fhir+json", Shapes.PATIENT);
        assertEquals(ServerOperationResults.Kind.FHIR_RESOURCE, presentation.kind());
        assertEquals("Patient", presentation.resource().fhirType());
    }

    /** A Bundle goes to the tree UI rather than the single-resource pane. */
    @Test
    @DisplayName("A Bundle is shown in the resource and tree UI")
    void showsABundle() {
        ServerOperationResults.Presentation presentation = run("bundle", 200,
                "application/fhir+json", Shapes.BUNDLE);
        assertEquals(ServerOperationResults.Kind.BUNDLE, presentation.kind());
        assertEquals("Bundle", presentation.resource().fhirType());
    }

    /** An OperationOutcome is a diagnostic, not a resource to browse. */
    @Test
    @DisplayName("An OperationOutcome is shown as a diagnostic")
    void showsAnOperationOutcome() {
        ServerOperationResults.Presentation presentation = run("outcome", 400,
                "application/fhir+json", Shapes.OUTCOME);
        assertEquals(ServerOperationResults.Kind.OPERATION_OUTCOME, presentation.kind());
        assertFalse(presentation.issues().isEmpty(), "The issues were not parsed");
    }

    /** A vendor JSON document that is not FHIR still reaches the JSON viewer. */
    @Test
    @DisplayName("A non-FHIR JSON document is shown in the JSON viewer")
    void showsJson() {
        ServerOperationResults.Presentation presentation = run("vendor-json", 200,
                "application/json", "{\"jobs\":[],\"status\":\"idle\"}");
        assertEquals(ServerOperationResults.Kind.JSON, presentation.kind());
        assertTrue(presentation.bodyOrEmpty().contains("\"status\""));
    }

    @Test
    @DisplayName("An XML document is shown in the XML viewer")
    void showsXml() {
        ServerOperationResults.Presentation presentation = run("vendor-xml", 200,
                "application/xml", "<report><status>idle</status></report>");
        assertEquals(ServerOperationResults.Kind.XML, presentation.kind());
    }

    /** A log extract has to survive as text, not be mangled by a JSON pane. */
    @Test
    @DisplayName("A plain text response is shown as text")
    void showsText() {
        ServerOperationResults.Presentation presentation = run("log", 200,
                "text/plain", "2026-01-01 INFO started\n2026-01-01 WARN slow query");
        assertEquals(ServerOperationResults.Kind.TEXT, presentation.kind());
        assertTrue(presentation.bodyOrEmpty().contains("WARN slow query"));
    }

    /** A 204 has nothing to show, and saying so beats showing an empty pane. */
    @Test
    @DisplayName("An answer with no body shows no body")
    void showsEmpty() {
        assertEquals(ServerOperationResults.Kind.EMPTY,
                run("log", 204, null, null).kind());
    }

    /**
     * A mislabelled body must still land somewhere sensible. This is the common real-world
     * case: a vendor endpoint that omits or misstates Content-Type on a JSON body.
     */
    @Test
    @DisplayName("A JSON or XML body from an under-declared operation is still recognised")
    void detectsShapeWithoutAContentType() {
        assertEquals(ServerOperationResults.Kind.JSON,
                run("untyped", 200, "application/octet-stream", "{\"a\":1}").kind());
        assertEquals(ServerOperationResults.Kind.XML,
                run("untyped", 200, "application/octet-stream", "<a/>").kind());
    }

    /**
     * An explicit text content type is honoured over the body's shape. A log extract whose
     * first line happens to be a JSON fragment is still a log extract, and rendering it in
     * the JSON pane would be the wrong call even though the two answers look alike.
     */
    @Test
    @DisplayName("An explicit text content type is honoured over the body's shape")
    void textContentTypeWinsOverBodyShape() {
        assertEquals(ServerOperationResults.Kind.TEXT,
                run("log", 200, "text/plain", "{\"looks\":\"like json\"}").kind());
    }

    /** A refusal is a result, and its status and the server's words are both reported. */
    @Test
    @DisplayName("A refusal is reported with its status and the server's own message")
    void reportsARefusal() {
        server.answerWith(409, "application/fhir+json", Shapes.OUTCOME);
        ServerOperationResult result = call("outcome");
        String summary = ServerOperationResults.summary(result);

        assertFalse(result.isSuccess(), "A 409 was treated as a success");
        assertTrue(summary.contains("409"), "The status is missing from: " + summary);
        assertTrue(summary.contains("No such patient"),
                "The server's own message is missing from: " + summary);
    }

    /** The diagnostics list is what an OperationOutcome pane actually renders. */
    @Test
    @DisplayName("Diagnostics are listed for display")
    void listsDiagnostics() {
        server.answerWith(400, "application/fhir+json", Shapes.OUTCOME);
        List<String> lines = ServerOperationResults.diagnostics(call("outcome"));

        assertFalse(lines.isEmpty(), "No diagnostics were produced");
        assertTrue(String.join("\n", lines).contains("No such patient"));
    }

    /** A null result must not make a dialog throw while it is drawing itself. */
    @Test
    @DisplayName("A missing result is classified as empty rather than crashing")
    void handlesNullResult() {
        assertEquals(ServerOperationResults.Kind.EMPTY,
                ServerOperationResults.classify(null).kind());
        assertNotNull(ServerOperationResults.summary(null));
    }

    /** The summary must not print the body: it can be a whole export. */
    @Test
    @DisplayName("Neither the summary nor toString echoes the body")
    void summaryDoesNotEchoTheBody() {
        server.answerWith(200, "application/json", "{\"secret\":\"patient data\"}");
        ServerOperationResult result = call("vendor-json");
        assertFalse(ServerOperationResults.summary(result).contains("patient data"));
        assertFalse(result.toString().contains("patient data"));
    }

    /** The bodies under test, kept together so the expectations read clearly. */
    static final class Shapes {

        static final String PATIENT =
                "{\"resourceType\":\"Patient\",\"id\":\"123\",\"name\":[{\"family\":\"Smith\"}]}";
        static final String BUNDLE =
                "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":1,"
                        + "\"entry\":[{\"resource\":{\"resourceType\":\"Patient\",\"id\":\"1\"}}]}";
        static final String OUTCOME =
                "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                        + "\"code\":\"not-found\",\"diagnostics\":\"No such patient\"}]}";

        private Shapes() {
        }
    }
}
