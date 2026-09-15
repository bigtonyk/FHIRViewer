package com.example.fhirviewer.fhir;

/**
 * Signals that text could not be parsed as a FHIR resource.
 *
 * <p>The message is intended to be shown to the user, so it explains which format
 * was attempted and carries the underlying HAPI message.</p>
 */
public class FhirParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sourceName;

    public FhirParseException(String message, String sourceName, Throwable cause) {
        super(message, cause);
        this.sourceName = sourceName == null ? "(unknown)" : sourceName;
    }

    /** The name of the file or resource the text came from. */
    public String getSourceName() {
        return sourceName;
    }
}