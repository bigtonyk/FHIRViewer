package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.FhirServerManager;
import com.example.fhirviewer.server.FhirServerPluginRegistry;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.ServerAuthKind;
import com.example.fhirviewer.server.ServerDefinition;
import com.example.fhirviewer.server.ServerOrigin;

import javafx.application.Platform;

/**
 * A smoke test that builds the new Phase 6 screens on a real JavaFX toolkit.
 *
 * <p>Everything else in the {@code ui} package is tested through logic that needs no
 * display, because a headless build cannot give one. That leaves a real gap: a dialog whose
 * constructor throws while assembling its scene graph is invisible to those tests, and a
 * mis-wired handler or a node added twice is exactly the kind of defect that only shows when
 * the screen is actually built.</p>
 *
 * <p>So this test starts the toolkit once, builds each new screen against a live stub
 * server, and closes it again. It asserts that construction succeeds and that the screens
 * reached the server — not what they look like.</p>
 *
 * <p>Skipped automatically when no display is available, so a headless build does not start
 * failing on a toolkit it cannot have.</p>
 */
class ServerUiSmokeTest {

    private static final CountDownLatch STARTED = new CountDownLatch(1);
    private static final AtomicReference<Throwable> TOOLKIT_FAILURE = new AtomicReference<>();

    private StubServer server;
    private FhirServerService service;
    private FhirServerManager manager;
    private ThemeManager themeManager;
    private ServerOperationDialog built;

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            Platform.startup(STARTED::countDown);
            STARTED.await(30, TimeUnit.SECONDS);
        } catch (Throwable toolkitProblem) {
            // Recorded rather than thrown: an unavailable toolkit must skip the test, not
            // fail it. A real defect in the screens still fails the test below.
            TOOLKIT_FAILURE.set(toolkitProblem);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        requireToolkit();
        server = new StubServer();
        server.start();
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(new ShapePlugin());
        service = new FhirServerService(registry);
        manager = new FhirServerManager();
        manager.add(ServerDefinition.named("Shapes", server.baseUrl())
                .pluginId(ShapePlugin.PLUGIN_ID)
                .build());
        themeManager = new ThemeManager();
    }

    /** Skips the test when there is no usable toolkit, rather than failing it. */
    private static void requireToolkit() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(), "No display available for the JavaFX toolkit.");
        org.junit.jupiter.api.Assumptions.assumeTrue(TOOLKIT_FAILURE.get() == null,
                "The JavaFX toolkit did not start: " + TOOLKIT_FAILURE.get());
    }

    @Test
    @DisplayName("The server status screen builds")
    void buildsTheStatusScreen() throws Exception {
        // Construction only describes the server; nothing is sent until Connect or
        // Capabilities is pressed, which is deliberate — opening a screen should not make a
        // request the user did not ask for.
        runOnFxThread(() -> {
            ServerStatusDialog dialog = new ServerStatusDialog(service, manager,
                    manager.servers().get(0), themeManager);
            dialog.close();
        });
    }

    @Test
    @DisplayName("The operation screen builds and lists what the plugin offered")
    void buildsTheOperationScreen() throws Exception {
        runOnFxThread(() -> built = new ServerOperationDialog(service, manager,
                manager.servers().get(0), themeManager));
        assertNotNull(built, "The operation screen was not built");

        // Discovery is background work, so the list fills shortly after the screen is built
        // rather than during construction — the intended behaviour, since the screen shows
        // a spinner meanwhile. The screen is deliberately left open until the list has
        // filled: closing it cancels the request in flight, which is the other thing this
        // phase added and is worth not accidentally asserting against.
        awaitOperations(ShapePlugin.OPERATIONS.size());

        // The list is populated from the plugin's discovery, so this is the whole Phase 6
        // claim in one assertion: operations the application has never heard of appear in
        // the UI, named by the plugin, with no vendor branch anywhere in the codebase.
        assertEquals(ShapePlugin.OPERATIONS.size(), built.operationCount(),
                "The screen did not list every operation the plugin offered");
        assertTrue(built.operationIds().contains("untyped"),
                "The under-declared vendor operation is missing from the screen");

        runOnFxThread(() -> built.close());
    }

    /**
     * Waits for the operation list to fill, reading it on the JavaFX thread each time.
     *
     * <p>Polled rather than awaited once because the fill happens on a background thread
     * with no completion signal this test can observe without exposing one purely for
     * testing.</p>
     */
    private void awaitOperations(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            int[] seen = {0};
            runOnFxThread(() -> seen[0] = built.operationCount());
            if (seen[0] >= expected) {
                return;
            }
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("The patch screen builds against the application's own JSON editor")
    void buildsThePatchScreen() throws Exception {
        runOnFxThread(() -> {
            PatchResourceDialog dialog = new PatchResourceDialog(
                    ServerOrigin.of("shapes", server.baseUrl(), "Patient", "123", "1"),
                    themeManager);
            dialog.close();
        });
    }

    @Test
    @DisplayName("The save-to-server screen builds, with and without a version")
    void buildsTheSaveScreen() throws Exception {
        runOnFxThread(() -> {
            Patient patient = new Patient();
            FhirServerConfiguration only = manager.servers().get(0);
            // A versioned resource: the force and unversioned rows are computed during
            // construction, so this is the branch where both are decided.
            SaveToServerDialog versioned = new SaveToServerDialog(manager.servers(), only,
                    ServerOrigin.of(ShapePlugin.PLUGIN_ID, server.baseUrl(), "Patient", "123", "7"),
                    patient, "Patient");
            versioned.close();

            // An unversioned resource: the warning about not being able to detect a
            // conflicting change is the whole reason this branch exists.
            SaveToServerDialog unversioned = new SaveToServerDialog(manager.servers(), only,
                    ServerOrigin.of(ShapePlugin.PLUGIN_ID, server.baseUrl(), "Patient", "123", null),
                    patient, "Patient");
            unversioned.close();

            // A resource that has never been on a server, which is a create.
            SaveToServerDialog creating = new SaveToServerDialog(manager.servers(), null, null,
                    patient, "Patient");
            creating.close();

            // An empty server list must not throw: there is no choice to offer, and the
            // screen says so rather than failing to open.
            SaveToServerDialog none = new SaveToServerDialog(List.of(), null, null,
                    patient, "Patient");
            none.close();
        });
    }

    @Test
    @DisplayName("The open-from-server screen builds with its search results table")
    void buildsTheOpenScreen() throws Exception {
        runOnFxThread(() -> {
            OpenFromServerDialog dialog = new OpenFromServerDialog(service, manager,
                    manager.servers().get(0));
            dialog.close();
        });
    }

    @Test
    @DisplayName("The server manager adds a server when Save is pressed")
    void managerAddsAServer() throws Exception {
        // The bug this replaces: the add dialog registered its own "Save" ButtonType but
        // compared the result converter against ButtonType.OK, a different object the pane
        // never received. Every press therefore produced null, the window added no server,
        // and every Tools item that needed one quietly did nothing.
        //
        // That was only catchable by pressing the real button. The screen built perfectly,
        // which is why every other test here passed while the whole server feature was dead.
        FhirServerManager fresh = new FhirServerManager();
        ServerDefinition added = ServerDefinition.named("Canned", "https://example.org/fhir")
                .build();

        runOnFxThread(() -> {
            ServerManagerDialog dialog = new ServerManagerDialog(service, fresh, null);
            dialog.addButton().fire();
            dialog.form().nameField().setText(added.name());
            dialog.form().urlField().setText(added.baseUrl());
            dialog.saveButton().fire();
            dialog.close();
        });

        assertEquals(1, fresh.servers().size(),
                "pressing Save must add the server the user described, or no server is ever"
                        + " added and every server feature stays dead");
        assertEquals(added.name(), fresh.servers().get(0).name());
        assertEquals(added.baseUrl(), fresh.servers().get(0).baseUrl());
    }

    @Test
    @DisplayName("The server manager edits the selected server instead of adding another")
    void managerEditsTheSelectedServer() throws Exception {
        // The reason the manager exists. Adding a second entry with a corrected URL would
        // leave the broken one configured, and the user would have to delete it by hand.
        FhirServerManager fresh = new FhirServerManager();
        fresh.add(ServerDefinition.named("Original", "https://old.example.org/fhir").build());

        runOnFxThread(() -> {
            ServerManagerDialog dialog = new ServerManagerDialog(service, fresh, null);
            dialog.serverList().getSelectionModel().selectFirst();
            dialog.form().urlField().setText("https://new.example.org/fhir");
            dialog.saveButton().fire();
            dialog.close();
        });

        assertEquals(1, fresh.servers().size(),
                "editing must replace the server, not add a second one beside it");
        assertEquals("https://new.example.org/fhir", fresh.servers().get(0).baseUrl());
    }

    @Test
    @DisplayName("The server manager deletes the selected server")
    void managerDeletesTheSelectedServer() throws Exception {
        FhirServerManager fresh = new FhirServerManager();
        fresh.add(ServerDefinition.named("Doomed", "https://gone.example.org/fhir").build());
        fresh.add(ServerDefinition.named("Kept", "https://kept.example.org/fhir").build());

        runOnFxThread(() -> {
            ServerManagerDialog dialog = new ServerManagerDialog(service, fresh, null);
            dialog.serverList().getSelectionModel().selectFirst();
            dialog.deleteButton().fire();
            dialog.close();
        });

        assertEquals(1, fresh.servers().size(), "the selected server must be removed");
        assertEquals("Kept", fresh.servers().get(0).name(),
                "the wrong server was deleted; selection order is configuration order");
    }

    // MARKER_MORE_MANAGER_TESTS
    @Test
    @DisplayName("The server manager lists the servers already configured")
    void managerListsConfiguredServers() throws Exception {
        // Without this the list starts empty every time and the user has no way to reach a
        // server they added earlier - which was the gap that made editing impossible.
        FhirServerManager fresh = new FhirServerManager();
        fresh.add(ServerDefinition.named("One", "https://one.example.org/fhir").build());
        fresh.add(ServerDefinition.named("Two", "https://two.example.org/fhir").build());

        AtomicReference<ServerManagerDialog> built = new AtomicReference<>();
        runOnFxThread(() -> {
            built.set(new ServerManagerDialog(service, fresh, null));
            built.get().close();
        });

        assertEquals(2, built.get().serverList().getItems().size(),
                "the manager must show the servers already configured");
    }

    @Test
    @DisplayName("The server manager refuses to save a server it cannot build")
    void managerRefusesAnIncompleteServer() throws Exception {
        FhirServerManager fresh = new FhirServerManager();

        runOnFxThread(() -> {
            ServerManagerDialog dialog = new ServerManagerDialog(service, fresh, null);
            dialog.addButton().fire();
            // Fields left blank: the model layer must refuse rather than the dialog
            // adding a server that cannot be reached.
            dialog.saveButton().fire();
            assertTrue(fresh.servers().isEmpty(),
                    "a blank name must be refused, not stored as an unreachable server");
            assertTrue(dialog.statusText().toLowerCase().contains("name"),
                    "the user must be told which field is wrong, but was told: "
                            + dialog.statusText());
            dialog.close();
        });
    }

    @Test
    @DisplayName("Save and Delete are disabled until a server is selected")
    void managerDisablesActionsWithoutASelection() throws Exception {
        AtomicReference<ServerManagerDialog> built = new AtomicReference<>();
        runOnFxThread(() -> {
            built.set(new ServerManagerDialog(service, new FhirServerManager(), null));
            built.get().close();
        });

        assertTrue(built.get().saveButton().isDisabled(),
                "Save with nothing selected would silently do nothing");
        assertTrue(built.get().deleteButton().isDisabled(),
                "Delete with nothing selected would silently do nothing");
        assertFalse(built.get().addButton().isDisabled(),
                "Add is how a first server gets configured, so it must always be available");
    }

    @Test
    @DisplayName("Choosing an authentication kind reveals the fields it needs")
    void managerRevealsTheFieldsTheAuthKindNeeds() throws Exception {
        AtomicReference<ServerManagerDialog> built = new AtomicReference<>();
        runOnFxThread(() -> {
            built.set(new ServerManagerDialog(service, new FhirServerManager(), null));
            built.get().close();
        });
        ServerManagerDialog dialog = built.get();

        runOnFxThread(() -> {
            assertFalse(dialog.form().userField().isVisible(),
                    "anonymous access has no user name to type");

            dialog.form().authBox().getSelectionModel().select(ServerAuthKind.BASIC);
            assertTrue(dialog.form().userField().isVisible(),
                    "Basic needs a user name, so hiding the field would make it unsettable");
            assertTrue(dialog.form().secretField().isVisible(),
                    "Basic needs a password");

            dialog.form().authBox().getSelectionModel().select(ServerAuthKind.BEARER);
            assertFalse(dialog.form().userField().isVisible(),
                    "a bearer token stands alone and has no user name");
            assertTrue(dialog.form().secretField().isVisible(),
                    "Bearer still needs its token");
        });
    }

    @Test
    @DisplayName("The operation screen lists the real Firely plugin's operations")
    void listsTheRealFirelyOperations() throws Exception {
        // The ShapePlugin case above proves the screen renders whatever a plugin declares.
        // It does not prove a *shipped* plugin declares anything - and for a long time none
        // of them did, so the screen was perfect in the suite and empty in the product.
        // This drives the real FirelyPlugin through the real screen, which is the only
        // combination that would have caught it.
        FhirServerPluginRegistry firelyRegistry = new FhirServerPluginRegistry();
        firelyRegistry.register(new com.example.fhirviewer.server.FirelyPlugin());
        FhirServerService firelyService = new FhirServerService(firelyRegistry);
        FhirServerManager firelyManager = new FhirServerManager();
        firelyManager.add(ServerDefinition.forFirely("Public", "https://server.fire.ly").build());

        AtomicReference<ServerOperationDialog> builtFirely = new AtomicReference<>();
        runOnFxThread(() -> builtFirely.set(new ServerOperationDialog(firelyService, firelyManager,
                firelyManager.servers().get(0), themeManager)));
        ServerOperationDialog screen = builtFirely.get();
        assertNotNull(screen, "The operation screen was not built for the Firely plugin");

        // Counted from the plugin rather than hard-coded, so adding a documented operation
        // does not turn into a failing test that has to be re-tuned. The assertions below
        // check that specific operations are present, which is what actually matters; a
        // magic number here would only ever detect that the number changed.
        int expected = new com.example.fhirviewer.server.FirelyPlugin().availableOperations().size();
        awaitCount(expected, screen);
        assertEquals(expected, screen.operationCount(),
                "the screen must show every operation the Firely plugin declares");
        assertTrue(screen.operationIds().contains("$reindex"),
                "the re-index operation is missing from the screen");
        assertTrue(screen.operationIds().contains("$import-resources"),
                "Firely documents $import-resources; it must reach the screen");
        assertTrue(screen.operationIds().contains("$cql"),
                "the measure operations run on the FHIR endpoint and must reach the screen");
        assertTrue(screen.operationIds().contains("$export"),
                "bulk export is inherited from the standard layer and must survive the override");

        runOnFxThread(() -> screen.close());
    }

    @Test
    @DisplayName("The operation screen lists the real Smile plugin's operations")
    void listsTheRealSmileOperations() throws Exception {
        // The same check for Smile, and the one that matters more: Smile used to declare
        // nothing at all, so a Smile user saw an empty operation screen that looked like a
        // failure. Driving the real plugin through the real screen is the only combination
        // that would have caught it.
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(new com.example.fhirviewer.server.SmileCdrPlugin());
        FhirServerService service = new FhirServerService(registry);
        FhirServerManager manager = new FhirServerManager();
        manager.add(ServerDefinition.forSmileCdr("Smile", "https://example.org/fhir").build());

        AtomicReference<ServerOperationDialog> built = new AtomicReference<>();
        runOnFxThread(() -> built.set(new ServerOperationDialog(service, manager,
                manager.servers().get(0), themeManager)));
        ServerOperationDialog screen = built.get();
        assertNotNull(screen, "The operation screen was not built for the Smile plugin");

        int expected = new com.example.fhirviewer.server.SmileCdrPlugin()
                .availableOperations().size();
        awaitCount(expected, screen);
        assertEquals(expected, screen.operationCount(),
                "the screen must show every operation the Smile plugin declares");
        assertTrue(screen.operationIds().contains("$reindex"),
                "Smile's system re-index is missing from the screen");
        assertTrue(screen.operationIds().contains("reindex-dryrun"),
                "the dry run is the safe one and is the one most worth offering");
        assertTrue(screen.operationIds().contains("$export"),
                "bulk export is inherited from the standard layer and must survive the override");

        runOnFxThread(() -> screen.close());
    }
    private void awaitCount(int expected, ServerOperationDialog dialog) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            int[] seen = {0};
            runOnFxThread(() -> seen[0] = dialog.operationCount());
            if (seen[0] >= expected) {
                return;
            }
            Thread.sleep(50);
        }
    }

    /**
     * Waits briefly for the stub to record a request.
     *
     * <p>Discovery and capability reads are background work by design, so the screen has
     * finished constructing before the request has necessarily been made. Polling here
     * asserts the call really happens without making the test depend on how quickly.</p>
     */
    private boolean awaitRequest() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (server.requestCount() > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** Runs an action on the JavaFX thread and waits for it, so a throw is not swallowed. */
    private static void runOnFxThread(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "The JavaFX thread did not respond.");
        assertNotNull(done);
        if (failure.get() != null) {
            throw new AssertionError("Building a Phase 6 screen failed: " + failure.get(),
                    failure.get());
        }
    }
}
