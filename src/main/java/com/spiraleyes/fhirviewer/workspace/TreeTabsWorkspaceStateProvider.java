package com.spiraleyes.fhirviewer.workspace;

import java.util.List;
import java.util.Map;

/**
 * Capture/restore boundary for tree and tab view state.
 *
 * <p>Serialises selected resource reference, open tab ids, filters, expanded nodes and
 * supported view state. Restore reopens tabs by id/reference and warns if missing; it
 * never duplicates tabs or creates competing state stores.</p>
 */
public class TreeTabsWorkspaceStateProvider implements WorkspaceStateProvider {

    /** Callback supplying current tab/tree state for capture. */
    @FunctionalInterface
    public interface StateSnapshot {
        Map<String, Object> capture();
    }

    /** Callback applying restored tab/tree state. */
    @FunctionalInterface
    public interface StateApplier {
        void apply(Map<String, Object> snapshot);
    }

    private final StateSnapshot snapshot;
    private final StateApplier applier;

    /** No-arg constructor: capture/restore are no-ops until wired to a UI shell. */
    public TreeTabsWorkspaceStateProvider() {
        this(null, null);
    }

    public TreeTabsWorkspaceStateProvider(StateSnapshot snapshot,
                                          StateApplier applier) {
        this.snapshot = snapshot;
        this.applier = applier;
    }

    @Override
    public String providerId() {
        return "tree-tabs";
    }

    @Override
    public Object capture() {
        if (snapshot == null) return Map.of();
        Map<String, Object> raw = snapshot.capture();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> { if (v != null) out.put(k, v); });
        }
        return out;
    }

    @Override
    public void restore(Object state) {
        if (!(state instanceof Map) || applier == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) state;
        applier.apply(snapshot);
    }
}

