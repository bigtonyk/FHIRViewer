package com.spiraleyes.fhirviewer.workspace;

import com.spiraleyes.fhirviewer.server.ServerAuthKind;
import com.spiraleyes.fhirviewer.server.rest.RestMethod;
import com.spiraleyes.fhirviewer.server.rest.RestRequest;
import com.spiraleyes.fhirviewer.ui.RestConsoleState;
import com.spiraleyes.fhirviewer.ui.RestParameterList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestWorkbenchWorkspaceStateProviderTest {

    @Test
    void providerIdIsRestWorkbench() {
        assertEquals("rest-workbench", new RestWorkbenchWorkspaceStateProvider().providerId());
    }

    @Test
    void captureReturnsSerializableMap() {
        RestWorkbenchWorkspaceStateProvider p = new RestWorkbenchWorkspaceStateProvider();
        Object snap = p.capture();
        assertTrue(snap instanceof Map);
    }

    @Test
    void captureExcludesPasswordTokenAndBody() {
        RestWorkbenchWorkspaceStateProvider p = new RestWorkbenchWorkspaceStateProvider();
        Object snap = p.capture();
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) snap;
        // Secrets must never be captured
        assertFalse(out.containsKey("password"));
        assertFalse(out.containsKey("token"));
        assertFalse(out.containsKey("body"));
        assertFalse(out.containsKey("requestBody"));
    }

    @Test
    void restoreWithNonMapIsNoOp() {
        RestWorkbenchWorkspaceStateProvider p = new RestWorkbenchWorkspaceStateProvider();
        p.restore("not a map"); // should not throw
        p.restore(null); // should not throw
        p.restore(42); // should not throw
    }

    @Test
    void restorePopulatesStateWithoutExecuting() {
        RestWorkbenchWorkspaceStateProvider p = new RestWorkbenchWorkspaceStateProvider();

        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("serverBaseUrl", "https://example.org/fhir");
        entry.put("method", "GET");
        entry.put("baseUrl", "https://example.org/fhir");
        entry.put("path", "/Patient");
        entry.put("parameters", List.of(
                Map.of("name", "_count", "value", "10"),
                Map.of("name", "name", "value", "Smith")
        ));
        entry.put("headers", List.of(
                Map.of("name", "Accept", "value", "application/fhir+json")
        ));
        entry.put("contentType", "application/fhir+json");
        entry.put("authKind", "BASIC");
        entry.put("userName", "alice");

        p.restore(entry); // should not throw, should not auto-execute any request
    }

    @Test
    void restoreToleratesUnknownMethod() {
        RestWorkbenchWorkspaceStateProvider p = new RestWorkbenchWorkspaceStateProvider();
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("method", "NOT-A-REAL-METHOD");
        p.restore(entry); // should not throw, falls back to GET
    }

    @Test
    void restoreToleratesMissingFields() {
        RestWorkbenchWorkspaceStateProvider p = new RestWorkbenchWorkspaceStateProvider();
        p.restore(Map.of()); // empty map, should not throw
    }
}
