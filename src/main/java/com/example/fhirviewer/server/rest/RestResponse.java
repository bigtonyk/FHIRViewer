package com.example.fhirviewer.server.rest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a server sent back: the status, the headers, the body, and any FHIR problems.
 *
 * <p>A response is returned for every HTTP exchange that reached the server and produced a
 * status, including {@code 404} and {@code 500}. Only a failure to complete the exchange
 * at all — no route to the host, a timeout, a body that would not read — is reported as a
 * {@link com.example.fhirviewer.server.ServerOperationException} instead. Keeping the two
 * apart is what lets a caller handle "the server said no, and here is why" without a
 * try/catch, while still catching the case where there was never an answer.
 *
 * <p>When the body was a FHIR {@code OperationOutcome} its issues are already parsed into
 * {@link #issues()}, so the diagnostics a server uses to explain a refusal are available
 * without the caller owning a FHIR parser.
 */
public final class RestResponse {

    private final int statusCode;
    private final RestHeaders headers;
    private final String body;
    private final List<RestOperationOutcome> issues;

    private RestResponse(int statusCode, RestHeaders headers, String body,
            List<RestOperationOutcome> issues) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
        this.issues = issues;
    }

    /**
     * Builds a response.
     *
     * @param statusCode the HTTP status; must be between 100 and 599
     * @param headers    the response headers, never {@code null}
     * @param body       the response body as text, or {@code null} when there was none
     * @param issues     the parsed {@code OperationOutcome} issues, never {@code null}
     * @throws IllegalArgumentException when the status is not a real HTTP status
     */
    public static RestResponse of(int statusCode, RestHeaders headers, String body,
            List<RestOperationOutcome> issues) {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("Not an HTTP status code: " + statusCode);
        }
        return new RestResponse(statusCode,
                Objects.requireNonNull(headers, "headers"),
                body,
                RestOperationOutcome.copyOf(Objects.requireNonNull(issues, "issues")));
    }

    /** A response with no body and no parsed issues, for a status that carried neither. */
    public static RestResponse ofStatus(int statusCode, RestHeaders headers) {
        return of(statusCode, headers, null, List.of());
    }

    /** The HTTP status. */
    public int statusCode() {
        return statusCode;
    }

    /** The response headers. Never {@code null}. */
    public RestHeaders headers() {
        return headers;
    }

    /** The response body, or {@code null} when the server sent none. */
    public String body() {
        return body;
    }

    /** The body, or the empty string when there was none, for callers that will parse it. */
    public String bodyOrEmpty() {
        return body == null ? "" : body;
    }

    /** The declared content type, or {@code null} when the server did not say. */
    public String contentType() {
        return headers.first("Content-Type");
    }

    /** The issues from a FHIR {@code OperationOutcome}. Never {@code null}; may be empty. */
    public List<RestOperationOutcome> issues() {
        return issues;
    }

    /** The first issue, when the server reported any. */
    public Optional<RestOperationOutcome> primaryIssue() {
        return issues.isEmpty() ? Optional.empty() : Optional.of(issues.get(0));
    }

    /** True when the status is in the 2xx range. */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    /** True when the status is in the 4xx range. */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /** True when the status is in the 5xx range. */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    /**
     * The server's own explanation, or a short one derived from the status.
     *
     * <p>Prefers what the server said over what the status implies, because that is the
     * difference between "Unknown search parameter 'foo'" and "Bad Request". Falls back to
     * the body when it was not a parsable {@code OperationOutcome}, and to the status line
     * when there was no usable body at all.
     */
    public String diagnostics() {
        if (!issues.isEmpty()) {
            return issues.get(0).describe();
        }
        if (body != null && !body.isBlank() && issues.isEmpty()) {
            String trimmed = body.strip();
            // A JSON or XML document is noise in a dialog; a short plain-text message is not.
            if (!trimmed.startsWith("{") && !trimmed.startsWith("<") && trimmed.length() <= 500) {
                return trimmed;
            }
        }
        return "The server answered with HTTP " + statusCode + ".";
    }

    /**
     * A description safe to log: status, content type, issue count, body size.
     *
     * <p>The body is never printed. A server can echo the submitted resource back, and that
     * resource is patient data.
     */
    @Override
    public String toString() {
        return "RestResponse[status=" + statusCode
                + ", contentType=" + contentType()
                + ", issues=" + issues.size()
                + ", body=" + (body == null ? "none" : body.length() + " characters") + "]";
    }
}
