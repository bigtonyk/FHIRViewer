package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerService;
import com.spiraleyes.fhirviewer.server.smart.SmartAuthorizationSession;
import com.spiraleyes.fhirviewer.server.smart.SmartSessionHolder;
import com.spiraleyes.fhirviewer.server.smart.SmartTokenResponse;
import com.spiraleyes.fhirviewer.service.FhirService;
import com.spiraleyes.fhirviewer.service.scripting.FailingScriptingSessions;
import com.spiraleyes.fhirviewer.service.scripting.ScriptingCoordinator;
import com.spiraleyes.fhirviewer.service.scripting.testing.SuiteCaseResult;
import com.spiraleyes.fhirviewer.service.scripting.testing.SuiteRunResult;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Builds the scripting console on a real JavaFX toolkit and drives it.
 *
 * <p>A blank console is saved to a file first, which binds the buffer the same way Open
 * does, then the bundled example is loaded. The example is not that file, so the console
 * must return to the interactive session and Save must ask for a new location instead of
 * rewriting the file the example replaced.</p>
 *
 * <p>The suite tests press Run Tests against the real coordinator, and once against a
 * coordinator whose session factory fails after the first case, so the partial-failure
 * path is exercised through the dialog rather than only through the coordinator.</p>
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
        try (ScriptingCoordinator coordinator = new ScriptingCoordinator()) {
            SuiteOutcome outcome = runSuite("""
                    // case: passes
                    System.out.println("pass-case-output");
                    test.assertTrue(true, "this passes");

                    // case: fails
                    System.out.println("fail-case-output");
                    test.assertTrue(false, "expected true here");
                    """, coordinator);

            String reportText = outcome.report();
            assertTrue(reportText.contains("1 passed, 1 failed"),
                    "the report should carry both counts: " + reportText);
            assertTrue(reportText.contains("FAILED fails"),
                    "the failing case should be named in the report: " + reportText);
            assertFalse(outcome.output().contains("FAILED"),
                    "verdicts must not leak into the output pane: " + outcome.output());
            assertFalse(outcome.output().contains("passed"),
                    "verdicts must not leak into the output pane: " + outcome.output());
            String detailsText = outcome.details();
            assertTrue(detailsText.contains("pass-case-output"),
                    "the details tab should keep what the passing case printed: " + detailsText);
            assertTrue(detailsText.contains("fail-case-output"),
                    "the details tab should keep what the failing case printed: " + detailsText);
            assertTrue(detailsText.contains("AssertionError")
                            && detailsText.contains("expected true here"),
                    "the details tab should carry the full failure, not just the first line: "
                            + detailsText);
        }
    }

    /**
     * A session factory that fails on the second case must not cost the UI the reason,
     * the counts of what did run, or the output the completed case produced.
     */
    @Test
    @DisplayName("A suite that fails after one case still shows the reason, the counts and that case's output")
    void failedSuiteKeepsReasonCountsAndDetails() throws Exception {
        requireToolkit();
        String reason = "no session for the second case";
        try (ScriptingCoordinator coordinator =
                FailingScriptingSessions.failAfterOneSession(reason)) {
            SuiteOutcome outcome = runSuite("""
                    // case: passes
                    System.out.println("kept-case-output");
                    test.assertTrue(true, "this passes");

                    // case: never starts
                    System.out.println("never-runs");
                    """, coordinator);

            String reportText = outcome.report();
            assertTrue(reportText.contains("The suite could not finish at case \"never starts\":"
                            + " IllegalStateException: " + reason),
                    "the report must keep the failure reason and name the case: " + reportText);
            assertTrue(reportText.contains("1 passed, 0 failed (1 of 2 case(s) ran"),
                    "the report must separate executed from planned cases: " + reportText);
            assertFalse(reportText.contains("FAILED"),
                    "no case failed on its own, so no FAILED line belongs in the report: "
                            + reportText);

            String detailsText = outcome.details();
            assertTrue(detailsText.contains("The suite could not finish"),
                    "the details tab must repeat the failure reason: " + detailsText);
            assertTrue(detailsText.contains("case: passes \u2014 PASSED"),
                    "the details tab must name the completed case: " + detailsText);
            assertTrue(detailsText.contains("kept-case-output"),
                    "the details tab must keep what the completed case printed: " + detailsText);
            assertFalse(detailsText.contains("never-runs"),
                    "the case that never started must not appear to have run: " + detailsText);
            assertEquals("", outcome.output(),
                    "a suite verdict or failure must not be written to the output pane");
        }
    }

    /**
     * A stopped suite must name its interrupted case without calling it a failure, and its
     * counts must agree with the lines below them.
     */
    @Test
    @DisplayName("A stopped case is reported as stopped, not as a failure")
    void stoppedCaseIsNotReportedAsFailed() throws Exception {
        requireToolkit();
        SuiteRunResult stoppedSuite = new SuiteRunResult("stopped.jsh", List.of(
                new SuiteCaseResult("passes", SuiteCaseResult.Status.PASSED, "", List.of()),
                new SuiteCaseResult("interrupted", SuiteCaseResult.Status.STOPPED, "", List.of())),
                2, Duration.ofMillis(12), true, "");

        try (ScriptingCoordinator coordinator = new ScriptingCoordinator()) {
            List<String> shown = onFxThread(() -> {
                ProbeConsole console = new ProbeConsole(coordinator);
                console.presentSuiteForTest(stoppedSuite);
                return List.of(console.suiteReportForTest().getText(),
                        console.suiteDetailsTextForTest());
            });

            String reportText = shown.get(0);
            assertTrue(reportText.contains("1 passed, 0 failed, 1 stopped (2 case(s)"),
                    "the counts must keep the stopped case apart from the failures: " + reportText);
            assertTrue(reportText.contains("STOPPED interrupted"),
                    "the stopped case should be named as stopped: " + reportText);
            assertFalse(reportText.contains("FAILED"),
                    "Stop is the user's action, not a failure: " + reportText);
            assertTrue(shown.get(1).contains("case: interrupted \u2014 STOPPED"),
                    "the details tab should say stopped too: " + shown.get(1));
        }
    }

    /** Replacing the buffer must not leave the previous buffer's suite verdicts on screen. */
    @Test
    @DisplayName("Loading the Example clears the previous suite report and details")
    void loadingExampleClearsSuiteResults() throws Exception {
        requireToolkit();
        SuiteRunResult result = new SuiteRunResult("previous.jsh", List.of(
                new SuiteCaseResult("passes", SuiteCaseResult.Status.PASSED, "", List.of())),
                1, Duration.ofMillis(5), false, "");

        try (ScriptingCoordinator coordinator = new ScriptingCoordinator()) {
            List<String> shown = onFxThread(() -> {
                ProbeConsole console = new ProbeConsole(coordinator);
                console.presentSuiteForTest(result);
                boolean reportWasVisible = console.suiteReportForTest().isVisible();
                console.exampleButtonForTest().fire();
                return List.of(
                        String.valueOf(reportWasVisible),
                        console.suiteReportForTest().getText(),
                        String.valueOf(console.suiteReportForTest().isVisible()),
                        console.suiteDetailsTextForTest(),
                        console.editorTextForTest());
            });

            assertEquals("true", shown.get(0), "the report should be visible before loading");
            assertEquals("", shown.get(1), "the previous verdict line must be cleared");
            assertEquals("false", shown.get(2), "the report line must be hidden again");
            assertEquals("", shown.get(3), "the previous details must be cleared");
            assertFalse(shown.get(4).isBlank(), "the example should have replaced the buffer");
        }
    }

    /**
     * The console must see the session the Workbench published, not an empty facade.
     */
    @Test
    @DisplayName("The console sees the Workbench's authorized SMART session")
    void consoleSeesSharedSmartSession() throws Exception {
        requireToolkit();
        SmartSessionHolder holder = new SmartSessionHolder();
        holder.set(session("stub-token", "stub-patient"), null);
        try (ScriptingCoordinator coordinator = new ScriptingCoordinator()) {
            ProbeConsole console = onFxThread(() -> {
                ProbeConsole created = new ProbeConsole(coordinator, holder);
                created.setEditorTextForTest("""
                        // case: smart
                        test.assertTrue(smart.isSessionActive(), "shared session");
                        test.assertEquals("stub-patient", smart.launchPatientId());
                        """);
                return created;
            });

            SuiteOutcome outcome = runSuite(console);

            assertTrue(outcome.report().contains("1 passed, 0 failed"),
                    "the SMART case should pass: " + outcome.report());
        }
    }

    private static SmartAuthorizationSession session(String token, String patient) {
        SmartTokenResponse tokens = new SmartTokenResponse(
                token, "Bearer", 3600L, "patient/*.read", patient, null, null, null);
        return new SmartAuthorizationSession("http://example.com/fhir", tokens, null);
    }

    /**
     * A refusal must not leave the console stuck: fixing the buffer and pressing Run Tests
     * again in the same console has to ask for trust and run the suite normally.
     */
    @Test
    @DisplayName("A refused buffer can be fixed and run in the same console")
    void refusedSuiteCanBeFixedAndRun() throws Exception {
        requireToolkit();
        try (ScriptingCoordinator coordinator = new ScriptingCoordinator()) {
            ProbeConsole console = onFxThread(() -> {
                ProbeConsole created = new ProbeConsole(coordinator);
                created.setEditorTextForTest("""
                        // case: notes
                        // TODO later
                        /* still nothing */
                        """);
                created.runTestsButtonForTest().fire();
                return created;
            });
            String refusal = onFxThread(() -> console.suiteReportForTest().getText());
            assertTrue(refusal.startsWith("Could not read tests:"),
                    "the first press should be refused: " + refusal);

            onFxThread(() -> {
                console.setEditorTextForTest("""
                        // case: passes
                        test.assertTrue(true, "this passes");
                        """);
                return null;
            });

            SuiteOutcome outcome = runSuite(console);

            assertTrue(outcome.report().contains("1 passed, 0 failed (1 case(s)"),
                    "the fixed suite should run: " + outcome.report());
            assertTrue(outcome.details().contains("case: passes \u2014 PASSED"),
                    "the fixed suite's details should appear: " + outcome.details());
        }
    }

    /**
     * The parser refusal has to reach the console, and nothing may run: the dialog must not
     * ask for trust or start a suite for a buffer it could not read.
     */
    @Test
    @DisplayName("Run Tests on a comment-only case reports the refusal and runs nothing")
    void commentOnlyCaseIsRefusedInConsole() throws Exception {
        requireToolkit();
        try (ScriptingCoordinator coordinator = new ScriptingCoordinator()) {
            List<String> shown = onFxThread(() -> {
                ProbeConsole console = new ProbeConsole(coordinator);
                console.setEditorTextForTest("""
                        // case: notes
                        // TODO later
                        /* still nothing */
                        """);
                // No trust prompt may appear: parsing happens first, and this returns only
                // because the refusal needs no confirmation.
                console.runTestsButtonForTest().fire();
                return List.of(
                        console.suiteReportForTest().getText(),
                        String.valueOf(console.suiteReportForTest().isVisible()),
                        console.suiteDetailsTextForTest(),
                        console.outputTextForTest(),
                        console.runTestsButtonForTest().isDisabled() ? "disabled" : "enabled",
                        String.valueOf(coordinator.isRunning()));
            });

            assertTrue(shown.get(0).startsWith("Could not read tests:"),
                    "the refusal belongs in the report: " + shown.get(0));
            assertTrue(shown.get(0).contains(
                            "The case \"notes\" from line 1 has no source"),
                    "the refusal must name the case: " + shown.get(0));
            assertEquals("true", shown.get(1), "the report line should be visible");
            assertEquals("", shown.get(2), "a refused suite has no details to show");
            assertEquals("", shown.get(3), "nothing was run, so the output must stay empty");
            assertEquals("enabled", shown.get(4), "the Run Tests button must not stay disabled");
            assertEquals("false", shown.get(5), "the coordinator must never have started");
        }
    }

    /** Runs FX work and returns its value, failing the test when the task throws. */
    private static <T> T onFxThread(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                value.set(action.call());
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "the FX task did not finish");
        if (failure.get() != null) {
            throw new AssertionError("the FX task failed", failure.get());
        }
        return value.get();
    }

    /**
     * Drives one Run Tests press to completion and returns what the three surfaces show.
     *
     * <p>A background helper posts FX tasks: they run inside the trust dialog's nested
     * event loop, answer the prompt, then watch the report label for the verdicts.</p>
     */
    private static SuiteOutcome runSuite(String source, ScriptingCoordinator coordinator)
            throws Exception {
        ProbeConsole console = onFxThread(() -> {
            ProbeConsole created = new ProbeConsole(coordinator);
            created.setEditorTextForTest(source);
            return created;
        });
        return runSuite(console);
    }

    /** Runs the suite already loaded in the given console, then waits for its result. */
    private static SuiteOutcome runSuite(ProbeConsole console) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<SuiteOutcome> outcome = new AtomicReference<>();
        AtomicReference<String> lastReport = new AtomicReference<>("<none>");
        AtomicBoolean trustAnswered = new AtomicBoolean();
        AtomicInteger fxTicks = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        Thread helper = new Thread(() -> {
            while (done.getCount() > 0 && !Thread.currentThread().isInterrupted()) {
                Platform.runLater(() -> {
                    try {
                        fxTicks.incrementAndGet();
                        lastReport.set(console.suiteReportForTest().getText());
                        if (!trustAnswered.get() && acknowledgeTrustPrompt()) {
                            trustAnswered.set(true);
                        }
                        String current = console.suiteReportForTest().getText();
                        if (trustAnswered.get() && outcome.get() == null
                                && console.suiteReportForTest().isVisible()
                                && !current.startsWith("Running")) {
                            outcome.set(new SuiteOutcome(current,
                                    console.outputTextForTest(),
                                    console.suiteDetailsTextForTest()));
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
        }, "scripting-console-suite");
        helper.setDaemon(true);
        helper.start();

        Platform.runLater(() -> {
            try {
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
        assertNotNull(outcome.get(), "the suite finished without showing a report");
        return outcome.get();
    }

    /** The three places a suite result can appear. */
    private record SuiteOutcome(String report, String output, String details) {
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
            this(new ScriptingCoordinator(), first, second, null);
        }

        ProbeConsole(ScriptingCoordinator coordinator) {
            this(coordinator, null, null, null);
        }

        ProbeConsole(ScriptingCoordinator coordinator, SmartSessionHolder smartSessions) {
            this(coordinator, null, null, smartSessions);
        }

        private ProbeConsole(ScriptingCoordinator coordinator, Path first, Path second,
                SmartSessionHolder smartSessions) {
            super(new ThemeManager(), new FhirService(), new FhirServerService(),
                    new FhirServerManager(), coordinator, smartSessions);
            if (first != null) {
                targets.add(first);
            }
            if (second != null) {
                targets.add(second);
            }
        }

        @Override
        protected Path chooseSaveLocation() {
            saveAsked++;
            return targets.poll();
        }
    }
}
