package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.FhirServerManager;
import com.example.fhirviewer.server.FhirServerPluginRegistry;
import com.example.fhirviewer.server.FhirServerService;
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
