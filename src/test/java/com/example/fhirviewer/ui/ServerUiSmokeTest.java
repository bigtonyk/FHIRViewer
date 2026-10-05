package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import com.example.fhirviewer.server.SearchCriterion;
import com.example.fhirviewer.server.SearchRequest;
import com.example.fhirviewer.server.ServerDefinition;
import com.example.fhirviewer.server.ServerOrigin;
import com.example.fhirviewer.server.rest.RestMethod;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.Node;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

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
    @DisplayName("The status screen names the plugin serving the server, in words")
    void statusScreenNamesTheServingPlugin() throws Exception {
        // The confusion this fixes was real and cost several rounds: a Firely server saved
        // with the default plugin is served as a plain FHIR server, and offers two
        // operations - which reads as "Firely only has two". Naming the plugin is what makes
        // the two cases tellable apart.
        AtomicReference<String> report = new AtomicReference<>();
        runOnFxThread(() -> {
            ServerStatusDialog dialog = new ServerStatusDialog(service, manager,
                    manager.servers().get(0), themeManager);
            report.set(dialog.reportTextForTest());
            dialog.close();
        });

        String text = report.get();
        assertNotNull(text, "the status screen showed no report at all");
        assertTrue(text.contains("Served by:"),
                "the status screen does not say which plugin serves this server:\n" + text);
        assertTrue(text.contains("Result Shapes"),
                "the plugin is named by id rather than by its display name, which is not "
                        + "something a user can act on:\n" + text);
    }

    @Test
    @DisplayName("The open-from-server screen builds with its search results table")
    void buildsTheOpenScreen() throws Exception {
        runOnFxThread(() -> {
            OpenFromServerDialog dialog = new OpenFromServerDialog(service, manager,
                    manager.servers().get(0), new SearchMemory());
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
            dialog.serverBox().getSelectionModel().select(1);
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
            dialog.serverBox().getSelectionModel().select(1);
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

        assertEquals(3, built.get().serverBox().getItems().size(),
                "the selector must offer the blank entry plus both configured servers, so the"
                        + " user can switch between them from the form");
        assertTrue(built.get().serverBox().getItems().contains("One"),
                "the configured server names must be listed");
        assertTrue(built.get().serverBox().getItems().contains("Two"),
                "the configured server names must be listed");
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
    @DisplayName("Delete needs a configured server; Save and Add are always available")
    void managerDisablesDeleteWithoutASelection() throws Exception {
        // With the selector, the blank entry means "a form ready for a new server", so Save
        // is deliberately available from the moment the dialog opens - that is how a first
        // server gets added, and disabling it there left no way to configure anything.
        // Delete is different: it can only remove something that exists.
        AtomicReference<ServerManagerDialog> built = new AtomicReference<>();
        runOnFxThread(() -> {
            built.set(new ServerManagerDialog(service, new FhirServerManager(), null));
            built.get().close();
        });

        assertTrue(built.get().deleteButton().isDisabled(),
                "Delete with no server selected would silently do nothing");
        assertFalse(built.get().addButton().isDisabled(),
                "Add is how a first server gets configured, so it must always be available");
        assertFalse(built.get().saveButton().isDisabled(),
                "Save must be available on the blank selector entry, which is a form ready"
                        + " for a new server");
    }

    @Test
    @DisplayName("No two form fields are laid out in the same grid row")
    void noTwoFieldsShareAGridRow() throws Exception {
        // "User name" and "Password" were both added with `row` instead of `row++`, so they
        // landed in the same cell. Their labels drew over each other and the label column
        // clipped them to an ellipsis. Every other check passed: both controls existed, were
        // enabled and were populated correctly - they were simply in the wrong place, which
        // is invisible until someone looks at the window.
        AtomicReference<String> clash = new AtomicReference<>();
        runOnFxThread(() -> {
            ServerManagerDialog dialog = new ServerManagerDialog(service, new FhirServerManager(), null);
            inspect(dialog.getDialogPane(), node -> {
                if (node instanceof javafx.scene.layout.GridPane grid) {
                    java.util.Map<Integer, java.util.List<Node>> rows = new java.util.HashMap<>();
                    for (Node child : grid.getChildren()) {
                        if (child instanceof javafx.scene.control.Label) {
                            // Row labels must not share a row; controls may, in principle.
                            if (rows.computeIfAbsent(GridPane.getRowIndex(child),
                                    k -> new java.util.ArrayList<>()).size() > 1) {
                                clash.set("row " + GridPane.getRowIndex(child));
                            }
                        }
                    }
                }
            });
            dialog.close();
        });

        assertNull(clash.get(), "two labels share grid " + clash.get()
                + ", so they overlap and the narrow label column clips them");
    }

    @Test
    @DisplayName("Choosing a server from the selector loads it into the form")
    void choosingAServerLoadsIt() throws Exception {
        // The behaviour the selector exists for: switch between configured servers without
        // deleting and re-adding, and without a separate list to find the right one in.
        FhirServerManager fresh = new FhirServerManager();
        fresh.add(ServerDefinition.named("Prod", "https://prod.example.org/fhir").build());
        fresh.add(ServerDefinition.named("Test", "https://test.example.org/fhir").build());

        runOnFxThread(() -> {
            ServerManagerDialog dialog = new ServerManagerDialog(service, fresh, null);
            dialog.serverBox().getSelectionModel().select("Test");
            assertEquals("https://test.example.org/fhir",
                    dialog.form().urlField().getText(),
                    "choosing a server must load its details");
            dialog.serverBox().getSelectionModel().select("Prod");
            assertEquals("https://prod.example.org/fhir",
                    dialog.form().urlField().getText(),
                    "choosing another server must replace what is loaded");
            dialog.close();
        });
    }

    @Test
    @DisplayName("The selector opens on a new server rather than silently selecting one")
    void theSelectorOpensOnNew() throws Exception {
        // Without the blank entry an editable combo opens on the first configured server,
        // so simply looking at the dialog would start an edit of it.
        FhirServerManager fresh = new FhirServerManager();
        fresh.add(ServerDefinition.named("Prod", "https://prod.example.org/fhir").build());

        runOnFxThread(() -> {
            ServerManagerDialog dialog = new ServerManagerDialog(service, fresh, null);
            // The blank entry is selected, not null: it is a real item in the drop-down and
            // the user selects it deliberately to start a new server.
            assertTrue(dialog.serverBox().getValue() == null
                            || dialog.serverBox().getValue().isBlank(),
                    "opening the dialog must not preselect a server, but selected "
                            + dialog.serverBox().getValue());
            assertTrue(dialog.form().urlField().getText().isEmpty(),
                    "the form must start empty");
            dialog.close();
        });
    }

    @Test
    @DisplayName("The form is actually visible once laid out")
    void theFormIsActuallyVisible() throws Exception {
        // Checks the symptom a user reports rather than a property. The dialog was once
        // squashed to the left with the server list measured at 12px, because a preferred
        // width of MAX_VALUE on the status line made every container above it unbounded.
        // Laying out at a real window size and measuring is what catches that class of bug;
        // asserting on grow priorities alone would not.
        FhirServerManager fresh = new FhirServerManager();
        fresh.add(ServerDefinition.named("Prod", "https://prod.example.org/fhir").build());

        AtomicReference<double[]> bounds = new AtomicReference<>();
        runOnFxThread(() -> {
            ServerManagerDialog dialog = new ServerManagerDialog(service, fresh, null);
            // The DialogPane is already the root of the dialog's own scene, so it cannot be
            // made the root of another. Wrapping it in a container lets it be laid out at a
            // known size without disturbing that.
            javafx.scene.layout.StackPane host = new javafx.scene.layout.StackPane(
                    dialog.getDialogPane());
            Scene scene = new Scene(host, 900, 500);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            Bounds laidOut = dialog.form().nameField().getBoundsInParent();
            bounds.set(new double[] { laidOut.getWidth(), laidOut.getHeight() });
            dialog.close();
        });

        assertTrue(bounds.get()[0] >= 200,
                "the form collapsed to " + bounds.get()[0] + "px wide");
        assertTrue(bounds.get()[1] > 0, "the form has no height at all");
    }

    @Test
    @DisplayName("The Firely plugin reaches the running application")
    void firelyReachesTheApplication() throws Exception {
        // Investigating "the Firely operation list only shows 2 operations" turned up two
        // things that look identical and are not:
        //
        //  - FirelyPlugin is deliberately absent from the META-INF/services file. It is
        //    loaded from configuration instead, so a user can switch it off without
        //    changing the class path. Adding it to the service file would be wrong, and
        //    defeats the deny list.
        //  - The application builds its registry with PluginLoader.load(), which combines
        //    both, so Firely *is* available.
        //
        // The actual cause is the form: the "Server type" list defaults to its first entry,
        // and the first entry is the standard plugin. A server added for a Firely endpoint
        // without changing that is saved as standard-rest and shows the standard
        // operations - with nothing on screen to say the plugin had not been chosen.
        //
        // This test pins the first half, so the service file is not "fixed" later by
        // someone who has not found this.
        AtomicReference<List<String>> loaded = new AtomicReference<>();
        runOnFxThread(() -> loaded.set(
                com.example.fhirviewer.server.PluginLoader.load().plugins().stream()
                        .map(com.example.fhirviewer.server.FhirServerPlugin::id).toList()));

        assertTrue(loaded.get().contains("firely"),
                "Firely must reach the application through PluginLoader; found "
                        + loaded.get());
        assertTrue(loaded.get().contains("smile-cdr"), "found " + loaded.get());
        assertTrue(loaded.get().contains("standard-rest"), "found " + loaded.get());
    }

    @Test
    @DisplayName("Firely stays out of the service file on purpose")
    void firelyIsNotInTheServiceFile() throws Exception {
        // Kept deliberately: it is loaded from configuration so the user can disable it
        // without touching the class path.
        assertFalse(com.example.fhirviewer.server.PluginLoader.discoverableIds()
                        .contains(com.example.fhirviewer.server.FirelyPlugin.class.getName()),
                "Firely belongs in the config file, not service discovery");
    }

    @Test
    @DisplayName("Several parameters can be entered at once")
    void severalParametersCanBeEntered() throws Exception {
        // The screen took exactly one parameter before; this is the change.
        runOnFxThread(() -> {
            SearchCriteriaEditor editor = new SearchCriteriaEditor();
            editor.nameFieldAt(0).setText("family");
            editor.valueFieldAt(0).setText("Smith");
            editor.addButton().fire();
            editor.nameFieldAt(1).setText("given");
            editor.valueFieldAt(1).setText("John");
            editor.addButton().fire();
            editor.nameFieldAt(2).setText("birthdate");
            editor.valueFieldAt(2).setText("1990-01-01");

            assertEquals(3, editor.rowCount(), "every added row must be there");
            List<SearchCriterion> criteria = editor.criteria();
            assertEquals(3, criteria.size(), "all three should be sent: " + criteria);
            assertEquals("family", criteria.get(0).name());
            assertEquals("Smith", criteria.get(0).value());
            assertEquals("birthdate", criteria.get(2).name());
            assertEquals("1990-01-01", criteria.get(2).value());
        });
    }

    @Test
    @DisplayName("A row can be removed again")
    void aRowCanBeRemoved() throws Exception {
        runOnFxThread(() -> {
            SearchCriteriaEditor editor = new SearchCriteriaEditor();
            editor.addButton().fire();
            editor.addButton().fire();
            assertEquals(3, editor.rowCount());
            editor.removeButton().fire();
            assertEquals(2, editor.rowCount());
        });
    }

    @Test
    @DisplayName("The last remaining row cannot be removed")
    void theLastRowCannotBeRemoved() throws Exception {
        // Removing the only row would leave nothing to type into, with no way back except
        // reloading the dialog.
        runOnFxThread(() -> {
            SearchCriteriaEditor editor = new SearchCriteriaEditor();
            assertTrue(editor.removeButton().isDisabled(),
                    "there must always be somewhere to type");
        });
    }

    @Test
    @DisplayName("A raw search string is offered as an alternative to parameters")
    void rawModeIsOffered() throws Exception {
        runOnFxThread(() -> {
            SearchCriteriaEditor editor = new SearchCriteriaEditor();
            assertFalse(editor.isRawMode(), "parameters is the default");

            editor.rawMode().fire();
            assertTrue(editor.isRawMode(), "choosing Search string must switch the mode");
            editor.rawField().setText("name:contains=Smith&_sort=-birthdate");

            List<SearchCriterion> criteria = editor.criteria();
            assertEquals(1, criteria.size(), "a raw search is one criterion");
            assertTrue(criteria.get(0).isRaw());
            assertEquals("name:contains=Smith&_sort=-birthdate", criteria.get(0).value());
        });
    }

    @Test
    @DisplayName("Switching to raw mode drops the parameters rather than mixing them")
    void switchingModeDoesNotMixKinds() throws Exception {
        // A half-typed parameter silently carried into a raw search would send a search the
        // user did not write. SearchRequest refuses a mixed request; the editor makes sure it
        // cannot build one.
        runOnFxThread(() -> {
            SearchCriteriaEditor editor = new SearchCriteriaEditor();
            editor.nameFieldAt(0).setText("family");
            editor.valueFieldAt(0).setText("Smith");
            editor.rawMode().fire();
            editor.rawField().setText("given=John");

            List<SearchCriterion> criteria = editor.criteria();
            assertEquals(1, criteria.size(), "only the raw string should remain: " + criteria);
            assertTrue(criteria.get(0).isRaw());
            // And it must be a request the model will actually accept.
            new SearchRequest("Patient", criteria, 20);
        });
    }

    @Test
    @DisplayName("A blank row means browse every resource of the type")
    void aBlankRowIsNoCriteria() throws Exception {
        runOnFxThread(() -> {
            SearchCriteriaEditor editor = new SearchCriteriaEditor();
            assertTrue(editor.criteria().isEmpty(),
                    "leaving the row blank is how you ask for the whole type");
        });
    }

    @Test
    @DisplayName("A value with no parameter name is refused")
    void aValueWithNoNameIsRefused() throws Exception {
        // "name=" matches nothing on most servers and everything on some, which is worse
        // than being told the row is half-finished.
        runOnFxThread(() -> {
            SearchCriteriaEditor editor = new SearchCriteriaEditor();
            editor.valueFieldAt(0).setText("Smith");
            assertThrows(IllegalArgumentException.class, editor::criteria,
                    "a value with no name should be refused");
        });
    }

    @Test
    @DisplayName("The criteria editor's buttons cannot be squeezed to hide their text")
    void editorButtonsKeepTheirText() throws Exception {
        // A JavaFX Button shrinks its text rather than overflowing it, so a narrow dialog
        // silently renders "Add parameter" as "Add param". Reported as buttons with the text
        // cut off.
        runOnFxThread(() -> {
            SearchCriteriaEditor editor = new SearchCriteriaEditor();
            for (Button button : List.of(editor.addButton(), editor.removeButton())) {
                // JavaFX normalises USE_PREF_SIZE to its -1 sentinel once CSS is applied, so
                // the stored value cannot be compared directly. What matters is the effect:
                // the minimum is the preferred width, so the label cannot shrink.
                double min = button.getMinWidth();
                boolean minIsPref = min == Region.USE_PREF_SIZE
                        || min == -1.0
                        || min >= button.prefWidth(-1);
                assertTrue(minIsPref,
                        button.getText() + " can be squeezed below its natural width (min "
                                + min + "px, pref " + button.prefWidth(-1) + "px)");
            }
        });
    }

    @Test
    @DisplayName("The last search survives reopening the search screen")
    void lastSearchSurvivesReopening() throws Exception {
        // The bug this replaces, reported as "when I search and then open in viewer, the
        // search UI clears". Three separate faults, none of which the earlier tests could see
        // because none of them reopened the dialog:
        //
        //  1. isComplete() demanded at least one criterion, so a plain browse - every
        //     Patient, no filter - was never remembered. That is the commonest search there
        //     is, and it came back blank.
        //  2. The resource type is restored into a ComboBox whose items are filled from the
        //     server's capabilities. Set-value alone is not enough, because that filling
        //     runs shortly afterwards.
        //  3. When the types did arrive, selectFirst() threw the remembered type away and
        //     replaced it with the first in the list - which is what the user saw.
        //
        // So restore the form, then let capabilities land on top of it, and require the
        // remembered type to survive - which is the sequence a user actually goes through.
        SearchMemory memory = new SearchMemory();
        memory.remember(new SearchMemory.Search(null, "Observation",
                List.of(new SearchCriterion("status", "final")), 20));

        runOnFxThread(() -> {
            ServerSearchDialog reopened = new ServerSearchDialog(service, new FhirServerManager(),
                    null, themeManager, memory);
            assertEquals("Observation", reopened.typeBoxValue(),
                    "the remembered resource type did not come back");

            // Capabilities loading afterwards must not quietly replace it with the first
            // item in the list, which is what the user had to ask about.
            reopened.simulateCapabilitiesArriving(List.of("Patient", "Observation", "Practitioner"));
            assertEquals("Observation", reopened.typeBoxValue(),
                    "loading capabilities discarded the remembered resource type");
            reopened.close();
        });
    }

    /** A registry with the real standard REST plugin, for tests that need actual HTTP calls. */
    private static FhirServerPluginRegistry standardRegistry() {
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(new com.example.fhirviewer.server.StandardFhirRestPlugin());
        return registry;
    }

    /** A minimal, valid searchset Bundle with no entries. */
    private static String searchBundleOf(String resourceType, int count) {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < count; i++) {
            entries.append("<entry><resource><")
                    .append(resourceType)
                    .append("><id>stub-").append(i).append("</id></")
                    .append(resourceType)
                    .append("></resource></entry>");
        }
        return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":["
                + entries + "]}";
    }

    @Test
    @DisplayName("Pressing Search leaves the search remembered for the next opening")
    void pressingSearchRemembersItForNextTime() throws Exception {
        // The gap this closes: every earlier test injected SearchMemory.remember() directly,
        // which proved the restore path but never that pressing Search fills the memory in
        // the first place. If search() returned early - no server chosen, no type, a bad
        // parameter - nothing was recorded and the screen came back blank, and none of those
        // tests could tell.
        //
        // So drive the real button on a real dialog, against the stub server, then throw the
        // dialog away and build a new one with the same memory.
        SearchMemory memory = new SearchMemory();
        server.answerWith(200, "application/fhir+json", searchBundleOf("Patient", 0));

        runOnFxThread(() -> {
            ServerSearchDialog dialog = new ServerSearchDialog(service, manager, null,
                    themeManager, memory);
            dialog.simulateCapabilitiesArriving(List.of("Patient", "Observation"));
            dialog.searchButton().fire();
            dialog.close();
        });

        // The search runs on a worker; give it a moment before asking whether it landed.
        Thread.sleep(1500);

        assertNotNull(memory.last(),
                "pressing Search did not record anything, so the next opening cannot "
                        + "restore it - this is what the user sees as the search clearing");

        runOnFxThread(() -> {
            ServerSearchDialog reopened = new ServerSearchDialog(service, manager, null,
                    themeManager, memory);
            assertEquals("Patient", reopened.typeBoxValue(),
                    "the searched-for type did not come back on the next opening");
            reopened.close();
        });
    }

    @Test
    @DisplayName("Reopening after a search with no parameters still re-reads the server")
    void reopeningAfterUnparameterisedSearchRefetches() throws Exception {
        // The bug the trace found, after five wrong fixes. A search with no criteria - which
        // is what "browse every Account" is - hit an early "return" in restoreLastSearch that
        // skipped the re-read entirely. So the memory was restored correctly, the type came
        // back, and yet there were no capabilities and no results.
        //
        // Every previous version of these tests used a search WITH a parameter, which is why
        // none of them saw it. This one uses the empty case, exactly as reported.
        SearchMemory memory = new SearchMemory();
        memory.remember(new SearchMemory.Search("Rest", "Account", List.of(), 20));

        AtomicInteger requests = new AtomicInteger();
        server.onRequest(ignored -> requests.set(ignored));

        FhirServerManager rest = new FhirServerManager();
        rest.add(ServerDefinition.named("Rest", server.baseUrl()).build());
        FhirServerService restService = new FhirServerService(standardRegistry());

        runOnFxThread(() -> {
            ServerSearchDialog reopened = new ServerSearchDialog(restService, rest, null,
                    themeManager, memory);
            assertEquals("Account", reopened.typeBoxValue(),
                    "the type should still be restored for an unparameterised search");
            reopened.close();
        });

        waitUntil("reopening sent no request at all", () -> requests.get() >= 2);
        assertTrue(requests.get() >= 2,
                "reopening a search with no parameters sent " + requests.get()
                        + " request(s); an empty criteria list must not skip the re-read, "
                        + "which is the bug behind \"the search UI clears\"");
    }

    @Test
    @DisplayName("Reopening brings the results back, not just the form")
    void reopeningRefetchesTheResults() throws Exception {
        // The reported symptom was that only the server survived: capabilities, parameters
        // and results all came back empty. Restoring the form alone produced exactly that -
        // and the one field that looked remembered was the server box, which the
        // constructor fills with selectFirst() whether or not memory has anything.
        //
        // So reopening must read the server again: that is what repopulates the type list,
        // and the search that follows is what repopulates the results.
        SearchMemory memory = new SearchMemory();
        memory.remember(new SearchMemory.Search(null, "Patient",
                List.of(new SearchCriterion("family", "Smith")), 20));

        AtomicInteger requests = new AtomicInteger();
        server.onRequest(ignored -> requests.set(ignored));

        // A real REST plugin, not the ShapePlugin the rest of this class uses: that one
        // answers from memory and never speaks HTTP, so it cannot show whether the screen
        // re-reads the server. Counting requests only works against a plugin that makes them.
        FhirServerManager rest = new FhirServerManager();
        rest.add(ServerDefinition.named("Rest", server.baseUrl()).build());
        FhirServerService restService = new FhirServerService(standardRegistry());

        runOnFxThread(() -> {
            ServerSearchDialog reopened = new ServerSearchDialog(restService, rest, null,
                    themeManager, memory);
            reopened.close();
        });

        // Two requests: the CapabilityStatement, then the search.
        waitUntil("reopening sent no request at all", () -> requests.get() >= 2);

        assertTrue(requests.get() >= 2,
                "reopening sent " + requests.get() + " request(s); the capabilities and the "
                        + "search should both have been re-run, which is what puts the type "
                        + "list and the results back");
    }

    @Test
    @DisplayName("Pressing Open in viewer actually opens the selected resource")
    void pressingOpenActuallyOpens() throws Exception {
        // The regression from the duplicate-Read fix: the pane's button was wired to a
        // result converter reading a field nothing ever assigned, so every press returned
        // null and the window closed with nothing opened. The button looked correct and
        // did nothing, which is the same failure as the very first ServerDialog bug.
        //
        // So press the real button and require a result to come back out of show().
        AtomicReference<OpenFromServerDialog.Outcome> got = new AtomicReference<>();
        server.answerWith(200, "application/fhir+json",
                "{\"resourceType\":\"Patient\",\"id\":\"123\"}");

        runOnFxThread(() -> {
            OpenFromServerDialog dialog = new OpenFromServerDialog(service, manager, null,
                    new SearchMemory(), new ServerCapabilitiesCache());
            // Select a row: that is what enables the button and what Open acts on.
            Patient patient = new Patient();
            patient.setId("Patient/123");
            dialog.resultsForTest().getItems().setAll(List.of(patient));
            dialog.resultsForTest().getSelectionModel().select(0);

            assertFalse(dialog.openButtonForTest().isDisabled(),
                    "Open should be enabled once a result is selected");
            dialog.openButtonForTest().fire();
            got.set(dialog.getResult());
        });

        assertNotNull(got.get(),
                "pressing Open in viewer produced no result: the button was wired to a "
                        + "converter reading a field nothing assigns, so every press closed "
                        + "the window with nothing opened");
        assertEquals("Patient", got.get().resourceType());
        assertEquals("123", got.get().resourceId());
    }

    @Test
    @DisplayName("Open from Server has one Open button and a type drop-down")
    void openFromServerHasOneOpenButtonAndATypeDropDown() throws Exception {
        // Two defects reported together. There were two buttons labelled "Read" where only
        // the dialog pane's was wired to the result converter, so one of them did nothing -
        // and "Load types" filled a separate ListView, not the type box, so the box itself
        // stayed a plain text field and looked as though the load had failed.
        //
        // Asserted through the controls themselves rather than by walking the scene graph:
        // the pane's button bar is only built when the dialog is shown, and showing a modal
        // dialog on the JavaFX thread deadlocks the test.
        runOnFxThread(() -> {
            OpenFromServerDialog dialog = new OpenFromServerDialog(service, manager, null,
                    new SearchMemory());

            assertNotNull(dialog.openButtonForTest(),
                    "the dialog pane's Open button is missing");
            assertEquals("Open in viewer", dialog.openButtonForTest().getText(),
                    "the Open button should be named to match the search screen");
            assertEquals("Load capabilities", dialog.loadCapabilitiesButton().getText(),
                    "the button should be named to match the search screen");

            // No second, look-alike button in the content. This is the one that did nothing.
            for (javafx.scene.Node node : findNodes(
                    (javafx.scene.Parent) dialog.getDialogPane().getContent(),
                    n -> n instanceof Button)) {
                String text = ((Button) node).getText();
                assertFalse("Read".equals(text),
                        "the duplicate 'Read' button is still present: it is not wired to "
                                + "the result converter, so it does nothing");
            }

            assertTrue(dialog.typeBox().isEditable(),
                    "the type box must be editable so a type can still be typed");

            dialog.close();
        });
    }

    @Test
    @DisplayName("Reopening does not re-read capabilities when the types are already known")
    void reopeningSkipsCapabilitiesWhenTypesAreKnown() throws Exception {
        // Reopening was spending a round trip on the CapabilityStatement every time. The
        // advertised types have not changed, and Load capabilities still re-reads on
        // demand, so this asks for the behaviour the user asked for: no repeat read.
        AtomicInteger requests = new AtomicInteger();
        server.onRequest(ignored -> requests.set(ignored));
        FhirServerManager rest = new FhirServerManager();
        rest.add(ServerDefinition.named("Rest", server.baseUrl()).build());
        FhirServerService restService = new FhirServerService(standardRegistry());
        // One cache for both dialogs, as MainWindow does, plus a search to restore.
        ServerCapabilitiesCache cache = new ServerCapabilitiesCache();
        SearchMemory memory = new SearchMemory();
        memory.remember(new SearchMemory.Search("Rest", "Patient",
                List.of(new SearchCriterion("family", "Smith")), 20));

        runOnFxThread(() -> {
            ServerSearchDialog first = new ServerSearchDialog(restService, rest, null,
                    themeManager, memory, cache);
            first.setRememberedTypeForTest("Patient");
            first.close();
        });
        waitUntil("the first dialog made no request", () -> requests.get() >= 2);
        int afterFirst = requests.get();
        assertTrue(afterFirst >= 2,
                "the first dialog should have read the CapabilityStatement and searched; sent "
                        + afterFirst + " request(s)");

        // Second dialog, same session and the same cache: the types are already known, so
        // only the search is re-run - one request, not two.
        runOnFxThread(() -> {
            ServerSearchDialog second = new ServerSearchDialog(restService, rest, null,
                    themeManager, memory, cache);
            second.setRememberedTypeForTest("Patient");
            second.close();
        });
        waitUntil("the second dialog sent no request", () -> requests.get() > afterFirst);
        assertEquals(1, requests.get() - afterFirst,
                "reopening with the types already known should spend one request on the "
                        + "search, not a second one re-reading the CapabilityStatement");
    }

    /**
 * Waits for a condition, rather than sleeping a fixed time.
 *
 * <p>These tests drive background work on the JavaFX thread, which is also running the rest
 * of the suite. A fixed sleep is either too short - and the test fails for no reason when the
 * machine is busy - or too long. Polling is bounded and stops as soon as the work lands.</p>
     */
    private static void waitUntil(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    /** Every node in the tree matching the predicate, including the root. */
    private static List<javafx.scene.Node> findNodes(javafx.scene.Parent root,
            java.util.function.Predicate<javafx.scene.Node> match) {
        List<javafx.scene.Node> found = new java.util.ArrayList<>();
        java.util.ArrayDeque<javafx.scene.Node> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            javafx.scene.Node node = queue.poll();
            if (match.test(node)) {
                found.add(node);
            }
            if (node instanceof javafx.scene.Parent parent) {
                parent.getChildrenUnmodifiable().forEach(queue::add);
            }
        }
        return found;
    }

    /** The single node matching the predicate. */
    private static javafx.scene.Node single(javafx.scene.Parent root,
            java.util.function.Predicate<javafx.scene.Node> match) {
        List<javafx.scene.Node> found = findNodes(root, match);
        assertEquals(1, found.size(), "expected exactly one matching node, found " + found.size());
        return found.get(0);
    }

    @Test
    @DisplayName("Opening with nothing remembered makes no requests")
    void openingWithNothingRememberedIsQuiet() throws Exception {
        // The other half of that change: a first-time user must not pay for two network
        // calls just to open the screen.
        AtomicInteger requests = new AtomicInteger();
        server.onRequest(ignored -> requests.set(ignored));

        FhirServerManager rest = new FhirServerManager();
        rest.add(ServerDefinition.named("Rest", server.baseUrl()).build());
        FhirServerService restService = new FhirServerService(standardRegistry());

        runOnFxThread(() -> {
            ServerSearchDialog fresh = new ServerSearchDialog(restService, rest, null,
                    themeManager, new SearchMemory());
            fresh.close();
        });

        Thread.sleep(1500);
        assertEquals(0, requests.get(),
                "opening the screen with no search to restore should not talk to the server");
    }

    @Test
    @DisplayName("The parameters come back too, not just the type")
    void parametersComeBackAsWell() throws Exception {
        // The type is the easy half. What the user actually types is the parameter list, and
        // restoring the type while leaving that blank reads as "the search cleared".
        SearchMemory memory = new SearchMemory();
        memory.remember(new SearchMemory.Search(null, "Patient",
                List.of(new SearchCriterion("family", "Smith"),
                        new SearchCriterion("gender", "male")),
                20));

        runOnFxThread(() -> {
            ServerSearchDialog reopened = new ServerSearchDialog(service, manager, null,
                    themeManager, memory);
            assertEquals(List.of("family", "gender"),
                    reopened.criteriaEditor().criteria().stream()
                            .map(SearchCriterion::name).toList(),
                    "the parameters did not come back, so the search reads as cleared");
            assertEquals(List.of("Smith", "male"),
                    reopened.criteriaEditor().criteria().stream()
                            .map(SearchCriterion::value).toList(),
                    "the parameter values did not come back");
            reopened.close();
        });
    }

    @Test
    @DisplayName("A search with no parameters is remembered")
    void searchWithoutParametersIsRemembered() {
        // Browsing a whole resource type is the most ordinary thing this screen does, and
        // it used to be the one thing that could never be restored.
        SearchMemory memory = new SearchMemory();
        memory.remember(new SearchMemory.Search("Demo", "Patient", List.of(), 20));
        assertNotNull(memory.last(), "an unfiltered search was not remembered");
        assertEquals("Patient", memory.last().resourceType());

        // A search with no resource type is not a search at all, and still is not recorded.
        SearchMemory empty = new SearchMemory();
        empty.remember(new SearchMemory.Search("Demo", null, List.of(), 20));
        assertNull(empty.last(), "a search with no resource type should not be remembered");
    }

    @Test
    @DisplayName("The search screens are wide enough for the editor's buttons")
    void searchScreensAreWideEnough() throws Exception {
        // The editor's widest row is "Parameters" / "Search string" plus its two buttons.
        // Measured from the laid-out controls rather than a guessed number, so a longer
        // button label is caught here instead of on screen.
        AtomicReference<Double> available = new AtomicReference<>();
        runOnFxThread(() -> {
            SearchCriteriaEditor editor = new SearchCriteriaEditor();
            javafx.scene.layout.StackPane host = new javafx.scene.layout.StackPane(
                    editor.build());
            Scene scene = new Scene(host, 900, 500);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            double needed = editor.addButton().prefWidth(-1)
                    + editor.removeButton().prefWidth(-1)
                    + editor.rawMode().prefWidth(-1)
                    + editor.parametersMode().prefWidth(-1)
                    + 8 * 4;
            available.set(needed);
        });
        assertTrue(available.get() > 0, "could not measure the editor's buttons");

        runOnFxThread(() -> {
            ServerSearchDialog search = new ServerSearchDialog(service, new FhirServerManager(), null, themeManager, new SearchMemory());
            assertTrue(search.getDialogPane().getMinWidth() >= available.get(),
                    "the search dialog is " + search.getDialogPane().getMinWidth()
                            + "px but its buttons need " + available.get());
            search.close();

            OpenFromServerDialog open = new OpenFromServerDialog(service, new FhirServerManager(), null, new SearchMemory());
            assertTrue(open.getDialogPane().getMinWidth() >= available.get(),
                    "the open dialog is " + open.getDialogPane().getMinWidth()
                            + "px but its buttons need " + available.get());
            open.close();
        });
    }

    @Test
    @DisplayName("The operation screen's server selector is actually on screen")
    void theServerSelectorIsOnScreen() throws Exception {
        // The server box was created, populated, given a cell factory, wired to reload the
        // operation list, and read in three places - and never added to any layout. It
        // worked perfectly and could not be seen, so the server was fixed at whatever the
        // main window preselected.
        //
        // The check that would have caught it: walk the scene graph and require the control
        // to be in it. A control that is not reachable from the dialog pane is not a control,
        // however much of it is written and wired.
        AtomicReference<Boolean> onScreen = new AtomicReference<>(false);
        runOnFxThread(() -> {
            FhirServerManager fresh = new FhirServerManager();
            fresh.add(ServerDefinition.named("Prod", "https://prod.example.org/fhir").build());
            fresh.add(ServerDefinition.named("Test", "https://test.example.org/fhir").build());
            ServerOperationDialog dialog = new ServerOperationDialog(service, fresh,
                    fresh.servers().get(0), themeManager);
            inspect(dialog.getDialogPane(), node -> {
                if (node == dialog.serverBox()) {
                    onScreen.set(true);
                }
            });
            dialog.close();
        });

        assertTrue(onScreen.get(),
                "the server selector is not in the scene graph, so the user cannot change"
                        + " which server the operation runs against");
    }

    @Test
    @DisplayName("The operation screen offers every configured server")
    void theOperationScreenOffersEveryServer() throws Exception {
        runOnFxThread(() -> {
            FhirServerManager fresh = new FhirServerManager();
            fresh.add(ServerDefinition.named("Prod", "https://prod.example.org/fhir").build());
            fresh.add(ServerDefinition.named("Test", "https://test.example.org/fhir").build());
            ServerOperationDialog dialog = new ServerOperationDialog(service, fresh,
                    fresh.servers().get(0), themeManager);
            assertEquals(2, dialog.serverBox().getItems().size(),
                    "every configured server must be selectable, or the user cannot run an"
                            + " operation against the second one");
            dialog.close();
        });
    }

    @Test
    @DisplayName("No control in the manager claims an unbounded preferred width")
    void noControlHasAnUnboundedPreferredWidth() throws Exception {
        // A layout bug that cannot be seen from a test is not much use, and this class of
        // one is invisible until someone looks at the window. prefWidth is the size a parent
        // adds up when working out its own size; MAX_VALUE there makes the whole chain above
        // it unbounded, and the result is content pinned to one side of a wide dialog.
        // maxWidth is the property that means "grow to fill" - it is the right one and it
        // does not feed the parent's arithmetic.
        AtomicReference<Node> offender = new AtomicReference<>();
        runOnFxThread(() -> {
            ServerManagerDialog dialog = new ServerManagerDialog(service, new FhirServerManager(), null);
            inspect(dialog.getDialogPane(), node -> {
                double pref = node.prefWidth(-1);
                if (pref == Double.MAX_VALUE || pref > 100_000) {
                    offender.set(node);
                }
            });
            dialog.close();
        });

        assertNull(offender.get(), "unbounded preferred width on "
                + (offender.get() == null ? "?" : offender.get().getClass().getName())
                + "; use maxWidth instead");
    }

    /** Visits a node, and every node below it, reporting each to {@code check}. */
    private static void inspect(Node node, java.util.function.Consumer<Node> check) {
        check.accept(node);
        if (node instanceof javafx.scene.layout.Pane pane) {
            for (Node child : pane.getChildren()) {
                inspect(child, check);
            }
        } else if (node instanceof javafx.scene.control.ScrollPane pane
                && pane.getContent() != null) {
            inspect(pane.getContent(), check);
        }
    }

    @Test
    @DisplayName("The form column grows to fill the dialog width")
    void theFormColumnGrowsToFill() throws Exception {
        // The form is the part with fields, so it is the part that must take the slack.
        // Without an explicit hgrow on the scroll pane it sat at its content's preferred
        // width, which is what left the right-hand side of the dialog empty.
        AtomicReference<Boolean> grows = new AtomicReference<>(false);
        runOnFxThread(() -> {
            ServerManagerDialog dialog = new ServerManagerDialog(service, new FhirServerManager(), null);
            inspect(dialog.getDialogPane(), node -> {
                if (node instanceof javafx.scene.control.ScrollPane pane
                        && HBox.getHgrow(pane) == Priority.ALWAYS) {
                    grows.set(true);
                }
            });
            dialog.close();
        });

        assertTrue(grows.get(),
                "the form's scroll pane must grow horizontally or the form hugs its fields"
                        + " and the rest of the dialog stays empty");
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
    @DisplayName("The credential fields are on screen, not merely present")
    void credentialFieldsAreWithinTheVisibleArea() throws Exception {
        // The test above asserted isVisible(), which is true for a control scrolled out of
        // sight. That is exactly the gap: "choose an authentication and the user name and
        // password never appear" passed every assertion above, because both fields existed,
        // were enabled, were populated - and sat below the bottom of the form pane.
        //
        // So measure. Lay the dialog out at its own preferred size, choose Basic, and require
        // both fields to fall inside the scroll viewport.
        AtomicReference<ServerManagerDialog> built = new AtomicReference<>();
        runOnFxThread(() -> built.set(new ServerManagerDialog(service, new FhirServerManager(), null)));
        ServerManagerDialog dialog = built.get();

        runOnFxThread(() -> {
            dialog.form().authBox().getSelectionModel().select(ServerAuthKind.BASIC);
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            javafx.scene.control.ScrollPane scroll = dialog.formScrollForTest();
            scroll.applyCss();
            scroll.layout();

            Bounds view = scroll.localToScene(scroll.getViewportBounds());
            for (javafx.scene.control.TextField field : java.util.List.of(
                    dialog.form().userField(), dialog.form().secretField())) {
                Bounds bounds = field.localToScene(field.getBoundsInLocal());
                assertTrue(bounds.getMaxY() <= view.getMaxY() + 1,
                        "the " + field.getPromptText() + " field ends at "
                                + (int) bounds.getMaxY() + " but the form pane ends at "
                                + (int) view.getMaxY() + " - it is below the fold, so the user "
                                + "cannot see it however correct everything else is");
                assertTrue(bounds.getHeight() > 0,
                        "the " + field.getPromptText() + " field has no height on screen");
            }
            dialog.close();
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

    @Test
    @DisplayName("The REST console builds on a real toolkit and says what is missing")
    void theRestConsoleBuildsAndSaysWhatIsMissing() throws Exception {
        // The console is the only screen here that does not go through a plugin, so nothing
        // else in this file exercises it. Constructing it is the assertion that matters: a
        // scene graph that throws while being built, or a handler wired to a node that was
        // never added to a layout, is invisible to every logic-only test in the suite.
        //
        // Built and closed without show(), which is the pattern the rest of this file uses
        // for dialogs: the content pane is constructed in the constructor either way, and a
        // window left open here would keep the JavaFX thread alive after the class ended.
        //
        // Started from cleared settings, so this asserts what an empty console does rather
        // than inheriting whatever another test remembered.
        clearRememberedConsole();
        AtomicReference<RestConsoleDialog> built = new AtomicReference<>();
        runOnFxThread(() -> {
            // A null FhirService is the "no Bundle expansion" mode, which keeps this
            // assertion about the console's own construction. Entry listing is covered
            // against a real service by RestConsoleIntegrationTest.
            RestConsoleDialog dialog = new RestConsoleDialog(List.of(), null, null);
            built.set(dialog);
            dialog.close();
        });
        RestConsoleDialog dialog = built.get();
        assertNotNull(dialog);

        // A console with nothing configured is still usable: an empty server list is not a
        // reason to refuse, because calling an unconfigured URL is the point of the screen.
        // What it must do is name what is missing rather than open ready to send.
        AtomicReference<String> problem = new AtomicReference<>("none");
        runOnFxThread(() -> problem.set(dialog.problem().orElse("none")));
        assertEquals("a base URL", problem.get(),
                "an empty console should say what it needs, not open ready to send");
    }

    @Test
    @DisplayName("The console comes back the way it was left")
    void theConsoleRemembersItsSettings() throws Exception {
        // The scenario that motivates remembering anything: search, open a result, come back
        // and adjust the search. Without this, every reopening starts from an empty form.
        clearRememberedConsole();
        try {
            RestConsoleState remembered = new RestConsoleState(
                    "", RestMethod.POST, "https://example.com/fhir", "Patient",
                    List.of(new RestParameterList.Parameter("name", "Smith"),
                            new RestParameterList.Parameter("_include", "Patient:organization")),
                    List.of(new RestParameterList.Parameter("Prefer", "return=representation")),
                    "application/fhir+json", ServerAuthKind.BASIC, "alice", 1400, 820, 0.5);
            remembered.save();

            AtomicReference<RestRequestPane> pane = new AtomicReference<>();
            runOnFxThread(() -> {
                RestConsoleDialog dialog = new RestConsoleDialog(List.of(), null, null);
                pane.set(dialog.requestPane());
                dialog.close();
            });

            RestConsoleForm restored = pane.get().form();
            assertEquals(RestMethod.POST, restored.method());
            assertEquals("https://example.com/fhir", restored.baseUrl());
            assertEquals("Patient", restored.path());
            assertEquals("Smith", restored.parameters().value("name"));
            assertEquals("Patient:organization", restored.parameters().value("_include"));
            assertEquals("return=representation", restored.headers().value("Prefer"));
            assertEquals(ServerAuthKind.BASIC, restored.authKind());
            assertEquals("alice", restored.userName(),
                    "the user name is not a secret and retyping it is pure friction");

            // The point of the whole exercise: a reopened console is ready to send, not an
            // empty form the user has to rebuild.
            assertTrue(restored.problem().isEmpty(),
                    "a restored console should be ready to send, but reports: " + restored.problem());
        } finally {
            clearRememberedConsole();
        }
    }

    @Test
    @DisplayName("The console is never opened wider than the screen it opens on")
    void theConsoleNeverExceedsTheScreen() {
        // The report was "the last button on the right is still cut off", and widening the
        // dialog did not fix it - which was the clue. Asking for 1400 on a display whose
        // logical width is less than that (150% scaling on a 1920 screen gives 1280) leaves
        // the window manager to clamp the window, and it clips the right-hand edge. Widening
        // the request makes that worse, not better.
        //
        // So the invariant is that the applied size is bounded by the space available.
        assertEquals(1400, RestConsoleDialog.fitWithin(1400, 900, 1920),
                "a width the screen has room for is used as asked");
        assertEquals(1280, RestConsoleDialog.fitWithin(1400, 900, 1280),
                "a width the screen does not have is reduced to what it has");
        assertEquals(900, RestConsoleDialog.fitWithin(400, 900, 1920),
                "a window the user shrank below the floor comes back to the floor");
        assertEquals(800, RestConsoleDialog.fitWithin(500, 900, 800),
                "the screen wins when the floor is wider than the screen has room for");
    }

    @Test
    @DisplayName("The result tab headers are short enough to fit side by side")
    void tabHeadersAreShort() {
        // The screenshot showed "Diagnostics" rendered as "Diagnosti..." behind a chevron. A
        // TabPane does not shrink its headers - it inserts scroll arrows over the overflow -
        // so a long title is a title the user cannot read or reach.
        //
        // This is a proxy rather than a measurement, and deliberately so: it does not ask the
        // skin whether it overflowed, because a headless toolkit does not produce the arrows
        // to look for. A measurement was tried and removed - it passed with the long titles
        // too, so it proved nothing. Holding the titles to a length that fits at the
        // narrowest pane the dialog can be given is what actually stops the regression.
        assertEquals(4, RestResponsePane.TAB_TITLES.length, "there should still be four result tabs");
        for (String title : RestResponsePane.TAB_TITLES) {
            assertTrue(title.length() <= 8,
                    "\"" + title + "\" is longer than eight characters and will not fit beside "
                            + "the other three in a narrow pane");
        }
    }

    @Test
    @DisplayName("Both cURL buttons actually do something when clicked")
    void curlButtonsAreWired() throws Exception {
        // Reported as "the paste and copy cURL buttons don't do anything", and the cause was
        // that RestRequestPane.setCurlActions was written and never called from anywhere.
        // The buttons existed, were laid out, and had a null onAction - so a click was
        // dispatched to nothing. Everything they needed (parse, render, clipboard) was already
        // implemented and individually tested; only the last inch was missing.
        //
        // This is the assertion that would have caught it: a handler has to be present.
        AtomicReference<RestRequestPane> pane = new AtomicReference<>();
        runOnFxThread(() -> {
            RestConsoleDialog dialog = new RestConsoleDialog(List.of(), null, null);
            pane.set(dialog.requestPane());
            dialog.close();
        });

        assertNotNull(pane.get().pasteCurlButton().getOnAction(),
                "Paste cURL has no handler, so clicking it does nothing");
        assertNotNull(pane.get().copyCurlButton().getOnAction(),
                "Copy as cURL has no handler, so clicking it does nothing");
    }

    @Test
    @DisplayName("A pasted cURL fills the form, and the form renders back to cURL")
    void pasteAndCopyRoundTrip() throws Exception {
        // The two halves have to agree: paste in, copy out, and the command survives. If they
        // drift the user pastes a command, edits nothing, and quietly gets a different request.
        AtomicReference<RestRequestPane> pane = new AtomicReference<>();
        AtomicReference<Boolean> applied = new AtomicReference<>();
        runOnFxThread(() -> {
            RestConsoleDialog dialog = new RestConsoleDialog(List.of(), null, null);
            pane.set(dialog.requestPane());
            // No Authorization header here, so nothing offers a credential and this stays a
            // test of the form rather than of the auth selector.
            applied.set(pane.get().pasteCurl(
                    "curl -X POST 'https://example.com/fhir/Patient?name=Smith' "
                            + "-H 'Accept: application/fhir+json'",
                    (value, header) -> { }));
            dialog.close();
        });

        assertTrue(applied.get(), "a well-formed cURL command should be recognised");
        RestConsoleForm form = pane.get().form();
        assertEquals(RestMethod.POST, form.method(), "the -X POST should be picked up");
        assertTrue(form.resolvedUrl().contains("example.com/fhir/Patient"),
                "the URL should have been taken from the command, got " + form.resolvedUrl());
        assertTrue(form.resolvedUrl().contains("name=Smith"),
                "the query string should have become a parameter, got " + form.resolvedUrl());

        String copied = pane.get().asCurl();
        assertTrue(copied.startsWith("curl"), "copying should produce a command, got: " + copied);
        assertTrue(copied.contains("example.com/fhir/Patient"),
                "the copy should contain the URL it was pasted from, got: " + copied);
    }

    @Test
    @DisplayName("Something that is not a cURL command is declined rather than half-applied")
    void pasteRejectsANonCommand() throws Exception {
        // The declined case and the empty-clipboard case were both previously silent, which
        // is a second way for the button to look broken: it "did nothing" because it decided
        // not to, and never said which of the two it was.
        AtomicReference<Boolean> applied = new AtomicReference<>(true);
        runOnFxThread(() -> {
            RestConsoleDialog dialog = new RestConsoleDialog(List.of(), null, null);
            applied.set(dialog.requestPane()
                    .pasteCurl("not a command at all", (value, header) -> { }));
            dialog.close();
        });

        assertFalse(applied.get(), "text that is not a cURL command must not report success");
    }

    /**
     * Forgets the remembered console settings.
     *
     * <p>The console writes to the real preferences store, which is what makes these tests
     * worth writing — so the tests are obliged to clean up after themselves, or one test's
     * saved URL becomes the next test's starting state.
     */
    private static void clearRememberedConsole() throws Exception {
        RestConsoleState.nodeForTest().clear();
    }

    /**
     * Runs an action on the JavaFX thread and waits for it, so a throw is not swallowed.
     */
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
