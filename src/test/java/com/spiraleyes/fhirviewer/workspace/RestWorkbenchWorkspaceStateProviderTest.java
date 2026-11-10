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
    void captureExcludesSecretsAndTheRequestBody() {
        RestWorkbenchWorkspaceStateProvider p = new RestWorkbenchWorkspaceStateProvider();
        Object snap = p.capture();
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) snap;
        // Secrets must never be captured
        assertFalse(out.containsKey("password"));
        assertFalse(out.containsKey("token"));
        assertFalse(out.containsKey("secret"));
        assertFalse(out.containsKey("requestBody"));
        // The body is usually a patient resource, so it is not captured either.
        assertFalse(out.containsKey("body"));
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

        p.restore(entry);

        // Written back to the console's own store, which is what restore is for: reopening
        // the workbench shows the request the archive described.
        RestConsoleState restored = RestConsoleState.load(null);
        assertEquals(RestMethod.GET, restored.method());
        assertEquals("https://example.org/fhir", restored.baseUrl());
        assertEquals("/Patient", restored.path());
        assertEquals(2, restored.parameters().size());
        assertEquals("_count", restored.parameters().get(0).name());
        assertEquals("10", restored.parameters().get(0).value());
        assertEquals(1, restored.headers().size());
        assertEquals("Accept", restored.headers().get(0).name());
        assertEquals("application/fhir+json", restored.headers().get(0).value());
        assertEquals("application/fhir+json", restored.contentType());
        assertEquals(ServerAuthKind.BASIC, restored.authKind());
        assertEquals("alice", restored.userName());
    }

    @Test
    void restoreToleratesUnknownMethod() {
        RestWorkbenchWorkspaceStateProvider p = new RestWorkbenchWorkspaceStateProvider();
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("method", "NOT-A-REAL-METHOD");
        p.restore(entry);

        assertEquals(RestMethod.GET, RestConsoleState.load(null).method(),
                "an unknown verb must fall back to GET rather than abort the restore");
    }

    @Test
    void restoreToleratesMissingFields() {
        RestWorkbenchWorkspaceStateProvider p = new RestWorkbenchWorkspaceStateProvider();
        p.restore(Map.of()); // empty map, should not throw
    }
}
