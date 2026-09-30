package com.example.fhirviewer.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.server.ServerOperation;
import com.example.fhirviewer.server.ServerOperationResult;
import com.example.fhirviewer.server.rest.RestOperationOutcome;

/**
 * Decides how one {@link ServerOperationResult} should be shown.
 *
 * <p>Split out from the JavaFX screen that shows it, because the decision is the part that
 * has to be right and the part that can be tested. A caller cannot know in advance whether a
 * vendor endpoint answers with a FHIR resource, a Bundle, JSON, XML or a log extract, so
 * this class asks the result what it turned out to be and answers with the one word the UI
 * needs to pick a pane. Nothing here knows what any endpoint means.</p>
 *
 * <p><b>Prefer the parsed resource over the raw body.</b> When the transport managed to
 * parse the answer as FHIR, that resource is the better answer even if the operation
 * declared plain text: a Bundle that lands in the resource viewer is more useful than the
 * same Bundle as a wall of JSON. The declared {@link ServerOperation.ResultKind} only
 * breaks ties.</p>
 *
 * <p><b>A refusal is still a result.</b> A {@code 404} or a {@code 500} arrives here as an
 * answer, not an exception, and is displayed: the body a vendor endpoint returns on failure
 * is frequently the only diagnostic there is. {@link #summary} says what went wrong without
 * pretending the call succeeded.</p>
 */
public final class ServerOperationResults {

    /** The panes the UI can show an answer in. */
    public enum Kind {
        /** A single FHIR resource: the existing resource viewer. */
        FHIR_RESOURCE,
        /** A {@code Bundle}: the existing resource/tree UI. */
        BUNDLE,
        /** A {@code OperationOutcome}: shown as a diagnostic list. */
        OPERATION_OUTCOME,
        /** A JSON document: the existing JSON viewer. */
        JSON,
        /** An XML document: the existing XML viewer. */
        XML,
        /** Plain text: an appropriate text view. */
        TEXT,
        /** No body at all; only the status is worth showing. */
        EMPTY
    }

    private ServerOperationResults() {
    }

    /**
     * What a result turned out to be.
     *
     * @param kind     the pane to show it in
     * @param resource the parsed resource, when there is one
     * @param body     the raw body, which is always kept even when a resource was parsed
     * @param issues   the {@code OperationOutcome} issues, possibly empty
     */
    public record Presentation(Kind kind, IBaseResource resource, String body,
            List<RestOperationOutcome> issues) {

        /** The body, or the empty string, so a caller never has to null-check it. */
        public String bodyOrEmpty() {
            return body == null ? "" : body;
        }
    }

    /**
     * Classifies a result.
     *
     * <p>The order of the questions is the whole logic: a parsed FHIR answer wins, then an
     * empty body, then a body that is not FHIR — by declared kind, then content type, then
     * its first character. A vendor endpoint that declares nothing useful still lands
     * somewhere sensible, and a 204 does not land in a text pane.</p>
     */
    public static Presentation classify(ServerOperationResult result) {
        if (result == null) {
            return new Presentation(Kind.EMPTY, null, null, List.of());
        }
        String body = result.body();
        List<RestOperationOutcome> issues = new ArrayList<>(result.issues());
        Optional<IBaseResource> parsed = result.resource();

        if (parsed.isPresent()) {
            IBaseResource resource = parsed.get();
            String type = resource.fhirType();
            if ("Bundle".equals(type)) {
                return new Presentation(Kind.BUNDLE, resource, body, issues);
            }
            if ("OperationOutcome".equals(type)) {
                return new Presentation(Kind.OPERATION_OUTCOME, resource, body, issues);
            }
            return new Presentation(Kind.FHIR_RESOURCE, resource, body, issues);
        }
        if (issues.isEmpty() && (body == null || body.isBlank())) {
            return new Presentation(Kind.EMPTY, null, body, issues);
        }
        ServerOperation.ResultKind declared = result.kind();
        if (declared == ServerOperation.ResultKind.OPERATION_OUTCOME) {
            return new Presentation(Kind.OPERATION_OUTCOME, null, body, issues);
        }
        return new Presentation(fromContentType(result.contentType(), declared, body), null, body, issues);
    }

    /**
     * The pane for an answer that is not FHIR.
     *
     * <p>Precedence is evidence first, declaration second, body shape third. An explicit
     * content type is the strongest signal and is honoured outright — a {@code text/plain}
     * body is text even when it happens to begin with a brace. Failing that, what the
     * plugin declared is believed. Only when the server said nothing useful and the plugin
     * declared nothing either is the body's first character used, which is what keeps a
     * mislabelled vendor document from arriving as one enormous line of plain text.</p>
     */
    private static Kind fromContentType(String contentType, ServerOperation.ResultKind declared, String body) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!type.isEmpty()) {
            if (type.contains("json")) {
                return Kind.JSON;
            }
            if (type.contains("xml")) {
                return Kind.XML;
            }
            // An explicit text type outranks a body that looks like something else: a log
            // extract whose first line is a JSON fragment is still a log extract.
            if (type.startsWith("text/")) {
                return Kind.TEXT;
            }
        }
        if (declared == ServerOperation.ResultKind.JSON) {
            return Kind.JSON;
        }
        if (declared == ServerOperation.ResultKind.XML) {
            return Kind.XML;
        }
        if (body != null) {
            String stripped = body.strip();
            if (stripped.startsWith("{") || stripped.startsWith("[")) {
                return Kind.JSON;
            }
            if (stripped.startsWith("<")) {
                return Kind.XML;
            }
        }
        if (declared == ServerOperation.ResultKind.TEXT) {
            return Kind.TEXT;
        }
        return Kind.TEXT;
    }

    /**
     * One line describing what came back: the operation, the status and how it was shown.
     *
     * <p>The status is always included, including on success, because an operation that
     * answered {@code 200} with an {@code OperationOutcome} of errors is a case where the
     * status alone would read as a success.</p>
     */
    public static String summary(ServerOperationResult result) {
        if (result == null) {
            return "The operation produced no result.";
        }
        Presentation presentation = classify(result);
        StringBuilder text = new StringBuilder();
        text.append(presentation.kind() == Kind.EMPTY ? "No body" : presentation.kind().name().toLowerCase(
                Locale.ROOT).replace('_', ' '));
        text.append(" (HTTP ").append(result.statusCode()).append(')');
        if (!result.isSuccess()) {
            text.append(": ").append(result.diagnostics());
        } else if (result.hasIssues()) {
            text.append(": ").append(result.issues().get(0).describe());
        }
        return text.toString();
    }

    /**
     * The diagnostic lines for a result, in the order a user should read them.
     *
     * <p>One line per {@code OperationOutcome} issue, then a line naming the status when the
     * answer was a refusal, so a failure always ends with the number that a server's own
     * support channel will ask for.</p>
     */
    public static List<String> diagnostics(ServerOperationResult result) {
        List<String> lines = new ArrayList<>();
        if (result == null) {
            return lines;
        }
        for (RestOperationOutcome issue : result.issues()) {
            lines.add(issue.describe());
        }
        if (!result.isSuccess() && lines.isEmpty()) {
            lines.add(result.diagnostics());
        }
        return lines;
    }
}
