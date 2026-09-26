package com.example.fhirviewer.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.gclient.ICriterion;
import ca.uhn.fhir.rest.gclient.StringClientParam;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

/**
 * The standard FHIR REST plugin: metadata, search and read over plain FHIR REST using
 * the HAPI FHIR client.
 *
 * <p>Only anonymous access is supported for now; the plugin asks the session's
 * authentication whether it is anonymous and reports anything else with a clear
 * message, so credentials can be introduced later behind {@link ServerAuthentication}
 * without changing this class's shape. Custom headers from the server configuration
 * are applied to every client this plugin creates.</p>
 *
 * <p>Vendor plugins can extend this class and override single hooks
 * ({@link #newClient}, {@link #criteriaOf}, {@link #convertFailure}) instead of
 * reimplementing the whole REST flow.</p>
 */
public class StandardFhirRestPlugin implements FhirServerPlugin {

    /** The id stored in server definitions served by this plugin. */
    public static final String PLUGIN_ID = "standard-rest";

    private static final Logger log = LoggerFactory.getLogger(StandardFhirRestPlugin.class);

    private final FhirContext context;

    /** Creates the plugin on the shared application FHIR context. */
    public StandardFhirRestPlugin() {
        this(com.example.fhirviewer.fhir.FhirContextFactory.r4());
    }

    /** Creates the plugin on an explicit FHIR context (used by tests). */
    public StandardFhirRestPlugin(FhirContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String displayName() {
        return "Standard FHIR REST";
    }

    @Override
    public String description() {
        return "Plain FHIR REST (metadata, search, read) over HTTP, anonymous access.";
    }

    @Override
    public List<String> supportedFhirVersions() {
        return List.of("R4");
    }

    @Override
    public boolean supports(FhirServerConfiguration configuration) {
        return configuration != null
                && PLUGIN_ID.equals(configuration.pluginId())
                && supportedFhirVersions().contains(configuration.fhirVersion());
    }

    @Override
    public ServerCapabilities capabilities(ServerSession session) throws ServerOperationException {
        requireSession(session);
        long started = System.currentTimeMillis();
        IGenericClient client = newClient(session);
        try {
            org.hl7.fhir.r4.model.CapabilityStatement statement = client.capabilities()
                    .ofType(org.hl7.fhir.r4.model.CapabilityStatement.class)
                    .execute();
            ServerCapabilities capabilities = capabilitiesOf(statement);
            log.info("server capabilities baseUrl={} fhirVersion={} resources={} elapsedMs={}",
                    redactedBase(session), capabilities.fhirVersion(),
                    capabilities.resourceTypes().size(), System.currentTimeMillis() - started);
            return capabilities;
        } catch (RuntimeException e) {
            throw convertFailure("Could not read the server capabilities", e);
        }
    }

    @Override
    public ConnectionResult testConnection(ServerSession session) {
        try {
            ServerCapabilities capabilities = capabilities(session);
            String version = capabilities.fhirVersion().isBlank() ? session.server().fhirVersion()
                    : capabilities.fhirVersion();
            return ConnectionResult.reachable(
                    "Connected to " + session.server().name() + " (FHIR " + version + ", "
                            + capabilities.resourceTypes().size() + " resource types).",
                    capabilities);
        } catch (ServerOperationException e) {
            log.info("connection test failed baseUrl={} reason={}", redactedBase(session), e.displayMessage());
            return ConnectionResult.unreachable(e.displayMessage());
        }
    }

    @Override
    public SearchResultPage search(ServerSession session, SearchRequest request) throws ServerOperationException {
        requireSession(session);
        requireRequest(request);
        long started = System.currentTimeMillis();
        IGenericClient client = newClient(session);
        try {
            ca.uhn.fhir.rest.gclient.IQuery<org.hl7.fhir.r4.model.Bundle> query = client.search()
                    .forResource(request.resourceType())
                    .returnBundle(org.hl7.fhir.r4.model.Bundle.class)
                    .count(request.pageSize());
            List<ICriterion<?>> criteria = criteriaOf(request);
            for (int i = 0; i < criteria.size(); i++) {
                // HAPI wants the first parameter through where() and the rest through and().
                query = i == 0 ? query.where(criteria.get(i)) : query.and(criteria.get(i));
            }
            org.hl7.fhir.r4.model.Bundle bundle = query.execute();
            SearchResultPage page = pageOf(bundle);
            log.info("search baseUrl={} request={} results={} total={} elapsedMs={}",
                    redactedBase(session), request, page.resources().size(), page.total(),
                    System.currentTimeMillis() - started);
            return page;
        } catch (RuntimeException e) {
            throw convertFailure("Search failed", e);
        }
    }

    @Override
    public SearchResultPage nextPage(ServerSession session, SearchRequest request, String pageToken)
            throws ServerOperationException {
        requireSession(session);
        if (pageToken == null || pageToken.isBlank()) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "There is no next page to fetch.");
        }
        long started = System.currentTimeMillis();
        IGenericClient client = newClient(session);
        try {
            org.hl7.fhir.r4.model.Bundle bundle = client.loadPage().byUrl(pageToken)
                    .andReturnBundle(org.hl7.fhir.r4.model.Bundle.class).execute();
            SearchResultPage page = pageOf(bundle);
            log.info("search next page baseUrl={} results={} elapsedMs={}",
                    redactedBase(session), page.resources().size(), System.currentTimeMillis() - started);
            return page;
        } catch (RuntimeException e) {
            throw convertFailure("Could not fetch the next page", e);
        }
    }

    @Override
    public IBaseResource read(ServerSession session, String resourceType, String resourceId)
            throws ServerOperationException {
        requireSession(session);
        if (resourceType == null || resourceType.isBlank() || resourceId == null || resourceId.isBlank()) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "A resource type and id are required to read a resource.");
        }
        long started = System.currentTimeMillis();
        IGenericClient client = newClient(session);
        try {
            IBaseResource resource = client.read().resource(resourceType)
                    .withId(resourceId).execute();
            log.info("read baseUrl={} resource={}/{} elapsedMs={}",
                    redactedBase(session), resourceType, resourceId, System.currentTimeMillis() - started);
            return resource;
        } catch (RuntimeException e) {
            throw convertFailure("Could not read " + resourceType + "/" + resourceId, e);
        }
    }

    /**
     * Creates the HAPI client for one operation. Vendor plugins override this to add
     * authentication, custom timeouts or a different transport; the default pins JSON
     * encoding and applies the server's extra headers.
     */
    protected IGenericClient newClient(ServerSession session) {
        FhirServerConfiguration server = session.server();
        IGenericClient client = context.newRestfulGenericClient(server.baseUrl());
        client.setEncoding(ca.uhn.fhir.rest.api.EncodingEnum.JSON);
        for (java.util.Map.Entry<String, String> header : server.extraHeaders().entrySet()) {
            if (header.getKey() != null && !header.getKey().isBlank() && header.getValue() != null) {
                client.registerInterceptor(new StaticHeaderInterceptor(header.getKey(), header.getValue()));
            }
        }
        // Credentials come from the session, never from the configuration, so persisting
        // a server definition can never persist a secret.
        ServerAuthentication authentication = session.authentication();
        if (authentication instanceof BasicServerAuthentication basic) {
            client.registerInterceptor(new StaticHeaderInterceptor("Authorization", basic.authorizationHeaderValue()));
        }
        return client;
    }

    /**
     * Turns a generic request into HAPI search criteria. Vendor plugins override this to
     * translate names, add fixed parameters or rewrite values.
     */
    protected List<ICriterion<?>> criteriaOf(SearchRequest request) {
        List<ICriterion<?>> criteria = new ArrayList<>();
        for (SearchCriterion criterion : request.criteria()) {
            criteria.add(new StringClientParam(criterion.name()).matchesExactly().value(criterion.value()));
        }
        return criteria;
    }

    /**
     * Converts a HAPI/client failure into the plugin error model. Vendor plugins override
     * this to map proprietary error bodies onto {@link ServerOperationException} kinds.
     */
    protected ServerOperationException convertFailure(String action, Throwable failure) {
        if (failure instanceof ServerOperationException operationFailure) {
            return operationFailure;
        }
        if (failure instanceof ResourceNotFoundException) {
            return new ServerOperationException(ServerOperationException.Kind.NOT_FOUND,
                    action + ": the resource does not exist.", statusOf(failure), failure);
        }
        if (failure instanceof AuthenticationException) {
            return new ServerOperationException(ServerOperationException.Kind.UNAUTHORIZED,
                    action + ": the server rejected the credentials.", statusOf(failure), failure);
        }
        if (failure instanceof ForbiddenOperationException) {
            return new ServerOperationException(ServerOperationException.Kind.FORBIDDEN,
                    action + ": the server refused the operation.", statusOf(failure), failure);
        }
        if (failure instanceof InvalidRequestException) {
            return new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    action + ": the server rejected the request.", statusOf(failure), failure);
        }
        // Transport failures carry HTTP-ish status codes of their own, so they are tested
        // before the generic HTTP-error branch below.
        if (failure instanceof FhirClientConnectionException || hasTransportCause(failure)) {
            return new ServerOperationException(ServerOperationException.Kind.UNREACHABLE,
                    action + ": the server could not be reached. Check the base URL.", failure);
        }
        if (failure instanceof BaseServerResponseException responseFailure) {
            return new ServerOperationException(ServerOperationException.Kind.SERVER_ERROR,
                    action + ": the server returned an error.", responseFailure.getStatusCode(), failure);
        }
        String detail = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure == null ? "unknown error" : failure.getClass().getSimpleName()
                : failure.getMessage();
        return new ServerOperationException(ServerOperationException.Kind.SERVER_ERROR,
                action + ": " + detail, failure);
    }

    /** True when the failure chain contains a network/transport problem. */
    private static boolean hasTransportCause(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof javax.net.ssl.SSLException) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private void requireSession(ServerSession session) throws ServerOperationException {
        if (session == null || session.server() == null) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST, "A server is required.");
        }
        if (session.authentication() == null) {
            throw new ServerOperationException(ServerOperationException.Kind.UNAUTHORIZED,
                    "This operation needs an authentication method.");
        }
        // Only anonymous and basic are implemented. Anything else is a newer mechanism this
        // build has no interceptor for, so say so plainly rather than silently sending
        // an unauthenticated request.
        if (!session.authentication().isAnonymous()
                && !(session.authentication() instanceof BasicServerAuthentication)) {
            throw new ServerOperationException(ServerOperationException.Kind.UNAUTHORIZED,
                    "This build supports anonymous and basic authentication only, not '"
                            + session.authentication().type() + "'.");
        }
    }

    private static void requireRequest(SearchRequest request) throws ServerOperationException {
        if (request == null) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "A search request is required.");
        }
    }

    private static Integer statusOf(Throwable failure) {
        if (failure instanceof BaseServerResponseException responseFailure) {
            return responseFailure.getStatusCode();
        }
        return null;
    }

    private static ServerCapabilities capabilitiesOf(org.hl7.fhir.r4.model.CapabilityStatement statement) {
        if (statement == null) {
            return ServerCapabilities.empty();
        }
        String version = statement.getFhirVersion() == null ? "" : statement.getFhirVersion().toCode();
        List<String> types = new ArrayList<>();
        if (!statement.getRest().isEmpty() && statement.getRest().get(0) != null) {
            for (org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent resource
                    : statement.getRest().get(0).getResource()) {
                if (resource != null && resource.getType() != null && !resource.getType().isBlank()
                        && !types.contains(resource.getType())) {
                    types.add(resource.getType());
                }
            }
        }
        return new ServerCapabilities(version, types, false);
    }

    private static SearchResultPage pageOf(org.hl7.fhir.r4.model.Bundle bundle) {
        if (bundle == null) {
            return SearchResultPage.empty();
        }
        List<IBaseResource> resources = new ArrayList<>();
        for (org.hl7.fhir.r4.model.Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry != null && entry.getResource() != null) {
                resources.add(entry.getResource());
            }
        }
        Integer total = bundle.hasTotal() ? bundle.getTotal() : null;
        String next = null;
        for (org.hl7.fhir.r4.model.Bundle.BundleLinkComponent link : bundle.getLink()) {
            if (link != null && "next".equalsIgnoreCase(link.getRelation()) && link.getUrl() != null
                    && !link.getUrl().isBlank()) {
                next = link.getUrl();
                break;
            }
        }
        return new SearchResultPage(resources, total, next);
    }

    private static String redactedBase(ServerSession session) {
        if (session == null || session.server() == null) {
            return "(no server)";
        }
        return session.server().baseUrl();
    }

    /**
     * Adds one static header to every request. Only used for non-secret configured
     * headers; credentials must never travel this way.
     */
    static final class StaticHeaderInterceptor extends
            ca.uhn.fhir.rest.client.interceptor.SimpleRequestHeaderInterceptor {

        StaticHeaderInterceptor(String name, String value) {
            super(name, value);
        }
    }
}
