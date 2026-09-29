package com.example.fhirviewer.server;

import java.util.List;
import java.util.Optional;

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
     * <p>Kept as the name the UI and the service already use. It is a thin wrapper over
     * {@link #pageAt}, which is the general case: a page token is a server-minted URL,
     * and the same call fetches a {@code previous}, {@code first} or {@code last} link
     * unchanged.</p>
     *
     * @param request the search the page belongs to; a plugin may ignore it, since the
     *                URL already carries everything the server needs
     * @param pageToken the {@code next} URL from {@link SearchResultPage#nextPageToken()}
     * @throws ServerOperationException when the page cannot be fetched
     */
    default SearchResultPage nextPage(ServerSession session, SearchRequest request, String pageToken)
            throws ServerOperationException {
        return pageAt(session, pageToken);
    }

    /**
     * Fetches the page at a URL the server itself supplied.
     *
     * <p>FHIR search results are navigated by following the Bundle's own links. Nothing
     * here reconstructs one, because the server alone knows what its page token means
     * and what order it assumed — a viewer that builds its own {@code _getpages} value
     * silently shows the wrong page on any server that encodes it differently.</p>
     *
     * @param pageUrl a {@code self}, {@code first}, {@code previous}, {@code next} or
     *                {@code last} URL from {@link SearchResultPage#links()}
     * @throws ServerOperationException when the page cannot be fetched
     */
    default SearchResultPage pageAt(ServerSession session, String pageUrl) throws ServerOperationException {
        throw unsupported("following a paging link");
    }

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
     * Applies a partial update to a resource already on the server.
     *
     * <p>Distinct from {@link #update} because the two are not interchangeable: an update
     * replaces the whole resource, a patch changes named parts of it. A server may
     * support one and not the other — PATCH is the interaction most often omitted from a
     * capability statement — so this is a separate, separately-refusable operation
     * rather than a flag on update.</p>
     *
     * <p>The patch format is carried in {@code Content-Type}, not in the body, so
     * {@link PatchFormat} is part of the call and not something to infer.</p>
     *
     * @param body   the patch document, already written in {@code format}
     * @param format the patch format the body is written in
     * @return what the server assigned, so the caller can rebase its {@link ServerOrigin}
     * @throws ServerOperationException with kind {@code UNSUPPORTED} when this plugin
     *         cannot patch, {@code CONFLICT} when the server reports the resource changed
     *         underneath us
     */
    default ServerWriteResult patch(ServerSession session, ServerOrigin origin, String body,
            PatchFormat format) throws ServerOperationException {
        throw unsupported("patch");
    }

    /**
     * True when this plugin can apply a partial update.
     *
     * <p>Separate from {@link #supportsWrite()} because a server can accept create,
     * update and delete and still refuse PATCH, and offering a patch the server will
     * reject is worse than not offering it.</p>
     */
    default boolean supportsPatch() {
        return false;
    }

    /**
     * Invokes a standard FHIR {@code $}-operation.
     *
     * <p>System, type and instance level operations all come through here; the request
     * says which. A plugin that cannot run them reports
     * {@link ServerOperationException.Kind#UNSUPPORTED} rather than silently
     * succeeding, which is what lets the UI disable an operation it knows will
     * fail.</p>
     *
     * @return the operation's result resource, which is whatever the operation defines
     * @throws ServerOperationException with kind {@code UNSUPPORTED} when this plugin
     *         cannot invoke operations, or the mapped failure when the server refuses
     */
    default IBaseResource invoke(ServerSession session, FhirOperationRequest request)
            throws ServerOperationException {
        throw unsupported("FHIR operations");
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
     * The operations this plugin offers beyond the standard plugin API, as data.
     *
     * <p>This is the discovery side of the vendor seam, and the fine-grained counterpart to
     * {@link #vendorActions()}. An operation is a callable endpoint with a verb, a path and
     * a parameter list, which is what a UI needs in order to build a form and run
     * something; a vendor action is a whole screen and cannot be filled in or invoked
     * generically. The two coexist deliberately: a plugin may offer either, or both.</p>
     *
     * <p>A plugin declares; the core performs. Execution goes through
     * {@link #executeOperation}, whose default implementation is generic and needs no
     * vendor code, so adding a vendor endpoint is a change inside the plugin and nowhere
     * else. A plugin that needs to alter the request or the answer overrides that method.</p>
     *
     * <p>Declared without a session, so a UI can build its menu before connecting. Narrow
     * the list in {@link #supportedOperations(ServerSession)} when what a server supports
     * only becomes known after a connection test.</p>
     *
     * @return the operations, never {@code null}; possibly empty
     */
    default List<ServerOperation> availableOperations() {
        return List.of();
    }

    /**
     * The operations this plugin can actually run on the given session right now.
     *
     * <p>Defaults to {@link #availableOperations()}, which is right for a plugin whose
     * endpoints do not depend on what the server reports. A plugin that knows an endpoint
     * needs a capability the connected server may lack overrides this to drop it, so the
     * UI can grey the entry out before the user presses it rather than after the server
     * refuses it.</p>
     *
     * @param session the session the operations would run on
     * @return the usable operations, never {@code null}
     */
    default List<ServerOperation> supportedOperations(ServerSession session) {
        return availableOperations();
    }

    /**
     * Finds one of this plugin's declared operations by id.
     *
     * @return the operation, or empty when this plugin does not declare it
     */
    default Optional<ServerOperation> operation(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return Optional.empty();
        }
        for (ServerOperation operation : availableOperations()) {
            if (operation.id().equals(operationId.trim())) {
                return Optional.of(operation);
            }
        }
        return Optional.empty();
    }

    /**
     * Runs one of this plugin's declared operations.
     *
     * <p>The default implementation is the whole mechanism and contains no vendor code: it
     * resolves the descriptor, builds the request from it and the caller's values through
     * the shared {@link PluginOperationClient}, and returns the answer as a
     * {@link ServerOperationResult}. A plugin therefore exposes a vendor endpoint by
     * declaring it and nothing more — which is the entire point of this seam, and the
     * reason {@code if (serverType == SMILE)} never appears anywhere in the application.</p>
     *
     * <p>Overrides exist for the cases a descriptor cannot express: an endpoint that needs
     * a signature computed from a secret, or a response that has to be read by vendor code
     * before the application sees it.</p>
     *
     * @param session    the session to send on; supplies the base URL, timeout and credentials
     * @param invocation the operation id and the caller's values
     * @return the server's answer, including an answer that was a refusal
     * @throws ServerOperationException with kind {@code BAD_REQUEST} when the operation is
     *         not declared, {@code UNAUTHORIZED} when it needs credentials the session does
     *         not have, or the mapped transport failure when no answer arrived
     */
    default ServerOperationResult executeOperation(ServerSession session,
            ServerOperationInvocation invocation) throws ServerOperationException {
        if (session == null || session.server() == null) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "A server is required.");
        }
        if (invocation == null) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "An operation call is required.");
        }
        ServerOperation operation = operation(invocation.operationId())
                .orElseThrow(() -> new ServerOperationException(ServerOperationException.Kind.UNSUPPORTED,
                        "This server plugin does not offer an operation called '"
                                + invocation.operationId() + "'."));
        try (PluginOperationClient client = new PluginOperationClient(session)) {
            return client.execute(operation, invocation);
        }
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
