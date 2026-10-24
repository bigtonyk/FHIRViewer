package com.spiraleyes.fhirviewer.workspace;

/**
 * Typed capture/restore boundary for state owned by an existing feature.
 * Implementations must not duplicate tabs or create a competing state store.
 * Restore must not execute remote calls, scripts, validations, or writes.
 */
public interface WorkspaceStateProvider {
    /** Stable provider id used in workspace metadata. */
    String providerId();

    /** Capture the current feature state into a serializable snapshot. */
    Object capture();

    /** Restore feature state from a previously captured snapshot. */
    void restore(Object snapshot);
}
