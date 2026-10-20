package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.SearchCriterion;
import com.spiraleyes.fhirviewer.server.SearchPageLinks;
import com.spiraleyes.fhirviewer.server.SearchResultPage;
import com.spiraleyes.fhirviewer.server.ServerDefinition;
import com.spiraleyes.fhirviewer.server.ServerOperationException;
import com.spiraleyes.fhirviewer.server.ServerWriteResult;

class FhirApiTest {

    private FhirServerManager manager;
    private TestFhirServerService service;
    private FhirApi api;
    private FhirApi writes;

    private static Patient patient(String id, String family) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.addName().setFamily(family);
        return patient;
    }

    @BeforeEach
    void setUp() {
        manager = new FhirServerManager();
        service = new TestFhirServerService();
        ServerDefinition server = ServerDefinition.named("Test", "http://test.example.com/fhir")
                .pluginId("standard-rest").build();
        manager.add(server);
        manager.setActive(server);
        api = new FhirApi(service, manager, WritePolicy.READ_ONLY);
        writes = new FhirApi(service, manager, WritePolicy.enabled());
    }

    @Test
    void readReturnsTheResource() {
        service.readResource = patient("1", "Smith");
        Object result = api.read("Patient", "1");
        assertNotNull(result);
        assertEquals("Patient", ((Patient) result).fhirType());
        assertEquals("1", ((Patient) result).getIdElement().getIdPart());
    }

    @Test
    void readNotFoundReturnsNull() {
        service.readFailure = new ServerOperationException(
                ServerOperationException.Kind.NOT_FOUND, "Not Found", Integer.valueOf(404), null);
        assertNull(api.read("Patient", "nope"));
    }

    @Test
    void readGoneReturnsNull() {
        service.readFailure = new ServerOperationException(
                ServerOperationException.Kind.NOT_FOUND, "Gone", Integer.valueOf(410), null);
        assertNull(api.read("Patient", "nope"));
    }

    @Test
    void readUnreachableThrowsAnActionableException() {
        service.readFailure = new ServerOperationException(
                ServerOperationException.Kind.UNREACHABLE, "no route");
        Exception thrown = assertThrows(ScriptApiException.class,
                () -> api.read("Patient", "1"));
        assertTrue(thrown.getMessage().contains("Cannot read"),
                "expected a script-facing message, got: " + thrown.getMessage());
    }

    @Test
    void readRequiresAnActiveServer() {
        manager = new FhirServerManager();
        api = new FhirApi(service, manager, WritePolicy.READ_ONLY);
        Exception thrown = assertThrows(ScriptApiException.class,
                () -> api.read("Patient", "1"));
        assertTrue(thrown.getMessage().contains("No server is selected"),
                "expected a no-server message, got: " + thrown.getMessage());
    }

    @Test
    void searchReturnsAPage() {
        service.searchPage = new SearchResultPage(
                List.of(patient("1", "Smith"), patient("2", "Jones")), 42,
                new SearchPageLinks(null, null, null,
                        "http://test.example.com/fhir/Patient?page=2", null));
        SearchResults results = api.search("Patient", new SearchCriterion("name", "Smith"));
        assertEquals(2, results.size());
        assertEquals(Integer.valueOf(42), results.total());
        assertTrue(results.hasNextPage());
        assertEquals("http://test.example.com/fhir/Patient?page=2", results.nextPageToken());
    }

    @Test
    void searchRawAcceptsAQueryString() {
        service.searchPage = new SearchResultPage(
                List.of(patient("1", "Smith")), null, SearchPageLinks.none());
        SearchResults results = api.searchRaw("Patient", "name=Smith&_count=5");
        assertEquals(1, results.size());
        assertFalse(results.hasNextPage());
    }

    @Test
    void nextPageRequiresAToken() {
        Exception thrown = assertThrows(ScriptApiException.class, () -> api.nextPage(""));
        assertTrue(thrown.getMessage().contains("token"),
                "expected a token message, got: " + thrown.getMessage());
    }

    @Test
    void createIsRefusedByDefault() {
        assertThrows(WriteDeniedException.class, () -> api.create(patient("1", "Smith")));
    }

    @Test
    void createIsAllowedWhenWritesAreEnabled() {
        service.writeResult = ServerWriteResult.created("created-7", "v2");
        ServerWriteResult result = writes.create(patient("1", "Smith"));
        assertEquals("created-7", result.resourceId());
        assertEquals("v2", result.versionId());
        assertTrue(result.created());
    }

    @Test
    void updateIsRefusedByDefault() {
        assertThrows(WriteDeniedException.class, () -> api.update(patient("1", "Smith")));
    }

    @Test
    void deleteIsRefusedByDefault() {
        assertThrows(WriteDeniedException.class, () -> api.delete("Patient", "1"));
    }

    @Test
    void readNeedsNonBlankTypeAndId() {
        assertThrows(ScriptApiException.class, () -> api.read("", "1"));
        assertThrows(ScriptApiException.class, () -> api.read("Patient", "   "));
    }
}
