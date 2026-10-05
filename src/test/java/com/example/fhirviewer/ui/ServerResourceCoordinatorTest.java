package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.model.ValidationIssue;
import com.example.fhirviewer.model.ValidationReport;
import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.FhirServerPlugin;
import com.example.fhirviewer.server.FhirServerPluginRegistry;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.SearchRequest;
import com.example.fhirviewer.server.SearchResultPage;
import com.example.fhirviewer.server.ServerCapabilities;
import com.example.fhirviewer.server.ServerDefinition;
import com.example.fhirviewer.server.ServerOperationException;
import com.example.fhirviewer.server.ServerOrigin;
import com.example.fhirviewer.server.ServerSession;
import com.example.fhirviewer.server.ServerWriteResult;

/**
 * Tests the rules that decide what a write to a FHIR server does, with no network and
 * no JavaFX toolkit.
 *
 * <p>This is the reason {@link ServerResourceCoordinator} exists. The decisions -
 * validate before sending, create versus update, what a conflict means, how the origin is
 * rebased, which failure is which - used to live inside {@code MainWindow} handlers where
 * a headless build cannot reach them. A fake plugin makes every one of them assertable.</p>
 */
class ServerResourceCoordinatorTest {

    private static final String BASE_URL = "http://canned.example/fhir";
    private static final String PLUGIN_ID = "fake-write";

    private RecordingPlugin plugin;
    private ServerDefinition server;
    private ServerResourceCoordinator coordinator;
    private ValidationReport report;

    @BeforeEach
    void setUp() {
        plugin = new RecordingPlugin();
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(plugin);
        FhirServerService service = new FhirServerService(registry);
        server = ServerDefinition.named("Canned", BASE_URL).pluginId(PLUGIN_ID).build();
        coordinator = new ServerResourceCoordinator(service, resource -> report);
        report = ValidationReport.successful("Patient/example");
    }

    private static Patient patient() {
        Patient patient = new Patient();
        patient.setId("example-1");
        patient.addName().setFamily("Server");
        return patient;
    }

    private static ServerOrigin origin(String version) {
        return ServerOrigin.of(PLUGIN_ID, BASE_URL, "Patient", "example-1", version);
    }

    @Test
    @DisplayName("A resource with no origin is created, and the origin adopts the new id")
    void createAdoptsTheServerId() {
        plugin.createResult = ServerWriteResult.created("generated-1", "1");

        ServerResourceCoordinator.PushResult result = coordinator.push(server, patient(), null);

        assertEquals(ServerResourceCoordinator.Outcome.CREATED, result.outcome());
        assertTrue(result.written());
        assertEquals(1, plugin.createCalls, "a create, not an update");
        assertEquals(0, plugin.updateCalls);
        ServerOrigin rebased = result.origin();
        assertNotNull(rebased, "a created resource must be trackable afterwards");
        assertEquals("generated-1", rebased.resourceId());
        assertEquals("1", rebased.versionId());
        assertTrue(rebased.isSaved(), "the next save must update rather than create again");
    }

    @Test
    @DisplayName("A resource with a server id is updated, keeping its id")
    void updateKeepsTheId() {
        plugin.updateResult = ServerWriteResult.updated("example-1", "3");

        ServerResourceCoordinator.PushResult result =
                coordinator.push(server, patient(), origin("2"));

        assertEquals(ServerResourceCoordinator.Outcome.UPDATED, result.outcome());
        assertEquals(1, plugin.updateCalls, "a resource that exists on the server is updated");
        assertEquals(0, plugin.createCalls);
        assertEquals("3", result.origin().versionId(), "the version moves forward");
        assertEquals("example-1", result.origin().resourceId());
    }

    @Test
    @DisplayName("An origin with no id is treated as a create, not a failed update")
    void unsavedOriginCreates() {
        plugin.createResult = ServerWriteResult.created("generated-2", "1");

        ServerResourceCoordinator.PushResult result = coordinator.push(server, patient(),
                ServerOrigin.unsaved(PLUGIN_ID, BASE_URL, "Patient"));

        assertEquals(ServerResourceCoordinator.Outcome.CREATED, result.outcome());
        assertEquals(1, plugin.createCalls);
    }

    @Test
    @DisplayName("Validation runs first, and a blocking error stops the write")
    void validationBlocksTheWrite() {
        report = new ValidationReport("Patient/example", List.of(
                new ValidationIssue(ValidationIssue.Severity.ERROR, "bad code", "Patient.code", 1, 1)));

        ServerResourceCoordinator.PushResult result =
                coordinator.push(server, patient(), origin("2"));

        assertEquals(ServerResourceCoordinator.Outcome.BLOCKED, result.outcome());
        assertFalse(result.written());
        assertEquals(0, plugin.createCalls, "nothing may be sent when validation failed");
        assertEquals(0, plugin.updateCalls);
        assertNotNull(result.report(), "the issues have to reach the UI to be fixable");
        assertTrue(result.message().contains("1 validation error"));
    }

    @Test
    @DisplayName("A warning does not block the write, and is reported afterwards")
    void warningDoesNotBlock() {
        report = new ValidationReport("Patient/example", List.of(
                new ValidationIssue(ValidationIssue.Severity.WARNING, "soft", "Patient.name", 1, 1)));
        plugin.updateResult = ServerWriteResult.updated("example-1", "2");

        ServerResourceCoordinator.PushResult result =
                coordinator.push(server, patient(), origin("1"));

        assertEquals(ServerResourceCoordinator.Outcome.UPDATED, result.outcome());
        assertEquals(1, plugin.updateCalls);
        assertTrue(result.message().contains("warning"),
                "the user should still learn the resource was sent with one");
    }

    @Test
    @DisplayName("A plugin that cannot write refuses before validation and before the request")
    void unsupportedPluginIsRefused() {
        plugin.writeCapable = false;
        report = new ValidationReport("Patient/example", List.of(
                new ValidationIssue(ValidationIssue.Severity.ERROR, "bad", "Patient.code", 1, 1)));

        ServerResourceCoordinator.PushResult result =
                coordinator.push(server, patient(), origin("2"));

        assertEquals(ServerResourceCoordinator.Outcome.UNSUPPORTED, result.outcome());
        assertNull(result.report(),
                "advising about validation for a request that will never be made is noise");
        assertEquals(0, plugin.createCalls);
    }

    @Test
    @DisplayName("A 412 comes back as a conflict outcome, not an exception")
    void conflictIsAnOutcomeNotAnException() {
        plugin.updateFailure = new ServerOperationException(
                ServerOperationException.Kind.CONFLICT, "version conflict", 412);

        ServerResourceCoordinator.PushResult result =
                coordinator.push(server, patient(), origin("1"));

        assertTrue(result.isConflict());
        assertFalse(result.written());
        assertEquals("example-1", result.origin().resourceId(),
                "the origin is kept so the user can choose what to do about it");
    }

    @Test
    @DisplayName("Forcing a write drops the version so the retry goes through")
    void forceWriteDropsTheVersion() {
        plugin.updateResult = ServerWriteResult.updated("example-1", "5");

        ServerResourceCoordinator.PushResult result =
                coordinator.forceWrite(server, patient(), origin("1"));

        assertEquals(ServerResourceCoordinator.Outcome.UPDATED, result.outcome());
        assertEquals(1, plugin.updateCalls);
        assertNull(plugin.lastOriginVersion,
                "a force write must not send If-Match, or it would just conflict again");
        assertEquals("example-1", result.origin().resourceId(),
                "the id is kept, so this is an update and not a second copy");
    }

    @Test
    @DisplayName("A forced write refuses a resource the server has never seen")
    void forceWriteRefusesANewResource() {
        ServerResourceCoordinator.PushResult result = coordinator.forceWrite(server, patient(),
                ServerOrigin.unsaved(PLUGIN_ID, BASE_URL, "Patient"));

        assertFalse(result.written());
        assertEquals(0, plugin.updateCalls);
    }

    @Test
    @DisplayName("A read-only server reports permission denied cleanly")
    void permissionDeniedIsReportedNotThrown() {
        plugin.updateFailure = new ServerOperationException(
                ServerOperationException.Kind.FORBIDDEN, "not allowed to write", 403);

        ServerResourceCoordinator.PushResult result =
                coordinator.push(server, patient(), origin("2"));

        assertEquals(ServerResourceCoordinator.Outcome.FAILED, result.outcome());
        assertFalse(result.written());
        assertTrue(result.message().toLowerCase(Locale.ROOT).contains("not allowed"),
                "the server's own words should survive: " + result.message());
    }

    @Test
    @DisplayName("A failure message is never null and never empty")
    void everyResultHasAMessage() {
        plugin.updateFailure = new ServerOperationException(
                ServerOperationException.Kind.UNAUTHORIZED, "bad credentials", 401);

        ServerResourceCoordinator.PushResult result =
                coordinator.push(server, patient(), origin("2"));

        assertNotNull(result.message());
        assertFalse(result.message().isBlank());
    }

    @Test
    @DisplayName("A credential in a failure message is redacted before display")
    void credentialsAreRedacted() {
        plugin.updateFailure = new ServerOperationException(
                ServerOperationException.Kind.UNAUTHORIZED,
                "rejected Authorization: Basic YWxpY2U6aHVudGVyMg==", 401);

        ServerResourceCoordinator.PushResult result =
                coordinator.push(server, patient(), origin("2"));

        assertFalse(result.message().contains("YWxpY2U6aHVudGVyMg=="),
                "a message is what ends up in a screenshot: " + result.message());
    }

    @Test
    @DisplayName("No server chosen is reported rather than thrown")
    void missingServerIsReported() {
        ServerResourceCoordinator.PushResult result = coordinator.push(null, patient(), origin("2"));

        assertEquals(ServerResourceCoordinator.Outcome.FAILED, result.outcome());
        assertEquals(0, plugin.createCalls);
    }

    @Test
    @DisplayName("Nothing sensitive appears in the coordinator's own toString")
    void toStringHoldsNoSecret() {
        assertFalse(coordinator.toString().contains(BASE_URL),
                "the server address does not belong in a log line either");
    }

    /**
     * A plugin that records what it was asked to do and answers with whatever the test
     * set up, so no network and no FHIR client is involved.
     */
    private static final class RecordingPlugin implements FhirServerPlugin {

        private boolean writeCapable = true;
        private int createCalls;
        private int updateCalls;
        private String lastOriginVersion;
        private ServerWriteResult createResult = ServerWriteResult.created("generated-1", "1");
        private ServerWriteResult updateResult = ServerWriteResult.updated("example-1", "2");
        private ServerOperationException createFailure;
        private ServerOperationException updateFailure;

        @Override
        public String id() {
            return PLUGIN_ID;
        }

        @Override
        public String displayName() {
            return "Fake write plugin";
        }

        @Override
        public String description() {
            return "Records writes instead of sending them.";
        }

        @Override
        public List<String> supportedFhirVersions() {
            return List.of("R4");
        }

        @Override
        public boolean supports(FhirServerConfiguration configuration) {
            return true;
        }

        @Override
        public ServerCapabilities capabilities(ServerSession session) {
            return ServerCapabilities.empty();
        }

        @Override
        public com.example.fhirviewer.server.ConnectionResult testConnection(ServerSession session) {
            return com.example.fhirviewer.server.ConnectionResult.unreachable("not used by this test");
        }

        @Override
        public SearchResultPage search(ServerSession session, SearchRequest request) {
            return SearchResultPage.empty();
        }

        @Override
        public IBaseResource read(ServerSession session, String resourceType, String resourceId) {
            return null;
        }

        @Override
        public boolean supportsWrite() {
            return writeCapable;
        }

        @Override
        public ServerWriteResult create(ServerSession session, IBaseResource resource)
                throws ServerOperationException {
            createCalls++;
            if (createFailure != null) {
                throw createFailure;
            }
            return createResult;
        }

        @Override
        public ServerWriteResult update(ServerSession session, IBaseResource resource,
                ServerOrigin origin) throws ServerOperationException {
            updateCalls++;
            lastOriginVersion = origin == null ? null : origin.versionId();
            if (updateFailure != null) {
                throw updateFailure;
            }
            return updateResult;
        }
    }
}
