package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

/**
 * Tests the standard REST plugin against a tiny canned HTTP server: metadata, search,
 * paging, read and the error mapping. No Internet access is needed; everything runs on
 * localhost.
 */
public class StandardFhirRestPluginTest {

    private static final String PATIENT_JSON = "{ \"resourceType\": \"Patient\", \"id\": \"example-1\", "
            + "\"name\": [ { \"family\": \"Server\", \"given\": [ \"Sam\" ] } ] }";

    private static final String CAPABILITY_JSON = "{ \"resourceType\": \"CapabilityStatement\", \"status\": \"active\", "
            + "\"date\": \"2024-01-01\", \"kind\": \"instance\", \"fhirVersion\": \"4.0.1\", "
            + "\"format\": [ \"json\" ], "
            + "\"rest\": [ { \"mode\": \"server\", \"resource\": [ "
            + "{ \"type\": \"Patient\" }, { \"type\": \"Observation\" } ] } ] }";

    private static final String SEARCH_JSON = "{ \"resourceType\": \"Bundle\", \"type\": \"searchset\", \"total\": 2, "
            + "\"link\": [ { \"relation\": \"next\", \"url\": \"/NEXT-PAGE/\" } ], "
            + "\"entry\": [ { \"resource\": " + PATIENT_JSON + " } ] }";

    private static final String SEARCH_LAST_JSON = "{ \"resourceType\": \"Bundle\", \"type\": \"searchset\", "
            + "\"entry\": [ { \"resource\": " + PATIENT_JSON + " } ] }";

    private HttpServer server;
    private String baseUrl;
    private StandardFhirRestPlugin plugin;
    private ServerSession session;
    private final java.util.concurrent.atomic.AtomicReference<String> lastAuthorization =
            new java.util.concurrent.atomic.AtomicReference<>();
@Test
    @DisplayName("A plain FHIR server offers the specification's operations, not just bulk")
    void specificationOperationsAreDeclared() {
        // The observation that started Phase 10: a standard server showed exactly two
        // operations, both bulk, and the specification's own were absent from the codebase
        // entirely. The specification defines the type-level and instance-level forms as
        // separate interactions, so both are declared.
        List<String> ids = plugin.availableOperations().stream()
                .map(ServerOperation::id)
                .toList();

        for (String expected : List.of("$validate", "$expand", "$lookup", "$everything",
                "$everything-instance", "$patient", "$patient-instance", "$compartment",
                "$convert", "$vread", "$graph", "$history", "$export-poll-status",
                "$import-poll-status", "$graphql", "$snapshots", "$export", "$import")) {
            assertTrue(ids.contains(expected),
                    "the specification operation " + expected + " is not offered; a standard"
                            + " server offers " + ids);
        }
    }

    @Test
    @DisplayName("The operations that take a resource say so, and require one")
    void bodyRequirementsMatchTheSpecification() {
        // $validate, $expand, $lookup and $convert all POST a resource. Declaring a body as
        // optional would let the screen send a request the server will only refuse, and the
        // user would learn it from a 400 rather than from the form.
        for (ServerOperation operation : plugin.availableOperations()) {
            if (List.of("$validate", "$expand", "$lookup", "$convert", "$import")
                    .contains(operation.id())) {
                assertEquals(ServerOperation.BodyRequirement.REQUIRED,
                        operation.bodyRequirement(),
                        operation.id() + " takes a resource and must require one");
            }
        }
    }

    @Test
    @DisplayName("$search is not offered here, because the search screens already do it")
    void searchIsNotOfferedAsAnOperation() {
        // Deliberate. $search needs parameters this screen's form cannot express well, and
        // the two search screens do it properly. Offering a worse version of a feature that
        // already exists elsewhere is not a gain.
        assertNull(plugin.availableOperations().stream()
                        .filter(operation -> operation.id().contains("$search"))
                        .findFirst()
                        .orElse(null),
                "$search is offered from the operation screen as well as from the two search "
                        + "screens, which do it better");
    }

    @Test
    @DisplayName("The vendor plugins inherit them, and do not narrow the list")
    void vendorPluginsInheritThem() {
        // Declared on the base class precisely so the three plugins cannot drift apart. This
        // is the test that stops a vendor plugin quietly narrowing what the user is offered.
        for (FhirServerPlugin vendor : List.of(new SmileCdrPlugin(), new FirelyPlugin())) {
            List<String> ids = vendor.availableOperations().stream()
                    .map(ServerOperation::id)
                    .toList();
            assertTrue(ids.contains("$validate"),
                    vendor.id() + " does not inherit the specification operations: " + ids);
            assertTrue(ids.contains("$export"),
                    vendor.id() + " lost the bulk operations: " + ids);
        }
    }

    @Test
    @DisplayName("Every declared path parameter is named by its own template")
    void declaredParametersMatchThePathTemplate() {
        // The failure this catches happened while writing them: the descriptor refuses to
        // build when a path names a placeholder that was not declared, or declares a path
        // parameter its template does not name. Neither is visible until the class is
        // initialised, so a mistake takes every test touching the plugin down with it - which
        // is exactly how 80 errors appeared at once.
        for (ServerOperation operation : plugin.availableOperations()) {
            assertNotNull(operation.pathTemplate(), operation.id() + " has no path template");
            for (ServerOperationParameter parameter : operation.parameters()) {
                if (parameter.location() == ServerOperationParameter.Location.PATH) {
                    assertTrue(operation.pathTemplate().contains("{" + parameter.name() + "}"),
                            operation.id() + " declares the path parameter '" + parameter.name()
                                    + "' but its template does not name it: "
                                    + operation.pathTemplate());
                }
            }
        }
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
        plugin = new StandardFhirRestPlugin(FhirContext.forR4());
        ServerDefinition definition = ServerDefinition.named("Canned", baseUrl).build();
        session = new ServerSession(definition, AnonymousServerAuthentication.INSTANCE);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Capabilities, search, paging and read work against a canned server")
    void exercisesTheRestFlow() throws Exception {
        ServerCapabilities capabilities = plugin.capabilities(session);
        assertEquals("4.0.1", capabilities.fhirVersion());
        assertEquals(List.of("Patient", "Observation"), capabilities.resourceTypes());

        SearchRequest request = new SearchRequest("Patient", List.of(new SearchCriterion("family", "Server")), 20);
        SearchResultPage first = plugin.search(session, request);
        assertEquals(1, first.resources().size());
        assertEquals(Integer.valueOf(2), first.total());
        assertTrue(first.hasNextPage());
        assertTrue(first.nextPageToken().contains("page=2"));
        assertEquals("Server", ((Patient) first.resources().get(0)).getNameFirstRep().getFamily());

        SearchResultPage second = plugin.nextPage(session, request, first.nextPageToken());
        assertEquals(1, second.resources().size());
        assertTrue(!second.hasNextPage());

        IBaseResource read = plugin.read(session, "Patient", "example-1");
        assertTrue(read instanceof Patient);
        assertEquals("example-1", read.getIdElement().getIdPart());
    }

    @Test
    @DisplayName("A missing resource surfaces as NOT_FOUND and a dead server as UNREACHABLE")
    void mapsErrors() {
        ServerOperationException missing = org.junit.jupiter.api.Assertions.assertThrows(
                ServerOperationException.class, () -> plugin.read(session, "Patient", "does-not-exist"));
        assertEquals(ServerOperationException.Kind.NOT_FOUND, missing.kind());
        assertEquals(Integer.valueOf(404), missing.httpStatus());

        ServerDefinition down = ServerDefinition.named("Down", "http://127.0.0.1:1/fhir").build();
        ServerSession downSession = new ServerSession(down, AnonymousServerAuthentication.INSTANCE);
        ConnectionResult result = plugin.testConnection(downSession);
        assertTrue(!result.isReachable());
        assertTrue(result.message().toLowerCase(java.util.Locale.ROOT).contains("could not be reached"));
        assertNull(result.capabilities());
    }

    @Test
    @DisplayName("A password saved for a server is sent when the service searches it")
    void savedCredentialsReachTheServer() throws Exception {
        // The end-to-end claim: a password saved in the plugin manager is actually sent
        // with a request. Before this phase the service built every session anonymous,
        // so this header stayed null no matter what had been saved.
        PluginSettingsStore store = new PluginSettingsStore(
                java.nio.file.Files.createTempFile("fhirviewer-credentials", ".properties"));
        try {
            store.save(new PluginSettings(StandardFhirRestPlugin.PLUGIN_ID, baseUrl,
                    "alice", "s3cret"), "pass phrase");

            FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
            registry.register(plugin);
            FhirServerService service = new FhirServerService(registry,
                    new ServerCredentials(store, () -> "pass phrase"));

            lastAuthorization.set(null);
            SearchResultPage page = service.search(session.server(),
                    new SearchRequest("Patient", List.of(), 20));

            assertEquals(1, page.resources().size());
            assertEquals("Basic YWxpY2U6czNjcmV0", lastAuthorization.get(),
                    "the saved password must be sent with the request");
        } finally {
            java.nio.file.Files.deleteIfExists(store.file());
        }
    }

    @Test
    @DisplayName("The service still searches anonymously when nothing is saved")
    void serviceIsAnonymousWithoutSavedCredentials() throws Exception {
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(plugin);
        FhirServerService service = new FhirServerService(registry);

        lastAuthorization.set(null);
        service.search(session.server(), new SearchRequest("Patient", List.of(), 20));

        assertNull(lastAuthorization.get(),
                "a service with no stored credentials must behave exactly as before");
    }

    @Test
    @DisplayName("The plugin advertises itself and supports its own server definitions")
    void advertisesItself() {
        assertEquals("standard-rest", plugin.id());
        assertTrue(!plugin.supportedFhirVersions().isEmpty());
        assertTrue(plugin.supports(session.server()));

        ServerDefinition other = ServerDefinition.named("Other", baseUrl).pluginId("vendor-x").build();
        assertTrue(!plugin.supports(other));

        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(plugin);
        assertEquals(plugin, registry.pluginFor(session.server()));
        assertNotNull(registry.plugins());
    }

    @Test
    @DisplayName("The service delegates to the plugin picked by the registry")
    void serviceDelegates() throws Exception {
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(plugin);
        FhirServerService service = new FhirServerService(registry);

        assertEquals(1, service.plugins().size());
        assertTrue(service.testConnection(session.server()).isReachable());

        SearchRequest request = new SearchRequest("Patient", List.of(), 20);
        assertEquals(1, service.search(session.server(), request).resources().size());
        assertTrue(service.read(session.server(), "Patient", "example-1") instanceof Patient);
    }

    @Test
    @DisplayName("A bearer or vendor mechanism is sent by the HAPI client like basic is")
    void anyMechanismReachesTheHapiClient() throws Exception {
        // Before this phase newClient() had an instanceof branch naming Basic, and
        // requireSession refused everything else by name. Both are gone, so any
        // mechanism now travels the same path.
        assertEquals("Bearer tok", authorizationSeenFor(
                new BearerServerAuthentication("tok")));
        assertEquals("Basic YWxpY2U6czNjcmV0", authorizationSeenFor(
                new BasicServerAuthentication("alice", "s3cret")));
        assertNull(authorizationSeenFor(AnonymousServerAuthentication.INSTANCE),
                "anonymous still sends nothing");
    }

    @Test
    @DisplayName("A mechanism that supplies no header is refused, not sent unauthenticated")
    void aHeaderlessMechanismIsRefused() {
        ServerAuthentication headerless = new ServerAuthentication() {
            @Override
            public String type() {
                return "smart";
            }

            @Override
            public String displayName() {
                return "SMART";
            }

            @Override
            public boolean isAnonymous() {
                return false;
            }
        };

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> plugin.capabilities(
                        new ServerSession(session.server(), headerless)));

        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, failure.kind());
        assertTrue(failure.getMessage().contains("smart"),
                "the message must name the mechanism so the user can see what happened");
    }

    /** Runs a capabilities call with the given authentication and returns what arrived. */
    private String authorizationSeenFor(ServerAuthentication authentication) throws Exception {
        lastAuthorization.set(null);
        plugin.capabilities(new ServerSession(session.server(), authentication));
        return lastAuthorization.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String body;
        int status = 200;
        if (path.endsWith("/metadata")) {
            body = CAPABILITY_JSON;
        } else if (query != null && query.contains("_getpages")) {
            body = SEARCH_LAST_JSON;
        } else if (path.endsWith("/Patient/example-1") && exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            body = PATIENT_JSON;
        } else if (path.endsWith("/Patient/does-not-exist")) {
            status = 404;
            body = outcome("not found");
        } else if (path.endsWith("/Patient")) {
            body = SEARCH_JSON.replace("/NEXT-PAGE/", baseUrl + "/Patient?_getpages=abc&page=2");
        } else {
            status = 404;
            body = outcome("unknown");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String outcome(String diagnostics) {
        return "{ \"resourceType\": \"OperationOutcome\", \"issue\": [ { \"severity\": \"error\", "
                + "\"diagnostics\": \"" + diagnostics + "\" } ] }";
    }

    @Test
    @DisplayName("Canned Bundle and CapabilityStatement shapes really parse as R4")
    void parsesCannedShapes() {
        FhirContext context = FhirContext.forR4();
        Bundle bundle = (Bundle) context.newJsonParser().parseResource(SEARCH_JSON);
        assertEquals(1, bundle.getEntry().size());
        CapabilityStatement statement =
                (CapabilityStatement) context.newJsonParser().parseResource(CAPABILITY_JSON);
        assertEquals("4.0.1", statement.getFhirVersion().toCode());
    }
}
