package com.example.fhirviewer.server;

import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The application's FHIR server service: the single entry point the UI uses for server
 * work.
 *
 * <p>This mirrors {@link com.example.fhirviewer.service.FhirService}: the UI talks to
 * this class and never to plugins, HTTP clients, URLs or authentication directly. Every
 * call resolves the server's plugin through the registry, asks the plugin for a
 * session, and delegates. The manager (servers, storage, active selection)
 * behind this service can grow later; the UI contract here does not change.</p>
 *
 * <p><b>Credentials.</b> A session is built with whatever {@link ServerCredentials}
 * finds saved for that server, so a password entered in the plugin manager is now
 * actually sent; it falls back to anonymous whenever there is nothing usable. The three
 * write methods keep an overload that takes an explicit authentication for a caller
 * holding a credential it obtained itself.</p>
 */
public class FhirServerService {

    private static final Logger log = LoggerFactory.getLogger(FhirServerService.class);

    private final FhirServerPluginRegistry registry;
    private final ServerCredentials credentials;

    /**
     * Creates the service with the plugins described by the application's plugin
     * configuration: service-file discovery plus anything the config enables, minus
     * anything the config disables. See {@link PluginConfig} and {@link PluginLoader}.
     */
    public FhirServerService() {
        this(PluginLoader.load());
    }

    /** Creates the service on an explicit registry (used by tests and bootstrapping). */
    public FhirServerService(FhirServerPluginRegistry registry) {
        this(registry, new ServerCredentials());
    }

    /**
     * Creates the service with the plugins and the saved credentials to authenticate with.
     *
     * <p>Passing the {@link ServerCredentials} here rather than building one per call is
     * what makes a stored password actually reach the server: previously every read
     * session was anonymous, so a credential saved in the plugin manager was written,
     * encrypted and then never used.
     */
    public FhirServerService(FhirServerPluginRegistry registry, ServerCredentials credentials) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /**
     * The saved credentials this service authenticates with.
     *
     * <p>Exposed so a UI can ask whether a server has a locked credential to unlock,
     * without reaching past the service into the settings file itself.
     */
    public ServerCredentials credentials() {
        return credentials;
    }

    /** Every registered plugin, in registration order. Exposed for the server dialog. */
    public List<FhirServerPlugin> plugins() {
        return registry.plugins();
    }

    /**
     * The registry backing this service.
     *
     * <p>Exposed so the plugin manager dialog can list the loaded plugins and register
     * ones it loads at run time. The UI is not expected to resolve plugins for its own
     * operations; it should keep calling this service for that.</p>
     */
    public FhirServerPluginRegistry registry() {
        return registry;
    }

    /**
     * Tests a server connection without throwing: unreachable servers come back as
     * failed results the dialog can show.
     */
    public ConnectionResult testConnection(FhirServerConfiguration server) {
        FhirServerPlugin plugin = pluginFor(server);
        if (plugin == null) {
            return ConnectionResult.unreachable("No plugin supports " + describe(server) + ".");
        }
        return plugin.testConnection(sessionOf(server));
    }

    /** Reads a server's capabilities. */
    public ServerCapabilities capabilities(FhirServerConfiguration server) throws ServerOperationException {
        return pluginForChecked(server).capabilities(sessionOf(server));
    }

    /** Runs a search and returns one page of results. */
    public SearchResultPage search(FhirServerConfiguration server, SearchRequest request)
            throws ServerOperationException {
        return pluginForChecked(server).search(sessionOf(server), request);
    }

    /** Fetches the next page of a search. */
    public SearchResultPage nextPage(FhirServerConfiguration server, SearchRequest request, String pageToken)
            throws ServerOperationException {
        return pluginForChecked(server).nextPage(sessionOf(server), request, pageToken);
    }

    /**
     * Fetches the page at any link the search Bundle supplied.
     *
     * <p>Accepts a {@code previous}, {@code first} or {@code last} URL as readily as a
     * {@code next} one, and forwards it untouched: the server minted it, the server
     * understands it, and this application never reconstructs a page token.
     */
    public SearchResultPage pageAt(FhirServerConfiguration server, String pageUrl)
            throws ServerOperationException {
        return pluginForChecked(server).pageAt(sessionOf(server), pageUrl);
    }

    /**
     * True when this server's own capability statement advertises an interaction.
     *
     * <p>Reads the metadata, so a caller checking several things about one server should
     * call {@link #capabilities(FhirServerConfiguration)} once and ask that instead. The
     * plan is explicit that PATCH, history, batch, transactions and conditional
     * operations cannot be assumed, and this is where that rule reaches the UI.</p>
     *
     * <p>A server that cannot be reached, or that answers with no usable metadata, is
     * reported as supporting nothing rather than throwing: the caller's question was
     * "may I try this", and "no" is the safe answer to a server we know nothing about.</p>
     */
    public boolean supports(FhirServerConfiguration server, String resourceType,
            ServerInteraction interaction) {
        try {
            return capabilities(server).supports(resourceType, interaction);
        } catch (ServerOperationException unknown) {
            log.info("capability check failed for {}: {}", describe(server), unknown.displayMessage());
            return false;
        }
    }

    /**
     * Applies a partial update to a resource on a server.
     *
     * @return what the server assigned, so the caller can rebase its {@link ServerOrigin}
     */
    public ServerWriteResult patch(FhirServerConfiguration server, ServerOrigin origin, String body,
            PatchFormat format) throws ServerOperationException {
        return patch(server, origin, body, format, credentials.forServer(server));
    }

    /** Applies a partial update with an explicit authentication. */
    public ServerWriteResult patch(FhirServerConfiguration server, ServerOrigin origin, String body,
            PatchFormat format, ServerAuthentication authentication) throws ServerOperationException {
        requireSameServer(server, origin, "patched");
        return pluginForChecked(server).patch(sessionOf(server, authentication), origin, body, format);
    }

    /** Invokes a standard FHIR {@code $}-operation on a server. */
    public IBaseResource invoke(FhirServerConfiguration server, FhirOperationRequest request)
            throws ServerOperationException {
        return pluginForChecked(server).invoke(sessionOf(server), request);
    }

    /**
     * The operations the plugin for this server offers, as data.
     *
     * <p>Discovery for the UI. It reads no network and touches no credentials, so a dialog
     * can build its menu from it before the user has connected to anything — which is what
     * makes the list useful for explaining what a server <em>can</em> do rather than only
     * what it has just been asked for.</p>
     *
     * @return the declared operations, never {@code null}; possibly empty
     */
    public List<ServerOperation> availableOperations(FhirServerConfiguration server) {
        FhirServerPlugin plugin = pluginFor(server);
        return plugin == null ? List.of() : plugin.availableOperations();
    }

    /**
     * The operations that can be run on this server right now.
     *
     * <p>Prefer this once a connection has been made: a plugin can drop an operation the
     * connected server will not accept, so this is the list a UI should offer, and
     * {@link #availableOperations} is the list a UI should describe.</p>
     */
    public List<ServerOperation> supportedOperations(FhirServerConfiguration server) {
        FhirServerPlugin plugin = pluginFor(server);
        return plugin == null ? List.of() : plugin.supportedOperations(sessionOf(server));
    }

    /**
     * Runs one of the server's plugin operations.
     *
     * <p>The one entry point a UI needs for a vendor endpoint. The service resolves the
     * plugin, builds the session with whatever credentials are saved for the server, and
     * delegates; the caller never sees a plugin, a URL or a header.</p>
     *
     * @param server     the configured server
     * @param invocation the operation id and the caller's values
     * @return the server's answer, including an answer that was a refusal
     * @throws ServerOperationException with kind {@code UNSUPPORTED} when the plugin does
     *         not offer that operation, {@code BAD_REQUEST} when the call is incomplete,
     *         or the mapped transport failure when no answer arrived
     */
    public ServerOperationResult executeOperation(FhirServerConfiguration server,
            ServerOperationInvocation invocation) throws ServerOperationException {
        return pluginForChecked(server).executeOperation(sessionOf(server), invocation);
    }

    /**
     * Runs one of the server's plugin operations with an explicit authentication.
     *
     * <p>Mirrors the write methods: an operation that changes server state may be run with
     * a credential the user supplied for this one call rather than one saved in the
     * settings, and the credentials still travel on the session and never on the
     * configuration.</p>
     */
    public ServerOperationResult executeOperation(FhirServerConfiguration server,
            ServerOperationInvocation invocation, ServerAuthentication authentication)
            throws ServerOperationException {
        Objects.requireNonNull(authentication, "authentication");
        return pluginForChecked(server).executeOperation(sessionOf(server, authentication), invocation);
    }

    /** Reads one resource by type and id. */
    public org.hl7.fhir.instance.model.api.IBaseResource read(
            FhirServerConfiguration server, String resourceType, String resourceId)
            throws ServerOperationException {
        return pluginForChecked(server).read(sessionOf(server), resourceType, resourceId);
    }

    /**
     * Creates a resource on a server with whatever credentials are saved for it.
     *
     * @return what the server assigned, so the caller can rebase its {@link ServerOrigin}
     */
    public ServerWriteResult create(FhirServerConfiguration server,
            org.hl7.fhir.instance.model.api.IBaseResource resource) throws ServerOperationException {
        return create(server, resource, credentials.forServer(server));
    }

    /**
     * Creates a resource on a server with the given authentication.
     *
     * <p>Credentials reach the plugin through the session and never through the
     * configuration, so a server definition on disk can never hold a secret.</p>
     */
    public ServerWriteResult create(FhirServerConfiguration server,
            org.hl7.fhir.instance.model.api.IBaseResource resource,
            ServerAuthentication authentication) throws ServerOperationException {
        return pluginForChecked(server).create(sessionOf(server, authentication), resource);
    }

    /** Updates a resource on a server with whatever credentials are saved for it. */
    public ServerWriteResult update(FhirServerConfiguration server,
            org.hl7.fhir.instance.model.api.IBaseResource resource, ServerOrigin origin)
            throws ServerOperationException {
        return update(server, resource, origin, credentials.forServer(server));
    }

    /** Updates a resource on a server with the given authentication. */
    public ServerWriteResult update(FhirServerConfiguration server,
            org.hl7.fhir.instance.model.api.IBaseResource resource, ServerOrigin origin,
            ServerAuthentication authentication) throws ServerOperationException {
        requireSameServer(server, origin, "saved to");
        return pluginForChecked(server).update(sessionOf(server, authentication), resource, origin);
    }

    /** Deletes a resource from a server with whatever credentials are saved for it. */
    public void delete(FhirServerConfiguration server, ServerOrigin origin)
            throws ServerOperationException {
        delete(server, origin, credentials.forServer(server));
    }

    /** Deletes a resource from a server with the given authentication. */
    public void delete(FhirServerConfiguration server, ServerOrigin origin,
            ServerAuthentication authentication) throws ServerOperationException {
        requireSameServer(server, origin, "deleted on");
        pluginForChecked(server).delete(sessionOf(server, authentication), origin);
    }

    /**
     * Refuses a write of a resource to a server it did not come from.
     *
     * <p>The origin remembers which server it came from. Checking it here stops a
     * resource read from one server being written to another by mistake, before any
     * request is made and before any credential is attached to it. Shared by update,
     * delete and patch, so the rule and its wording are written down once.</p>
     *
     * @param verb how the message describes the attempted write, for example
     *             {@code "deleted on"}
     */
    private static void requireSameServer(FhirServerConfiguration server, ServerOrigin origin, String verb)
            throws ServerOperationException {
        Objects.requireNonNull(origin, "origin");
        if (origin.baseUrl() != null && server != null && !origin.baseUrl().equals(server.baseUrl())) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "This resource came from " + origin.baseUrl() + ", so it cannot be " + verb + " "
                            + server.baseUrl() + ".");
        }
    }

    /**
     * True when this server's plugin can write, so the UI can offer "Save to Server"
     * instead of letting the user press a button that will be refused.
     */
    public boolean supportsWrite(FhirServerConfiguration server) {
        FhirServerPlugin plugin = pluginFor(server);
        return plugin != null && plugin.supportsWrite();
    }

    private FhirServerPlugin pluginFor(FhirServerConfiguration server) {
        if (server == null) {
            return null;
        }
        return registry.pluginFor(server);
    }

    private FhirServerPlugin pluginForChecked(FhirServerConfiguration server) throws ServerOperationException {
        FhirServerPlugin plugin = pluginFor(server);
        if (plugin == null) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "No plugin supports " + describe(server) + ".");
        }
        return plugin;
    }

    /**
     * Builds the session for a read, authenticating with whatever the user saved.
     *
     * <p>This is the point where a stored credential stops being decoration and starts
     * being used. It falls back to anonymous whenever no usable credential exists, so
     * every read behaves exactly as it did before when nothing is saved.
     */
    private ServerSession sessionOf(FhirServerConfiguration server) {
        Objects.requireNonNull(server, "server");
        return new ServerSession(server, credentials.forServer(server));
    }

    /**
     * Builds a session for an explicit authentication.
     *
     * <p>The write methods take the authentication the caller resolved for this operation
     * rather than looking one up, so a credential the plugin manager stored for a server
     * is used and a credential the user typed for one write is honoured. Read paths keep
     * using the single-argument form.</p>
     */
    private static ServerSession sessionOf(FhirServerConfiguration server,
            ServerAuthentication authentication) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(authentication, "authentication");
        return new ServerSession(server, authentication);
    }

    private static String describe(FhirServerConfiguration server) {
        if (server == null) {
            return "this server";
        }
        log.info("server operation server={} plugin={}", server.name(), server.pluginId());
        return server.name() + " (" + server.baseUrl() + ")";
    }
}
