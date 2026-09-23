package com.example.fhirviewer.model;

/**
 * A single issue reported by FHIR validation.
 *
 * @param severity  the FHIR issue severity
 * @param message   the human readable message
 * @param location  the FHIRPath location, for example <code>Observation.status</code>
 * @param line      the line number in the source document, when known
 * @param column    the column number in the source document, when known
 */
public record ValidationIssue(
        Severity severity,
        String message,
        String location,
        Integer line,
        Integer column,
        boolean isProfileResolutionFailure) {

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
        isProfileResolutionFailure = false;
    }

    /**
     * Creates a validation issue for profile resolution failures.
     */
    public static ValidationIssue profileResolutionFailure(
            Severity severity, String message, String location,
            Integer line, Integer column) {
        return new ValidationIssue(severity, message, location, line, column, true);
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
        return sb.toString();
    }

    @Override
    public String toString() {
        return getDisplayText();
    }
}