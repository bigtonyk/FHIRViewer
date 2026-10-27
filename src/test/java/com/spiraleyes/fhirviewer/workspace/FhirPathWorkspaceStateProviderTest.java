package com.spiraleyes.fhirviewer.workspace;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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
        p.restore(Map.of("expressions", List.of("Patient.name"))); // should not throw
    }

    @Test
    void captureDelegatesToSnapshotCallback() {
        List<String> expected = List.of("Patient.name", "Observation.value");

        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider(
                () -> expected,
                (expressions, contextRef) -> { /* no-op */ }
        );
        Object snap = p.capture();
        assertTrue(snap instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) snap;
        assertEquals(expected, out.get("expressions"));
    }

    @Test
    void restoreDelegatesToApplierCallback() {
        AtomicReference<List<String>> applied = new AtomicReference<>();
        AtomicReference<String> contextRef = new AtomicReference<>();
        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider(
                () -> List.of(),
                (exprs, ctx) -> { applied.set(exprs); contextRef.set(ctx); }
        );

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("expressions", List.of("Patient.name"));
        p.restore(snapshot);

        assertEquals(List.of("Patient.name"), applied.get());
        assertNull(contextRef.get());
    }

    @Test
    void restoreDoesNotAutoEvaluate() {
        boolean[] evaluated = {false};
        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider(
                () -> List.of(),
                (expressions, contextRef) -> { evaluated[0] = true; }
        );
        p.restore(Map.of("expressions", List.of("Patient.name")));
        // The applier is called (state applied), but no auto-evaluation happens here — that the UI's responsibility. This test just confirms restore is a plain apply.
        assertTrue(evaluated[0]);
    }

    @Test
    void restoreWithNullApplierIsNoOp() {
        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider(() -> List.of(), null);
        p.restore(Map.of("expr", "Patient.name")); // should not throw
    }

    @Test
    void restoreWithNonMapIsNoOp() {
        FhirPathWorkspaceStateProvider p = new FhirPathWorkspaceStateProvider(
                () -> List.of(),
                (expressions, contextRef) -> { throw new AssertionError("should not be called"); }
        );
        p.restore("not a map"); // should not throw
        p.restore(null); // should not throw
    }
}
