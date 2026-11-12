package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.pretty.PrettyBlock;
import com.spiraleyes.fhirviewer.pretty.PrettyDocument;
import com.spiraleyes.fhirviewer.pretty.PrettyRow;

import javafx.application.Platform;

/**
 * Pins the scroll reset the Pretty View needs when it swaps documents: the offset belonged to
 * content that is gone, so the replacement has to open at the top.
 *
 * <p>Skipped automatically when no display is available, like the other UI tests, so a headless
 * build keeps passing.</p>
 */
class PrettyViewScrollTest {

    private static final CountDownLatch STARTED = new CountDownLatch(1);
    private static final AtomicReference<Throwable> TOOLKIT_FAILURE = new AtomicReference<>();

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            Platform.startup(STARTED::countDown);
            STARTED.await(30, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyStarted) {
            // Another UI test class started the toolkit first. That is the state this class
            // wants, not a reason to skip.
        } catch (Throwable toolkitProblem) {
            TOOLKIT_FAILURE.set(toolkitProblem);
        }
    }

    @BeforeEach
    void requireToolkit() {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "No display available for the JavaFX toolkit.");
        org.junit.jupiter.api.Assumptions.assumeTrue(TOOLKIT_FAILURE.get() == null,
                "The JavaFX toolkit did not start: " + TOOLKIT_FAILURE.get());
    }

    @Test
    @DisplayName("Showing a document puts the view back at the top")
    void showingADocumentResetsTheScrollPosition() throws Exception {
        runOnFxThread(() -> {
            PrettyView view = new PrettyView();
            // Stand-in for a reader who scrolled down the document that is on screen.
            view.setVvalue(1);

            view.show(document("Patient", "example-1"));
            assertEquals(0, view.getVvalue(), 0.0001,
                    "a shown document must not inherit the previous scroll offset");

            view.setVvalue(0.5);
            view.show(document("Observation", "second"));
            assertEquals(0, view.getVvalue(), 0.0001,
                    "replacing one document with another must start at the top");
        });
    }

    private static PrettyDocument document(String resourceType, String resourceId) {
        return new PrettyDocument(
                resourceType,
                resourceId,
                List.of(),
                List.of(new PrettyRow("Gender", "male")),
                List.of(PrettyBlock.builder("Name 1")
                        .row("Family", "Smith", "name")
                        .build()));
    }

    private static void runOnFxThread(Runnable body) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable problem) {
                thrown.set(problem);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "the JavaFX thread did not finish in time");
        Throwable problem = thrown.get();
        if (problem instanceof AssertionError assertion) {
            throw assertion;
        }
        if (problem != null) {
            throw new AssertionError("the JavaFX thread failed", problem);
        }
    }
}
