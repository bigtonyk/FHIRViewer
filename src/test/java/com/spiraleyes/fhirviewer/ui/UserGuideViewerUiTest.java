package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.input.Clipboard;

/**
 * Exercises the guide viewer controls on a real JavaFX toolkit: search,
 * contents navigation, Back and the per-sample Copy button.
 */
class UserGuideViewerUiTest {

    private static final CountDownLatch STARTED = new CountDownLatch(1);
    private static final AtomicReference<Throwable> TOOLKIT_FAILURE = new AtomicReference<>();

    private static final String GUIDE = """
            # Test guide

            ## First section

            A patient sample lives here.

            ## Second section

            Another patient sample, plus section navigation.

            ## Code sample

            ```java
            if (!smart.isSessionActive()) {
                log.warn("No SMART session.");
            }
            ```
            """;

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            Platform.startup(STARTED::countDown);
            STARTED.await(30, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyStarted) {
            STARTED.countDown();
        } catch (Throwable toolkitProblem) {
            TOOLKIT_FAILURE.set(toolkitProblem);
        }
    }

    @BeforeEach
    void requireToolkit() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(), "No display available for the JavaFX toolkit.");
        org.junit.jupiter.api.Assumptions.assumeTrue(TOOLKIT_FAILURE.get() == null,
                "The JavaFX toolkit did not start: " + TOOLKIT_FAILURE.get());
    }

    @Test
    @DisplayName("Search reports matches and moves through them")
    void searchReportsAndMovesThroughMatches() throws Exception {
        runOnFxThread(() -> {
            UserGuideDialog dialog = new UserGuideDialog(new ThemeManager(), GUIDE);
            dialog.searchField().setText("patient");
            dialog.search(true);
            assertEquals("1 of 2", dialog.searchStatus().getText());
            dialog.search(true);
            assertEquals("2 of 2", dialog.searchStatus().getText());
            dialog.search(true);
            assertEquals("1 of 2", dialog.searchStatus().getText(), "next wraps to the first match");

            dialog.searchField().setText("missing");
            dialog.search(true);
            assertEquals("No matches", dialog.searchStatus().getText());
        });
    }

    @Test
    @DisplayName("Repeated matches in one paragraph are one navigation stop")
    void repeatedMatchesInOneParagraphCountOnce() throws Exception {
        String repeated = """
                # Repeated matches

                ## First paragraph

                patient patient patient

                ## Second paragraph

                patient
                """;
        runOnFxThread(() -> {
            UserGuideDialog dialog = new UserGuideDialog(new ThemeManager(), repeated);
            dialog.searchField().setText("patient");
            dialog.search(true);
            assertEquals("1 of 2", dialog.searchStatus().getText(),
                    "three occurrences in one paragraph are one stop, not three");
            dialog.search(true);
            assertEquals("2 of 2", dialog.searchStatus().getText());
            dialog.search(true);
            assertEquals("1 of 2", dialog.searchStatus().getText());
            dialog.search(false);
            assertEquals("2 of 2", dialog.searchStatus().getText(),
                    "Previous returns to the preceding matching block");
        });
    }

    @Test
    @DisplayName("Contents lists headings and Back returns from an anchor jump")
    void contentsAndBackWork() throws Exception {
        runOnFxThread(() -> {
            UserGuideDialog dialog = new UserGuideDialog(new ThemeManager(), GUIDE);
            assertTrue(dialog.contentsButton().getItems().size() >= 3,
                    "The contents menu should list the rendered headings");
            assertEquals("Test guide", dialog.contentsButton().getItems().get(0).getText());

            dialog.guideView().scrollToAnchor("second-section");
            assertTrue(dialog.guideView().canGoBack());
            assertTrue(dialog.guideView().goBack());
            assertFalse(dialog.guideView().canGoBack());
        });
    }

    @Test
    @DisplayName("A code sample has a Copy button that copies its source")
    void codeCopyCopiesTheSample() throws Exception {
        runOnFxThread(() -> {
            UserGuideDialog dialog = new UserGuideDialog(new ThemeManager(), GUIDE);
            dialog.guideView().applyCss();
            dialog.guideView().layout();
            Button copy = findButton(dialog.guideView(), "guide-copy-button");
            assertNotNull(copy, "the code block should expose a Copy button");
            copy.fire();
            assertEquals("Copied", copy.getText());
            assertEquals("if (!smart.isSessionActive()) {\n"
                            + "    log.warn(\"No SMART session.\");\n"
                            + "}",
                    Clipboard.getSystemClipboard().getString());
        });
    }

    private static Button findButton(Node node, String styleClass) {
        if (node instanceof Button button && button.getStyleClass().contains(styleClass)) {
            return button;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Button found = findButton(child, styleClass);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
        assertTrue(done.await(20, TimeUnit.SECONDS), "the JavaFX task did not finish");
        if (thrown.get() != null) {
            throw new AssertionError(thrown.get());
        }
    }
}
