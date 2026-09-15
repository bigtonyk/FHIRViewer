package com.example.fhirviewer.fhir;

import java.util.Optional;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.model.ResourceFormat;

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;

/**
 * Parses FHIR JSON and XML text into HAPI FHIR resources.
 *
 * <p>Parsing is generic: the resource type is determined by HAPI from the input, so
 * any FHIR resource (including Bundle, contained resources and extensions) is
 * supported without resource specific code.</p>
 */
public class ResourceParser {

    private final FhirContext context;

    public ResourceParser(FhirContext context) {
        this.context = context;
    }

    /**
     * Parses the supplied text using an explicitly known format.
     *
     * @param content    the FHIR document
     * @param format     the format of the document
     * @param sourceName a name used in error messages
     * @return the parsed resource
     * @throws FhirParseException when the text is not a valid resource of that format
     */
    public IBaseResource parse(String content, ResourceFormat format, String sourceName) {
        if (content == null || content.isBlank()) {
            throw new FhirParseException(
                    (sourceName == null ? "The document" : sourceName) + " is empty; nothing to parse.",
                    sourceName,
                    null);
        }
        try {
            return switch (format) {
                case JSON -> context.newJsonParser().parseResource(content);
                case XML -> context.newXmlParser().parseResource(content);
            };
        } catch (DataFormatException | ConfigurationException e) {
            throw new FhirParseException(describeFailure(format, sourceName, e), sourceName, e);
        } catch (RuntimeException e) {
            // HAPI occasionally reports other runtime failures (for example FHIRException).
            throw new FhirParseException(describeFailure(format, sourceName, e), sourceName, e);
        }
    }

    /**
     * Parses text whose format is not known up front.
     *
     * <p>The format is detected from the content (falling back to the file name).</p>
     */
    public IBaseResource parse(String content, String sourceName) {
        Optional<ResourceFormat> detected = ResourceFormat.detect(content, sourceName);
        ResourceFormat format = detected.orElse(ResourceFormat.JSON);
        return parse(content, format, sourceName);
    }

    private String describeFailure(ResourceFormat format, String sourceName, Exception failure) {
        String name = sourceName == null ? "the document" : sourceName;
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return "Could not parse " + name + " as FHIR " + format.getDisplayName() + ": " + detail;
    }
}