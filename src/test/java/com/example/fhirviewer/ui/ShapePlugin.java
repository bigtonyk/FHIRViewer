package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.server.ConnectionResult;
import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.FhirServerPlugin;
import com.example.fhirviewer.server.SearchRequest;
import com.example.fhirviewer.server.SearchResultPage;
import com.example.fhirviewer.server.ServerCapabilities;
import com.example.fhirviewer.server.ServerOperation;
import com.example.fhirviewer.server.ServerSession;
import com.example.fhirviewer.server.rest.RestMethod;

/**
 * A test-only plugin that declares one operation per answer shape.
 *
 * <p>Shared by the Phase 6 UI tests. Deliberately not a vendor and not a subclass of
 * anything the application ships — the property under test is that the UI can discover,
 * describe and display endpoints belonging to a plugin it has never heard of, and a
 * fixture that shared a base class with the production plugins would quietly weaken that.</p>
 */
final class ShapePlugin implements FhirServerPlugin {

    static final String PLUGIN_ID = "shapes";

    static final List<ServerOperation> OPERATIONS = List.of(
            get("patient", "shapes/patient", ServerOperation.ResultKind.FHIR_RESOURCE),
            get("bundle", "shapes/bundle", ServerOperation.ResultKind.BUNDLE),
            get("outcome", "shapes/outcome", ServerOperation.ResultKind.OPERATION_OUTCOME),
            get("vendor-json", "shapes/json", ServerOperation.ResultKind.JSON),
            get("vendor-xml", "shapes/xml", ServerOperation.ResultKind.XML),
            get("log", "shapes/log", ServerOperation.ResultKind.TEXT),
            // Declares nothing about what it returns, so classification has to fall back to
            // the content type and then to the body's first character. This is what a real
            // vendor plugin gives you when it under-declares.
            ServerOperation.builder("untyped", RestMethod.GET, "shapes/untyped")
                    .displayName("untyped")
                    .category(ServerOperation.Category.VENDOR)
                    .build());

    private static ServerOperation get(String id, String path, ServerOperation.ResultKind kind) {
        return ServerOperation.builder(id, RestMethod.GET, path)
                .displayName(id)
                .category(ServerOperation.Category.VENDOR)
                .returns(kind)
                .build();
    }

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String displayName() {
        return "Result Shapes";
    }

    @Override
    public String description() {
        return "Declares one operation per answer shape, for the Phase 6 UI tests.";
    }

    @Override
    public List<String> supportedFhirVersions() {
        return List.of("R4");
    }

    @Override
    public boolean supports(FhirServerConfiguration configuration) {
        return configuration != null && PLUGIN_ID.equals(configuration.pluginId());
    }

    @Override
    public List<ServerOperation> availableOperations() {
        return OPERATIONS;
    }

    // The rest of the contract is irrelevant to these tests: they only ever run operations,
    // which the inherited default implements in full. Declared so the fixture cannot
    // accidentally pick up a behaviour change in the defaults.
    @Override
    public ServerCapabilities capabilities(ServerSession session) {
        return ServerCapabilities.empty();
    }

    @Override
    public ConnectionResult testConnection(ServerSession session) {
        return ConnectionResult.unreachable("Test-only plugin.");
    }

    @Override
    public SearchResultPage search(ServerSession session, SearchRequest request) {
        throw new UnsupportedOperationException("This plugin does not search.");
    }

    @Override
    public IBaseResource read(ServerSession session, String resourceType, String resourceId) {
        throw new UnsupportedOperationException("This plugin does not read.");
    }
}
