package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.ServerDefinition;
import com.spiraleyes.fhirviewer.server.ServerInteraction;
import com.spiraleyes.fhirviewer.server.ServerOperationException;

class ServerApiTest {

    private FhirServerManager manager;
    private TestFhirServerService service;
    private ServerApi api;

    @BeforeEach
    void setUp() {
        manager = new FhirServerManager();
        ServerDefinition server = ServerDefinition.named("Test", "http://test.example.com/fhir")
                .pluginId("standard-rest").build();
        manager.add(server);
        manager.setActive(server);
        service = new TestFhirServerService();
        service.capabilities = new com.spiraleyes.fhirviewer.server.ServerCapabilities(
                "R4", List.of("Patient", "Observation"), true,
                Set.of(ServerInteraction.READ, ServerInteraction.SEARCH), java.util.Map.of());
        api = new ServerApi(service, manager);
    }

    @Test
    void activeServerReportsIdentity() {
        ServerInfo info = api.activeServer();
        assertNotNull(info);
        assertEquals("Test", info.name());
        assertEquals("http://test.example.com/fhir", info.baseUrl());
        assertEquals("R4", info.fhirVersion());
        assertEquals("standard-rest", info.pluginId());
    }

    @Test
    void serversListsAllConfigured() {
        manager.add(ServerDefinition.named("Two", "http://two.example.com/fhir").build());
        List<ServerInfo> all = api.servers();
        assertEquals(2, all.size());
        assertEquals("Test", all.get(0).name());
        assertEquals("Two", all.get(1).name());
    }

    @Test
    void capabilitiesReportsWhatTheServerAdvertises() {
        com.spiraleyes.fhirviewer.server.ServerCapabilities caps = api.capabilities();
        assertNotNull(caps);
        assertEquals("R4", caps.fhirVersion());
        assertTrue(caps.resourceTypes().contains("Patient"));
        assertTrue(caps.pagingSupported());
    }

    @Test
    void supportsAnswersAdvertisedInteractions() {
        assertTrue(api.supports("Patient", ServerInteraction.READ));
        assertTrue(api.supports("Patient", ServerInteraction.SEARCH));
        assertFalse(api.supports("Patient", ServerInteraction.PATCH));
        assertFalse(api.supports("Unknown", ServerInteraction.READ));
    }

    @Test
    void noActiveServerMeansNothingIsAvailable() {
        manager = new FhirServerManager();
        api = new ServerApi(service, manager);
        assertNull(api.activeServer());
        assertTrue(api.servers().isEmpty());
        assertNull(api.capabilities());
        assertFalse(api.supports("Patient", ServerInteraction.READ));
        assertEquals("", api.fhirVersion());
    }

    @Test
    void capabilitiesFailureIsActionable() {
        service.capabilitiesFailure = new ServerOperationException(
                ServerOperationException.Kind.UNREACHABLE, "down");
        assertThrows(ScriptApiException.class, api::capabilities);
    }
}
