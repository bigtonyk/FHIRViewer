package com.example.fhirviewer.server;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The application's FHIR server service: the single entry point the UI uses for server
 * work.
 *
 * <p>This mirrors {@link com.example.fhirviewer.service.FhirService}: the UI talks to
 * this class and never to plugins, HTTP clients, URLs or authentication directly. Every
 * call resolves the server's plugin through the registry, asks the plugin for an
 * anonymous session, and delegates. The manager (servers, storage, active selection)
 * behind this service can grow later; the UI contract here does not change.</p>
 */
public class FhirServerService {

    private static final Logger log = LoggerFactory.getLogger(FhirServerService.class);

    private final FhirServerPluginRegistry registry;

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
        this.registry = Objects.requireNonNull(registry, "registry");
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

    /** Reads one resource by type and id. */
    public org.hl7.fhir.instance.model.api.IBaseResource read(
            FhirServerConfiguration server, String resourceType, String resourceId)
            throws ServerOperationException {
        return pluginForChecked(server).read(sessionOf(server), resourceType, resourceId);
    }

    /**
     * Creates a resource on a server, anonymously.
     *
     * @return what the server assigned, so the caller can rebase its {@link ServerOrigin}
     */
    public ServerWriteResult create(FhirServerConfiguration server,
            org.hl7.fhir.instance.model.api.IBaseResource resource) throws ServerOperationException {
        return create(server, resource, AnonymousServerAuthentication.INSTANCE);
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

    /** Updates a resource on a server, anonymously. */
    public ServerWriteResult update(FhirServerConfiguration server,
            org.hl7.fhir.instance.model.api.IBaseResource resource, ServerOrigin origin)
            throws ServerOperationException {
        return update(server, resource, origin, AnonymousServerAuthentication.INSTANCE);
    }

    /** Updates a resource on a server with the given authentication. */
    public ServerWriteResult update(FhirServerConfiguration server,
            org.hl7.fhir.instance.model.api.IBaseResource resource, ServerOrigin origin,
            ServerAuthentication authentication) throws ServerOperationException {
        Objects.requireNonNull(origin, "origin");
        // The origin remembers which server it came from. Refusing a mismatched pair stops
        // a resource read from one server being written to another by mistake.
        if (origin.baseUrl() != null && server != null && !origin.baseUrl().equals(server.baseUrl())) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "This resource came from " + origin.baseUrl()
                            + ", so it cannot be saved to " + server.baseUrl() + ".");
        }
        return pluginForChecked(server).update(sessionOf(server, authentication), resource, origin);
    }

    /** Deletes a resource from a server, anonymously. */
    public void delete(FhirServerConfiguration server, ServerOrigin origin)
            throws ServerOperationException {
        delete(server, origin, AnonymousServerAuthentication.INSTANCE);
    }

    /** Deletes a resource from a server with the given authentication. */
    public void delete(FhirServerConfiguration server, ServerOrigin origin,
            ServerAuthentication authentication) throws ServerOperationException {
        Objects.requireNonNull(origin, "origin");
        if (origin.baseUrl() != null && server != null && !origin.baseUrl().equals(server.baseUrl())) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "This resource came from " + origin.baseUrl()
                            + ", so it cannot be deleted on " + server.baseUrl() + ".");
        }
        pluginForChecked(server).delete(sessionOf(server, authentication), origin);
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

    private static ServerSession sessionOf(FhirServerConfiguration server) {
        Objects.requireNonNull(server, "server");
        return new ServerSession(server, AnonymousServerAuthentication.INSTANCE);
    }

    /**
     * Builds a session for an explicit authentication.
     *
     * <p>The write methods take the authentication the caller resolved for this operation
     * rather than assuming anonymous, so credentials the plugin manager stored for a server
     * are actually used. Read paths keep using the single-argument form.</p>
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
