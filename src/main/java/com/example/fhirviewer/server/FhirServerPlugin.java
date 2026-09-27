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

    /**
     * Creates a new resource on the server.
     *
     * <p>Every method here is a {@code default} that reports
     * {@link ServerOperationException.Kind#UNSUPPORTED}, so a read-only vendor plugin
     * still loads and works, and the UI can offer a local export instead of failing with
     * a generic error. Plugins that can write override these.</p>
     *
     * @return what the server assigned, so the editor can rebase its {@link ServerOrigin}
     * @throws ServerOperationException with kind {@code UNSUPPORTED} when this plugin
     *         cannot write, or the mapped failure when the server refuses
     */
    default ServerWriteResult create(ServerSession session, IBaseResource resource)
            throws ServerOperationException {
        throw unsupported("create");
    }

    /**
     * Updates an existing resource on the server.
     *
     * <p>When the supplied {@link ServerOrigin} carries a {@code versionId}, an
     * implementation should send it as {@code If-Match} and report
     * {@link ServerOperationException.Kind#CONFLICT} on HTTP 412, so a stale editor
     * cannot silently overwrite someone else's change.</p>
     *
     * @param origin where the resource came from; its {@code resourceId} is required
     * @throws ServerOperationException with kind {@code UNSUPPORTED} when this plugin
     *         cannot write, {@code CONFLICT} when the server reports the resource
     *         changed underneath us
     */
    default ServerWriteResult update(ServerSession session, IBaseResource resource, ServerOrigin origin)
            throws ServerOperationException {
        throw unsupported("update");
    }

    /**
     * Deletes a resource from the server.
     *
     * @throws ServerOperationException with kind {@code UNSUPPORTED} when this plugin
     *         cannot write, {@code NOT_FOUND} when it is already gone
     */
    default void delete(ServerSession session, ServerOrigin origin) throws ServerOperationException {
        throw unsupported("delete");
    }

    /**
     * True when this plugin implements the write verbs.
     *
     * <p>Lets the UI disable "Save to Server" up front rather than letting the user
     * discover it by pressing the button.</p>
     */
    default boolean supportsWrite() {
        return false;
    }

    private static ServerOperationException unsupported(String verb) {
        return new ServerOperationException(ServerOperationException.Kind.UNSUPPORTED,
                "This server plugin does not support " + verb + ".");
    }

    /**
     * The server-specific screens this plugin offers, for the main UI to list.
     *
     * <p>This is the vendor-tooling seam: a plugin whose server exposes more than plain
     * FHIR REST declares its extra screens here, and the main UI presents them. The
     * declaration is data rather than a JavaFX {@code Node} on purpose, so this package
     * stays free of JavaFX and a plugin never has to build UI the application owns.</p>
     *
     * <p>Defaults to none, so a plugin that only speaks standard FHIR REST — or a
     * third-party plugin written before this hook existed — needs no change.</p>
     *
     * @return the vendor actions, never {@code null}; possibly empty
     */
    default List<ServerVendorAction> vendorActions() {
        return List.of();
    }

    /**
     * Opens one of this plugin's {@link #vendorActions() vendor actions}.
     *
     * <p>Intentionally stubbed: no plugin ships a working screen yet, so this always
     * reports {@link ServerOperationException.Kind#UNSUPPORTED} with a clear message. It
     * exists now so the plugin contract, the menu wiring and the UI are agreed and tested
     * before any vendor screen is written, and so adding one later is a change inside
     * plugins rather than another change across the application.</p>
     *
     * <p>Implementations will receive the session to talk to the server and the host
     * window to attach to; {@code owner} is the intended parent and is ignored here.</p>
     *
     * @param actionId the id from the matching {@link ServerVendorAction}
     * @param session  the session for the server the action applies to
     * @param owner    the intended parent window; ignored by this stub
     * @throws ServerOperationException with kind {@code UNSUPPORTED} in this stub
     */
    default void openVendorTool(String actionId, ServerSession session, Object owner)
            throws ServerOperationException {
        throw unsupported("the server tool '" + actionId + "'");
    }
}
