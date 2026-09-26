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

    private static String describe(FhirServerConfiguration server) {
        if (server == null) {
            return "this server";
        }
        log.info("server operation server={} plugin={}", server.name(), server.pluginId());
        return server.name() + " (" + server.baseUrl() + ")";
    }
}
