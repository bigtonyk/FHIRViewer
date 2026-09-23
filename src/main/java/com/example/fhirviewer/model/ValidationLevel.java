package com.example.fhirviewer.model;

/**
 * Which level of validation produced an issue.
 *
 * <p>The validator always checks a resource against its base FHIR definition
 * first; an Implementation Guide profile adds further constraints on top. The
 * level lets the UI say where a problem came from.</p>
 */
public enum ValidationLevel {

    /** Base FHIR R4 definition validation (no Implementation Guide profile). */
    R4_BASE("FHIR R4"),

    /** Validation against an Implementation Guide profile. */
    IG_PROFILE("IG profile");

    private final String displayName;

    ValidationLevel(String displayName) {
        this.displayName = displayName;
    }

    /** Human readable name shown in the validation results list. */
    public String getDisplayName() {
        return displayName;
    }
}
