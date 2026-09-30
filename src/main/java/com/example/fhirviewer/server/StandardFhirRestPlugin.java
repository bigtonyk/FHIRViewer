package com.example.fhirviewer.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.fhirviewer.server.rest.FhirOperationClient;
import com.example.fhirviewer.server.rest.JdkHttpRestClient;
import com.example.fhirviewer.server.rest.RestMethod;
import com.example.fhirviewer.server.rest.RestOperationOutcome;
import com.example.fhirviewer.server.rest.RestOutcomeParser;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.apache.ApacheRestfulClientFactory;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.gclient.ICriterion;
import ca.uhn.fhir.rest.gclient.StringClientParam;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;

/**
 * The standard FHIR REST plugin: metadata, search, read and the write verbs over plain
 * FHIR REST, plus PATCH and {@code $}-operations.
 *
 * <p>Authentication is not this class's business. The session's
 * {@link ServerAuthentication} says which headers its mechanism needs and
 * {@link #newClient} applies them, so anonymous, basic and bearer all travel the same
 * path and a new mechanism needs no change here. A method that claims to authenticate
 * but supplies no header is refused with a clear message rather than silently sending
 * an unauthenticated request. Custom headers from the server configuration are applied
 * to every client this plugin creates.</p>
 *
 * <p>Vendor plugins can extend this class and override single hooks
 * ({@link #newClient}, {@link #criteriaOf}, {@link #convertFailure}) instead of
 * reimplementing the whole REST flow.</p>
 *
 * <p><b>Which transport serves which operation.</b> The interactions whose result type
 * is known before the request is sent — metadata, read, search, paging, create, update,
 * delete — go through HAPI, which already turns them into an {@code IBaseResource} and
 * is what the vendor subclasses hook into. {@code PATCH} and {@code $}-operations go
 * through {@link FhirOperationClient} on the generic transport, because the patch format
 * is chosen by the caller in {@code Content-Type} and an operation's result type is not
 * known until the server answers. That split is deliberate, not drift: both paths share
 * one session, one authentication mechanism and one failure model, and Phase 4 added
 * the second path rather than replacing the first.</p>
 */
public class StandardFhirRestPlugin implements FhirServerPlugin {

    /** The id stored in server definitions served by this plugin. */
    public static final String PLUGIN_ID = "standard-rest";

    private static final Logger log = LoggerFactory.getLogger(StandardFhirRestPlugin.class);

    /**
     * The FHIR bulk data operations, which every conforming server is expected to offer.
     *
     * <p>Declared here rather than per vendor so that {@link SmileCdrPlugin} and
     * {@link FirelyPlugin}, which both extend this class, inherit them: bulk data is part of
     * the FHIR specification, not a vendor extension, and duplicating it per plugin would
     * give the three a chance to drift apart.</p>
     *
     * <p>Both are asynchronous. Each answers {@code 202 Accepted} with a
     * {@code Content-Location} header naming a URL to poll, and the payload arrives later
     * as NDJSON files wrapped in Binary resources — so neither completes within the request
     * this screen makes. They are offered because starting a bulk job is genuinely useful
     * and is the operation itself; note the completion behaviour in the description rather
     * than leaving the user to infer it from a bare 202.</p>
     */
    private static final List<ServerOperation> BULK_OPERATIONS = List.of(
            ServerOperation.builder("$export", RestMethod.GET, "$export")
                    .displayName("Bulk export")
                    .description("Starts a bulk export of the whole server, or of the"
                            + " groups named by _type. Answers 202 Accepted immediately with a"
                            + " Content-Location header; the export runs in the background and"
                            + " the completed NDJSON files are fetched separately, so this"
                            + " screen will show the acknowledgement rather than the data.")
                    .category(ServerOperation.Category.STANDARD)
                    .requiresAuthentication()
                    .queryParameter("_type", "Comma-separated resource types to export."
                            + " Exports everything when omitted.", false)
                    .queryParameter("_since", "Only resources changed after this instant.", false)
                    .queryParameter("outputFormat", "application/fhir+ndjson by default.", false)
                    .returns(ServerOperation.ResultKind.JSON)
                    .build(),
            ServerOperation.builder("$import", RestMethod.POST, "$import")
                    .displayName("Bulk import")
                    .description("Starts a bulk import from NDJSON files already held on the"
                            + " server. The files must be uploaded as Binary resources first;"
                            + " this operation references them rather than carrying the data."
                            + " Answers 202 Accepted with a Content-Location header to poll.")
                    .category(ServerOperation.Category.STANDARD)
                    .requiresAuthentication()
                    .requiresBody("application/fhir+json")
                    .returns(ServerOperation.ResultKind.JSON)
                    .build());

    private final FhirContext context;

    /**
     * One HAPI client factory per distinct timeout, kept for the life of the plugin.
     *
     * <p>Two things force a factory to exist rather than a bare
     * {@code newRestfulGenericClient}. The server configuration's {@code timeoutMillis}
     * was carried by {@link FhirServerConfiguration} and read by nobody, and HAPI 8
     * exposes the socket and connect deadlines only on the factory, not on the client.
     * And the factory, not the shared {@code FhirContext}, owns the set of base URLs it
     * has already validated: a fresh factory per operation would send an extra unlogged
     * {@code GET /metadata} before every read. Keyed by the effective timeout so two
     * servers with different deadlines cannot overwrite each other's setting, and so a
     * server definition with no explicit deadline still reuses one factory.</p>
     */
    private final ConcurrentMap<Integer, ApacheRestfulClientFactory> factories = new ConcurrentHashMap<>();

    /** Parses a refused response's {@code OperationOutcome} for its diagnostics. */
    private final RestOutcomeParser outcomeParser;

    /** Creates the plugin on the shared application FHIR context. */
    public StandardFhirRestPlugin() {
        this(com.example.fhirviewer.fhir.FhirContextFactory.r4());
    }

    /** Creates the plugin on an explicit FHIR context (used by tests). */
    public StandardFhirRestPlugin(FhirContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.outcomeParser = new RestOutcomeParser(context);
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
        // Shown in the server dialog's plugin list, so it has to keep up with what this
        // plugin can actually do. It said "anonymous access" after Phase 3 wired
        // credentials in, and would have said "metadata, search, read" after Phase 4
        // added the write verbs, PATCH and $operations.
        return "Plain FHIR REST over HTTP: metadata, search, paging, read, create, update,"
                + " delete, patch and $operations, with whatever credentials the server has.";
    }

    @Override
    public List<String> supportedFhirVersions() {
        return List.of("R4");
    }

    /**
     * The bulk data operations every conforming FHIR server should offer.
     *
     * <p>Declared on this class so that the Smile CDR and Firely plugins, which both extend
     * it, inherit them without restating the paths. That inheritance is also why their own
     * declarations are additions rather than replacements.</p>
     */
    @Override
    public List<ServerOperation> availableOperations() {
        return BULK_OPERATIONS;
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
            ServerCapabilities capabilities = ServerCapabilityReader.of(statement);
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
            SearchResultPage page = ServerSearchBundleReader.of(bundle);
            log.info("search baseUrl={} request={} results={} total={} elapsedMs={}",
                    redactedBase(session), request, page.resources().size(), page.total(),
                    System.currentTimeMillis() - started);
            return page;
        } catch (RuntimeException e) {
            throw convertFailure("Search failed", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #pageAt}: a page token is a server-minted URL, and the
     * {@code previous}, {@code first} and {@code last} links travel the same road.</p>
     */
    @Override
    public SearchResultPage nextPage(ServerSession session, SearchRequest request, String pageToken)
            throws ServerOperationException {
        return pageAt(session, pageToken);
    }

    @Override
    public SearchResultPage pageAt(ServerSession session, String pageUrl) throws ServerOperationException {
        requireSession(session);
        if (pageUrl == null || pageUrl.isBlank()) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "There is no page to fetch; the search returned no such link.");
        }
        long started = System.currentTimeMillis();
        IGenericClient client = newClient(session);
        try {
            org.hl7.fhir.r4.model.Bundle bundle = client.loadPage().byUrl(pageUrl)
                    .andReturnBundle(org.hl7.fhir.r4.model.Bundle.class).execute();
            SearchResultPage page = ServerSearchBundleReader.of(bundle);
            log.info("fetched page baseUrl={} results={} links={} elapsedMs={}",
                    redactedBase(session), page.resources().size(), page.links().describe(),
                    System.currentTimeMillis() - started);
            return page;
        } catch (RuntimeException e) {
            throw convertFailure("Could not fetch that page of results", e);
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
     * {@inheritDoc}
     *
     * <p>Writes the resource to the server with {@code POST [type]} and reads back the id
     * the server assigned.</p>
     */
    @Override
    public ServerWriteResult create(ServerSession session, IBaseResource resource)
            throws ServerOperationException {
        requireSession(session);
        requireResource(resource, "create");
        try {
            IGenericClient client = newClient(session);
            // HAPI's create builder returns a MethodOutcome, not the resource: the server
            // assigns the id, so it is read back from the outcome rather than from the
            // resource we sent.
            ca.uhn.fhir.rest.api.MethodOutcome outcome = client.create()
                    .resource(resource)
                    .execute();
            return ServerWriteResult.created(requireAssignedId("create", idPartOf(outcome)),
                    versionPartOf(outcome));
        } catch (ServerOperationException alreadyMapped) {
            throw alreadyMapped;
        } catch (Exception failure) {
            throw convertFailure("create", failure);
        }
    }

    /**
     * Guards the one case where a successful-looking create still has nothing to return.
     *
     * <p>A server that accepts the write but reports no id leaves the editor unable to
     * address the resource it just created. Failing with a clear message beats letting
     * the null travel into {@link ServerWriteResult#created(String, String)} and surface
     * as an unexplained {@code NullPointerException}.</p>
     */
    private static String requireAssignedId(String verb, String assignedId)
            throws ServerOperationException {
        if (assignedId == null) {
            throw new ServerOperationException(ServerOperationException.Kind.SERVER_ERROR,
                    "The server accepted the " + verb + " but did not return a resource id, "
                            + "so the new resource cannot be addressed.");
        }
        return assignedId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes with {@code PUT [type]/[id]}, sending {@code If-Match} when the origin
     * carries a {@code versionId} so the server can refuse a stale write. HAPI's generic
     * client has no conditional-update builder, so the header is attached through the
     * same interceptor mechanism the static headers use.</p>
     */
    @Override
    public ServerWriteResult update(ServerSession session, IBaseResource resource, ServerOrigin origin)
            throws ServerOperationException {
        requireSession(session);
        requireResource(resource, "update");
        if (origin == null) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "update needs to know which server resource came from.");
        }
        if (!origin.isSaved()) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "update needs a server-assigned id; use create for a new resource.");
        }
        try {
            IGenericClient client = newClient(session);
            applyIfMatch(client, origin);
            client.update()
                    .resource(resource)
                    .withId(origin.resourceId())
                    .execute();
            // HAPI's generic update builder discards the response body, so the new version
            // is genuinely unknown here. The next write therefore cannot be conditional,
            // which the UI reports honestly rather than implying a conflict check ran.
            return ServerWriteResult.updated(origin.resourceId(), null);
        } catch (Exception failure) {
            throw convertFailure("update", failure);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes with {@code DELETE [type]/[id]}, sending {@code If-Match} when the origin
     * carries a version, so a delete cannot silently remove a resource somebody else has
     * since changed.</p>
     */
    @Override
    public void delete(ServerSession session, ServerOrigin origin) throws ServerOperationException {
        requireSession(session);
        if (origin == null || !origin.isSaved()) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "delete needs a server-assigned id.");
        }
        try {
            IGenericClient client = newClient(session);
            applyIfMatch(client, origin);
            client.delete()
                    .resourceById(origin.resourceType(), origin.resourceId())
                    .execute();
        } catch (Exception failure) {
            throw convertFailure("delete", failure);
        }
    }

    @Override
    public boolean supportsWrite() {
        return true;
    }

    @Override
    public boolean supportsPatch() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sent over the generic REST transport rather than HAPI: the patch format is the
     * request's {@code Content-Type} and HAPI's patch builder offers no way to set it,
     * so a caller could not say which of the three formats it meant. The conditional
     * {@code If-Match} and the {@code 412} to {@code CONFLICT} mapping are the same ones
     * update and delete already use, and a server that does not accept PATCH answers
     * with a refusal the shared status table already names {@code UNSUPPORTED}.</p>
     */
    @Override
    public ServerWriteResult patch(ServerSession session, ServerOrigin origin, String body,
            PatchFormat format) throws ServerOperationException {
        requireSession(session);
        if (origin == null || !origin.isSaved()) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "patch needs a server-assigned id; use create for a new resource.");
        }
        if (format == null) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "A patch needs a format; it is what the server reads the Content-Type for.");
        }
        try (FhirOperationClient operations = new FhirOperationClient(session, context)) {
            IBaseResource patched = operations.patch(origin.resourceType(), origin.resourceId(),
                    origin.versionId(), body, format);
            // A server may answer a PATCH with 200 and no body. Reporting the version we
            // already knew is then the only honest option, and it keeps the next write's
            // If-Match meaningful instead of silently dropping the conflict check.
            String version = versionIdOf(patched);
            return ServerWriteResult.updated(origin.resourceId(),
                    version == null ? origin.versionId() : version);
        } catch (ServerOperationException alreadyMapped) {
            throw alreadyMapped;
        } catch (Exception failure) {
            throw convertFailure("patch", failure);
        }
    }

    @Override
    public IBaseResource invoke(ServerSession session, FhirOperationRequest request)
            throws ServerOperationException {
        requireSession(session);
        if (request == null) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "An operation request is required.");
        }
        long started = System.currentTimeMillis();
        try (FhirOperationClient operations = new FhirOperationClient(session, context)) {
            IBaseResource result = operations.invoke(request);
            log.info("invoke baseUrl={} operation={} result={} elapsedMs={}", redactedBase(session),
                    request, result == null ? "(none)" : result.fhirType(),
                    System.currentTimeMillis() - started);
            return result;
        } catch (ServerOperationException alreadyMapped) {
            throw alreadyMapped;
        } catch (Exception failure) {
            throw convertFailure("invoke " + request.describe(), failure);
        }
    }

    /** Attaches a conditional-write header when the origin knows the current version. */
    private static void applyIfMatch(IGenericClient client, ServerOrigin origin) {
        if (origin.hasVersion()) {
            client.registerInterceptor(
                    new StaticHeaderInterceptor("If-Match", "W/" + origin.versionId().trim()));
        }
    }

    /** Reads the server-assigned id out of a create/update outcome. */
    private static String idPartOf(ca.uhn.fhir.rest.api.MethodOutcome outcome) {
        if (outcome == null || outcome.getId() == null) {
            return null;
        }
        String id = outcome.getId().getIdPart();
        return id == null || id.isBlank() ? null : id;
    }

    /**
     * Reads the new version out of a create/update outcome.
     *
     * <p>Preferred from the id's version part, falling back to {@code meta.versionId} on the
     * returned resource for servers that only report it there. Returns {@code null} when the
     * server reported no version at all, which the caller surfaces honestly rather than
     * implying a conflict check is in place.</p>
     */
    private static String versionPartOf(ca.uhn.fhir.rest.api.MethodOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        if (outcome.getId() != null) {
            String fromId = outcome.getId().getVersionIdPart();
            if (fromId != null && !fromId.isBlank()) {
                return fromId;
            }
        }
        IBaseResource resource = outcome.getResource();
        if (resource != null && resource.getMeta() != null) {
            // IBaseMetaType has no hasVersionId() — getVersionId() returns null when absent.
            String fromMeta = resource.getMeta().getVersionId();
            if (fromMeta != null && !fromMeta.isBlank()) {
                return fromMeta;
            }
        }
        return null;
    }

    /** Extracts the trailing id from a {@code Location} like {@code Patient/123/_history/4}. */
    private static String lastPathSegment(String location) {
        String path = location;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i].trim();
            if (!segment.isEmpty() && !segment.startsWith("_")) {
                return segment;
            }
        }
        return null;
    }

    private static void requireResource(IBaseResource resource, String action) throws ServerOperationException {
        if (resource == null) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    action + " needs a resource to write.");
        }
    }

    /**
     * Creates the HAPI client for one operation. Vendor plugins override this to add
     * authentication, custom timeouts or a different transport; the default pins JSON
     * encoding, applies the server's configured deadline and its extra headers.
     */
    protected IGenericClient newClient(ServerSession session) {
        FhirServerConfiguration server = session.server();
        IGenericClient client = factoryFor(server).newGenericClient(server.baseUrl());
        client.setEncoding(ca.uhn.fhir.rest.api.EncodingEnum.JSON);
        for (Map.Entry<String, String> header : server.extraHeaders().entrySet()) {
            if (header.getKey() != null && !header.getKey().isBlank() && header.getValue() != null) {
                client.registerInterceptor(new StaticHeaderInterceptor(header.getKey(), header.getValue()));
            }
        }
        // Credentials come from the session, never from the configuration, so persisting
        // a server definition can never persist a secret. The mechanism says which
        // headers it needs; this code does not know or care which mechanism that is, so
        // adding a bearer token or a vendor API key needs no change here.
        for (Map.Entry<String, List<String>> header
                : session.authentication().requestHeaders().asMap().entrySet()) {
            for (String value : header.getValue()) {
                client.registerInterceptor(new StaticHeaderInterceptor(header.getKey(), value));
            }
        }
        return client;
    }

    /**
     * The client factory carrying this server's deadline, created once per distinct one.
     *
     * <p>A timeout of zero or less means "the plugin default", exactly as
     * {@link ServerDefinition} documents, and is resolved here so every client built for
     * that server shares one deadline and one validation cache.</p>
     */
    private ApacheRestfulClientFactory factoryFor(FhirServerConfiguration server) {
        int timeout = effectiveTimeoutMillis(server);
        return factories.computeIfAbsent(timeout, millis -> {
            ApacheRestfulClientFactory factory = new ApacheRestfulClientFactory(context);
            factory.setSocketTimeout(millis);
            factory.setConnectTimeout(millis);
            // Stated rather than left to the library default: ONCE means the factory
            // remembers which base URLs it has already checked, so a client created per
            // operation does not re-fetch metadata every time. This plugin reads
            // capabilities explicitly when the user asks for them, so nothing is lost.
            factory.setServerValidationMode(ServerValidationModeEnum.ONCE);
            return factory;
        });
    }

    /**
     * The deadline for one server, in milliseconds.
     *
     * <p>Exposed so a caller — and a test — can ask what deadline the plugin will
     * actually apply, rather than inferring it from a value it configured.
     */
    int effectiveTimeoutMillis(FhirServerConfiguration server) {
        int configured = server == null ? 0 : server.timeoutMillis();
        return configured > 0 ? configured : JdkHttpRestClient.DEFAULT_TIMEOUT_MILLIS;
    }

    /**
     * Turns a generic request into HAPI search criteria. Vendor plugins override this to
     * translate names, add fixed parameters or rewrite values.
     *
     * <p><b>Raw criteria are skipped here.</b> They are sent by
     * {@link #rawSearch}, because a raw string cannot be expressed as an
     * {@link ICriterion} — which is the whole point of it. Building one as a
     * {@link StringClientParam} with {@code matchesExactly()} would quietly normalise away
     * exactly the prefixes, modifiers and chains the user typed. {@link SearchRequest}
     * forbids a request that mixes the two kinds, so this method never sees a mixture.</p>
     */
    protected List<ICriterion<?>> criteriaOf(SearchRequest request) {
        List<ICriterion<?>> criteria = new ArrayList<>();
        for (SearchCriterion criterion : request.criteria()) {
            if (criterion.isRaw()) {
                continue;
            }
            criteria.add(new StringClientParam(criterion.name()).matchesExactly().value(criterion.value()));
        }
        return criteria;
    }

    /**
     * Converts a HAPI/client failure into the plugin error model. Vendor plugins override
     * this to map proprietary error bodies onto {@link ServerOperationException} kinds.
     *
     * <p>The kind now comes from the shared
     * {@link ServerOperationException#kindOfStatus(int) table} rather than from a chain
     * of {@code instanceof} tests, so this path and the generic REST transport cannot
     * report the same status differently. What the server said about it is read from the
     * response body's {@code OperationOutcome} and carried on the exception, which is
     * the difference between "the server returned an error" and "Unknown search
     * parameter 'foo'". The body itself is never put in a message: it can echo a
     * submitted resource back.</p>
     */
    protected ServerOperationException convertFailure(String action, Throwable failure) {
        if (failure instanceof ServerOperationException operationFailure) {
            return operationFailure;
        }
        // Transport failures carry HTTP-ish status codes of their own, so they are tested
        // before the generic HTTP-error branch below.
        if (failure instanceof FhirClientConnectionException || hasTransportCause(failure)) {
            return new ServerOperationException(ServerOperationException.Kind.UNREACHABLE,
                    action + ": the server could not be reached. Check the base URL.", failure);
        }
        if (failure instanceof BaseServerResponseException responseFailure) {
            int status = responseFailure.getStatusCode();
            String diagnostics = diagnosticsOf(responseFailure);
            return new ServerOperationException(ServerOperationException.kindOfStatus(status),
                    action + ": " + (diagnostics == null ? describeStatus(status) : diagnostics),
                    status, failure, diagnostics);
        }
        String detail = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure == null ? "unknown error" : failure.getClass().getSimpleName()
                : failure.getMessage();
        return new ServerOperationException(ServerOperationException.Kind.SERVER_ERROR,
                action + ": " + detail, failure);
    }

    /**
     * What the server said, read out of the refused response's {@code OperationOutcome}.
     *
     * <p>The raw body is handed to the shared {@link RestOutcomeParser} rather than
     * inspected here: it already handles JSON and XML, tolerates a vendor extension, and
     * never throws on a body that is not an {@code OperationOutcome} at all. A server
     * that answered an error with HTML — a proxy page, typically — yields nothing, and
     * the status is all the caller gets, which is the honest description.</p>
     */
    private String diagnosticsOf(BaseServerResponseException failure) {
        List<RestOperationOutcome> issues = outcomeParser.parse(failure.getResponseBody());
        if (issues.isEmpty()) {
            return null;
        }
        List<String> described = new ArrayList<>();
        for (RestOperationOutcome issue : issues) {
            described.add(issue.describe());
        }
        return String.join("; ", described);
    }

    /** A last-resort sentence for a refusal that carried no parsable explanation. */
    private static String describeStatus(int status) {
        return switch (ServerOperationException.kindOfStatus(status)) {
            case NOT_FOUND -> "the resource does not exist.";
            case UNAUTHORIZED -> "the server rejected the credentials.";
            case FORBIDDEN -> "the server refused the operation.";
            case BAD_REQUEST -> "the server rejected the request.";
            case CONFLICT -> "the resource changed on the server after it was read.";
            case UNSUPPORTED -> "the server does not support that operation.";
            default -> "the server answered with HTTP " + status + ".";
        };
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
        // A mechanism that claims to authenticate but contributes no header cannot be
        // honoured: the request would go out unauthenticated and the server would answer
        // 401, which sends the user looking for the wrong problem. Say so instead.
        if (!session.authentication().isAnonymous()
                && session.authentication().requestHeaders().isEmpty()) {
            throw new ServerOperationException(ServerOperationException.Kind.UNAUTHORIZED,
                    "This build cannot send '" + session.authentication().type()
                            + "' credentials, because that method supplies no request header.");
        }
    }

    private static void requireRequest(SearchRequest request) throws ServerOperationException {
        if (request == null) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "A search request is required.");
        }
    }

    /**
     * The {@code meta.versionId} a resource carries, or {@code null}.
     *
     * <p>Read through {@code IBaseResource} rather than by casting to an R4 class, so a
     * server speaking another FHIR version still yields its version instead of a
     * {@code ClassCastException} in the middle of a successful write.</p>
     */
    private static String versionIdOf(IBaseResource resource) {
        if (resource == null || resource.getMeta() == null) {
            return null;
        }
        try {
            // getVersionId() returns null when the resource has none, so there is no
            // hasVersionId() to ask on the version-neutral interface.
            String version = resource.getMeta().getVersionId();
            return version == null || version.isBlank() ? null : version;
        } catch (RuntimeException notThisVersion) {
            // A version this build does not model is a version we cannot compare against,
            // which is the same thing as not having one: no If-Match on the next write.
            return null;
        }
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
