package com.example.fhirviewer.server;

import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * A FHIR server integration.
 *
 * <p>This is the stable boundary the whole server feature hangs off: the JavaFX UI and
 * the application service talk to this interface only, and every server-specific detail
 * (HTTP, URL construction, search syntax, paging, authentication mechanics, vendor
 * quirks) lives in an implementation. Adding a vendor plugin means adding a class that
 * implements this interface and registering it; the core application does not change.</p>
 *
 * <p>All operations complete synchronously from the caller's point of view and may block
 * on the network, so the UI must always call them from a background thread (see the
 * plan: all network operations are asynchronous). Failures surface as
 * {@link ServerOperationException}, never as raw client or HTTP exceptions, so the UI
 * can show them without knowing which library performed the request.</p>
 */
public interface FhirServerPlugin {

    /** A stable id such as <code>standard-rest</code>, also stored in server definitions. */
    String id();

    /** The name shown in the UI, for example <code>Standard FHIR REST</code>. */
    String displayName();

    /** One or two sentences describing what this plugin connects to. */
    String description();

    /** The FHIR versions this plugin speaks, for example <code>[R4]</code>. Never empty. */
    List<String> supportedFhirVersions();

    /**
     * True when this plugin can serve the given configuration. The registry uses this to
     * pick a plugin for a stored server; unknown plugin ids or versions fail here with
     * {@code false} rather than later with an exception.
     */
    boolean supports(FhirServerConfiguration configuration);

    /**
     * Reads the server's capabilities without changing anything.
     *
     * @return the capabilities the server reported
     * @throws ServerOperationException when the server cannot be reached or answers badly
     */
    ServerCapabilities capabilities(ServerSession session) throws ServerOperationException;

    /**
     * Tests the connection by reading the server's capabilities.
     *
     * <p>This never throws: an unreachable server is a failed result so the dialog can
     * report it directly.</p>
     */
    ConnectionResult testConnection(ServerSession session);

    /**
     * Runs a search and returns one page of results.
     *
     * @throws ServerOperationException when the search fails
     */
    SearchResultPage search(ServerSession session, SearchRequest request) throws ServerOperationException;

    /**
     * Fetches the next page of a search started with {@link #search}.
     *
     * @param pageToken the token from {@link SearchResultPage#nextPageToken()}
     * @throws ServerOperationException when the page cannot be fetched
     */
    SearchResultPage nextPage(ServerSession session, SearchRequest request, String pageToken)
            throws ServerOperationException;

    /**
     * Reads one resource by type and id.
     *
     * @throws ServerOperationException in particular with kind {@code NOT_FOUND} when the
     *                                  resource does not exist
     */
    IBaseResource read(ServerSession session, String resourceType, String resourceId)
            throws ServerOperationException;

    /**
     * Copies a resource out of a search result for display without another request.
     * Standard FHIR search Bundles carry the full resource in each entry; the UI calls
     * this rather than {@link #read} when the entry already has one.
     */
    default IBaseResource localCopy(IBaseResource resource) {
        return ServerOperationException.localCopy(resource);
    }
}
