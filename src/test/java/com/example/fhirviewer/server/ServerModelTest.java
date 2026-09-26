package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the server value types: definitions, search model, sessions and results. */
class ServerModelTest {

    @Test
    @DisplayName("A server definition validates its name and base URL")
    void validatesServerDefinitions() {
        ServerDefinition server = ServerDefinition.named("Local HAPI", "http://localhost:8080/fhir/")
                .fhirVersion("R4")
                .timeoutMillis(5_000)
                .build();

        assertEquals("Local HAPI", server.name());
        assertEquals("http://localhost:8080/fhir", server.baseUrl());
        assertEquals("R4", server.fhirVersion());
        assertEquals(StandardFhirRestPlugin.PLUGIN_ID, server.pluginId());
        assertEquals(5_000, server.timeoutMillis());
        assertEquals(Map.of(), server.extraHeaders());

        assertThrows(IllegalArgumentException.class, () -> ServerDefinition.named(" ", "https://x/fhir"));
        assertThrows(IllegalArgumentException.class, () -> ServerDefinition.named("X", "ftp://x/fhir"));
        assertThrows(IllegalArgumentException.class, () -> ServerDefinition.named("X", "https://x/fhir").timeoutMillis(-1));
    }

    @Test
    @DisplayName("A search request needs a type and a positive page size")
    void validatesSearchRequests() {
        SearchRequest request = new SearchRequest("Patient",
                List.of(new SearchCriterion("name", "Smith")), 20);

        assertEquals("Patient", request.resourceType());
        assertEquals(1, request.criteria().size());
        assertEquals("name", request.criteria().get(0).name());
        assertEquals("Smith", request.criteria().get(0).value());
        assertEquals(20, request.pageSize());

        assertThrows(IllegalArgumentException.class,
                () -> new SearchRequest(" ", List.of(), 20));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchRequest("Patient", List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new SearchCriterion(" ", "x"));
    }

    @Test
    @DisplayName("Result pages report totals and next pages only when the server sent them")
    void modelsResultPages() {
        assertFalse(SearchResultPage.empty().hasNextPage());
        assertTrue(SearchResultPage.empty().resources().isEmpty());

        SearchResultPage page = new SearchResultPage(List.of(), 42, "http://x/next");
        assertTrue(page.hasNextPage());
        assertEquals(42, page.total());
        assertEquals("http://x/next", page.nextPageToken());
    }

    @Test
    @DisplayName("Sessions pair a server with anonymous authentication by default")
    void modelsSessions() {
        ServerDefinition server = ServerDefinition.named("X", "https://x/fhir").build();
        ServerSession session = new ServerSession(server, AnonymousServerAuthentication.INSTANCE);

        assertEquals(server, session.server());
        assertTrue(session.authentication().isAnonymous());
    }

    @Test
    @DisplayName("Connection results distinguish reachable from unreachable servers")
    void modelsConnectionResults() {
        assertTrue(ConnectionResult.reachable("ok", ServerCapabilities.empty()).isReachable());
        assertFalse(ConnectionResult.unreachable("down").isReachable());
        assertTrue(ServerCapabilities.empty().resourceTypes().isEmpty());
    }
}
