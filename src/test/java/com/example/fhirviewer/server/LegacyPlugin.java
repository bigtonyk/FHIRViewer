package com.example.fhirviewer.server;

import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * A plugin written against the interface as it was <em>before</em> the REST work began.
 *
 * <p>This fixture implements exactly the eleven methods that were abstract in commit
 * {@code 295c97f} — the last commit before the plugin interface grew — and overrides
 * nothing added since: no {@code availableOperations}, no {@code create}, {@code update},
 * {@code delete}, {@code patch}, no {@code pageAt}, no {@code vendorActions}, no
 * {@code execute}.</p>
 *
 * <p>It exists because "the new methods all have defaults" is a claim, and a claim about
 * source compatibility is only worth as much as a compile. If a method added since gains an
 * abstract signature, or loses its default, this file stops compiling and the build fails.
 * That is the whole test, and it is why the class looks deliberately incomplete.</p>
 *
 * <p>Its name and id are fixed because {@link BackwardCompatibilityTest} asserts on them
 * when checking that discovery and the UI still see the plugin.</p>
 */
final class LegacyPlugin implements FhirServerPlugin {

    static final String PLUGIN_ID = "legacy-2019";

    /** A resource the plugin will hand back, so a read can be shown to work. */
    private static IBaseResource canned() {
        org.hl7.fhir.r4.model.Patient patient = new org.hl7.fhir.r4.model.Patient();
        patient.setId("Patient/legacy-1");
        return patient;
    }

    // ---- The eleven methods that were abstract before the REST work ------------------

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String displayName() {
        return "Legacy Plugin";
    }

    @Override
    public String description() {
        return "Written against the pre-REST plugin interface.";
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
    public ServerCapabilities capabilities(ServerSession session) throws ServerOperationException {
        return ServerCapabilities.empty();
    }

    @Override
    public ConnectionResult testConnection(ServerSession session) {
        return ConnectionResult.unreachable("Legacy plugin.");
    }

    @Override
    public SearchResultPage search(ServerSession session, SearchRequest request)
            throws ServerOperationException {
        return new SearchResultPage(List.of(canned()), null, SearchPageLinks.none());
    }

    @Override
    public SearchResultPage nextPage(ServerSession session, SearchRequest request,
            String pageToken) throws ServerOperationException {
        return new SearchResultPage(List.of(), null, SearchPageLinks.none());
    }

    @Override
    public IBaseResource read(ServerSession session, String resourceType, String resourceId)
            throws ServerOperationException {
        return canned();
    }
}