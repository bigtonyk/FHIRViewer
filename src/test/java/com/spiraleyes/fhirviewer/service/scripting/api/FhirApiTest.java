package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.PatchFormat;
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
        assertEquals(0, service.createCalls, "a refused write must not reach the server");
    }

    @Test
    void createIsAllowedWhenWritesAreEnabled() {
        service.writeResult = ServerWriteResult.created("created-7", "v2");
        ServerWriteResult result = writes.create(patient("1", "Smith"));
        assertEquals("created-7", result.resourceId());
        assertEquals("v2", result.versionId());
        assertTrue(result.created());
        assertEquals(1, service.createCalls);
    }

    @Test
    void updateIsRefusedByDefault() {
        assertThrows(WriteDeniedException.class, () -> api.update(patient("1", "Smith")));
        assertEquals(0, service.updateCalls, "a refused write must not reach the server");
    }

    @Test
    void updateIsAllowedWhenWritesAreEnabled() {
        service.writeResult = ServerWriteResult.updated("1", "v4");
        ServerWriteResult result = writes.update(patient("1", "Jones"));

        assertEquals("1", result.resourceId());
        assertEquals("v4", result.versionId());
        assertFalse(result.created());
        assertEquals(1, service.updateCalls);
        assertEquals("1", service.lastUpdateOrigin.resourceId());
    }

    @Test
    void deleteIsRefusedByDefault() {
        assertThrows(WriteDeniedException.class, () -> api.delete("Patient", "1"));
        assertEquals(0, service.deleteCalls, "a refused delete must not reach the server");
    }

    @Test
    void patchIsRefusedByDefault() {
        assertThrows(WriteDeniedException.class,
                () -> api.patch("Patient", "1", "{\"active\":false}", PatchFormat.JSON_MERGE_PATCH));
        assertEquals(0, service.patchCalls, "a refused patch must not reach the server");
    }

    @Test
    void patchIsAllowedWhenWritesAreEnabled() {
        service.patchResult = ServerWriteResult.updated("1", "v3");
        ServerWriteResult result = writes.patch("Patient", "1", "{\"active\":false}", PatchFormat.JSON_MERGE_PATCH);
        assertEquals("1", result.resourceId());
        assertEquals("v3", result.versionId());
        assertFalse(result.created());
        assertEquals(1, service.patchCalls);
    }

    @Test
    void deleteIsAllowedWhenWritesAreEnabled() {
        int before = service.deleteCalls;
        writes.delete("Patient", "1");
        assertEquals(before + 1, service.deleteCalls);
    }

    @Test
    void deleteIsCancelledWhenConfirmationDenies() {
        int before = service.deleteCalls;
        AtomicReference<String> summary = new AtomicReference<>();
        FhirApi cancelling = new FhirApi(service, manager,
                WritePolicy.enabled((text) -> {
                    summary.set(text);
                    return false;
                }));
        assertThrows(WriteDeniedException.class, () -> cancelling.delete("Patient", "1"));
        assertEquals(before, service.deleteCalls);
        assertTrue(summary.get().contains("Patient/1"), summary.get());
        assertTrue(summary.get().contains("Test"), summary.get());
        assertTrue(summary.get().contains("http://test.example.com/fhir"), summary.get());
    }

    @Test
    void deleteIsAllowedWhenConfirmationApproves() {
        int before = service.deleteCalls;
        AtomicReference<String> summary = new AtomicReference<>();
        FhirApi approving = new FhirApi(service, manager,
                WritePolicy.enabled((text) -> {
                    summary.set(text);
                    return true;
                }));

        approving.delete("Patient", "1");

        assertEquals(before + 1, service.deleteCalls);
        assertTrue(summary.get().contains("Patient/1"), summary.get());
    }

    @Test
    void readNeedsNonBlankTypeAndId() {
        assertThrows(ScriptApiException.class, () -> api.read("", "1"));
        assertThrows(ScriptApiException.class, () -> api.read("Patient", "   "));
    }

    @Test
    void aStoredReferenceStopsWritingWhenTheGrantIsRevoked() {
        WritePolicy policy = WritePolicy.enabled();
        FhirApi held = new FhirApi(service, manager, policy);

        policy.revoke();

        assertThrows(WriteDeniedException.class, () -> held.create(patient("1", "Smith")));
        assertEquals(0, service.createCalls, "revocation must happen before the request");
    }

    @Test
    void aGrantBoundToAServerRefusesAnotherActiveServer() {
        WriteJournal journal = new WriteJournal();
        WritePolicy policy = WritePolicy.enabled(null, manager.active().orElseThrow(), journal,
                0, null, null);
        FhirApi bound = new FhirApi(service, manager, policy);
        ServerDefinition other = ServerDefinition.named("Other", "http://other.example.com/fhir")
                .pluginId("standard-rest").build();
        manager.add(other);
        manager.setActive(other);

        assertThrows(WriteDeniedException.class, () -> bound.create(patient("1", "Smith")));
        assertEquals(0, service.createCalls, "the wrong server must never receive the write");
    }

    @Test
    void aSuccessfulWriteIsJournaledWithoutTheResourceBody() {
        WriteJournal journal = new WriteJournal();
        WritePolicy policy = WritePolicy.enabled(null, manager.active().orElseThrow(), journal,
                0, null, null);
        FhirApi journaled = new FhirApi(service, manager, policy);
        service.writeResult = ServerWriteResult.created("created-9", "v3");

        journaled.create(patient("1", "Smith"));

        assertEquals(1, journal.size());
        WriteJournal.Entry entry = journal.entries().get(0);
        assertEquals("create", entry.operation());
        assertEquals("Patient", entry.resourceType());
        assertEquals("created-9", entry.resourceId());
        assertEquals("v3", entry.versionId());
        assertEquals(WriteJournal.Status.SUCCEEDED, entry.status());
        assertFalse(entry.toString().contains("Smith"), "the journal must not keep the body");
    }

    @Test
    void anUpdateCapturesThePreviousResourceForBestEffortUndo() {
        WriteJournal journal = new WriteJournal();
        WritePolicy policy = WritePolicy.enabled(null, manager.active().orElseThrow(), journal,
                0, null, null);
        FhirApi journaled = new FhirApi(service, manager, policy);
        Patient before = patient("1", "Before");
        service.readResource = before;

        journaled.update(patient("1", "After"));

        WriteJournal.Entry entry = journal.entries().get(0);
        assertEquals(before, journal.undoFor(entry).orElseThrow().before());
    }

    @Test
    void aTimedOutWriteIsReportedAsOutcomeUnknown() {
        WriteJournal journal = new WriteJournal();
        WritePolicy policy = WritePolicy.enabled(null, manager.active().orElseThrow(), journal,
                0, null, null);
        FhirApi journaled = new FhirApi(service, manager, policy);
        service.writeFailure = new ServerOperationException(
                ServerOperationException.Kind.TIMEOUT, "deadline passed");

        WriteOutcomeUnknownException failure = assertThrows(WriteOutcomeUnknownException.class,
                () -> journaled.create(patient("1", "Smith")));

        assertTrue(failure.getMessage().contains("may have been applied") ||
                failure.getMessage().contains("outcome is unknown"), failure.getMessage());
        assertEquals(1, service.createCalls);
        assertEquals(WriteJournal.Status.UNKNOWN, journal.entries().get(0).status());
    }

    @Test
    void patchAndDeleteSendTheVersionWhenOneIsSupplied() {
        service.patchResult = ServerWriteResult.updated("1", "v4");
        writes.patch("Patient", "1", "{\"active\":false}", PatchFormat.JSON_MERGE_PATCH, "v3");
        writes.delete("Patient", "1", "v4");

        assertEquals("v3", service.lastPatchOrigin.versionId());
        assertEquals("v4", service.lastDeleteOrigin.versionId());
    }

    @Test
    void aWriteBudgetPausesAtTheLimitAndResumesWhenApproved() {
        AtomicReference<Integer> promptedAt = new AtomicReference<>();
        WritePolicy policy = WritePolicy.enabled(null, manager.active().orElseThrow(),
                new WriteJournal(), 2, (completed, target) -> {
                    promptedAt.set(completed);
                    return true;
                }, null);
        FhirApi budgeted = new FhirApi(service, manager, policy);
        service.writeResult = ServerWriteResult.created("created", "v1");

        budgeted.create(patient("1", "Smith"));
        budgeted.create(patient("2", "Jones"));
        budgeted.create(patient("3", "Brown"));

        assertEquals(2, promptedAt.get(), "the prompt must happen at the configured limit");
        assertEquals(3, service.createCalls);
    }
}
