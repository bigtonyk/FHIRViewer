package com.example.fhirviewer.model;

/**
 * A single issue reported by FHIR validation.
 *
 * @param severity                       the FHIR issue severity
 * @param message                        the human readable message
 * @param location                       the FHIRPath location, for example <code>Observation.status</code>
 * @param line                           the line number in the source document, when known
 * @param column                         the column number in the source document, when known
 * @param isProfileResolutionFailure     true when a referenced profile could not be resolved
 * @param isTerminologyResolutionFailure true when a ValueSet/CodeSystem could not be resolved
 */
public record ValidationIssue(
        Severity severity,
        String message,
        String location,
        Integer line,
        Integer column,
        boolean isProfileResolutionFailure,
        boolean isTerminologyResolutionFailure) {

    /** Mirrors <code>ca.uhn.fhir.validation.ResultSeverityEnum</code>. */
    public enum Severity {
        FATAL,
        ERROR,
        WARNING,
        INFORMATION;

        /** True for severities that make the resource invalid. */
        public boolean isProblem() {
            return this == FATAL || this == ERROR;
        }
    }

    public ValidationIssue {
        severity = severity == null ? Severity.INFORMATION : severity;
        message = message == null ? "" : message;
        location = location == null ? "" : location;
        // Resolution flags are kept exactly as given; forcing them here would
        // silently neutralise the factory methods below.
    }

    /** Creates a plain validation issue that is not a resolution failure. */
    public ValidationIssue(
            Severity severity, String message, String location,
            Integer line, Integer column) {
        this(severity, message, location, line, column, false, false);
    }

    /**
     * Creates a validation issue for profile resolution failures.
     */
    public static ValidationIssue profileResolutionFailure(
            Severity severity, String message, String location,
            Integer line, Integer column) {
        return new ValidationIssue(severity, message, location, line, column, true, false);
    }

    /**
     * Creates a validation issue for terminology resolution failures — a
     * ValueSet or CodeSystem that could not be found, as opposed to a code
     * that was checked and is not in the ValueSet.
     */
    public static ValidationIssue terminologyResolutionFailure(
            Severity severity, String message, String location,
            Integer line, Integer column) {
        return new ValidationIssue(severity, message, location, line, column, false, true);
    }

    /** Text rendered in the validation list. */
    public String getDisplayText() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(severity).append("] ");
        if (!location.isBlank()) {
            sb.append(location).append(": ");
        }
        sb.append(message);
        if (line != null) {
            sb.append(" (line ").append(line);
            if (column != null) {
                sb.append(", column ").append(column);
            }
            sb.append(')');
        }
        if (isProfileResolutionFailure) {
            sb.append(" [profile resolution]");
        }
        if (isTerminologyResolutionFailure) {
            sb.append(" [terminology resolution]");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return getDisplayText();
    }
}