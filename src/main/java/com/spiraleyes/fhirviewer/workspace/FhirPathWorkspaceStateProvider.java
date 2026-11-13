package com.spiraleyes.fhirviewer.workspace;

import java.util.List;
import java.util.Map;

/**
 * Capture/restore boundary for FHIRPath state.
 *
 * <p>Serialises saved expressions and associated resource context/reference. Restore does
 * not auto-evaluate if remote or expensive - expressions are stored for later use, not
 * re-run on workspace open.</p>
 */
public class FhirPathWorkspaceStateProvider implements WorkspaceStateProvider {

    /** Callback supplying saved FHIRPath expressions for capture. */
    @FunctionalInterface
    public interface ExpressionSnapshot {
        List<String> capture();
    }

    /** Callback applying restored FHIRPath expressions. */
    @FunctionalInterface
    public interface ExpressionApplier {
        void apply(List<String> expressions, String contextRef);
    }

    private final ExpressionSnapshot snapshot;
    private final ExpressionApplier applier;

    /** No-arg constructor: capture/restore are no-ops until wired to a FHIRPath view. */
    public FhirPathWorkspaceStateProvider() {
        this(null, null);
    }

    public FhirPathWorkspaceStateProvider(ExpressionSnapshot snapshot,
                                          ExpressionApplier applier) {
        this.snapshot = snapshot;
        this.applier = applier;
    }

    @Override
    public String providerId() {
        return "fhirpath";
    }

    @Override
    public Object capture() {
        if (snapshot == null) return Map.of();
        List<String> expressions = snapshot.capture();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("expressions", expressions == null ? List.of() : expressions);
        return out;
    }

    @Override
    public void restore(Object state) {
        if (!(state instanceof Map) || applier == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) state;
        @SuppressWarnings("unchecked")
        List<String> expressions = (List<String>) snapshot.get("expressions");
        if (expressions == null) expressions = List.of();
        applier.apply(expressions, null);
    }
}
