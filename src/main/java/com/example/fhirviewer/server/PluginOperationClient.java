package com.example.fhirviewer.server;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.fhirviewer.fhir.FhirParseException;
import com.example.fhirviewer.fhir.ResourceParser;
import com.example.fhirviewer.server.rest.JdkHttpRestClient;
import com.example.fhirviewer.server.rest.RestClient;
import com.example.fhirviewer.server.rest.RestRequest;
import com.example.fhirviewer.server.rest.RestResponse;

import ca.uhn.fhir.context.FhirContext;

/**
 * Runs a {@link ServerOperation} the plugin declared, using the shared REST transport.
 *
 * <p>This is the whole of the "common mechanism to execute a discovered operation". It
 * knows how to turn a descriptor plus an invocation into an HTTP exchange, and it has no
 * idea what any particular endpoint <em>means</em> — that is the plugin's business, and the
 * descriptor is where the plugin said it. A vendor endpoint therefore needs no code in the
 * application at all: a plugin adds a descriptor, and this class performs it.</p>
 *
 * <p><b>Two layers of checking, in this order.</b> Everything that can be decided without a
 * network call is decided first — the operation exists, the session can authenticate, the
 * required parameters are present, the body rule is satisfied, the header is one the
 * operation declared and is safe to send. A request is only then built and sent. This is
 * not just tidiness: an operation that needs credentials and a caller with none should
 * produce a clear local message, not a {@code 401} from a server that was never going to
 * say anything more useful.</p>
 *
 * <p><b>Header safety.</b> A caller may only set a header the operation declared, and only
 * if the name is not a credential header, not one the transport computes, and not carrying
 * a line break. Without that, "the plugin declared an operation" would be a licence for a
 * caller to attach an {@code Authorization} header of its own — a second credential path
 * around {@link ServerAuthentication}, with none of its redaction.</p>
 *
 * <p><b>Lifecycle.</b> One instance performs one operation and is then closed, matching
 * {@link com.example.fhirviewer.server.rest.FhirOperationClient}. The client comes from
 * {@link JdkHttpRestClient#forSession(ServerSession)}, so the base URL, the timeout and
 * the credentials all come from the session the application already holds. This class
 * never reads a credential and never builds an {@code Authorization} value of its own.</p>
 *
 * <p><b>Threading.</b> Blocks on the network, so the caller must not be the JavaFX
 * application thread — the same rule as every other plugin method.</p>
 */
public final class PluginOperationClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginOperationClient.class);

    /** The FHIR type name a Bundle is recognised by, compared case-insensitively. */
    private static final String BUNDLE_TYPE = "Bundle";

    /**
     * Headers the HTTP transport computes from the request it is actually sending.
     *
     * <p>A declared value for one of these is either ignored or describes a different
     * request than the one being made — {@code Content-Length} in particular would then
     * disagree with the body, which is a request-smuggling primitive rather than a
     * mistake.</p>
     */
    private static final Set<String> TRANSPORT_OWNED_HEADERS = Set.of(
            "content-length", "transfer-encoding", "connection", "keep-alive",
            "upgrade", "te", "trailer", "expect", "host", "content-type", "accept");

    private final RestClient client;
    private final FhirContext context;
    private final ResourceParser parser;
    private final ServerAuthentication authentication;

    /** Uses the shared R4 context, which is what the plugins already parse with. */
    public PluginOperationClient(ServerSession session) {
        this(session, com.example.fhirviewer.fhir.FhirContextFactory.r4());
    }

    /** Uses an explicit context, for a server speaking another FHIR version. */
    public PluginOperationClient(ServerSession session, FhirContext context) {
        Objects.requireNonNull(session, "session");
        this.client = JdkHttpRestClient.forSession(session);
        this.context = Objects.requireNonNull(context, "context");
        this.parser = new ResourceParser(context);
        // Read once, and only to answer "is this session anonymous". The credential itself
        // stays on the session and is applied by the transport; nothing is copied here.
        this.authentication = session.authentication();
    }

    /**
     * Runs one declared operation and returns whatever the server answered.
     *
     * <p>Every refusal that can be decided locally is decided here, before a socket is
     * opened; the returned {@link ServerOperationResult} is therefore only about the
     * server's own answer, including an answer that was a refusal.</p>
     *
     * @throws ServerOperationException when the session cannot run the operation, a
     *         required input is missing, a header is not one the operation may send, or the
     *         exchange never completed
     */
    public ServerOperationResult execute(ServerOperation operation, ServerOperationInvocation invocation)
            throws ServerOperationException {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(invocation, "invocation");
        if (!operation.id().equals(invocation.operationId())) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "This call is for '" + invocation.operationId() + "', not '"
                            + operation.id() + "'.");
        }
        requireAuthentication(operation);
        String path = requirePath(operation, invocation);
        RestRequest request = buildRequest(operation, invocation, path);

        long started = System.currentTimeMillis();
        RestResponse response = client.execute(request);
        ServerOperationResult result = readResult(operation, response);
        log.info("operation {} baseUrl={} status={} kind={} issues={} elapsedMs={}",
                operation.id(), client.baseUrl(), result.statusCode(), result.kind(),
                result.issues().size(), System.currentTimeMillis() - started);
        return result;
    }

    /**
     * Refuses to send a request on behalf of a caller that cannot authenticate.
     *
     * <p>Only when the plugin asked for it. A public vendor endpoint is perfectly valid, and
     * requiring credentials on every declared operation would make the seam unusable for
     * the read-only extensions that are the most common case.</p>
     */
    private void requireAuthentication(ServerOperation operation) throws ServerOperationException {
        if (!operation.requiresAuthentication()) {
            return;
        }
        ServerAuthentication mechanism = this.authentication;
        if (mechanism == null || mechanism.isAnonymous()) {
            throw new ServerOperationException(ServerOperationException.Kind.UNAUTHORIZED,
                    "The '" + operation.displayName() + "' operation needs credentials for this server.");
        }
    }

    /**
     * Resolves the path, requiring every value the template names.
     *
     * <p>A missing value is reported as a bad request rather than an internal error: the
     * caller left a field empty, and that is something the user can fix.</p>
     */
    private static String requirePath(ServerOperation operation, ServerOperationInvocation invocation)
            throws ServerOperationException {
        for (ServerOperationParameter parameter : operation.pathParameters()) {
            if (parameter.isRequired() && invocation.pathParameter(parameter.name()).isEmpty()) {
                throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                        "The '" + operation.displayName() + "' operation needs '" + parameter.name() + "'.");
            }
        }
        try {
            return OperationPathTemplate.resolve(operation.pathTemplate(), invocation.pathParameters());
        } catch (IllegalArgumentException unusable) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "The '" + operation.displayName() + "' operation: " + unusable.getMessage());
        }
    }

    /**
     * Assembles the HTTP request from the descriptor and the caller's values.
     *
     * <p>Content negotiation follows the declaration: a declared result kind implies an
     * {@code Accept}, and a declared body content type becomes the {@code Content-Type}.
     * Neither is guessed when the plugin has said nothing, so the transport's FHIR defaults
     * stand — the same defaults every other request in this application uses.</p>
     */
    private RestRequest buildRequest(ServerOperation operation, ServerOperationInvocation invocation,
            String path) throws ServerOperationException {
        RestRequest.Builder request = RestRequest.builder(operation.method(), path);
        // The plugin said where this belongs. Saying it here rather than letting the
        // transport guess from the path is what keeps a mis-pathed administration call from
        // being sent to the wrong origin.
        if (operation.isAdministration()) {
            request.administration();
        }

        for (ServerOperationParameter parameter : operation.parametersAt(
                ServerOperationParameter.Location.QUERY)) {
            List<String> values = invocation.queryParameter(parameter.name());
            if (parameter.isRequired() && values.isEmpty()) {
                throw missingInput(operation, parameter);
            }
            values.forEach(value -> request.parameter(parameter.name(), value));
        }

        applyHeaderParameters(operation, invocation, request);
        applyBody(operation, invocation, request);
        applyAccept(operation, request);

        try {
            return request.build();
        } catch (IllegalArgumentException malformed) {
            // Reached when the descriptor asked for a body on a verb that cannot carry one.
            // The Builder already refused the equivalent case at declaration time, so this
            // is a backstop rather than the normal path.
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "The '" + operation.displayName() + "' operation cannot be sent: " + malformed.getMessage());
        }
    }

    private static ServerOperationException missingInput(ServerOperation operation,
            ServerOperationParameter parameter) {
        return new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                "The '" + operation.displayName() + "' operation needs '" + parameter.name() + "'.");
    }

    /**
     * Copies the declared header parameters the caller supplied, and refuses the rest.
     *
     * <p>The rules, in order of how much damage ignoring them would do:</p>
     * <ol>
     *   <li>the operation must have declared the header — an undeclared one is a caller's
     *       typo or an attempt to reach past the descriptor, and either way it is not sent;</li>
     *   <li>it must not be a credential header — those belong to the session's
     *       {@link ServerAuthentication}, and a second way to set one would bypass every
     *       redaction this application has;</li>
     *   <li>it must not be one the transport computes for itself;</li>
     *   <li>the value must not contain a line break, which is request splitting.</li>
     * </ol>
     */
    private void applyHeaderParameters(ServerOperation operation, ServerOperationInvocation invocation,
            RestRequest.Builder request) throws ServerOperationException {
        for (Map.Entry<String, String> supplied : invocation.headerParameters().entrySet()) {
            String name = supplied.getKey();
            Optional<ServerOperationParameter> declared = operation.parameter(name);
            if (declared.isEmpty() || declared.get().location() != ServerOperationParameter.Location.HEADER) {
                throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                        "The '" + operation.displayName() + "' operation does not take a header called '"
                                + name + "'.");
            }
            if (RequestHeaders.isSecret(name)) {
                throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                        "'" + name + "' carries credentials and is set by the server's authentication, "
                                + "not as an operation parameter.");
            }
            if (isTransportOwnedHeader(name)) {
                throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                        "'" + name + "' is set by the HTTP client and cannot be supplied as a parameter.");
            }
            if (containsLineBreak(supplied.getValue())) {
                throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                        "The value for '" + name + "' contains a line break, which cannot be sent as a header.");
            }
            request.header(name, supplied.getValue());
        }
    }

    /** True for a header the HTTP transport computes from the request it is really sending. */
    public static boolean isTransportOwnedHeader(String name) {
        return name != null && TRANSPORT_OWNED_HEADERS.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * True when a value would let a caller append headers of its own.
     *
     * <p>{@code CR} and {@code LF} are the two characters that end a header line. A value
     * containing either can be a request-splitting attempt, so it is refused here rather
     * than left for the HTTP client to reject with a much less specific message.</p>
     */
    static boolean containsLineBreak(String value) {
        return value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0);
    }

    /**
     * Applies the body, after checking it against what the operation declared.
     *
     * <p>A body on an operation that takes none is refused rather than dropped. The server
     * would ignore it, so the request would go out carrying content the user did not ask
     * to send — which for an administration endpoint is worth a local error.</p>
     */
    private static void applyBody(ServerOperation operation, ServerOperationInvocation invocation,
            RestRequest.Builder request) throws ServerOperationException {
        String body = invocation.body();
        if (!operation.isSatisfiedBy(body)) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    operation.bodyRequirementMessage());
        }
        if (body == null || body.isBlank()) {
            return;
        }
        if (operation.bodyContentType() != null && !operation.bodyContentType().isBlank()) {
            request.contentType(operation.bodyContentType());
        }
        request.body(body);
    }

    /**
     * Asks for the declared result type.
     *
     * <p>Only for the kinds where the mapping is unambiguous. A declared
     * {@link ServerOperation.ResultKind#ANY} gets the transport's FHIR default, and a
     * {@code TEXT} or {@code JSON} result gets the plain media type for that, because
     * asking a vendor export endpoint for {@code application/fhir+json} is how you get a
     * {@code 406} back from a server that would otherwise have sent a CSV.</p>
     */
    private static void applyAccept(ServerOperation operation, RestRequest.Builder request) {
        String accept = switch (operation.expectedResult()) {
            case FHIR_RESOURCE, BUNDLE, OPERATION_OUTCOME -> "application/fhir+json";
            case JSON -> "application/json";
            case XML -> "application/fhir+xml";
            case TEXT -> "text/plain";
            case NONE, ANY -> null;
        };
        if (accept != null) {
            request.accept(accept);
        }
    }

    /**
     * Turns the server's answer into a {@link ServerOperationResult}.
     *
     * <p>Classification is from evidence, not from the declaration: the server's content
     * type decides whether a body is worth parsing as FHIR at all, and a parse only
     * happens when the body actually is one. A vendor endpoint that declares
     * {@code ResultKind.ANY} and answers with a Bundle is reported as
     * {@link ServerOperation.ResultKind#BUNDLE}, because that is what it is — and an
     * endpoint that declares a Bundle and answers with plain text is reported as text,
     * with the text kept.</p>
     *
     * <p>Never throws for an unreadable body. A {@code 500} with an HTML error page is a
     * perfectly ordinary thing for a server to return, and turning it into a parse
     * exception would replace the one useful fact — the status — with a complaint about
     * markup.</p>
     */
    private ServerOperationResult readResult(ServerOperation operation, RestResponse response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return ServerOperationResult.of(operation.id(), response.statusCode(), response.headers(),
                    ServerOperation.ResultKind.NONE, body, null, response.issues());
        }
        IBaseResource resource = parseIfFhir(response, body);
        return ServerOperationResult.of(operation.id(), response.statusCode(), response.headers(),
                kindOf(response, resource), body, resource, response.issues());
    }

    /**
     * Parses a body as FHIR when the content type says it might be.
     *
     * <p>Content type first, then a cheap shape test: a {@code text/plain} or
     * {@code application/json} body from a vendor endpoint is much more often a status
     * document than a resource, and handing it to a FHIR parser produces a slower version
     * of "no".</p>
     */
    private IBaseResource parseIfFhir(RestResponse response, String body) {
        String contentType = response.contentType();
        if (contentType == null) {
            // No declaration at all. Only try when the body carries a resource's own marker,
            // which is the one case where guessing right is worth it.
            return looksLikeFhir(body) ? tryParse(body) : null;
        }
        String media = contentType.toLowerCase(Locale.ROOT);
        if (media.contains("fhir+json") || media.contains("fhir+xml")) {
            return tryParse(body);
        }
        if (media.startsWith("application/json") || media.contains("xml")) {
            return looksLikeFhir(body) ? tryParse(body) : null;
        }
        return null;
    }

    /** A body carrying a FHIR {@code resourceType} element, in either serialisation. */
    static boolean looksLikeFhir(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.stripLeading();
        boolean json = trimmed.startsWith("{") && body.contains("\"resourceType\"");
        boolean xml = trimmed.startsWith("<") && body.contains("resourceType");
        return json || xml;
    }

    /** Parses, or reports nothing. A body that is not FHIR is an outcome, not a failure. */
    private IBaseResource tryParse(String body) {
        try {
            return parser.parse(body, "the server's answer");
        } catch (FhirParseException notFhir) {
            log.debug("the server's answer is not a FHIR resource: {}", notFhir.getMessage());
            return null;
        }
    }

    /** What the answer turned out to be, from the parsed resource and the content type. */
    private static ServerOperation.ResultKind kindOf(RestResponse response, IBaseResource resource) {
        if (resource != null) {
            String type = String.valueOf(resource.fhirType());
            if (BUNDLE_TYPE.equalsIgnoreCase(type)) {
                return ServerOperation.ResultKind.BUNDLE;
            }
            if (com.example.fhirviewer.server.rest.RestFailures.outcomeTypeName()
                    .equalsIgnoreCase(type)) {
                return ServerOperation.ResultKind.OPERATION_OUTCOME;
            }
            return ServerOperation.ResultKind.FHIR_RESOURCE;
        }
        return nonFhirKind(response);
    }

    /** The kind of a body that is not a FHIR resource, read from the content type. */
    private static ServerOperation.ResultKind nonFhirKind(RestResponse response) {
        String contentType = response.contentType();
        if (contentType == null) {
            return ServerOperation.ResultKind.TEXT;
        }
        String media = contentType.toLowerCase(Locale.ROOT);
        if (media.contains("json")) {
            return ServerOperation.ResultKind.JSON;
        }
        if (media.contains("xml")) {
            return ServerOperation.ResultKind.XML;
        }
        return ServerOperation.ResultKind.TEXT;
    }

    @Override
    public void close() {
        client.close();
    }
}




