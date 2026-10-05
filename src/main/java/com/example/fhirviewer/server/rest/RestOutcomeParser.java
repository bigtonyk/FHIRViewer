package com.example.fhirviewer.server.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.StringType;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;

/**
 * Turns a server's {@code OperationOutcome} body into plain {@link RestOperationOutcome}s.
 *
 * <p>This is the only place in the REST layer that knows what a FHIR model class looks
 * like. Everything downstream — the UI, the error mapping, a vendor plugin — works with
 * the parsed issues, so adding support for another FHIR version means a second parser here
 * rather than a change across the application.
 *
 * <p>Parsing never throws. A body that is not a valid {@code OperationOutcome} — a vendor's
 * own error envelope, an HTML error page, a truncated response — yields no issues, and the
 * caller falls back to the HTTP status. A malformed error body must not be able to turn a
 * clear {@code 404} into an exception about parsing.
 */
public final class RestOutcomeParser {

    private final FhirContext context;

    /** Parses using the shared R4 context, which is what the plugins already use. */
    public RestOutcomeParser() {
        this(com.example.fhirviewer.fhir.FhirContextFactory.r4());
    }

    /** Parses using an explicit context, for a server speaking another FHIR version. */
    public RestOutcomeParser(FhirContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** The FHIR context this parser reads with. */
    public FhirContext context() {
        return context;
    }

    /**
     * Parses a response body, trying JSON and then XML.
     *
     * <p>Order matters: a server that sends {@code application/fhir+xml} still frequently
     * serves JSON on the same endpoint, and the JSON parser fails fast and cleanly on XML.
     *
     * @return the issues, or an empty list when the body is absent or is not an
     *         {@code OperationOutcome}
     */
    public List<RestOperationOutcome> parse(String body) {
        if (!RestFailures.looksLikeOutcome(body)) {
            return List.of();
        }
        List<RestOperationOutcome> fromJson = parseWith(jsonParser(), body);
        return fromJson.isEmpty() ? parseWith(xmlParser(), body) : fromJson;
    }

    /** Parses a body that has already been read into a resource. */
    public List<RestOperationOutcome> fromResource(IBaseResource resource) {
        if (!RestFailures.isOutcome(resource)) {
            return List.of();
        }
        return fromOperationOutcome((OperationOutcome) resource);
    }

    private IParser jsonParser() {
        return lenient(context.newJsonParser());
    }

    private IParser xmlParser() {
        return lenient(context.newXmlParser());
    }

    /**
     * Makes a parser tolerate elements it does not recognise.
     *
     * <p>An {@code OperationOutcome} carrying a vendor extension would otherwise be rejected
     * whole, and the caller would fall back to "HTTP 400" having thrown away the one
     * sentence the server wrote to explain itself.
     */
    private IParser lenient(IParser parser) {
        parser.setParserErrorHandler(new LenientErrorHandler(false));
        return parser;
    }

    private List<RestOperationOutcome> parseWith(IParser parser, String body) {
        try {
            return fromResource(parser.parseResource(body));
        } catch (RuntimeException notThisFormat) {
            // A body that is not in the format this parser expected. The caller falls
            // back to the HTTP status, which is the honest description of what happened.
            return List.of();
        }
    }

    /**
     * Renders a repeated element as one string, or {@code null} when it was absent.
     *
     * <p>FHIR lets a server repeat {@code expression} as often as it likes; in practice
     * there is one, and a caller showing a diagnostic has no use for a list here.
     */
    private static String joinOf(List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        return values.size() == 1 ? values.get(0) : String.join(", ", values);
    }

    private List<RestOperationOutcome> fromOperationOutcome(OperationOutcome outcome) {
        List<RestOperationOutcome> issues = new ArrayList<>();
        for (OperationOutcome.OperationOutcomeIssueComponent issue : outcome.getIssue()) {
            if (issue == null) {
                continue;
            }
            issues.add(new RestOperationOutcome(
                    issue.hasSeverity() ? issue.getSeverity().toCode() : null,
                    issue.hasCode() ? issue.getCode().toCode() : null,
                    issue.getDiagnostics(),
                    joinOf(stringsOf(issue.getExpression())),
                    stringsOf(issue.getLocation())));
        }
        return List.copyOf(issues);
    }

    /**
     * Unwraps HAPI's {@code StringType} primitives into plain strings.
     *
     * <p>Done here so that {@link RestOperationOutcome} — and everything that reads it,
     * including the UI — stays free of the FHIR model classes.
     */
    private static List<String> stringsOf(List<StringType> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> unwrapped = new ArrayList<>();
        for (StringType value : values) {
            if (value != null && value.hasValue()) {
                unwrapped.add(value.getValue());
            }
        }
        return List.copyOf(unwrapped);
    }

    /**
     * A readable summary of the {@code details.code} coding, when the server supplied one.
     *
     * <p>Some servers put the useful text in {@code details} rather than
     * {@code diagnostics}; this surfaces it without making the caller know the difference.
     */
    public static String detailsText(OperationOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        for (OperationOutcome.OperationOutcomeIssueComponent issue : outcome.getIssue()) {
            if (issue != null && issue.hasDetails()) {
                CodeableConcept details = issue.getDetails();
                String text = details.hasText() ? details.getText() : null;
                if (text == null && details.hasCoding() && !details.getCoding().isEmpty()) {
                    text = details.getCodingFirstRep().getDisplay();
                }
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }
}
