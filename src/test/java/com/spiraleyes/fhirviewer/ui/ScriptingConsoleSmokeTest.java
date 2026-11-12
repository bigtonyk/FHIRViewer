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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerService;
import com.spiraleyes.fhirviewer.service.FhirService;

import javafx.application.Platform;

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
            STARTED.await(30, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyStarted) {
            // Another UI test class started the toolkit first; that is the state wanted.
        } catch (Throwable toolkitProblem) {
            TOOLKIT_FAILURE.set(toolkitProblem);
        }
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
