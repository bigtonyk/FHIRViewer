package com.example.fhirviewer.service;

import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.fhir.FhirContextFactory;
import com.example.fhirviewer.fhir.FhirParseException;
import com.example.fhirviewer.fhir.ResourceParser;
import com.example.fhirviewer.fhir.ResourceSerializer;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.model.ValidationReport;

import ca.uhn.fhir.context.FhirContext;

/**
 * Applies text edited in the JSON or XML editor views to the live FHIR resource.
 *
 * <p>The flow is deliberately explicit — parse first, then validate the result —
 * so a broken document can never replace the current resource. On any failure the
 * original resource is untouched, the caller receives the reason, and the edited
 * text stays in the editor for correction.</p>
 *
 * <p>All FHIR work stays in this service; the JavaFX controllers only coordinate
 * the views. A document that parses but fails FHIR validation is still rejected:
 * the viewer must never show validation errors as if the content were fine.</p>
 */
public class SourceEditorService {

    /** What went wrong when an apply operation failed. */
    public enum FailureKind {
        /** HAPI FHIR rejected the text (a syntax or structural problem). */
        PARSE,
        /** The parsed document holds no FHIR resource to apply. */
        EMPTY
    }

    /**
     * A parsed document that is ready to replace the current resource.
     *
     * @param resource the parsed HAPI FHIR resource
     * @param format   the format the text was parsed as
     * @param report   the validation report for the parsed resource (valid)
     */
    public record AppliedSource(IBaseResource resource, ResourceFormat format, ValidationReport report) {

        public AppliedSource {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(report, "report");
        }
    }

    /**
     * Why an apply operation failed. The edited text is left untouched so the
     * user can fix the problem; nothing in the application state is replaced.
     */
    public static class ApplyException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final FailureKind kind;

        public ApplyException(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        /** What went wrong. */
        public FailureKind kind() {
            return kind;
        }
    }

    private final ResourceParser parser;
    private final ResourceSerializer serializer;
    private final ValidationService validationService;

    /** Creates a service for the default R4 FHIR version. */
    public SourceEditorService() {
        this(FhirContextFactory.r4());
    }

    /** Creates a service for a specific FHIR context. */
    public SourceEditorService(FhirContext context) {
        Objects.requireNonNull(context, "context");
        this.parser = new ResourceParser(context);
        this.serializer = new ResourceSerializer(context);
        this.validationService = new ValidationService(context);
    }

    /**
     * Parses and validates edited source text without touching anything else.
     *
     * @param text       the edited JSON or XML document
     * @param format     the format the text claims to be in
     * @param sourceName a name describing the origin, used in error messages
     * @return the parsed resource together with its validation report
     * @throws ApplyException when the text cannot be parsed, holds no resource,
     *                        or fails FHIR validation
     */
    public AppliedSource apply(String text, ResourceFormat format, String sourceName) {
        Objects.requireNonNull(format, "format");
        if (text == null || text.isBlank()) {
            throw new ApplyException(FailureKind.EMPTY,
                    labelled(sourceName) + " is empty; nothing to apply.", null);
        }
        IBaseResource resource;
        try {
            resource = parser.parse(text, format, sourceName);
        } catch (FhirParseException e) {
            throw new ApplyException(FailureKind.PARSE, e.getMessage(), e);
        }
        if (resource == null) {
            throw new ApplyException(FailureKind.EMPTY,
                    labelled(sourceName) + " contains no FHIR resource; nothing to apply.", null);
        }
        ValidationReport report = validationService.validate(resource);
        if (!report.isValid()) {
            throw new ApplyException(FailureKind.PARSE,
                    "The document parses but is not valid FHIR: " + report.getSummary()
                            + ". The current resource is unchanged; fix the document and apply again.",
                    null);
        }
        return new AppliedSource(resource, format, report);
    }

    /**
     * Renders a replacement resource back to text in the requested format, so the
     * editor that was not used can be refreshed to match.
     */
    public String serialize(IBaseResource resource, ResourceFormat format) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(format, "format");
        return serializer.serialize(resource, format);
    }

    private static String labelled(String sourceName) {
        return sourceName == null || sourceName.isBlank() ? "The document" : sourceName;
    }
}



