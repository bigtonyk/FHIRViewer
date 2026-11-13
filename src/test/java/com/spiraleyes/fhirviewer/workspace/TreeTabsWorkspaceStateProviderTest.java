package com.spiraleyes.fhirviewer.workspace;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TreeTabsWorkspaceStateProviderTest {

    @Test
    void providerIdIsTreeTabs() {
        assertEquals("tree-tabs", new TreeTabsWorkspaceStateProvider().providerId());
    }

    @Test
    void noArgConstructorCaptureReturnsEmptyMap() {
        TreeTabsWorkspaceStateProvider p = new TreeTabsWorkspaceStateProvider();
        Object snap = p.capture();
        assertTrue(snap instanceof Map);
        assertTrue(((Map<?, ?>) snap).isEmpty());
    }

    @Test
    void noArgConstructorRestoreIsNoOp() {
        TreeTabsWorkspaceStateProvider p = new TreeTabsWorkspaceStateProvider();
        p.restore(Map.of("key", "value")); // should not throw, nothing applied
    }

    @Test
    void captureDelegatesToSnapshotCallback() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("selected", "Patient/123");
        expected.put("tabs", java.util.List.of("tree", "details"));

        TreeTabsWorkspaceStateProvider p = new TreeTabsWorkspaceStateProvider(
                () -> expected,
                snap -> { /* no-op */ }
        );
        Object snap = p.capture();
        assertTrue(snap instanceof Map);
        assertEquals(expected, snap);
    }

    @Test
    void restoreDelegatesToApplierCallback() {
        AtomicReference<Map<String, Object>> applied = new AtomicReference<>();
        TreeTabsWorkspaceStateProvider p = new TreeTabsWorkspaceStateProvider(
                () -> Map.of(),
                snap -> applied.set(snap)
        );

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("selected", "Observation/456");
        p.restore(snapshot);

        assertEquals(snapshot, applied.get());
    }

    @Test
    void restoreWithNullApplierIsNoOp() {
        TreeTabsWorkspaceStateProvider p = new TreeTabsWorkspaceStateProvider(() -> Map.of(), null);
        p.restore(Map.of("key", "value")); // should not throw
    }

    @Test
    void restoreWithNonMapIsNoOp() {
        TreeTabsWorkspaceStateProvider p = new TreeTabsWorkspaceStateProvider(
                () -> Map.of(),
                snap -> { throw new AssertionError("should not be called"); }
        );
        p.restore("not a map"); // should not throw
        p.restore(null); // should not throw
    }
}
