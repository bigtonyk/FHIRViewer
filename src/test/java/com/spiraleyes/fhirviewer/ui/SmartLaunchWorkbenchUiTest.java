package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.spiraleyes.fhirviewer.server.smart.SmartLaunchConfig;
import com.spiraleyes.fhirviewer.server.smart.SmartLaunchMode;
import com.spiraleyes.fhirviewer.server.smart.SmartLaunchStore;

import javafx.application.Platform;
import javafx.scene.control.PasswordField;

/**
 * Drives the SMART Launch Workbench on a real JavaFX toolkit, the way
 * {@link ServerUiSmokeTest} drives the server screens.
 *
 * <p>What is asserted here is the honest behaviour Phase 01 promises: the workbench builds,
 * configurations survive a save and a delete, Validate says out loud that no server was
 * contacted, the mode-specific rows swap with the mode, there is no client-secret field
 * anywhere, and Discover and Authorize stay disabled until their phases exist. A handler
 * wired to the wrong button is invisible to any other kind of test - which is exactly how
 * the original server add dialog shipped completely dead while its tests passed.</p>
 *
 * <p>Skipped automatically when no display is available, so a headless build does not start
 * failing on a toolkit it cannot have.</p>
 */
class SmartLaunchWorkbenchUiTest {

    private static final CountDownLatch STARTED = new CountDownLatch(1);
    private static final AtomicReference<Throwable> TOOLKIT_FAILURE = new AtomicReference<>();

    @TempDir
    Path tempDir;

    private SmartLaunchStore store;

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            Platform.startup(STARTED::countDown);
            STARTED.await(30, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyStarted) {
            // Another test class - ServerUiSmokeTest - already started the toolkit in this
            // JVM. Usable, not a failure.
            STARTED.countDown();
        } catch (Throwable toolkitProblem) {
            // Recorded rather than thrown: an unavailable toolkit must skip the test, not
            // fail it. A real defect in the screens still fails the tests below.
            TOOLKIT_FAILURE.set(toolkitProblem);
        }
    }

    @BeforeEach
    void setUp() {
        requireToolkit();
        store = new SmartLaunchStore(tempDir.resolve("smart-launches.properties"));
    }

    /** Skips the test when there is no usable toolkit, rather than failing it. */
    private static void requireToolkit() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(), "No display available for the JavaFX toolkit.");
        org.junit.jupiter.api.Assumptions.assumeTrue(TOOLKIT_FAILURE.get() == null,
                "The JavaFX toolkit did not start: " + TOOLKIT_FAILURE.get());
    }

    @Test
    @DisplayName("The workbench builds, and only the not-yet-implemented phase stays disabled")
    void workbenchBuildsAndFutureActionsStayDisabled() throws Exception {
        runOnFxThread(() -> {
            SmartLaunchDialog dialog = new SmartLaunchDialog(store);
            // Discover went live in Phase 02; Authorize waits for Phase 03. An enabled
            // button with no implementation would be how fake success starts.
            assertFalse(dialog.discoverButton().isDisabled(),
                    "Discover is implemented: it must be pressable");
            assertTrue(dialog.authorizeButton().isDisabled(),
                    "Authorize must be disabled until Phase 03 implements it");
            assertNotNull(dialog.validateButton(), "Validate is live: it only reads the form");
            assertTrue(dialog.statusText().contains("Nothing has"),
                    "the workbench must open saying no launch has happened: "
                            + dialog.statusText());
            dialog.close();
        });
    }
    @Test
    @DisplayName("Saving writes a launch the store returns on the next open")
    void savePersistsAndReloads() throws Exception {
        runOnFxThread(() -> {
            SmartLaunchDialog dialog = new SmartLaunchDialog(store);
            dialog.form().nameField().setText("Clinic");
            dialog.form().issuerField().setText("https://example.org/fhir");
            dialog.form().clientIdField().setText("public-client");
            dialog.saveButton().fire();
            assertTrue(dialog.statusText().startsWith("Saved Clinic"),
                    "the status line must say what was saved: " + dialog.statusText());
            dialog.close();
        });

        List<SmartLaunchConfig> read = store.read();
        assertEquals(1, read.size(), "Save must reach the store or the launch is gone "
                + "on restart - the exact defect that kills server features silently");
        assertEquals("Clinic", read.get(0).name());
        assertEquals("https://example.org/fhir", read.get(0).issuer());
        assertEquals("public-client", read.get(0).clientId());
    }

    @Test
    @DisplayName("Delete removes the launch from the list and from the file")
    void deleteRemovesFromListAndFile() throws Exception {
        SmartLaunchConfig doomed = SmartLaunchConfig.builder()
                .id("doomed-id")
                .name("Doomed")
                .issuer("https://gone.example.org/fhir")
                .clientId("client-x")
                .build();
        store.write(List.of(doomed), "Doomed");

        runOnFxThread(() -> {
            SmartLaunchDialog dialog = new SmartLaunchDialog(store);
            dialog.configBox().getSelectionModel().select("Doomed");
            dialog.deleteButton().fire();
            assertTrue(dialog.statusText().startsWith("Removed Doomed"),
                    "the status line must report the delete: " + dialog.statusText());
            dialog.close();
        });

        assertTrue(store.read().isEmpty(), "the deleted launch must not survive in the file");
    }

    @Test
    @DisplayName("Validate says a launch is complete - and that no server was contacted")
    void validateIsHonestAboutWhatItChecked() throws Exception {
        runOnFxThread(() -> {
            SmartLaunchDialog dialog = new SmartLaunchDialog(store);
            dialog.form().nameField().setText("Clinic");
            dialog.form().issuerField().setText("https://example.org/fhir");
            dialog.form().clientIdField().setText("public-client");
            dialog.validateButton().fire();
            assertTrue(dialog.statusText().contains("No server has been contacted"),
                    "Validate must not imply the server agreed to anything: "
                            + dialog.statusText());

            // Now a form missing everything but the name.
            dialog.newButton().fire();
            dialog.form().nameField().setText("Hollow");
            dialog.validateButton().fire();
            assertTrue(dialog.statusText().contains("iss"),
                    "Validate must name what is missing: " + dialog.statusText());
            dialog.close();
        });
    }
    @Test
    @DisplayName("The mode-specific rows follow the selected launch mode")
    void modeSwitchesShowOnlyTheRelevantRows() throws Exception {
        runOnFxThread(() -> {
            SmartLaunchFormPanel form = new SmartLaunchFormPanel();
            assertTrue(form.showsAudience(), "a standalone launch sends an audience");
            assertFalse(form.showsLaunchParameter(),
                    "a standalone launch has no EHR launch parameter to show");

            form.modeBox().getSelectionModel().select(SmartLaunchMode.EHR_LAUNCH);
            assertTrue(form.showsLaunchParameter(),
                    "an EHR launch must show where the launch parameter goes");
            assertFalse(form.showsAudience(),
                    "an EHR launch gets its server from iss; the audience row would invite "
                            + "the wrong half of the form to be filled in");
        });
    }

    @Test
    @DisplayName("The form round-trips a launch through load and toConfig")
    void formRoundTripsALaunch() throws Exception {
        AtomicReference<SmartLaunchConfig> result = new AtomicReference<>();
        runOnFxThread(() -> {
            SmartLaunchConfig original = SmartLaunchConfig.builder()
                    .id("round-id")
                    .name("Round trip")
                    .issuer("https://round.example.org/fhir")
                    .mode(SmartLaunchMode.EHR_LAUNCH)
                    .clientId("client-r")
                    .redirectUri("http://localhost:8080/callback")
                    .scopes("openid patient/*.read")
                    .launchParameter("launch-1")
                    .build();

            SmartLaunchFormPanel form = new SmartLaunchFormPanel();
            form.load(original);
            result.set(form.toConfig(original));
        });

        SmartLaunchConfig back = result.get();
        assertNotNull(back);
        assertEquals("round-id", back.id(), "editing must keep the identity of the "
                + "configuration being edited or its stored block is orphaned");
        assertEquals("Round trip", back.name());
        assertEquals("https://round.example.org/fhir", back.issuer());
        assertEquals(SmartLaunchMode.EHR_LAUNCH, back.mode());
        assertEquals("client-r", back.clientId());
        assertEquals("http://localhost:8080/callback", back.redirectUri());
        assertEquals("openid patient/*.read", back.scopes());
        assertEquals("launch-1", back.launchParameter());
    }

    @Test
    @DisplayName("There is no client-secret field anywhere in the workbench")
    void noPasswordFieldExists() throws Exception {
        runOnFxThread(() -> {
            SmartLaunchDialog dialog = new SmartLaunchDialog(store);
            for (javafx.scene.control.Control control : dialog.form().fields()) {
                assertFalse(control instanceof PasswordField,
                        "a desktop SMART client is public: it has no secret to type, and a "
                                + "field asking for one would be a lie about the protocol");
            }
            dialog.close();
        });
    }

    @Test
    @DisplayName("Discovery fills the Capabilities panel with what the server advertises")
    void discoveryFillsTheCapabilitiesPanel() throws Exception {
        StubServer server = new StubServer();
        server.start();
        try {
            String base = server.baseUrl();
            server.answerWith(200, "application/json", """
                    {
                      "issuer": "__BASE__",
                      "authorization_endpoint": "http://127.0.0.1/authorize",
                      "token_endpoint": "http://127.0.0.1/token",
                      "capabilities": ["launch-standalone", "client-public"],
                      "scopes_supported": ["openid", "launch/patient"],
                      "code_challenge_methods_supported": ["S256"]
                    }
                    """.replace("__BASE__", base));

            AtomicReference<SmartLaunchDialog> shown = new AtomicReference<>();
            runOnFxThread(() -> {
                SmartLaunchDialog dialog = new SmartLaunchDialog(store);
                dialog.form().issuerField().setText(base);
                dialog.discoverButton().fire();
                shown.set(dialog);
            });

            String status = waitForDiscovery(shown.get());
            assertTrue(status.contains("Discovered"),
                    "the status must say what was learned: " + status);
            assertTrue(status.contains("can be launched against"),
                    "a conformant loopback document is usable: " + status);
            assertEquals("/fhir/.well-known/smart-configuration", server.lastPath(),
                    "discovery anchors the well-known path at the FHIR base, path and all");
            runOnFxThread(() -> {
                String display = shown.get().capabilitiesPane().displayText();
                assertTrue(display.contains("launch-standalone"),
                        "capabilities must reach the panel: " + display);
                assertTrue(display.contains("S256"),
                        "the PKCE methods must reach the panel: " + display);
                assertTrue(display.contains("authorize"),
                        "the endpoints must reach the panel: " + display);
            });
            runOnFxThread(shown.get()::close);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("A server with no SMART configuration gets a diagnosis, not a crash")
    void nonSmartServerGetsADiagnosis() throws Exception {
        StubServer server = new StubServer();
        server.start();
        try {
            server.answerWith(404, "text/plain", "Not Found");

            AtomicReference<SmartLaunchDialog> shown = new AtomicReference<>();
            runOnFxThread(() -> {
                SmartLaunchDialog dialog = new SmartLaunchDialog(store);
                dialog.form().issuerField().setText(server.baseUrl());
                dialog.discoverButton().fire();
                shown.set(dialog);
            });

            String status = waitForDiscovery(shown.get());
            assertTrue(status.contains("does not publish a SMART configuration"),
                    "the user must be told the server is not SMART: " + status);
            runOnFxThread(() -> {
                String display = shown.get().capabilitiesPane().displayText();
                assertTrue(display.contains("does not publish a SMART configuration"),
                        "the failure must reach the panel too: " + display);
            });
            runOnFxThread(shown.get()::close);
        } finally {
            server.stop();
        }
    }

    /**
     * Waits for a discovery started in the workbench to settle.
     *
     * <p>Discovery runs on a background daemon thread and its callback lands on the FX
     * thread, so the test thread can only poll — reading nodes directly would race it.
     * Gives up after fifteen seconds, the same order as the service's own deadline.</p>
     *
     * @return the settled status text
     */
    private static String waitForDiscovery(SmartLaunchDialog dialog) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        AtomicReference<String> status = new AtomicReference<>("");
        while (System.currentTimeMillis() < deadline) {
            runOnFxThread(() -> status.set(dialog.statusText()));
            if (!status.get().contains("Discovering from")) {
                return status.get();
            }
            Thread.sleep(100);
        }
        return status.get();
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
        if (failure.get() != null) {
            throw new AssertionError("Driving the SMART workbench failed: " + failure.get(),
                    failure.get());
        }
    }
}
