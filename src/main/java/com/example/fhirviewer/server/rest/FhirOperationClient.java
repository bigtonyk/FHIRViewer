package com.example.fhirviewer.server.rest;

import java.util.Map;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.fhirviewer.fhir.FhirParseException;
import com.example.fhirviewer.fhir.ResourceParser;
import com.example.fhirviewer.server.FhirOperationRequest;
import com.example.fhirviewer.server.PatchFormat;
import com.example.fhirviewer.server.ServerOperationException;
import com.example.fhirviewer.server.ServerSession;

import ca.uhn.fhir.context.FhirContext;

/**
 * The two standard FHIR interactions that go over the generic REST transport rather than
 * through the typed HAPI client: {@code PATCH} and {@code $}-operations.
 *
 * <p><b>Why these two and not the others.</b> Read, search, create, update, delete and
 * metadata all have a result type known before the request is sent, and HAPI's generic
 * client already turns each of them into an {@link IBaseResource} with its own
 * conformance handling, content negotiation and per-base-URL validation cache.
 * Re-issuing those over a second transport would duplicate all of that and hand the UI
 * raw JSON it would then have to parse anyway. PATCH and operations are the opposite
 * case: the patch format lives in the {@code Content-Type}, and the result type of an
 * operation is not known until the server answers, so the caller chooses both at run
 * time — which is exactly what {@link RestClient} is for. The division is by result
 * kind, not by accident.</p>
 *
 * <p><b>No second parser.</b> Replies are read with the project's existing
 * {@link ResourceParser}, so an operation returning a Bundle, a Parameters, an
 * OperationOutcome or a resource type this build has never heard of is the same code
 * path as parsing a file the user opened.</p>
 *
 * <p><b>Lifecycle.</b> One instance performs one operation and is then closed, matching
 * how {@code StandardFhirRestPlugin} builds a client per operation. The client comes
 * from {@link JdkHttpRestClient#forSession(ServerSession)}, so the base URL, the
 * timeout and the credentials all come from the session the application already holds;
 * this class never reads a credential or builds a header of its own.</p>
 */
public final class FhirOperationClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FhirOperationClient.class);

    private final RestClient client;
    private final FhirContext context;
    private final ResourceParser parser;

    /** Uses the shared R4 context, which is what the plugins already parse with. */
    public FhirOperationClient(ServerSession session) {
        this(session, com.example.fhirviewer.fhir.FhirContextFactory.r4());
    }

    /** Uses an explicit context, for a server speaking another FHIR version. */
    public FhirOperationClient(ServerSession session, FhirContext context) {
        Objects.requireNonNull(session, "session");
        this.client = JdkHttpRestClient.forSession(session);
        this.context = Objects.requireNonNull(context, "context");
        this.parser = new ResourceParser(context);
    }

    /**
     * Invokes one {@code $}-operation and returns whatever resource it produced.
     *
     * <p>The verb follows the specification rather than convenience: a call with no
     * parameters is a {@code GET}, and a call with parameters is a {@code POST} carrying
     * a {@code Parameters} resource. Servers differ in which operations they will accept
     * a {@code GET} for, and a blanket {@code POST} would be refused where a
     * parameterless {@code GET} is allowed.</p>
     *
     * @return the result resource, or {@code null} when the server answered successfully
     *         with no body — legal for an operation defined to return nothing
     * @throws ServerOperationException with kind {@code UNSUPPORTED} when the server does
     *         not implement the operation, or the mapped failure when it refused
     */
    public IBaseResource invoke(FhirOperationRequest request) throws ServerOperationException {
        Objects.requireNonNull(request, "request");
        String action = "invoke " + request.describe();
        long started = System.currentTimeMillis();
        RestResponse response = request.parameters().isEmpty()
                ? client.get(request.path())
                : client.post(request.path(), parametersBody(request.parameters()),
                        RestRequest.DEFAULT_CONTENT_TYPE);
        return readResult(action, response, started);
    }

    /**
     * Applies a patch to an existing resource.
     *
     * <p>{@code If-Match} is sent whenever the caller knows the current version, in the
     * same weak-ETag form the other writes use, so a patch computed against a resource
     * somebody has since changed is refused rather than applied.</p>
     *
     * @param versionId the {@code meta.versionId} the patch was computed against, or
     *                  {@code null} to patch unconditionally
     * @return the patched resource when the server returned one, otherwise {@code null}
     * @throws ServerOperationException with kind {@code CONFLICT} when the server reports
     *         the resource changed underneath the patch
     */
    public IBaseResource patch(String resourceType, String resourceId, String versionId,
            String body, PatchFormat format) throws ServerOperationException {
        Objects.requireNonNull(format, "format");
        if (body == null || body.isBlank()) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "A patch needs a body to apply.");
        }
        String path = resourceType + "/" + resourceId;
        String action = "patch " + path;
        RestRequest.Builder request = RestRequest.builder(RestMethod.PATCH, path)
                .body(body)
                .contentType(format.contentType());
        if (versionId != null && !versionId.isBlank()) {
            request.header("If-Match", "W/" + versionId.trim());
        }
        long started = System.currentTimeMillis();
        return readResult(action, client.execute(request.build()), started);
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * Turns a completed exchange into either a resource or a typed failure.
     *
     * <p>A refusal goes through {@link RestFailures#httpFailure}, so an operation the
     * server does not implement comes back as {@code UNSUPPORTED} and one it rejects
     * with an {@code OperationOutcome} comes back carrying what the server said — the
     * same shape as every other failure this application surfaces.</p>
     */
    private IBaseResource readResult(String action, RestResponse response, long started)
            throws ServerOperationException {
        if (!response.isSuccess()) {
            log.info("{} refused: HTTP {} elapsedMs={}", action, response.statusCode(),
                    System.currentTimeMillis() - started);
            throw RestFailures.httpFailure(action, response);
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            log.info("{} succeeded with no body, HTTP {} elapsedMs={}", action, response.statusCode(),
                    System.currentTimeMillis() - started);
            return null;
        }
        try {
            IBaseResource resource = parser.parse(body, action);
            log.info("{} returned {} elapsedMs={}", action, resource.fhirType(),
                    System.currentTimeMillis() - started);
            return resource;
        } catch (FhirParseException unreadable) {
            // The status was a success, so this is not a refusal: the reply simply was
            // not a FHIR resource. Saying so beats a parse error the user cannot act on.
            throw new ServerOperationException(ServerOperationException.Kind.SERVER_ERROR,
                    action + ": the server's answer was not a FHIR resource.",
                    response.statusCode(), unreadable);
        }
    }

    /**
     * Writes the input parameters as a FHIR {@code Parameters} resource.
     *
     * <p>Every value is sent as a {@code valueString}. That is the form an R4 server
     * accepts for the common operations ({@code $expand}, {@code $everything}, bulk
     * data) and the only one producible without a per-operation type table; a typed
     * date, part or nested resource is a later increment on the request model rather
     * than a guess made here.</p>
     */
    private String parametersBody(Map<String, String> parameters) {
        Parameters body = new Parameters();
        parameters.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) {
                body.addParameter().setName(name).setValue(new StringType(value));
            }
        });
        return context.newJsonParser().encodeResourceToString(body);
    }
}
