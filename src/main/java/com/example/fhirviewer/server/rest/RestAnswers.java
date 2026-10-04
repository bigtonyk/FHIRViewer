package com.example.fhirviewer.server.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.fhir.FhirContextFactory;
import com.example.fhirviewer.fhir.ResourceParser;
import com.example.fhirviewer.model.ResourceFormat;

/**
 * Decides what a {@link RestResponse} is, so the console never has to.
 *
 * <p>The operation screen has {@code ui.ServerOperationResults} for the same job on a
 * plugin's answer. This is the equivalent for a raw response, and it exists because a REST
 * call can return anything at all: a Bundle, a JSON document from a vendor API, an HTML
 * error page, or nothing. The UI needs one word per answer to pick a pane, and working that
 * out from content types and first characters at three call sites is how the three sites
 * come to disagree.
 *
 * <p><b>Nothing here throws.</b> Every answer becomes a {@link RestAnswer}, including a
 * body that could not be parsed and a body that was not what its content type claimed. A
 * malformed 500 from a proxy is the case this exists for: it must produce a readable
 * refusal, not an exception that replaces the useful text with a parse error.
 *
 * <p><b>A parsed resource always wins.</b> If the text turns out to be a Bundle even though
 * the server labelled it {@code text/plain}, it is still a Bundle — a Bundle in the
 * resource viewer is more use than the same Bundle as a wall of unparsed text.
 */
public final class RestAnswers {

    /** Shared R4 parser, the same context the plugins and the outcome parser use. */
    private static final ResourceParser PARSER = new ResourceParser(FhirContextFactory.r4());

    /** Used only to fill in issues a caller-supplied response did not carry. */
    private static final RestOutcomeParser OUTCOMES = new RestOutcomeParser();

    private RestAnswers() {
    }

    /**
     * Classifies a response.
     *
     * @param response what the server sent; {@code null} yields an empty answer rather
     *                 than a failure, so a caller cannot show the word "null" to a user
     */
    public static RestAnswer classify(RestResponse response) {
        if (response == null) {
            return new RestAnswer(RestAnswer.Kind.EMPTY, 0, RestHeaders.empty(), null, null, List.of());
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return new RestAnswer(RestAnswer.Kind.EMPTY, response.statusCode(),
                    response.headers(), null, null, response.issues());
        }
        Optional<IBaseResource> parsed = resourceIn(body, response.contentType());
        if (parsed.isPresent()) {
            RestAnswer.Kind kind = kindOfResource(parsed.get());
            return new RestAnswer(kind, response.statusCode(), response.headers(), body,
                    parsed.get(), issuesFor(kind, parsed.get(), response.issues()));
        }
        return new RestAnswer(kindOfUnparsed(response.contentType(), body),
                response.statusCode(), response.headers(), body, null, response.issues());
    }

    /**
     * The issues to report, preferring the ones the transport already parsed.
     *
     * <p>{@code JdkHttpRestClient} parses the {@code OperationOutcome} body before the
     * response reaches here, so in normal operation this is the first branch and nothing
     * is parsed twice. The second branch exists because it is cheap: when the resource has
     * already been read — it has, that is how the kind was decided — the issues can come
     * straight off it. Without it, a response assembled anywhere other than the transport
     * would classify as {@code OPERATION_OUTCOME} and then report "HTTP 400" with no
     * reason, throwing away the one sentence the server wrote to explain itself.
     */
    private static List<RestOperationOutcome> issuesFor(RestAnswer.Kind kind,
            IBaseResource resource, List<RestOperationOutcome> alreadyParsed) {
        if (kind != RestAnswer.Kind.OPERATION_OUTCOME || !alreadyParsed.isEmpty()) {
            return alreadyParsed;
        }
        return OUTCOMES.fromResource(resource);
    }

    /**
     * The FHIR resource in this text, if there is one.
     *
     * <p>The format comes from the content type when the server declared one, and from the
     * text itself when it did not. It is deliberately <em>not</em> guessed when the server
     * declared something else: a log extract served as {@code text/plain} happens to start
     * with a brace often enough that guessing would parse server logs as FHIR.
     */
    public static Optional<IBaseResource> resourceIn(String body, String contentType) {
        ResourceFormat format = formatOf(contentType, body);
        if (format == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(PARSER.parse(body, format, "the response"));
        } catch (RuntimeException notFhir) {
            // Expected for any non-FHIR document, which is the whole reason this is optional.
            return Optional.empty();
        }
    }

/** The pane for a resource that did parse. */
    private static RestAnswer.Kind kindOfResource(IBaseResource resource) {
        String type = resource.fhirType() == null ? "" : resource.fhirType();
        if (RestFailures.outcomeTypeName().equalsIgnoreCase(type)) {
            return RestAnswer.Kind.OPERATION_OUTCOME;
        }
        if ("Bundle".equalsIgnoreCase(type)) {
            return RestAnswer.Kind.BUNDLE;
        }
        return RestAnswer.Kind.FHIR_RESOURCE;
    }

    /** The format to parse as, or {@code null} when the text is not a document we read. */
    private static ResourceFormat formatOf(String contentType, String body) {
        String declared = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (declared.contains("json")) {
            return ResourceFormat.JSON;
        }
        if (declared.contains("xml")) {
            return ResourceFormat.XML;
        }
        if (!declared.isEmpty()) {
            // The server told us what this is, and it is neither JSON nor XML.
            return null;
        }
        // No declaration: fall back to the first character, which is all there is to go on.
        String text = body.stripLeading();
        if (text.startsWith("{") || text.startsWith("[")) {
            return ResourceFormat.JSON;
        }
        return text.startsWith("<") ? ResourceFormat.XML : null;
    }

    /** The pane for a body that is not FHIR. */
    private static RestAnswer.Kind kindOfUnparsed(String contentType, String body) {
        String declared = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (declared.contains("json")) {
            return RestAnswer.Kind.JSON;
        }
        if (declared.contains("xml")) {
            return RestAnswer.Kind.XML;
        }
        // An explicit content type outranks the body's shape: a log extract whose first
        // line happens to be JSON is still a log extract.
        if (!declared.isEmpty()) {
            return RestAnswer.Kind.TEXT;
        }
        String text = body.stripLeading();
        if (text.startsWith("{") || text.startsWith("[")) {
            return RestAnswer.Kind.JSON;
        }
        return text.startsWith("<") ? RestAnswer.Kind.XML : RestAnswer.Kind.TEXT;
    }

/**
     * One line describing the answer: what it is, how big, how long, and why if it failed.
     *
     * <p>The status is included even on success, because a call that answered {@code 200}
     * with an {@code OperationOutcome} of errors is a case where the status alone would
     * read as a success. Bytes and milliseconds rather than a character count, because
     * those are the two numbers an HTTP-minded user expects beside a response.
     */
    public static String summary(RestAnswer answer, long elapsedMillis) {
        if (answer == null) {
            return "The request produced no answer.";
        }
        StringBuilder text = new StringBuilder();
        text.append(label(answer.kind())).append(" (HTTP ").append(answer.statusCode()).append(')');
        text.append(" — ").append(formatBytes(answer.byteCount()));
        if (elapsedMillis >= 0) {
            text.append(" — ").append(elapsedMillis).append(" ms");
        }
        if (!answer.isSuccess()) {
            text.append(": ").append(diagnosticsOf(answer));
        }
        return text.toString();
    }

    /** The same line without a duration, for callers that did not measure one. */
    public static String summary(RestAnswer answer) {
        return summary(answer, -1);
    }

    /**
     * The diagnostic lines, in the order a user should read them.
     *
     * <p>One line per issue, then the server's own words when it refused, so a failure
     * always ends with something more useful than a number.
     */
    public static List<String> diagnostics(RestAnswer answer) {
        List<String> lines = new ArrayList<>();
        if (answer == null) {
            return lines;
        }
        for (RestOperationOutcome issue : answer.issues()) {
            lines.add(issue.describe());
        }
        if (!answer.isSuccess() && lines.isEmpty()) {
            lines.add(diagnosticsOf(answer));
        }
        return lines;
    }

    /** What the server said about a refusal, falling back to the status. */
    private static String diagnosticsOf(RestAnswer answer) {
        if (!answer.issues().isEmpty()) {
            return answer.issues().get(0).describe();
        }
        String body = answer.body() == null ? "" : answer.body().strip();
        // A JSON or XML document is noise in a one-line message; a short plain-text one is not.
        if (!body.isEmpty() && body.length() <= 500
                && !body.startsWith("{") && !body.startsWith("<")) {
            return body;
        }
        return "The server answered with HTTP " + answer.statusCode() + ".";
    }

    private static String label(RestAnswer.Kind kind) {
        return kind == RestAnswer.Kind.EMPTY
                ? "No body"
                : kind.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** A human byte count: {@code 812 B}, {@code 4.1 KB}, {@code 1.2 MB}. */
    static String formatBytes(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}