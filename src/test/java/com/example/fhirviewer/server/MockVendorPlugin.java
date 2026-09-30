package com.example.fhirviewer.server;

import java.util.ArrayList;
import java.util.List;

import com.example.fhirviewer.server.rest.RestMethod;

/**
 * A plugin that exists only to prove the custom-operation seam works.
 *
 * <p>Deliberately not a real vendor. It is a third-party plugin in the only sense that
 * matters here: a class outside the application's own plugin set, written against the
 * published {@link FhirServerPlugin} contract, that adds endpoints the application has
 * never heard of. If {@link #availableOperations()} plus the inherited
 * {@link FhirServerPlugin#executeOperation} are enough to discover and run
 * {@code admin/export/{jobId}}, then no change to FHIRViewer core was needed — which is
 * the property this class exists to demonstrate.</p>
 *
 * <p>It declares one standard FHIR operation and one vendor REST operation, because
 * "a plugin can expose a vendor endpoint" is only half the claim. The other half is that a
 * standard interaction and a proprietary one travel the same discovery list, are invoked
 * the same way, and come back as the same result type.</p>
 *
 * <p>{@link #supports} refuses everything, so a test that accidentally wires this into the
 * real registry fails loudly rather than shadowing a production plugin.</p>
 */
public final class MockVendorPlugin implements FhirServerPlugin {

    /** The id this plugin registers under. */
    public static final String PLUGIN_ID = "mock-vendor";

    private static final List<ServerOperation> OPERATIONS = List.of(
            // A standard FHIR interaction, declared through the same list as the vendor one.
            ServerOperation.builder("validate-patient", RestMethod.POST, "Patient/$validate")
                    .displayName("Validate Patient")
                    .description("Runs the server's FHIR validator over a submitted Patient.")
                    .category(ServerOperation.Category.STANDARD)
                    .requiresBody("application/fhir+json")
                    .returns(ServerOperation.ResultKind.OPERATION_OUTCOME)
                    .build(),

            // The vendor endpoint: a path parameter, query parameters, a body and a
            // non-FHIR answer. Nothing in the application knows any of this.
            ServerOperation.builder("export-jobs", RestMethod.POST, "admin/export/{jobId}")
                    .displayName("Export Job")
                    .description("Starts a server export and reports progress for it.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .pathParameter("jobId", "The job to report on.")
                    .queryParameter("format", "ndjson, csv or bundle.", false)
                    .queryParameter("since", "Only resources changed after this instant.", false)
                    .headerParameter("X-Report-Mode", "summary or full.", false)
                    .acceptsBody("application/json")
                    .requiresAuthentication()
                    .returns(ServerOperation.ResultKind.JSON)
                    .build(),

            // A GET answering with plain text, to prove a non-JSON, non-FHIR result
            // survives the round trip intact.
            ServerOperation.builder("server-log", RestMethod.GET, "admin/log")
                    .displayName("Server Log")
                    .description("Returns the last lines of the server's own log.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .returns(ServerOperation.ResultKind.TEXT)
                    .build(),

            // A declared header parameter, to prove a plugin can ask for one safely.
            ServerOperation.builder("cache-status", RestMethod.GET, "admin/cache")
                    .displayName("Cache Status")
                    .description("Reports the server's cache state.")
                    .category(ServerOperation.Category.ADMINISTRATION)
                    .headerParameter("X-Report-Mode", "summary or full.", false)
                    .returns(ServerOperation.ResultKind.JSON)
                    .build());

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String displayName() {
        return "Mock Vendor Server";
    }

    @Override
    public String description() {
        return "A test-only plugin exposing a vendor export endpoint.";
    }

    @Override
    public List<String> supportedFhirVersions() {
        return List.of("R4");
    }

    /**
     * Accepts only a configuration that names this plugin.
     *
     * <p>Deliberately narrow: a test that accidentally wires this into the real registry
     * then fails loudly instead of shadowing a production plugin for every other test in
     * the suite.</p>
     */
    @Override
    public boolean supports(FhirServerConfiguration configuration) {
        return configuration != null && PLUGIN_ID.equals(configuration.pluginId());
    }

    @Override
    public ServerCapabilities capabilities(ServerSession session) {
        return ServerCapabilities.empty();
    }

    @Override
    public ConnectionResult testConnection(ServerSession session) {
        return ConnectionResult.unreachable("This plugin is test-only.");
    }

    @Override
    public SearchResultPage search(ServerSession session, SearchRequest request) {
        throw new UnsupportedOperationException("This plugin does not search.");
    }

    @Override
    public org.hl7.fhir.instance.model.api.IBaseResource read(ServerSession session,
            String resourceType, String resourceId) {
        throw new UnsupportedOperationException("This plugin does not read.");
    }

    @Override
    public List<ServerOperation> availableOperations() {
        return OPERATIONS;
    }

    /**
     * Narrows the list the way a real vendor plugin would, so the test can show that
     * discovery is not merely "return everything you declared".
     */
    @Override
    public List<ServerOperation> supportedOperations(ServerSession session) {
        boolean authenticated = session != null && session.authentication() != null
                && !session.authentication().isAnonymous();
        List<ServerOperation> usable = new ArrayList<>();
        for (ServerOperation operation : OPERATIONS) {
            if (!operation.requiresAuthentication() || authenticated) {
                usable.add(operation);
            }
        }
        return List.copyOf(usable);
    }
}
