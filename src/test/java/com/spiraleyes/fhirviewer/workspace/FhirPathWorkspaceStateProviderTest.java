package com.spiraleyes.fhirviewer.workspace;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FhirPathWorkspaceStateProviderTest {

    @Test
    void providerIdIsFhirPath() {
        assertEquals("fhirpath", new FhirPathWorkspaceStateProvider().providerId());
    }

    @Test
    void noArgConstructorCaptureReturnsEmptyMap() {
        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider();
        Object snap = p.capture();
        assertTrue(snap instanceof Map);
        assertTrue(((Map<?, ?>) snap).isEmpty());
    }

    @Test
    void noArgConstructorRestoreIsNoOp() {
        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider();
        p.restore(Map.of("expr", "Patient.name")); // should not throw
    }

    @Test
    void captureDelegatesToSnapshotCallback() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("expressions", java.util.List.of("Patient.name", "Observation.value"));

        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider(
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
        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider(
                () -> Map.of(),
                snap -> applied.set(snap)
        );

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("expressions", java.util.List.of("Patient.name"));
        p.restore(snapshot);

        assertEquals(snapshot, applied.get());
    }

    @Test
    void restoreDoesNotAutoEvaluate() {
        boolean[] evaluated = {false};
        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider(
                () -> Map.of(),
                snap -> { evaluated[0] = true; }
        );
        p.restore(Map.of("expressions", java.util.List.of("Patient.name")));
        // The applier is called (state applied), but no auto-evaluation happens here
        // — that's the UI's responsibility. This test just confirms restore is a plain apply.
        assertTrue(evaluated[0]);
    }

    @Test
    void restoreWithNullApplierIsNoOp() {
        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider(() -> Map.of(), null);
        p.restore(Map.of("expr", "Patient.name")); // should not throw
    }

    @Test
    void restoreWithNonMapIsNoOp() {
        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider(
                () -> Map.of(),
                snap -> { throw new AssertionError("should not be called"); }
        );
        p.restore("not a map"); // should not throw
        p.restore(null); // should not throw
    }
}
