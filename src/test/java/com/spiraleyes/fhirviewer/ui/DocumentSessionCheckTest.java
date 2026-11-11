package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.model.LoadedResource;
import com.spiraleyes.fhirviewer.model.ResourceNode;
import com.spiraleyes.fhirviewer.service.FhirService;
import com.spiraleyes.fhirviewer.service.ResourceEditorService;
import com.spiraleyes.fhirviewer.service.SourceEditorService;

import javafx.application.Platform;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

/**
 * Drives a real {@link DocumentSession} over a real sample and asserts the three things the M1
 * split moved without being able to watch: the dirty flag, the title asterisk and the undo cap.
 *
 * <p>Those were the known gap after steps 5 and 6 - the window was launched and loaded a
 * sample, but nothing edited, so nothing proved that an edit marks the document, that the
 * title says so, or that the history stops where it is documented to. This test closes that
 * gap without a human: the session is built on a real toolkit with its real views, a value is
 * edited, and the state and the title rule the window uses are asserted on the result.</p>
 *
 * <p>Skipped automatically when no display is available, like the server smoke test, so a
 * headless build keeps passing.</p>
 */
class DocumentSessionCheckTest {

    private static final CountDownLatch STARTED = new CountDownLatch(1);
    private static final AtomicReference<Throwable> TOOLKIT_FAILURE = new AtomicReference<>();

    private static final String FAMILY_PATH = "Patient.name[0].family";

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
    @DisplayName("An edit dirties the document and puts the asterisk in the title")
    void anEditDirtiesTheDocumentAndTitlesIt() throws Exception {
        AtomicInteger editCallbacks = new AtomicInteger();
        AtomicReference<String> failure = new AtomicReference<>();

        runOnFxThread(() -> {
            Harness harness = new Harness(editCallbacks);
            harness.session.show(harness.fhirService.openSample(FhirService.SAMPLE_PATIENT), null);

            assertFalse(harness.session.isDirty(),
                    "a freshly loaded sample must not look edited");
            String cleanTitle = MainWindow.titleFor(harness.session.label(),
                    harness.session.isDirty());
            assertFalse(cleanTitle.endsWith("*"),
                    "the title of an unedited resource must not carry an asterisk: " + cleanTitle);
            assertTrue(cleanTitle.contains(harness.session.label()),
                    "the title must name the displayed resource: " + cleanTitle);

            String original = harness.familyValue();
            harness.session.applyValueEdit(harness.node(), "Changed");

            assertTrue(harness.session.isDirty(),
                    "editing a value must mark the document, or Save and the asterisk both lie");
            String dirtyTitle = MainWindow.titleFor(harness.session.label(),
                    harness.session.isDirty());
            assertTrue(dirtyTitle.endsWith(" *"),
                    "the title must mark unsaved changes: " + dirtyTitle);
            assertTrue(editCallbacks.get() > 0,
                    "the edit must fire the enable-state callback, or Undo stays greyed out");
            assertNotEquals(original, harness.familyValue(),
                    "the edit did not reach the displayed resource");

            harness.session.undoEdit();

            assertFalse(harness.session.isDirty(),
                    "undoing the only edit must return the document to clean");
            assertFalse(harness.session.canUndo(), "the history should be empty again");
            assertEquals(original, harness.familyValue(), "undo must restore the value");
            assertFalse(MainWindow.titleFor(harness.session.label(), harness.session.isDirty())
                    .endsWith("*"), "the asterisk must go when the document is clean again");
            failure.set("ok");
        });

        assertEquals("ok", failure.get());
    }

    @Test
    @DisplayName("Undo stops at the twenty-edit cap instead of growing without bound")
    void undoStopsAtTheCap() throws Exception {
        AtomicReference<String> outcome = new AtomicReference<>();

        runOnFxThread(() -> {
            Harness harness = new Harness(new AtomicInteger());
            harness.session.show(harness.fhirService.openSample(FhirService.SAMPLE_PATIENT), null);
            String original = harness.familyValue();

            for (int edit = 1; edit <= 25; edit++) {
                harness.session.applyValueEdit(harness.node(), "Value" + edit);
            }

            int undos = 0;
            while (harness.session.canUndo() && undos < 100) {
                harness.session.undoEdit();
                undos++;
            }

            assertEquals(20, undos,
                    "the documented depth is twenty, so twenty undos must exhaust the history");
            assertFalse(harness.session.canUndo(), "nothing may remain to undo");
            assertNotEquals(original, harness.familyValue(),
                    "the five oldest snapshots are dropped, so exhausting the history must leave "
                            + "the fifth edit in place rather than the loaded value");
            outcome.set("ok");
        });

        assertEquals("ok", outcome.get());
    }

    /** A session over real views and a real sample, with the window's callbacks recorded. */
    private static final class Harness {

        private final FhirService fhirService = new FhirService();
        private final ResourceTreeView tree = new ResourceTreeView();
        private final DetailsView details = new DetailsView();
        private final JsonView json = new JsonView();
        private final XmlView xml = new XmlView();
        private final PrettyView pretty = new PrettyView();
        private final StatusView status = new StatusView();
        private final BundleView bundle = new BundleView();
        private final FhirPathView fhirPath = new FhirPathView();
        private final TabPane documentTabs = new TabPane();
        private final TabPane structureTabs = new TabPane();
        private final Tab treeTab = new Tab("Resource Tree");
        private final Tab bundleTab = new Tab("Bundle");
        private final Tab prettyTab = new Tab("Pretty");
        private final Tab detailsTab = new Tab("Details");
        private final Tab jsonTab = new Tab("JSON");
        private final Tab xmlTab = new Tab("XML");
        private final Tab fhirPathTab = new Tab("FHIRPath");
        private final CheckMenuItem showUnpopulated = new CheckMenuItem("Show unpopulated");

        private final DocumentSession session;

        Harness(AtomicInteger editCallbacks) {
            DocumentViews views = new DocumentViews() {
                @Override
                public ResourceTreeView tree() {
                    return tree;
                }

                @Override
                public DetailsView details() {
                    return details;
                }

                @Override
                public JsonView json() {
                    return json;
                }

                @Override
                public XmlView xml() {
                    return xml;
                }

                @Override
                public PrettyView pretty() {
                    return pretty;
                }

                @Override
                public StatusView status() {
                    return status;
                }

                @Override
                public BundleView bundle() {
                    return bundle;
                }

                @Override
                public FhirPathView fhirPath() {
                    return fhirPath;
                }

                @Override
                public TabPane documentTabs() {
                    return documentTabs;
                }

                @Override
                public TabPane structureTabs() {
                    return structureTabs;
                }

                @Override
                public Tab treeTab() {
                    return treeTab;
                }

                @Override
                public Tab bundleTab() {
                    return bundleTab;
                }

                @Override
                public Tab prettyTab() {
                    return prettyTab;
                }

                @Override
                public Tab detailsTab() {
                    return detailsTab;
                }

                @Override
                public Tab jsonTab() {
                    return jsonTab;
                }

                @Override
                public Tab xmlTab() {
                    return xmlTab;
                }

                @Override
                public Tab fhirPathTab() {
                    return fhirPathTab;
                }

                @Override
                public boolean showUnpopulated() {
                    return showUnpopulated.isSelected();
                }
            };
            session = new DocumentSession(new Stage(), fhirService, new ResourceEditorService(),
                    new SourceEditorService(), new ThemeManager(), views, message -> { },
                    (message, problem) -> {
                        throw new AssertionError(message, problem);
                    },
                    () -> { }, editCallbacks::incrementAndGet, () -> { }, busy -> { });
        }

        /** The family-name node of the sample, found by path in a fresh tree. */
        ResourceNode node() {
            return find(fhirService.buildTree(session.loaded(), true), FAMILY_PATH);
        }

        /** The family name the displayed resource currently holds. */
        String familyValue() {
            ResourceNode node = node();
            return node == null ? null : node.getValueText();
        }

        private static ResourceNode find(ResourceNode node, String path) {
            if (node == null) {
                return null;
            }
            if (path.equals(node.getElementInfo().getPath())) {
                return node;
            }
            for (ResourceNode child : node.getChildren()) {
                ResourceNode found = find(child, path);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }

    /** Runs the body on the JavaFX thread and rethrows whatever it threw. */
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
