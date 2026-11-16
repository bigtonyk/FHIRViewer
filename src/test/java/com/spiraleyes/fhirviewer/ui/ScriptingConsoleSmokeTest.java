package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

import org.hl7.fhir.r4.model.Patient;

import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.server.FhirServerService;
import com.spiraleyes.fhirviewer.server.PatchFormat;
import com.spiraleyes.fhirviewer.server.ServerDefinition;
import com.spiraleyes.fhirviewer.server.ServerOrigin;
import com.spiraleyes.fhirviewer.server.ServerWriteResult;
import com.spiraleyes.fhirviewer.server.smart.SmartAuthorizationSession;
import com.spiraleyes.fhirviewer.server.smart.SmartSessionHolder;
import com.spiraleyes.fhirviewer.server.smart.SmartTokenResponse;
import com.spiraleyes.fhirviewer.service.FhirService;
import com.spiraleyes.fhirviewer.service.scripting.FailingScriptingSessions;
import com.spiraleyes.fhirviewer.service.scripting.ScriptingCoordinator;
import com.spiraleyes.fhirviewer.service.scripting.api.ScriptBindings;
import com.spiraleyes.fhirviewer.service.scripting.api.WriteDeniedException;
import com.spiraleyes.fhirviewer.service.scripting.api.WriteJournal;
import com.spiraleyes.fhirviewer.service.scripting.api.WritePolicy;
import com.spiraleyes.fhirviewer.service.scripting.api.WriteScope;
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
    @DisplayName("The console Guides menu targets the scripting and SMART sections")
    void guidesMenuTargetsTheRelevantSections() throws Exception {
        List<String> opened = new java.util.ArrayList<>();
        onFxThread(() -> {
            ProbeConsole console = new ProbeConsole(new ScriptingCoordinator());
            console.setGuideOpener((guide, anchor) -> opened.add(guide + "|" + anchor));
            assertEquals(2, console.guidesButton().getItems().size());
            console.guidesButton().getItems().get(0).fire();
            console.guidesButton().getItems().get(1).fire();
            console.close();
            return null;
        });
        assertEquals(List.of("scripting|#smart-on-fhir", "smart|#coding-a-smart-workflow"), opened);
    }

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

    @Test
    @DisplayName("Run Tests needs its own write opt-in, independent of interactive Run")
    void testRunsNeedTheirOwnWriteOptIn() throws Exception {
        requireToolkit();
        String source = """
                // case: attempts a write
                try {
                    fhir.create(new org.hl7.fhir.r4.model.Patient());
                    System.out.println("WRITE_ALLOWED");
                } catch (WriteDeniedException denied) {
                    System.out.println("WRITE_DENIED");
                }
                """;

        WritableService deniedService = new WritableService();
        SuiteOutcome denied;
        try (ScriptingCoordinator coordinator = new ScriptingCoordinator()) {
            WriteProbeConsole console = onFxThread(() -> {
                WriteProbeConsole created = new WriteProbeConsole(deniedService,
                        managerWithServer(), summary -> true, true, coordinator);
                created.setEditorTextForTest(source);
                return created;
            });
            onFxThread(() -> {
                console.allowWritesForTest().fire();
                assertTrue(console.allowWritesForTest().isSelected());
                assertFalse(console.allowSuiteWritesForTest().isSelected(),
                        "test writes must start off even when Run writes are on");
                return null;
            });
            denied = runSuite(console);
            onFxThread(() -> {
                console.setEditorTextForTest("");
                console.close();
                return null;
            });
        }
        assertTrue(denied.details().contains("WRITE_DENIED"),
                "Run Tests must use read-only bindings without its own opt-in: "
                        + denied.details());
        assertEquals(0, deniedService.createCalls,
                "a suite without its own opt-in must not reach the server");

        WritableService allowedService = new WritableService();
        SuiteOutcome allowed;
        try (ScriptingCoordinator coordinator = new ScriptingCoordinator()) {
            WriteProbeConsole console = onFxThread(() -> {
                WriteProbeConsole created = new WriteProbeConsole(allowedService,
                        managerWithServer(), summary -> true, true, coordinator);
                created.setEditorTextForTest(source);
                return created;
            });
            onFxThread(() -> {
                console.allowWritesForTest().fire();
                assertFalse(console.allowSuiteWritesForTest().isDisabled(),
                        "the suite opt-in must become available with a write target");
                console.allowSuiteWritesForTest().fire();
                assertTrue(console.allowSuiteWritesForTest().isSelected());
                return null;
            });
            allowed = runSuite(console);
            onFxThread(() -> {
                console.setEditorTextForTest("");
                console.close();
                return null;
            });
        }
        assertTrue(allowed.details().contains("WRITE_ALLOWED"),
                "the separately approved suite should be able to write: " + allowed.details());
        assertEquals(1, allowedService.createCalls,
                "the approved suite write should reach the fake server once");
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

    @Test
    @DisplayName("Server writes are off by default and refused before the service is called")
    void serverWritesAreOffByDefault() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole console = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true);

            assertFalse(console.allowWritesForTest().isSelected());
            assertThrows(WriteDeniedException.class,
                    () -> console.bindingsForTest().fhir.create(new Patient()));
            assertEquals(0, service.createCalls,
                    "the read-only default must refuse before the server is reached");
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("Enabling writes requires a target and then permits fhir.create")
    void enablingWritesPermitsCreate() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole console = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true);

            assertEquals("Writes: off", console.writeModeLabelForTest().getText());
            console.allowWritesForTest().fire();

            assertTrue(console.allowWritesForTest().isSelected());
            assertEquals("Writes: on / Write Test", console.writeModeLabelForTest().getText());
            assertEquals("created-1", console.bindingsForTest().fhir.create(new Patient())
                    .resourceId());
            assertEquals(1, service.createCalls);
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("The scope button narrows a live grant and is enforced by the API")
    void scopeButtonNarrowsTheLiveGrant() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole console = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true);
            console.allowWritesForTest().fire();
            console.scopeToChoose = WriteScope.of(Set.of(WriteScope.Operation.UPDATE),
                    Set.of("Patient"));

            console.scopeButtonForTest().fire();

            assertTrue(console.writePolicyForTest().scope().allows("update", "Patient"));
            assertFalse(console.writePolicyForTest().scope().allows("create", "Patient"));
            Patient patient = new Patient();
            patient.setId("1");
            assertThrows(WriteDeniedException.class,
                    () -> console.bindingsForTest().fhir.create(patient));
            console.bindingsForTest().fhir.update(patient);
            assertEquals(1, service.updateCalls,
                    "the scoped update should reach the fake server");
            assertEquals(0, service.createCalls,
                    "the refused create must not reach the fake server");
            assertTrue(console.statusTextForTest().contains("Write scope:"),
                    console.statusTextForTest());
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("A read-only server refuses the write opt-in even when its plugin supports writes")
    void readOnlyServerRefusesWriteOptIn() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole console = new WriteProbeConsole(service,
                    managerWithServer(true), summary -> true, true);

            console.allowWritesForTest().fire();

            assertFalse(console.allowWritesForTest().isSelected());
            assertTrue(console.statusTextForTest().contains("marked read-only"),
                    console.statusTextForTest());
            assertTrue(console.allowSuiteWritesForTest().isDisabled(),
                    "test writes must stay unavailable while server writes are off");
            assertThrows(WriteDeniedException.class,
                    () -> console.bindingsForTest().fhir.create(new Patient()));
            assertEquals(0, service.createCalls);
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("Turning writes off revokes an old fhir reference kept by the session")
    void turningWritesOffRevokesOldReferences() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole console = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true);
            console.allowWritesForTest().fire();
            ScriptBindings enabled = console.bindingsForTest();

            console.allowWritesForTest().fire();

            assertFalse(console.allowWritesForTest().isSelected());
            assertThrows(WriteDeniedException.class,
                    () -> enabled.fhir.create(new Patient()));
            assertEquals(0, service.createCalls,
                    "the old reference must be refused before the server is reached");
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("An enabled grant refuses a different active server immediately")
    void enabledGrantIsBoundToItsServer() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            FhirServerManager manager = managerWithServer();
            WriteProbeConsole console = new WriteProbeConsole(service, manager,
                    summary -> true, true);
            console.allowWritesForTest().fire();
            ScriptBindings enabled = console.bindingsForTest();

            ServerDefinition other = ServerDefinition.named("Other", "http://other.example.com/fhir")
                    .pluginId("standard-rest").build();
            manager.add(other);
            manager.setActive(other);

            assertThrows(WriteDeniedException.class,
                    () -> enabled.fhir.create(new Patient()));
            assertEquals(0, service.createCalls,
                    "the grant must not follow the active server to a new target");
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("A successful write appears in the Writes journal")
    void successfulWriteAppearsInJournal() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole console = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true);
            console.allowWritesForTest().fire();

            console.bindingsForTest().fhir.create(new Patient());

            String journal = console.writeJournalTextForTest();
            assertTrue(journal.contains("[create] Patient/created-1 version v1 — ok"), journal);
            assertTrue(journal.contains("Write Test"), journal);
            assertFalse(journal.contains("Smith"), "the journal must not include a body");
            assertEquals(1, console.writeJournalForTest().size());
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("Undo Last Write restores the before-image against the fake server")
    void undoRestoresTheBeforeImage() throws Exception {
        requireToolkit();
        WritableService service = new WritableService();
        Patient before = new Patient();
        before.setId("1");
        before.addName().setFamily("Before");
        service.readResource = before;
        WriteProbeConsole console = onFxThread(() -> {
            WriteProbeConsole created = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true);
            created.allowWritesForTest().fire();
            return created;
        });

        onFxThread(() -> {
            Patient after = new Patient();
            after.setId("1");
            after.addName().setFamily("After");
            console.bindingsForTest().fhir.update(after);
            assertFalse(console.undoButtonForTest().isDisable(),
                    "attaching the before-image must enable Undo, not wait for another refresh");
            return null;
        });
        assertEquals(1, service.updateCalls);
        assertTrue(console.writeJournalTextForTest().contains("[update] Patient/1"),
                console.writeJournalTextForTest());

        onFxThread(() -> {
            console.undoButtonForTest().fire();
            return null;
        });
        long deadline = System.currentTimeMillis() + 10_000;
        while (service.updateCalls < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertEquals(2, service.updateCalls, "the undo should issue one update");
        onFxThread(() -> {
            assertEquals("Before", ((Patient) service.lastUpdatedResource)
                    .getNameFirstRep().getFamily(),
                    "undo must send the resource captured before the original update");
            assertTrue(console.writeJournalTextForTest().contains("[undone]"),
                    console.writeJournalTextForTest());
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("Turning writes off disables Undo instead of bypassing the grant")
    void undoIsDisabledWhenWritesAreOff() throws Exception {
        requireToolkit();
        WritableService service = new WritableService();
        WriteProbeConsole console = onFxThread(() -> {
            WriteProbeConsole created = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true);
            created.allowWritesForTest().fire();
            return created;
        });

        onFxThread(() -> {
            console.bindingsForTest().fhir.create(new Patient());
            assertFalse(console.undoButtonForTest().isDisable(),
                    "a successful create should offer Undo while writes are on");

            console.allowWritesForTest().fire();
            assertTrue(console.undoButtonForTest().isDisable(),
                    "turning writes off must also take Undo away");

            console.undoButtonForTest().fire();
            assertEquals(0, service.deleteCalls,
                    "a disabled Undo must not send a delete after the grant is revoked");
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("Undo Last Write deletes a resource the create made")
    void undoDeletesACreatedResource() throws Exception {
        requireToolkit();
        WritableService service = new WritableService();
        WriteProbeConsole console = onFxThread(() -> {
            WriteProbeConsole created = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true);
            created.allowWritesForTest().fire();
            return created;
        });

        onFxThread(() -> {
            console.bindingsForTest().fhir.create(new Patient());
            assertFalse(console.undoButtonForTest().isDisable());
            console.undoButtonForTest().fire();
            return null;
        });
        long deadline = System.currentTimeMillis() + 10_000;
        while (service.deleteCalls < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertEquals(1, service.deleteCalls, "undoing a create should delete it");
        onFxThread(() -> {
            assertEquals("created-1", service.lastDeleteOrigin.resourceId());
            assertTrue(console.writeJournalTextForTest().contains("[undone]"),
                    console.writeJournalTextForTest());
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("Review Unknown reads the resource and resolves the journal entry")
    void reviewUnknownReadsAndResolves() throws Exception {
        requireToolkit();
        WritableService service = new WritableService();
        Patient found = new Patient();
        found.setId("1");
        found.getMeta().setVersionId("v2");
        service.readResource = found;
        FhirServerManager manager = managerWithServer();
        WriteProbeConsole console = onFxThread(() -> new WriteProbeConsole(service, manager,
                summary -> true, true));

        WriteJournal.Entry unknown = onFxThread(() -> {
            WriteJournal.Entry entry = console.writeJournalForTest().record(
                    manager.active().orElseThrow(), "update", "Patient", "1", "v1",
                    WriteJournal.Status.UNKNOWN, "TIMEOUT", Duration.ZERO);
            assertFalse(console.reviewUnknownButtonForTest().isDisable(),
                    "an unknown outcome must enable Review Unknown");
            console.reviewUnknownButtonForTest().fire();
            return entry;
        });

        long deadline = System.currentTimeMillis() + 10_000;
        while (!console.writeJournalForTest().isResolved(unknown)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        onFxThread(() -> {
            assertTrue(console.writeJournalForTest().isResolved(unknown));
            assertTrue(console.reconciliationText.contains("found version v2"),
                    console.reconciliationText);
            assertTrue(console.writeJournalForTest().lastUnresolvedUnknown().isEmpty());
            assertTrue(console.writeJournalTextForTest().contains("[reviewed]"),
                    console.writeJournalTextForTest());
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("A run shows a write summary separately from its output")
    void runShowsWriteSummary() throws Exception {
        requireToolkit();
        WritableService service = new WritableService();
        WriteProbeConsole console = onFxThread(() -> {
            WriteProbeConsole created = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true);
            created.setEditorTextForTest("fhir.create(new org.hl7.fhir.r4.model.Patient());");
            created.allowWritesForTest().fire();
            return created;
        });

        AtomicBoolean trustAnswered = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread helper = new Thread(() -> {
            while (done.getCount() > 0) {
                Platform.runLater(() -> {
                    try {
                        if (!trustAnswered.get() && acknowledgeTrustPrompt()) {
                            trustAnswered.set(true);
                        }
                        if (console.writeSummaryForTest().contains("1 succeeded") &&
                                !console.runButtonForTest().isDisable()) {
                            done.countDown();
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                        done.countDown();
                    }
                });
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }, "scripting-console-write-summary");
        helper.setDaemon(true);
        helper.start();
        Platform.runLater(() -> {
            try {
                console.runButtonForTest().fire();
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
                done.countDown();
            }
        });

        boolean finished;
        try {
            finished = done.await(20, TimeUnit.SECONDS);
        } finally {
            helper.interrupt();
        }
        if (failure.get() != null) {
            throw new AssertionError("the write summary run failed", failure.get());
        }
        assertTrue(finished, "the write summary did not appear");
        assertEquals("Writes: 1 succeeded.", onFxThread(console::writeSummaryForTest));
        assertEquals(1, service.createCalls);
        onFxThread(() -> {
            console.setEditorTextForTest("");
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("The console turns writes off when the grant stops being usable")
    void consoleTurnsOffWhenGrantStopsBeingUsable() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole console = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true);
            console.allowWritesForTest().fire();

            console.writePolicyForTest().revoke();
            console.checkWriteExpiryForTest();

            assertFalse(console.allowWritesForTest().isSelected());
            assertEquals("Writes: off", console.writeModeLabelForTest().getText());
            assertTrue(console.statusTextForTest().contains("after inactivity")
                    || console.statusTextForTest().contains("turned off"));
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("A run turns writes off when the active server disappears")
    void runWithoutWritableTargetTurnsWritesOff() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            FhirServerManager manager = managerWithServer();
            WriteProbeConsole console = new WriteProbeConsole(service, manager,
                    summary -> true, true);
            console.allowWritesForTest().fire();
            assertTrue(console.allowWritesForTest().isSelected());

            manager.remove(manager.active().orElseThrow());
            console.runButtonForTest().fire();

            assertFalse(console.allowWritesForTest().isSelected());
            assertEquals("Writes: off", console.writeModeLabelForTest().getText());
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("Changing the active writable server turns writes off before a run")
    void changingWritableTargetTurnsWritesOff() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            FhirServerManager manager = managerWithServer();
            WriteProbeConsole console = new WriteProbeConsole(service, manager,
                    summary -> true, true);
            console.allowWritesForTest().fire();
            assertTrue(console.allowWritesForTest().isSelected());

            ServerDefinition other = ServerDefinition.named("Other", "http://other.example.com/fhir")
                    .pluginId("standard-rest").build();
            manager.add(other);
            manager.setActive(other);
            console.runButtonForTest().fire();

            assertFalse(console.allowWritesForTest().isSelected());
            assertEquals("Writes: off", console.writeModeLabelForTest().getText());
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("Run Tests turns writes off when the active server disappears")
    void runTestsWithoutWritableTargetTurnsWritesOff() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            FhirServerManager manager = managerWithServer();
            WriteProbeConsole console = new WriteProbeConsole(service, manager,
                    summary -> true, true);
            console.setEditorTextForTest("// case: adds\n1 + 1");
            console.allowWritesForTest().fire();
            assertTrue(console.allowWritesForTest().isSelected());

            manager.remove(manager.active().orElseThrow());
            console.runTestsButtonForTest().fire();

            assertFalse(console.allowWritesForTest().isSelected());
            assertEquals("Writes: off", console.writeModeLabelForTest().getText());
            console.setEditorTextForTest("");
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("Enabling writes is refused without an active or writable server")
    void enablingWritesNeedsAUsableServer() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole noServer = new WriteProbeConsole(service, new FhirServerManager(),
                    summary -> true, true);
            noServer.allowWritesForTest().fire();
            assertFalse(noServer.allowWritesForTest().isSelected());
            noServer.close();

            service.supportsWrites = false;
            WriteProbeConsole readOnlyPlugin = new WriteProbeConsole(service,
                    managerWithServer(), summary -> true, true);
            readOnlyPlugin.allowWritesForTest().fire();
            assertFalse(readOnlyPlugin.allowWritesForTest().isSelected());
            readOnlyPlugin.close();
            return null;
        });
    }

    @Test
    @DisplayName("Loading the Example turns server writes off again")
    void loadingExampleTurnsWritesOff() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole console = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true);
            console.allowWritesForTest().fire();
            assertTrue(console.allowWritesForTest().isSelected());

            console.exampleButtonForTest().fire();

            assertFalse(console.allowWritesForTest().isSelected());
            assertThrows(WriteDeniedException.class,
                    () -> console.bindingsForTest().fhir.create(new Patient()));
            // Do not close here: loading the bundled example normalizes line endings,
            // so the existing close guard would ask about a difference this test did not make.
            return null;
        });
    }

    @Test
    @DisplayName("Cancelling the write warning leaves server writes off")
    void cancellingWriteWarningLeavesWritesOff() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole console = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, false);

            console.allowWritesForTest().fire();

            assertFalse(console.allowWritesForTest().isSelected());
            assertThrows(WriteDeniedException.class,
                    () -> console.bindingsForTest().fhir.create(new Patient()));
            assertEquals(0, service.createCalls,
                    "cancelling the warning must leave the next run read-only");
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("A cancelled delete confirmation sends no delete request")
    void cancelledDeleteSendsNothing() throws Exception {
        requireToolkit();
        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole console = new WriteProbeConsole(service, managerWithServer(),
                    summary -> false, true);
            console.allowWritesForTest().fire();

            assertThrows(WriteDeniedException.class,
                    () -> console.bindingsForTest().fhir.delete("Patient", "1"));
            assertEquals(0, service.deleteCalls,
                    "cancelling the confirmation must prevent the request");
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("Opening an existing script turns server writes off again")
    void openingExistingScriptTurnsWritesOff() throws Exception {
        requireToolkit();
        Path script = directory.resolve("existing.jsh");
        Files.writeString(script, "System.out.println(\"existing\");\n");

        onFxThread(() -> {
            WritableService service = new WritableService();
            WriteProbeConsole console = new WriteProbeConsole(service, managerWithServer(),
                    summary -> true, true, script);
            console.allowWritesForTest().fire();
            assertTrue(console.allowWritesForTest().isSelected());

            console.openButtonForTest().fire();

            assertFalse(console.allowWritesForTest().isSelected());
            assertEquals(script, console.boundFileForTest().orElse(null),
                    "Open should bind the buffer to the existing script");
            assertEquals("System.out.println(\"existing\");", console.editorTextForTest().strip(),
                    "Open should load the script into the editor");
            assertThrows(WriteDeniedException.class,
                    () -> console.bindingsForTest().fhir.create(new Patient()));
            assertEquals(0, service.createCalls,
                    "opening a file must leave the next run read-only");
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("The write opt-in is disabled while a run is in flight")
    void writeToggleIsDisabledDuringARun() throws Exception {
        requireToolkit();
        ProbeConsole console = onFxThread(() -> {
            ProbeConsole created = new ProbeConsole(new ScriptingCoordinator());
            created.setEditorTextForTest("Thread.sleep(1200);");
            return created;
        });
        AtomicBoolean disabledSeen = new AtomicBoolean();
        AtomicBoolean trustAnswered = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread helper = new Thread(() -> {
            while (done.getCount() > 0) {
                Platform.runLater(() -> {
                    try {
                        if (!trustAnswered.get() && acknowledgeTrustPrompt()) {
                            trustAnswered.set(true);
                        }
                        if (trustAnswered.get() && console.allowWritesForTest().isDisable()) {
                            disabledSeen.set(true);
                        }
                        if (disabledSeen.get() && !console.runButtonForTest().isDisable()) {
                            done.countDown();
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                        done.countDown();
                    }
                });
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }, "scripting-console-busy");
        helper.setDaemon(true);
        helper.start();
        Platform.runLater(() -> {
            try {
                console.runButtonForTest().fire();
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
                done.countDown();
            }
        });

        boolean finished;
        try {
            finished = done.await(10, TimeUnit.SECONDS);
        } finally {
            helper.interrupt();
        }
        if (failure.get() != null) {
            throw new AssertionError("the console run failed", failure.get());
        }
        assertTrue(finished, "the busy-state run did not finish");
        assertTrue(disabledSeen.get(), "the write checkbox was never disabled during the run");
        assertFalse(console.allowWritesForTest().isDisable(),
                "the write checkbox must be enabled again after the run");
        onFxThread(() -> {
            console.setEditorTextForTest("");
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("The native write warning enables writes after Allow Writes")
    void nativeWriteWarningEnablesWrites() throws Exception {
        requireToolkit();
        WritableService service = new WritableService();
        DialogProbeConsole console = onFxThread(() -> {
            DialogProbeConsole created = new DialogProbeConsole(service, managerWithServer());
            created.show();
            return created;
        });
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread helper = new Thread(() -> {
            while (finished.getCount() > 0) {
                Platform.runLater(() -> clickDialogButton("Allow server writes?", "Allow Writes"));
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }, "scripting-console-warning");
        helper.setDaemon(true);
        helper.start();
        Platform.runLater(() -> {
            try {
                console.allowWritesForTest().fire();
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            } finally {
                finished.countDown();
            }
        });

        boolean answered;
        try {
            answered = finished.await(10, TimeUnit.SECONDS);
        } finally {
            helper.interrupt();
        }
        if (failure.get() != null) {
            throw new AssertionError("the write warning failed", failure.get());
        }
        assertTrue(answered, "the native write warning was not answered");
        assertTrue(console.allowWritesForTest().isSelected(),
                "Allow Writes should enable the checkbox");
        assertEquals("Writes: on / Write Test", console.writeModeLabelForTest().getText());
        onFxThread(() -> {
            console.close();
            return null;
        });
    }

    @Test
    @DisplayName("The delete confirmation is marshalled from the worker to the FX thread")
    void deleteConfirmationMarshalsToFxThread() throws Exception {
        requireToolkit();
        WritableService service = new WritableService();
        WriteProbeConsole console = onFxThread(() -> {
            WriteProbeConsole created = new WriteProbeConsole(service, managerWithServer(),
                    null, true);
            created.show();
            return created;
        });
        onFxThread(() -> {
            console.allowWritesForTest().fire();
            assertTrue(console.allowWritesForTest().isSelected());
            return null;
        });

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch deleted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                console.bindingsForTest().fhir.delete("Patient", "1");
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                deleted.countDown();
            }
        }, "scripting-console-delete");
        Thread helper = new Thread(() -> {
            while (deleted.getCount() > 0) {
                Platform.runLater(() -> clickDialogButton("Delete resource?", "Delete"));
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }, "scripting-console-delete-answer");
        helper.setDaemon(true);
        worker.setDaemon(true);
        helper.start();
        worker.start();

        boolean finished;
        try {
            finished = deleted.await(10, TimeUnit.SECONDS);
        } finally {
            helper.interrupt();
        }
        if (failure.get() != null) {
            throw new AssertionError("the delete confirmation failed", failure.get());
        }
        assertTrue(finished, "the delete confirmation did not finish");
        assertEquals(1, service.deleteCalls);
        onFxThread(() -> {
            console.close();
            return null;
        });
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

    private static FhirServerManager managerWithServer() {
        return managerWithServer(false);
    }

    private static FhirServerManager managerWithServer(boolean readOnly) {
        FhirServerManager manager = new FhirServerManager();
        ServerDefinition server = ServerDefinition
                .named("Write Test", "http://write.example.com/fhir")
                .pluginId("standard-rest")
                .writeProtected(readOnly)
                .build();
        manager.add(server);
        manager.setActive(server);
        return manager;
    }

    /** A service that records write calls without sending anything. */
    private static final class WritableService extends FhirServerService {

        boolean supportsWrites = true;
        int createCalls;
        int updateCalls;
        int deleteCalls;
        int readCalls;
        org.hl7.fhir.instance.model.api.IBaseResource readResource;
        org.hl7.fhir.instance.model.api.IBaseResource lastUpdatedResource;
        ServerOrigin lastUpdateOrigin;
        ServerOrigin lastDeleteOrigin;

        WritableService() {
            super(new FhirServerPluginRegistry());
        }

        @Override
        public boolean supportsWrite(FhirServerConfiguration server) {
            return supportsWrites;
        }

        @Override
        public org.hl7.fhir.instance.model.api.IBaseResource read(FhirServerConfiguration server,
                String resourceType, String resourceId) {
            readCalls++;
            return readResource;
        }

        @Override
        public ServerWriteResult create(FhirServerConfiguration server,
                org.hl7.fhir.instance.model.api.IBaseResource resource) {
            createCalls++;
            return ServerWriteResult.created("created-1", "v1");
        }

        @Override
        public ServerWriteResult update(FhirServerConfiguration server,
                org.hl7.fhir.instance.model.api.IBaseResource resource, ServerOrigin origin) {
            updateCalls++;
            lastUpdatedResource = resource;
            lastUpdateOrigin = origin;
            return ServerWriteResult.updated(origin.resourceId(), "v2");
        }

        @Override
        public ServerWriteResult patch(FhirServerConfiguration server, ServerOrigin origin,
                String body, PatchFormat format) {
            return ServerWriteResult.updated(origin.resourceId(), "v2");
        }

        @Override
        public void delete(FhirServerConfiguration server, ServerOrigin origin) {
            deleteCalls++;
            lastDeleteOrigin = origin;
        }
    }

    /** A console whose write-access warning is answered without a native dialog. */
    private static final class WriteProbeConsole extends ScriptingConsoleDialog {

        private final boolean approveWrites;
        private final Path fileToOpen;
        WriteScope scopeToChoose;
        String reconciliationText;

        WriteProbeConsole(FhirServerService service, FhirServerManager manager,
                WritePolicy.Confirmation confirmation, boolean approveWrites) {
            this(service, manager, confirmation, approveWrites, (Path) null);
        }

        WriteProbeConsole(FhirServerService service, FhirServerManager manager,
                WritePolicy.Confirmation confirmation, boolean approveWrites,
                Path fileToOpen) {
            super(new ThemeManager(), new FhirService(), service, manager,
                    new ScriptingCoordinator(), null, confirmation);
            this.approveWrites = approveWrites;
            this.fileToOpen = fileToOpen;
        }

        WriteProbeConsole(FhirServerService service, FhirServerManager manager,
                WritePolicy.Confirmation confirmation, boolean approveWrites,
                ScriptingCoordinator coordinator) {
            super(new ThemeManager(), new FhirService(), service, manager,
                    coordinator, null, confirmation);
            this.approveWrites = approveWrites;
            this.fileToOpen = null;
        }

        @Override
        protected boolean confirmWriteAccess(FhirServerConfiguration server) {
            return approveWrites;
        }

        @Override
        protected Optional<WriteScope> chooseWriteScope() {
            return Optional.ofNullable(scopeToChoose);
        }

        @Override
        protected boolean confirmUndo(WriteJournal.Entry entry) {
            return true;
        }

        @Override
        protected boolean confirmReconciliation(WriteJournal.Entry entry, String readResult) {
            reconciliationText = readResult;
            return true;
        }

        @Override
        protected File chooseOpenLocation() {
            return fileToOpen == null ? null : fileToOpen.toFile();
        }
    }

    /** A console that uses the real write warning and confirmation dialogs. */
    private static final class DialogProbeConsole extends ScriptingConsoleDialog {

        DialogProbeConsole(FhirServerService service, FhirServerManager manager) {
            super(new ThemeManager(), new FhirService(), service, manager,
                    new ScriptingCoordinator(), null);
        }
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
    private static SuiteOutcome runSuite(ScriptingConsoleDialog console) throws Exception {
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
        return clickDialogButton("Run this script?", "Run Script");
    }

    private static boolean clickDialogButton(String title, String buttonText) {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage && stage.isShowing()
                    && title.equals(stage.getTitle())) {
                for (Node node : stage.getScene().getRoot().lookupAll(".button")) {
                    if (node instanceof Button button && buttonText.equals(button.getText())) {
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
