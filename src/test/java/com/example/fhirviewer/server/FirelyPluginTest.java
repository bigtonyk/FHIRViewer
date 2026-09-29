package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.rest.RestMethod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

/**
 * Tests the Firely plugin: that it identifies Firely Server, stays distinct from
 * Smile CDR, and inherits a working REST flow from the standard layer.
 *
 * <p>Everything runs against a tiny canned HTTP server on localhost, so no Internet
 * access and no real Firely Server deployment is needed.</p>
 */
public class FirelyPluginTest {

    private static final String PATIENT_JSON = "{ \"resourceType\": \"Patient\", \"id\": \"example-1\", "
            + "\"name\": [ { \"family\": \"Server\", \"given\": [ \"Sam\" ] } ] }";

    /** A CapabilityStatement shaped like one Firely Server reports in its software name. */
    private static final String FIRELY_CAPABILITY_JSON = "{ \"resourceType\": \"CapabilityStatement\", "
            + "\"status\": \"active\", \"date\": \"2024-01-01\", \"kind\": \"instance\", \"fhirVersion\": \"4.0.1\", "
            + "\"format\": [ \"json\" ], \"software\": { \"name\": \"Firely Server\", \"version\": \"5.4.0\" }, "
            + "\"rest\": [ { \"mode\": \"server\", \"resource\": [ "
            + "{ \"type\": \"Patient\" }, { \"type\": \"Observation\" } ] } ] }";

    /** A CapabilityStatement shaped like one Smile CDR reports, which is NOT Firely Server. */
    private static final String SMILE_CAPABILITY_JSON = "{ \"resourceType\": \"CapabilityStatement\", "
            + "\"status\": \"active\", \"date\": \"2024-01-01\", \"kind\": \"instance\", \"fhirVersion\": \"4.0.1\", "
            + "\"format\": [ \"json\" ], \"software\": { \"name\": \"Smile CDR\", \"version\": \"1.0.0\" }, "
            + "\"rest\": [ { \"mode\": \"server\", \"resource\": [ { \"type\": \"Patient\" } ] } ] }";

    private HttpServer server;
    private String baseUrl;
    private FirelyPlugin plugin;
    private ServerSession session;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
        plugin = new FirelyPlugin(FhirContext.forR4());
        session = new ServerSession(
                ServerDefinition.forFirely("Canned Firely", baseUrl).build(),
                AnonymousServerAuthentication.INSTANCE);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("The plugin identifies itself as Firely Server and advertises R4")
    void advertisesItself() {
        assertEquals("firely", plugin.id());
        assertEquals("Firely Server", plugin.displayName());
        assertFalse(plugin.description().isBlank());
        assertEquals(List.of("R4"), plugin.supportedFhirVersions());
    }

    @Test
    @DisplayName("Capabilities, search and read work through the delegated REST layer")
    void delegatesToTheStandardLayer() throws Exception {
        ServerCapabilities capabilities = plugin.capabilities(session);
        assertEquals("4.0.1", capabilities.fhirVersion());
        assertEquals(List.of("Patient", "Observation"), capabilities.resourceTypes());
        assertTrue(capabilities instanceof FirelyPlugin.FirelyServerCapabilities);

        SearchRequest request = new SearchRequest("Patient", List.of(), 20);
        SearchResultPage page = plugin.search(session, request);
        assertEquals(1, page.resources().size());
        assertTrue(plugin.read(session, "Patient", "example-1") instanceof Patient);
    }

    @Test
    @DisplayName("A Firely server is reachable and the message names Firely")
    void testConnectionSucceeds() {
        ConnectionResult result = plugin.testConnection(session);
        assertTrue(result.isReachable());
        assertTrue(result.message().contains("Firely"),
                "expected the Firely name in the message but got: " + result.message());
    }

    @Test
    @DisplayName("Firely detection matches Firely and does not match Smile CDR")
    void detectionSeparatesFirelyFromSmileCdr() {
        FhirContext context = FhirContext.forR4();
        CapabilityStatement firely =
                (CapabilityStatement) context.newJsonParser().parseResource(FIRELY_CAPABILITY_JSON);
        CapabilityStatement smile =
                (CapabilityStatement) context.newJsonParser().parseResource(SMILE_CAPABILITY_JSON);

        assertTrue(FirelyPlugin.looksLikeFirely(firely), "a Firely statement should be detected");
        assertFalse(FirelyPlugin.looksLikeFirely(smile), "a Smile CDR statement is not Firely Server");
        assertFalse(FirelyPlugin.looksLikeFirely(null), "null is never detected");

        // Each plugin must recognise its own server and reject the other one: they are
        // separate products, so neither heuristic may match the other's statement.
        assertTrue(SmileCdrPlugin.looksLikeSmileCdr(smile), "Smile CDR should detect its own statement");
        assertFalse(SmileCdrPlugin.looksLikeSmileCdr(firely), "Firely Server is not Smile CDR");
    }

    @Test
    @DisplayName("The plugin only supports Firely definitions, never Smile or generic ones")
    void supportsOnlyItsOwnConfigurations() {
        assertTrue(plugin.supports(session.server()));

        ServerDefinition smile = ServerDefinition.forSmileCdr("Smile", baseUrl).build();
        assertFalse(plugin.supports(smile), "Firely must not claim a Smile CDR definition");

        ServerDefinition generic = ServerDefinition.named("Generic", baseUrl).build();
        assertFalse(plugin.supports(generic), "a generic definition belongs to the standard plugin");
        assertFalse(plugin.supports(null));
    }

    @Test
    @DisplayName("Firely and Smile are separate plugins the registry keeps apart")
    void registryKeepsFirelyAndSmileApart() {
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        FirelyPlugin firely = new FirelyPlugin(FhirContext.forR4());
        SmileCdrPlugin smile = new SmileCdrPlugin(FhirContext.forR4());
        registry.register(firely);
        registry.register(smile);

        assertNotEquals(firely.id(), smile.id());
        assertEquals(firely, registry.pluginFor(ServerDefinition.forFirely("F", baseUrl).build()));
        assertEquals(smile, registry.pluginFor(ServerDefinition.forSmileCdr("S", baseUrl).build()));
    }

    @Test
    @DisplayName("A Firely configuration is auto-detected and carries no secret by default")
    void configurationDefaultsAreSecretFree() {
        FirelyConfiguration configuration = new FirelyConfiguration() {
            @Override
            public String name() {
                return "F";
            }

            @Override
            public String baseUrl() {
                return baseUrl;
            }

            @Override
            public String fhirVersion() {
                return "R4";
            }

            @Override
            public int timeoutMillis() {
                return 0;
            }

            @Override
            public Map<String, String> extraHeaders() {
                return Map.of();
            }
        };

        assertEquals("firely", configuration.pluginId());
        assertFalse(configuration.apiKeyAuthentication(), "API key mode is off by default");
        assertTrue(plugin.supports(configuration), "a FirelyConfiguration is auto-detected");

        ServerDefinition definition = ServerDefinition.forFirely("F", baseUrl).build();
        assertEquals(FirelyPlugin.PLUGIN_ID, definition.pluginId());
        assertTrue(definition.extraHeaders().isEmpty(),
                "extra headers must not carry credentials by default");
    }

    @Test
    @DisplayName("Firely declares its administration operations")
    void declaresAdministrationOperations() {
        List<ServerOperation> operations = plugin.availableOperations();

        assertFalse(operations.isEmpty(),
                "a Firely server has an administration API; declaring nothing leaves the"
                        + " operation screen empty for every real user");

        for (ServerOperation operation : operations) {
            assertEquals(ServerOperation.Category.ADMINISTRATION, operation.category(),
                    operation.id() + " is an administration operation");
            assertTrue(operation.requiresAuthentication(),
                    operation.id() + " runs against the administration API and needs credentials");
        }
    }

    @Test
    @DisplayName("The administration branch is 'administration', not 'admin'")
    void usesTheRealAdministrationPath() {
        // Checked explicitly because this is the one thing that would make every declared
        // operation fail while looking entirely correct. Verified against a live Firely
        // Server: /administration/SearchParameter answers a search, while /admin does not
        // exist. Nothing else about the declaration could catch a wrong branch.
        for (ServerOperation operation : plugin.availableOperations()) {
            String path = operation.pathTemplate();
            assertTrue(path.startsWith("administration/"),
                    operation.id() + " should be under administration/, but was: " + path);
            assertFalse(path.startsWith("admin/"),
                    operation.id() + " uses the wrong branch: " + path);
        }
    }

    @Test
    @DisplayName("The conformance searches cover the types Firely documents")
    void coversTheDocumentedConformanceTypes() {
        // The list is from Firely's own administration API documentation. A type added to
        // Firely's support but missing here would simply be absent from the screen, which
        // is exactly the silent gap this phase is closing.
        for (String type : List.of("SearchParameter", "StructureDefinition", "ValueSet",
                "CodeSystem", "CompartmentDefinition", "StructureMap", "ConceptMap",
                "Library", "Measure", "Questionnaire", "Subscription")) {
            assertTrue(plugin.operation("list-" + type).isPresent(),
                    "no operation declared for the " + type + " administration search");
        }
    }

    @Test
    @DisplayName("The maintenance operations are POSTs that return an outcome")
    void maintenanceOperationsArePost() {
        for (String id : List.of("$reindex", "$reindex-all", "$preload", "$reset")) {
            ServerOperation operation = plugin.operation(id).orElseThrow(
                    () -> new AssertionError("no operation declared for " + id));
            assertEquals(RestMethod.POST, operation.method(), id + " is invoked with POST");
            assertEquals(ServerOperation.ResultKind.OPERATION_OUTCOME, operation.expectedResult(),
                    id + " answers with an OperationOutcome");
        }
    }

    @Test
    @DisplayName("The conformance searches are read-only")
    void conformanceSearchesAreReadOnly() {
        // The administration API does allow writing these types. They are not offered,
        // because changing a conformance resource changes what a server validates against,
        // and that should not sit behind a one-click generated form.
        for (ServerOperation operation : plugin.availableOperations()) {
            if (operation.id().startsWith("list-")) {
                assertEquals(RestMethod.GET, operation.method(), operation.id() + " must be a read");
                assertEquals(ServerOperation.ResultKind.BUNDLE, operation.expectedResult(),
                        operation.id() + " is a FHIR search and answers with a Bundle");
            }
        }
    }

    @Test
    @DisplayName("An anonymous Firely session still offers the operations")
    void anonymousSessionStillSeesTheOperations() {
        // Reproduces what a user does on a public Firely server: connect, open the
        // operation screen, and be shown nothing. The list is filtered on nothing, so an
        // anonymous session must see the same operations an authenticated one does -
        // whether the server then refuses them is the server's business, and is reported
        // per operation rather than by hiding the list.
        ServerSession anonymous = new ServerSession(
                ServerDefinition.forFirely("Public", "https://server.fire.ly").build(),
                AnonymousServerAuthentication.INSTANCE);

        List<ServerOperation> shown = plugin.supportedOperations(anonymous);

        assertFalse(shown.isEmpty(),
                "an anonymous session must still see the declared operations; an empty list"
                        + " here is indistinguishable from the plugin declaring nothing");
        assertEquals(plugin.availableOperations().size(), shown.size());
    }

    @Test
    @DisplayName("The service returns the Firely operations for a Firely server")
    void theServiceReturnsThem() {
        // The same thing one layer up, through the only entry point the UI uses. A plugin
        // whose declaration is right but which the registry does not resolve would still
        // leave the screen empty, and this is the seam where that would show.
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(plugin);
        FhirServerService service = new FhirServerService(registry);

        List<ServerOperation> shown = service.supportedOperations(
                ServerDefinition.forFirely("Public", "https://server.fire.ly").build());

        assertFalse(shown.isEmpty(),
                "Run Server Operation lists service.supportedOperations, so an empty result"
                        + " here is an empty screen");
    }

    @Test
    @DisplayName("An unreachable server fails cleanly instead of throwing")
    void unreachableServerFails() {
        ServerSession dead = new ServerSession(
                ServerDefinition.forFirely("Dead", "http://127.0.0.1:1/fhir").build(),
                AnonymousServerAuthentication.INSTANCE);
        ConnectionResult result = plugin.testConnection(dead);
        assertFalse(result.isReachable());
        assertNull(result.capabilities());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body;
        int status = 200;
        if (path.endsWith("/metadata")) {
            body = FIRELY_CAPABILITY_JSON;
        } else if (path.endsWith("/Patient/example-1")) {
            body = PATIENT_JSON;
        } else if (path.endsWith("/Patient")) {
            body = "{ \"resourceType\": \"Bundle\", \"type\": \"searchset\", \"total\": 1, "
                    + "\"entry\": [ { \"resource\": " + PATIENT_JSON + " } ] }";
        } else {
            status = 404;
            body = "{ \"resourceType\": \"OperationOutcome\", \"issue\": [ "
                    + "{ \"severity\": \"error\", \"diagnostics\": \"unknown\" } ] }";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
