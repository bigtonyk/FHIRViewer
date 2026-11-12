package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerService;
import com.spiraleyes.fhirviewer.service.FhirService;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Builds the scripting console on a real JavaFX toolkit and drives the Example button.
 *
 * <p>A blank console is saved to a file first, which binds the buffer the same way Open
 * does, then the bundled example is loaded. The example is not that file, so the console
 * must return to the interactive session and Save must ask for a new location instead of
 * rewriting the file the example replaced.</p>
 *
 * <p>Skipped automatically when no display is available, like {@link ServerUiSmokeTest}.</p>
 */
class ScriptingConsoleSmokeTest {

    private static final CountDownLatch STARTED = new CountDownLatch(1);
    private static final AtomicReference<Throwable> TOOLKIT_FAILURE = new AtomicReference<>();

    @TempDir
    Path directory;

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            Platform.startup(STARTED::countDown);
        } catch (IllegalStateException alreadyStarted) {
            // Another UI test class started the toolkit first; that is the state wanted.
            STARTED.countDown();
        } catch (Throwable toolkitProblem) {
            TOOLKIT_FAILURE.set(toolkitProblem);
            return;
        }
        if (!STARTED.await(30, TimeUnit.SECONDS)) {
            TOOLKIT_FAILURE.set(new IllegalStateException("The JavaFX toolkit did not start."));
            return;
        }
        // The trust prompt is the only window this class shows. Without this, closing it
        // would trigger the toolkit's default implicit exit before the suite result arrives.
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }

    /**
     * Saves the blank buffer, loads the example, then saves again. The probe answers the
     * save-dialog seam with two paths and counts how often Save had to ask.
     */
    @Test
    @DisplayName("Loading the Example unbinds the open file, so Run is interactive and Save asks again")
    void exampleUnbindsTheOpenFile() throws Exception {
        requireToolkit();
        Path first = directory.resolve("first.jsh");
        Path second = directory.resolve("second.jsh");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ProbeConsole console = new ProbeConsole(first, second);
                console.saveButtonForTest().fire();
                assertEquals("Run: fresh session (saved script)",
                        console.runModeLabelForTest().getText(),
                        "a saved buffer must say Run uses a fresh session");
                assertEquals(first, console.boundFileForTest().orElse(null),
                        "Save should have bound the buffer to the first file");

                console.exampleButtonForTest().fire();
                assertFalse(console.editorTextForTest().isBlank(),
                        "the bundled example should be in the editor");
                assertEquals("Run: interactive session",
                        console.runModeLabelForTest().getText(),
                        "the example is not the open file, so Run must be interactive");
                assertNull(console.boundFileForTest().orElse(null),
                        "loading the example must leave the buffer untitled");

                console.saveButtonForTest().fire();
                assertEquals(2, console.saveAsked,
                        "the second Save must ask where to write, not reuse the first file");
                assertEquals(console.editorTextForTest(), Files.readString(second),
                        "the example should be written to the newly chosen file");
                assertEquals("", Files.readString(first),
                        "the file the example replaced must not be overwritten");
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "the console flow did not finish");
        if (failure.get() != null) {
            throw new AssertionError("scripting console smoke test failed", failure.get());
        }
    }

    /**
     * Runs a two-case buffer through the real Run Tests button, answers the trust
     * prompt, and checks the counts land in the report label rather than the output.
     */
    @Test
    @DisplayName("Run Tests reports counts separately and leaves verdicts out of the output")
    void runTestsReportsCountsApartFromOutput() throws Exception {
        requireToolkit();
        AtomicReference<String> report = new AtomicReference<>();
        AtomicReference<String> output = new AtomicReference<>();
        AtomicReference<String> details = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ProbeConsole> consoleRef = new AtomicReference<>();
        AtomicReference<String> lastReport = new AtomicReference<>("<none>");
        AtomicBoolean trustAnswered = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger fxTicks =
                new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        // A background helper posts FX tasks: they run inside the trust dialog's nested
        // event loop, answer the prompt, then watch the report label for the verdicts.
        Thread helper = new Thread(() -> {
            while (done.getCount() > 0 && !Thread.currentThread().isInterrupted()) {
                Platform.runLater(() -> {
                    try {
                        fxTicks.incrementAndGet();
                        ProbeConsole console = consoleRef.get();
                        if (console == null) {
                            return;
                        }
                        lastReport.set(console.suiteReportForTest().getText());
                        if (!trustAnswered.get() && acknowledgeTrustPrompt()) {
                            trustAnswered.set(true);
                        }
                        String current = console.suiteReportForTest().getText();
                        if (trustAnswered.get() && report.get() == null
                                && console.suiteReportForTest().isVisible()
                                && !current.startsWith("Running")) {
                            report.set(console.suiteReportForTest().getText());
                            output.set(console.outputTextForTest());
                            details.set(console.suiteDetailsTextForTest());
                            done.countDown();
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                        done.countDown();
                    }
                });
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }, "scripting-console-smoke");
        helper.setDaemon(true);
        helper.start();

        Platform.runLater(() -> {
            try {
                ProbeConsole console = new ProbeConsole(directory.resolve("passed.jsh"),
                        directory.resolve("failed.jsh"));
                console.setEditorTextForTest("""
                        // case: passes
                        System.out.println("pass-case-output");
                        test.assertTrue(true, "this passes");

                        // case: fails
                        System.out.println("fail-case-output");
                        test.assertTrue(false, "expected true here");
                        """);
                consoleRef.set(console);
                // Blocks in the trust dialog's nested event loop until the helper answers it.
                console.runTestsButtonForTest().fire();
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
                done.countDown();
            }
        });

        boolean finished;
        try {
            finished = done.await(60, TimeUnit.SECONDS);
        } finally {
            // Every path stops the helper: a timed-out test must not keep posting FX tasks
            // into the UI test classes that run after it.
            helper.interrupt();
        }
        assertTrue(finished, () -> "the suite run did not finish"
                + "; trust=" + trustAnswered.get() + "; fx ticks=" + fxTicks.get()
                + "; report=[" + lastReport.get() + "]");
        if (failure.get() != null) {
            throw new AssertionError("scripting console suite smoke test failed", failure.get());
        }
        String reportText = report.get();
        assertTrue(reportText.contains("1 passed, 1 failed"),
                "the report should carry both counts: " + reportText);
        assertTrue(reportText.contains("FAILED fails"),
                "the failing case should be named in the report: " + reportText);
        assertFalse(output.get().contains("FAILED"),
                "verdicts must not leak into the output pane: " + output.get());
        assertFalse(output.get().contains("passed"),
                "verdicts must not leak into the output pane: " + output.get());
        String detailsText = details.get();
        assertTrue(detailsText.contains("pass-case-output"),
                "the details tab should keep what the passing case printed: " + detailsText);
        assertTrue(detailsText.contains("fail-case-output"),
                "the details tab should keep what the failing case printed: " + detailsText);
        assertTrue(detailsText.contains("AssertionError") && detailsText.contains("expected true here"),
                "the details tab should carry the full failure, not just the first line: "
                        + detailsText);
    }

    /** Presses Run Script on the trust prompt as soon as it is showing. */
    private static boolean acknowledgeTrustPrompt() {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage && stage.isShowing()
                    && "Run this script?".equals(stage.getTitle())) {
                for (Node node : stage.getScene().getRoot().lookupAll(".button")) {
                    if (node instanceof Button button && "Run Script".equals(button.getText())) {
                        button.fire();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void requireToolkit() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(), "No display available for the JavaFX toolkit.");
        org.junit.jupiter.api.Assumptions.assumeTrue(TOOLKIT_FAILURE.get() == null,
                "The JavaFX toolkit did not start: " + TOOLKIT_FAILURE.get());
    }

    /** Answers the save chooser with the queued paths and counts the questions. */
    private static final class ProbeConsole extends ScriptingConsoleDialog {

        private final Deque<Path> targets = new ArrayDeque<>();
        private int saveAsked;

        ProbeConsole(Path first, Path second) {
            super(new ThemeManager(), new FhirService(), new FhirServerService(),
                    new FhirServerManager());
            targets.add(first);
            targets.add(second);
        }

        @Override
        protected Path chooseSaveLocation() {
            saveAsked++;
            return targets.poll();
        }
    }
}
