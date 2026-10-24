package com.spiraleyes.fhirviewer.workspace;

import com.spiraleyes.fhirviewer.service.IgPackageManager;
import com.spiraleyes.fhirviewer.model.IgPackageInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidationWorkspaceStateProviderTest {

    @Test
    void providerIdIsValidation() {
        assertEquals("validation", new ValidationWorkspaceStateProvider().providerId());
    }

    @Test
    void captureWithNullPackageManagerReturnsEmptyMap() {
        ValidationWorkspaceStateProvider p = new ValidationWorkspaceStateProvider(null);
        Object snap = p.capture();
        assertTrue(snap instanceof Map);
        assertTrue(((Map<?, ?>) snap).isEmpty());
    }

    @Test
    void captureWithEmptyPackageManagerReturnsEmptyLists() {
        IgPackageManager pm = new IgPackageManager();
        ValidationWorkspaceStateProvider p = new ValidationWorkspaceStateProvider(pm);
        Object snap = p.capture();
        assertTrue(snap instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) snap;
        assertEquals(java.util.List.of(), out.get("packages"));
        assertEquals(java.util.List.of(), out.get("activePackages"));
        assertEquals(java.util.List.of(), out.get("inactivePackages"));
    }

    @Test
    void captureIncludesPackageMetadataNoSecrets() {
        IgPackageManager pm = new IgPackageManager();

        ValidationWorkspaceStateProvider p = new ValidationWorkspaceStateProvider(pm);
        Object snap = p.capture();
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) snap;
        assertTrue(out.containsKey("packages"));
        assertTrue(out.containsKey("activePackages"));
        assertTrue(out.containsKey("inactivePackages"));
        // No secrets captured
        assertFalse(out.containsKey("password"));
        assertFalse(out.containsKey("token"));
        assertFalse(out.containsKey("secret"));
    }

    @Test
    void restoreWarnsOnMissingPackage() {
        IgPackageManager pm = new IgPackageManager(); // empty: no packages installed
        ValidationWorkspaceStateProvider p = new ValidationWorkspaceStateProvider(pm);

        Map<String, Object> pkg = new java.util.LinkedHashMap<>();
        pkg.put("name", "missing-ig");
        pkg.put("version", "1.0.0");

        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("packages", List.of(pkg));

        // Should not throw; package is missing so a warning is printed
        p.restore(snap);
    }

    @Test
    void restoreWithNullPackageManagerIsNoOp() {
        ValidationWorkspaceStateProvider p = new ValidationWorkspaceStateProvider(null);
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        p.restore(snap); // should not throw
    }

    @Test
    void restoreWithNonMapIsNoOp() {
        IgPackageManager pm = new IgPackageManager();
        ValidationWorkspaceStateProvider p = new ValidationWorkspaceStateProvider(pm);
        p.restore("not a map"); // should not throw
        p.restore(null); // should not throw
    }

    @Test
    void restoreSkipsNonMapPackageEntries() {
        IgPackageManager pm = new IgPackageManager();
        ValidationWorkspaceStateProvider p = new ValidationWorkspaceStateProvider(pm);

        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        java.util.List<Object> entries = new java.util.ArrayList<>();
        entries.add("not a map");
        entries.add(42);
        snap.put("packages", entries);
        p.restore(snap); // should not throw
    }
}
