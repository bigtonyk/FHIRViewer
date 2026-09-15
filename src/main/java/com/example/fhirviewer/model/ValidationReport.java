package com.example.fhirviewer.model;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of validating a FHIR resource.
 */
public final class ValidationReport {

    private final List<ValidationIssue> issues;
    private final String resourceName;

    public ValidationReport(String resourceName, List<ValidationIssue> issues) {
        this.resourceName = resourceName == null ? "" : resourceName;
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    }

    public static ValidationReport successful(String resourceName) {
        return new ValidationReport(resourceName, List.of());
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }

    public String getResourceName() {
        return resourceName;
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }

    /** True when no <code>ERROR</code> or <code>FATAL</code> issue was reported. */
    public boolean isValid() {
        return issues.stream().noneMatch(issue -> issue.severity().isProblem());
    }

    public long count(ValidationIssue.Severity severity) {
        return issues.stream().filter(issue -> issue.severity() == severity).count();
    }

    /** A one line summary, for example <code>Patient/12345: valid (2 warnings)</code>. */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(resourceName.isEmpty() ? "Resource" : resourceName);
        sb.append(": ");
        if (isValid()) {
            sb.append("valid");
        } else {
            long errors = count(ValidationIssue.Severity.ERROR) + count(ValidationIssue.Severity.FATAL);
            sb.append(errors).append(errors == 1 ? " error" : " errors");
        }
        long warnings = count(ValidationIssue.Severity.WARNING);
        long information = count(ValidationIssue.Severity.INFORMATION);
        if (warnings > 0) {
            sb.append(", ").append(warnings).append(warnings == 1 ? " warning" : " warnings");
        }
        if (information > 0) {
            sb.append(", ").append(information).append(" information");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return getSummary();
    }
}