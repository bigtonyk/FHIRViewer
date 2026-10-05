package com.example.fhirviewer.server.rest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * What a {@link RestResponse} turned out to be, once it has been looked at.
 *
 * <p>Split from {@link RestAnswers} because it is data that travels, and because the
 * interesting part — {@link #kind()} — is the only word the UI needs in order to choose a
 * pane. Everything else is there so no pane has to re-derive it: the raw body is always
 * kept even when a resource was parsed out of it, because a user debugging a REST call
 * needs the bytes the server sent and not a re-serialisation of them.
 *
 * @param kind     the pane that should show this
 * @param statusCode the HTTP status, so a pane can colour a failure without re-reading it
 * @param headers  the response headers, already redacted by {@link RestHeaders#toString()}
 * @param body     the raw body, or {@code null} when the server sent none
 * @param resource the parsed FHIR resource, or {@code null} when there was none
 * @param issues   the parsed {@code OperationOutcome} issues, possibly empty
 */
public record RestAnswer(Kind kind, int statusCode, RestHeaders headers, String body,
        IBaseResource resource, List<RestOperationOutcome> issues) {

    /**
     * The kinds of thing a REST call can come back with.
     *
     * <p>Deliberately the same set {@code ui.ServerOperationResults.Kind} uses for a
     * plugin operation, so the console and the operation screen present like one tool.
     * The console version lives here because that class sits in the {@code ui} package and
     * this record is used by code that has no business importing from there.
     */
    public enum Kind {
        /** A single FHIR resource: the existing resource viewer. */
        FHIR_RESOURCE,
        /** A {@code Bundle}: the resource viewer plus the entry list. */
        BUNDLE,
        /** An {@code OperationOutcome}: the diagnostics list. */
        OPERATION_OUTCOME,
        /** A JSON document that is not FHIR. */
        JSON,
        /** An XML document that is not FHIR. */
        XML,
        /** Plain text. */
        TEXT,
        /** No body at all; only the status is worth showing. */
        EMPTY
    }

    public RestAnswer {
        headers = headers == null ? RestHeaders.empty() : headers;
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** The body, or the empty string, so a caller never has to null-check it. */
    public String bodyOrEmpty() {
        return body == null ? "" : body;
    }

    /**
     * The size of the body in bytes.
     *
     * <p>Bytes rather than characters, because bytes are what a server reports in
     * {@code Content-Length} and what an HTTP-minded user expects to see next to a
     * duration. A character count is also misleading here: a Bundle of non-ASCII patient
     * names is several bytes per character.
     */
    public int byteCount() {
        return body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
    }

    /** True when the status is in the 2xx range. */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    /** The parsed resource, when there was one. */
    public Optional<IBaseResource> resourceIfPresent() {
        return Optional.ofNullable(resource);
    }

    /**
     * A description safe to log: kind, status, issue count and size.
     *
     * <p>The body is never printed. A response body is patient data, and this record is the
     * kind of thing that ends up in an exception message.
     */
    @Override
    public String toString() {
        return "RestAnswer[" + kind + ", HTTP " + statusCode + ", issues=" + issues.size()
                + ", bytes=" + byteCount() + "]";
    }
}