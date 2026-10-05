package com.example.fhirviewer.server;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.server.rest.RestHeaders;
import com.example.fhirviewer.server.rest.RestOperationOutcome;

/**
 * What a discovered operation answered with, in a form the caller can act on.
 *
 * <p>The point of this type is that a caller never has to know in advance whether a vendor
 * endpoint returns a FHIR resource, a Bundle, JSON, XML or a log extract — which is
 * exactly the knowledge it cannot have, because the endpoint belongs to a plugin. The
 * server's own content type and, where the content allows it, what the body parses as
 * decide {@link #kind()}; the raw body and the HTTP metadata are always kept alongside, so
 * an answer this application does not recognise is still fully available.</p>
 *
 * <p>A response that arrived is a result, not an exception — including a {@code 404} or a
 * {@code 500}. A plugin endpoint's own error body is frequently the most useful thing it
 * produces, and reducing it to "HTTP 500" would throw away the only diagnostic there is.
 * {@link #isSuccess()} and {@link #issues()} are how a caller tells the two apart. Only a
 * failure to complete the exchange at all — no route to the host, a timeout — is reported
 * as a {@link ServerOperationException}, matching every other call in this application.</p>
 *
 * <p><b>Not logged in full.</b> A body can be a whole export, or a patient record.
 * {@link #toString()} prints the status, the kind and the size, and nothing else.</p>
 */
public final class ServerOperationResult {

    private final String operationId;
    private final int statusCode;
    private final RestHeaders headers;
    private final ServerOperation.ResultKind kind;
    private final String body;
    private final IBaseResource resource;
    private final List<RestOperationOutcome> issues;

    private ServerOperationResult(String operationId, int statusCode, RestHeaders headers,
            ServerOperation.ResultKind kind, String body, IBaseResource resource,
            List<RestOperationOutcome> issues) {
        this.operationId = operationId;
        this.statusCode = statusCode;
        this.headers = headers;
        this.kind = kind;
        this.body = body;
        this.resource = resource;
        this.issues = List.copyOf(issues);
    }

    /** Builds a result. Package-private: results come from {@link PluginOperationClient}. */
    static ServerOperationResult of(String operationId, int statusCode, RestHeaders headers,
            ServerOperation.ResultKind kind, String body, IBaseResource resource,
            List<RestOperationOutcome> issues) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("An operation id is required.");
        }
        return new ServerOperationResult(operationId, statusCode,
                Objects.requireNonNull(headers, "headers"),
                Objects.requireNonNull(kind, "kind"),
                body, resource,
                issues == null ? List.of() : issues);
    }

    /** The id of the operation that produced this. */
    public String operationId() {
        return operationId;
    }

    /** The HTTP status the server returned. */
    public int statusCode() {
        return statusCode;
    }

    /** The response headers. Never {@code null}; credential-bearing values are redacted. */
    public RestHeaders headers() {
        return headers;
    }

    /** The declared content type, or {@code null} when the server did not say. */
    public String contentType() {
        return headers.first("Content-Type");
    }

    /** What the answer turned out to be. Never {@link ServerOperation.ResultKind#ANY}. */
    public ServerOperation.ResultKind kind() {
        return kind;
    }

    /** The raw body, or {@code null} when the server sent none. */
    public String body() {
        return body;
    }

    /** The body, or the empty string, for a caller that will search or measure it. */
    public String bodyOrEmpty() {
        return body == null ? "" : body;
    }

    /**
     * The parsed FHIR resource, when the answer was one.
     *
     * <p>Empty for JSON, XML and text answers, and for a FHIR body this build cannot
     * parse. The raw {@link #body()} is still there in that case, so a caller that can
     * make sense of it is not blocked by this build's model coverage.</p>
     */
    public Optional<IBaseResource> resource() {
        return Optional.ofNullable(resource);
    }

    /** True when the server answered with a 2xx. */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * The issues from a FHIR {@code OperationOutcome}.
     *
     * <p>Parsed by the shared {@link com.example.fhirviewer.server.rest.RestOutcomeParser},
     * so they are already plain strings with no FHIR model on them. May be non-empty on a
     * 2xx — a server can return warnings with a success — which is why this is separate
     * from {@link #isSuccess()}.</p>
     */
    public List<RestOperationOutcome> issues() {
        return issues;
    }

    /** True when the server reported any problem at all, warning or worse. */
    public boolean hasIssues() {
        return !issues.isEmpty();
    }

    /**
     * The server's own explanation, or a short one derived from the status.
     *
     * <p>Same rule as {@link com.example.fhirviewer.server.rest.RestResponse#diagnostics()}:
     * what the server said beats what the status implies, and a raw JSON or XML document
     * is not shown to a user as a sentence.</p>
     */
    public String diagnostics() {
        if (!issues.isEmpty()) {
            return issues.get(0).describe();
        }
        if (body != null && !body.isBlank()) {
            String trimmed = body.strip();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("<") && trimmed.length() <= 500) {
                return trimmed;
            }
        }
        return "The server answered with HTTP " + statusCode + ".";
    }

    /** Status, kind and body size only: a body can be an export, or a patient record. */
    @Override
    public String toString() {
        return "ServerOperationResult[operation=" + operationId
                + ", status=" + statusCode
                + ", kind=" + kind
                + ", issues=" + issues.size()
                + ", body=" + (body == null ? "none" : body.length() + " characters") + "]";
    }
}
