package com.spiraleyes.fhirviewer.ui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IBaseMetaType;

import com.spiraleyes.fhirviewer.service.FhirService;
import com.spiraleyes.fhirviewer.service.scripting.BatchProcessor;
import com.spiraleyes.fhirviewer.service.scripting.ScriptRunResult;
import com.spiraleyes.fhirviewer.service.scripting.ScriptingCoordinator;
import com.spiraleyes.fhirviewer.service.scripting.testing.SuiteCaseResult;
import com.spiraleyes.fhirviewer.service.scripting.testing.SuiteRunResult;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestSuite;
import com.spiraleyes.fhirviewer.service.scripting.api.FhirApi;
import com.spiraleyes.fhirviewer.service.scripting.api.LogApi;
import com.spiraleyes.fhirviewer.service.scripting.api.ScriptBindings;
import com.spiraleyes.fhirviewer.service.scripting.api.ServerApi;
import com.spiraleyes.fhirviewer.service.scripting.api.SmartApi;
import com.spiraleyes.fhirviewer.service.scripting.api.TestApi;
import com.spiraleyes.fhirviewer.service.scripting.api.ValidationApi;
import com.spiraleyes.fhirviewer.service.scripting.api.ViewerApi;
import com.spiraleyes.fhirviewer.service.scripting.api.WriteJournal;
import com.spiraleyes.fhirviewer.service.scripting.api.WritePolicy;
import com.spiraleyes.fhirviewer.service.scripting.api.WriteScope;
import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerService;
import com.spiraleyes.fhirviewer.server.SearchCriterion;
import com.spiraleyes.fhirviewer.server.SearchRequest;
import com.spiraleyes.fhirviewer.server.SearchResultPage;
import com.spiraleyes.fhirviewer.server.ServerOrigin;
import com.spiraleyes.fhirviewer.server.smart.SmartSessionHolder;

/**
 * The Scripting Console: a multiline Java editor over a read-only output
 * console, running the buffer through an embedded JShell.
 *
 * <p>Shaped after {@link RestConsoleDialog} — a workspace {@code Dialog} with a
 * SplitPane of two halves and a status line that never lies — rather than
 * after any modal form: the editor and the output belong on screen together,
 * and Run/Stop/Reset/Open/Save/Save As/Example/Clear Output are the content of
 * the job, not decoration around it. The dialog themes and sizes itself, the
 * way {@link UserGuideDialog} does, instead of going through
 * {@code DialogTheme.apply}: that helper pins every dialog it touches to 620px,
 * and a scripting workspace at 620px is a form the panels collide in.</p>
 *
 * <p><b>Scripts are ordinary Java and are not sandboxed.</b> The first Run —
 * and the first Run after opening a file, which arrives from outside — asks
 * for an explicit acknowledgement before anything executes, and nothing ever
 * runs on its own: not on dialog open, not on file open, not on anything but
 * the Run button.</p>
 *
 * <p><b>Execution never blocks the JavaFX thread.</b> Every call into
 * {@link ScriptingCoordinator} happens on its own worker; results come back to
 * the toolkit through {@code Platform.runLater}. {@code setOnHidden} closes
 * the coordinator, so no JShell outlives the dialog; a JVM shutdown hook does
 * the same if the application exits while the dialog is open.</p>
 */
public class ScriptingConsoleDialog extends Dialog<Void> {

    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** How many identifier matches a reconciliation search asks for. */
    private static final int RECONCILE_PAGE_SIZE = 10;

    /** How many pages of one identifier search the review reads before it stops. */
    private static final int RECONCILE_MAX_PAGES = 5;

    private static final int WRITE_BUDGET = 50;
    private static final java.time.Duration WRITE_IDLE_TIMEOUT = java.time.Duration.ofMinutes(15);

    /** The bundled example the Example button loads; the session tests run it directly. */
    static final String EXAMPLE_RESOURCE = "/scripts/scripting-example.jsh";

    private final TextArea editor = new TextArea();
    private final TextArea output = new TextArea();
    private final TextArea suiteDetails = new TextArea();
    private final TextArea writeJournalView = new TextArea();
    private final Label writeSummaryLabel = new Label();
    private TabPane bottomTabs;
    private final Label statusLabel = new Label();
    private final Button runButton = new Button("Run");
    private final Label runModeLabel = new Label();
    private final Button runTestsButton = new Button("Run Tests");
    private final Label suiteReport = new Label();
    private final Button stopButton = new Button("Stop");
    private final Button resetButton = new Button("Reset Session");
    private final Button openButton = new Button("Open\u2026");
    private final Button saveButton = new Button("Save");
    private final Button saveAsButton = new Button("Save As\u2026");
    private final Button exampleButton = new Button("Example");
    private final Button clearButton = new Button("Clear Output");
    private final CheckBox allowWrites = new CheckBox("Allow server writes");
    private final CheckBox allowSuiteWrites = new CheckBox("Allow writes in test runs");
    private final Button scopeButton = new Button("Write scope\u2026");
    private final Button undoButton = new Button("Undo Last Write");
    private final Button reviewUnknownButton = new Button("Review Unknown");
    private final Label writeModeLabel = new Label();
    private final MenuButton guidesButton = new MenuButton("Guides");

    /** Opens a named guide at an anchor; the server screens supply the real opener. */
    private BiConsumer<String, String> guideOpener = (guide, anchor) -> {
    };

    private final ScriptingCoordinator coordinator;
    private final List<String> stylesheets;
    private final FhirService fhirService;
    private final FhirServerService serverService;
    private final FhirServerManager serverManager;
    private final SmartApi smartApi;
    private final BatchProcessor batch = new BatchProcessor();
    private final WritePolicy.Confirmation writeConfirmation;
    private final WriteJournal writeJournal = new WriteJournal();
    private final Timeline writeExpiryCheck = new Timeline(
            new KeyFrame(javafx.util.Duration.seconds(15), event -> checkWriteExpiry()));

    private static File lastDirectory;
    private Path currentFile;

    private String savedContent = "";
    private boolean trustAcknowledged;

    /** The script API objects exposed to every run; replaced when the write policy changes. */
    private ScriptBindings bindings;

    /** The live grant behind the current opt-in; replaced when the policy changes. */
    private WritePolicy writePolicy = WritePolicy.READ_ONLY;

    /** The operations and resource types the next or current grant permits. */
    private WriteScope writeScope = WriteScope.all();

    /** Index into the journal where the current Run or Run Tests began. */
    private int journalRunStart;

    /** The server named in the warning that enabled writes, or null when writes are off. */
    private FhirServerConfiguration writeTarget;

    /**
     * @param themeManager the application's theme source; never {@code null}
     * @param fhirService  shared local FHIR processing (validation, rendering)
     * @param serverService the active server connection used by {@code fhir}
     * @param serverManager  the configured servers, for {@code server}
     */
    public ScriptingConsoleDialog(ThemeManager themeManager,
            FhirService fhirService, FhirServerService serverService,
            FhirServerManager serverManager) {
        this(themeManager, fhirService, serverService, serverManager, new ScriptingCoordinator());
    }

    /** A console that can read through the application's authorized SMART session. */
    public ScriptingConsoleDialog(ThemeManager themeManager, FhirService fhirService,
            FhirServerService serverService, FhirServerManager serverManager,
            SmartSessionHolder smartSessions) {
        this(themeManager, fhirService, serverService, serverManager,
                new ScriptingCoordinator(), smartSessions);
    }

    /**
     * Builds the console over the given coordinator, so a UI test can drive suite
     * results the real session factory cannot produce. Package-private: production
     * callers use the public constructor. The dialog closes the coordinator when it
     * is hidden, so a caller must not share one with another window.
     */
    ScriptingConsoleDialog(ThemeManager themeManager, FhirService fhirService,
            FhirServerService serverService, FhirServerManager serverManager,
            ScriptingCoordinator coordinator) {
        this(themeManager, fhirService, serverService, serverManager, coordinator,
                new SmartSessionHolder());
    }

    ScriptingConsoleDialog(ThemeManager themeManager, FhirService fhirService,
            FhirServerService serverService, FhirServerManager serverManager,
            ScriptingCoordinator coordinator, SmartSessionHolder smartSessions) {
        this(themeManager, fhirService, serverService, serverManager, coordinator,
                smartSessions, null);
    }

    ScriptingConsoleDialog(ThemeManager themeManager, FhirService fhirService,
            FhirServerService serverService, FhirServerManager serverManager,
            ScriptingCoordinator coordinator, SmartSessionHolder smartSessions,
            WritePolicy.Confirmation writeConfirmation) {
        if (coordinator == null) {
            throw new IllegalArgumentException("A scripting coordinator is required.");
        }
        if (themeManager == null) {
            throw new IllegalArgumentException("A theme manager is required.");
        }
        if (fhirService == null || serverService == null || serverManager == null) {
            throw new IllegalArgumentException("The scripting services are required.");
        }

        this.coordinator = coordinator;
        this.fhirService = fhirService;
        this.serverService = serverService;
        this.serverManager = serverManager;
        this.smartApi = smartSessions == null ? new SmartApi() : smartSessions.api();
        this.writeConfirmation = writeConfirmation == null
                ? this::confirmWriteOnFxThread
                : writeConfirmation;
        writeJournal.addListener(this::journalEntryAdded);
        writeExpiryCheck.setCycleCount(Animation.INDEFINITE);
        bindings = createBindings(writePolicy);

        this.stylesheets = List.copyOf(themeManager.stylesheets());

        setTitle("Scripting Console");
        setHeaderText("Java snippets, powered by the embedded JShell. "
                + "Scripts run with your user's permissions — this is not a sandbox.");
        setResizable(true);

        statusLabel.getStyleClass().add("app-subtitle");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        setStatus("Ready. Write a script, then press Run.");
        runModeLabel.getStyleClass().add("app-subtitle");
        runModeLabel.setTooltip(new Tooltip("A saved script runs in a fresh JShell session"
                + " and leaves nothing behind; the unsaved editor keeps the interactive"
                + " session until Reset Session."));
        updateRunModeLabel();
        allowWrites.setTooltip(new Tooltip("Allow fhir.create, fhir.update, fhir.patch and"
                + " fhir.delete to change the active FHIR server for this console session."));
        allowWrites.setOnAction(event -> onWriteToggle());
        allowSuiteWrites.setTooltip(new Tooltip("Off by default: Run Tests uses a read-only"
                + " API even while interactive Run may write. Turn this on only for suites"
                + " that need to create fixtures; an optional // cleanup: block then runs"
                + " after the cases to remove them."));
        allowSuiteWrites.setDisable(true);
        scopeButton.setTooltip(new Tooltip("Limit this write grant to particular operations"
                + " and resource types. The scope is enforced for every script write."));
        scopeButton.setOnAction(event -> onChooseWriteScope());
        undoButton.setTooltip(new Tooltip("Separate recovery write: reapply the resource"
                + " captured before the latest successful write, or delete a resource that"
                + " write created. It is confirmed separately and is not limited by the"
                + " current write scope."));
        undoButton.setDisable(true);
        undoButton.setOnAction(event -> onUndoLastWrite());
        reviewUnknownButton.setTooltip(new Tooltip("Read the affected resource and resolve"
                + " the latest write whose outcome was unknown."));
        reviewUnknownButton.setDisable(true);
        reviewUnknownButton.setOnAction(event -> onReviewUnknownOutcome());
        writeModeLabel.getStyleClass().add("app-subtitle");
        updateWriteModeLabel();
        runTestsButton.setTooltip(new Tooltip("Runs each // case: block as a test in a session"
                + " of its own; counts appear above the panes and each case's output goes"
                + " to the Test details tab."));
        suiteReport.getStyleClass().add("app-subtitle");
        suiteReport.setWrapText(true);
        suiteReport.setMaxWidth(Double.MAX_VALUE);
        suiteReport.setManaged(false);
        suiteReport.setVisible(false);
        writeSummaryLabel.getStyleClass().add("app-subtitle");
        writeSummaryLabel.setWrapText(true);
        writeSummaryLabel.setMaxWidth(Double.MAX_VALUE);
        writeSummaryLabel.setManaged(false);
        writeSummaryLabel.setVisible(false);
        // A FlowPane, not an HBox: the controls plus the mode label are wider than the
        // 820px minimum dialog, and an HBox would squeeze the buttons or clip the label
        // instead of moving the overflow to a second row.
        MenuItem scriptingGuide = new MenuItem("Scripting Guide");
        scriptingGuide.setOnAction(event -> guideOpener.accept("scripting", "#smart-on-fhir"));
        MenuItem smartGuide = new MenuItem("SMART session");
        smartGuide.setOnAction(event -> guideOpener.accept("smart", "#coding-a-smart-workflow"));
        guidesButton.getItems().addAll(scriptingGuide, smartGuide);
        guidesButton.setTooltip(new Tooltip("Open the guide beside this console"));
        FlowPane toolbar = new FlowPane(8, 8, runButton, runTestsButton, runModeLabel,
                allowWrites, allowSuiteWrites, scopeButton, writeModeLabel, undoButton,
                reviewUnknownButton, stopButton, resetButton, openButton, saveButton,
                saveAsButton, exampleButton, clearButton, guidesButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        runTestsButton.setOnAction(event -> onRunTests());
        openButton.setOnAction(event -> onOpen());
        saveButton.setOnAction(event -> onSave(false));
        saveAsButton.setOnAction(event -> onSave(true));
        exampleButton.setOnAction(event -> onLoadExample());
        runButton.setOnAction(event -> onRun());
        stopButton.setOnAction(event -> onStop());
        resetButton.setOnAction(event -> onReset());
        clearButton.setOnAction(event -> output.clear());

        suiteDetails.setEditable(false);
        suiteDetails.setWrapText(true);
        writeJournalView.setEditable(false);
        writeJournalView.setWrapText(false);
        Tab outputTab = new Tab("Output", output);
        outputTab.setClosable(false);
        Tab detailsTab = new Tab("Test details", suiteDetails);
        detailsTab.setClosable(false);
        Tab writesTab = new Tab("Writes", writeJournalView);
        writesTab.setClosable(false);
        bottomTabs = new TabPane(outputTab, detailsTab, writesTab);
        bottomTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        SplitPane split = new SplitPane(editor, bottomTabs);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.55);

        VBox content = new VBox(8, toolbar, writeSummaryLabel, suiteReport, split, statusLabel);
        content.setPadding(new Insets(12));
        content.setPrefWidth(1180);
        content.setPrefHeight(640);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        getDialogPane().getStylesheets().addAll(stylesheets);
        getDialogPane().setPrefWidth(1220);
        getDialogPane().setMinWidth(820);
        getDialogPane().setPrefHeight(720);
        getDialogPane().setMinHeight(540);

        // Dialog.close() is final, so cleanup goes here: hide fires for a Close
        // press and a programmatic close alike — the arrangement SmartLaunchDialog
        // records in its own comment, for the same reason.
        setOnHidden(event -> {
            writeExpiryCheck.stop();
            writePolicy.revoke();
            coordinator.close();
        });
        setOnCloseRequest(event -> {
            if (dirty() && !confirmDiscard()) {
                event.consume();
            }
        });
    }

    /** Builds the script API objects for one write policy. */
    private ScriptBindings createBindings(WritePolicy writePolicy) {
        return new ScriptBindings(
                new FhirApi(serverService, serverManager, writePolicy),
                new ViewerApi(fhirService),
                new ValidationApi(fhirService),
                new ServerApi(serverService, serverManager),
                new LogApi(), new TestApi(), smartApi, batch);
    }

    /** Applies a write policy and keeps the toolbar and the next run in step. */
    private void applyWrites(boolean enabled) {
        writePolicy.revoke();
        WritePolicy policy = enabled
                ? WritePolicy.enabled(writeConfirmation, serverManager.active().orElse(null),
                        writeJournal, WRITE_BUDGET, this::confirmWriteBudgetOnFxThread,
                        WRITE_IDLE_TIMEOUT, writeScope)
                : WritePolicy.READ_ONLY;
        writePolicy = policy;
        bindings = createBindings(policy);
        writeTarget = enabled ? serverManager.active().orElse(null) : null;
        allowWrites.setSelected(enabled);
        allowSuiteWrites.setDisable(!enabled);
        if (!enabled) {
            allowSuiteWrites.setSelected(false);
        }
        writeExpiryCheck.stop();
        if (enabled) {
            writeExpiryCheck.play();
        }
        updateWriteModeLabel();
        updateJournalActions();
    }

    /** Turns the opt-in on or off, refusing it when there is no writable target. */
    private void onWriteToggle() {
        if (!allowWrites.isSelected()) {
            applyWrites(false);
            setStatus("Server writes off.");
            return;
        }
        FhirServerConfiguration server = serverManager.active().orElse(null);
        if (server == null) {
            applyWrites(false);
            setStatus("Server writes unavailable: select a server in Tools > FHIR Servers.");
            return;
        }
        if (server.writeProtected() || !serverService.supportsWrite(server)) {
            applyWrites(false);
            setStatus(server.writeProtected()
                    ? "Server writes unavailable: this server is marked read-only."
                    : "Server writes unavailable: this server's plugin does not support writes.");
            return;
        }
        if (!confirmWriteAccess(server)) {
            applyWrites(false);
            setStatus("Server writes were not enabled.");
            return;
        }
        applyWrites(true);
        setStatus("Server writes on: " + server.name() + " (" + server.baseUrl() + ").");
    }

    /** True when the current write opt-in still has a writable active server. */
    private boolean ensureWriteTarget() {
        if (!allowWrites.isSelected()) {
            return true;
        }
        FhirServerConfiguration server = serverManager.active().orElse(null);
        if (server != null && server == writeTarget && !server.writeProtected()
                && serverService.supportsWrite(server) && writePolicy.isEnabled()) {
            return true;
        }
        applyWrites(false);
        setStatus("Server writes were turned off: the active server changed, the grant expired, or it can no longer accept writes.");
        return false;
    }

    /** Stops an idle grant even if no Run starts before the deadline. */
    private void checkWriteExpiry() {
        if (allowWrites.isSelected() && !writePolicy.isEnabled()) {
            applyWrites(false);
            setStatus("Server writes turned off after inactivity. Enable them again to continue.");
        }
    }

    private void updateWriteModeLabel() {
        if (!allowWrites.isSelected()) {
            writeModeLabel.setText("Writes: off");
            return;
        }
        String target = writeTarget == null ? "no active server" : writeTarget.name();
        writeModeLabel.setText(writeScope.isEmpty()
                ? "Writes: on / no operations permitted"
                : "Writes: on / " + target);
        writeModeLabel.setTooltip(new Tooltip("Scope: " + writeScope.describe()));
    }

    /** Opens the scope picker; changing it while enabled replaces the live grant. */
    private void onChooseWriteScope() {
        if (coordinator.isRunning()) {
            return;
        }
        chooseWriteScope().ifPresent(scope -> {
            writeScope = scope;
            if (allowWrites.isSelected()) {
                // Replacing the grant revokes the old references and applies the smaller
                // scope immediately; no second warning is needed because the user just
                // chose the scope in a dialog of their own. Re-check the target first, so
                // the new grant cannot be attached to a server that became read-only.
                if (!ensureWriteTarget()) {
                    return;
                }
                applyWrites(true);
            }
            updateWriteModeLabel();
            setStatus("Write scope: " + scope.describe() + ".");
        });
    }

    /** Separated so a UI test can answer the native scope dialog. */
    protected Optional<WriteScope> chooseWriteScope() {
        return WriteScopeDialog.choose(ownerWindow(), writeScope);
    }

    /** The one warning before the opt-in changes. Protected so a UI test can answer it. */
    protected boolean confirmWriteAccess(FhirServerConfiguration server) {
        Window owner = ownerWindow();
        if (owner == null) {
            return false;
        }
        Alert warning = new Alert(Alert.AlertType.WARNING);
        warning.initOwner(owner);
        warning.setTitle("Allow server writes?");
        warning.setHeaderText("Allow scripts in this window to change " + server.name() + "?");
        warning.setContentText("Writes go to:\n" + server.name() + " (" + server.baseUrl() + ")\n\n"
                + "This grant allows: " + writeScope.describe() + ". Deletes ask again before"
                + " they are sent. Scripts are not sandboxed. Undo is best effort and only"
                + " covers writes made in this console session. Only allow this for code you"
                + " trust and data you can change.");
        ButtonType allow = new ButtonType("Allow Writes", ButtonBar.ButtonData.OK_DONE);
        warning.getButtonTypes().setAll(allow, ButtonType.CANCEL);
        warning.getDialogPane().getStylesheets().addAll(stylesheets);
        return warning.showAndWait().filter(allow::equals).isPresent();
    }

    /**
     * A destructive write is confirmed on the JavaFX thread. The script runs on the
     * coordinator's worker, so a direct {@code showAndWait} there would be illegal.
     */
    private boolean confirmWriteOnFxThread(String summary) {
        if (Platform.isFxApplicationThread()) {
            return showDeleteConfirmation(summary);
        }
        FutureTask<Boolean> prompt = new FutureTask<>(() -> showDeleteConfirmation(summary));
        try {
            Platform.runLater(prompt);
        } catch (IllegalStateException toolkitUnavailable) {
            return false;
        }
        try {
            return prompt.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException failed) {
            return false;
        }
    }

    private boolean showDeleteConfirmation(String summary) {
        Window owner = ownerWindow();
        if (owner == null) {
            return false;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle("Delete resource?");
        alert.setHeaderText("Delete this resource from the server?");
        alert.setContentText(summary + "\n\nA best-effort undo may be available in the"
                + " Writes tab while this console stays open, but do not rely on it.");
        // OTHER, not OK_DONE: ButtonBar marks OK_DONE as the default button, which would
        // make Enter delete when the dialog first appears. Cancel is the only default here.
        ButtonType delete = new ButtonType("Delete", ButtonBar.ButtonData.OTHER);
        alert.getButtonTypes().setAll(delete, ButtonType.CANCEL);
        alert.getDialogPane().getStylesheets().addAll(stylesheets);
        // Enter and initial focus land on Cancel: a destructive action must be chosen
        // deliberately, not fired by a stray keypress on a dialog that just appeared.
        Button deleteButton = (Button) alert.getDialogPane().lookupButton(delete);
        Button cancelButton = (Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (deleteButton != null) {
            deleteButton.setDefaultButton(false);
        }
        if (cancelButton != null) {
            alert.setOnShown(event -> {
                if (deleteButton != null) {
                    deleteButton.setDefaultButton(false);
                }
                cancelButton.setDefaultButton(true);
                cancelButton.requestFocus();
            });
        }
        return alert.showAndWait().filter(delete::equals).isPresent();
    }

    /** Reverts the newest successful write whose before-image is still available. */
    private void onUndoLastWrite() {
        if (coordinator.isRunning()) {
            return;
        }
        // Undo is a separate, confirmed recovery write. It still requires the grant to be
        // on and re-checks the target and expiry, but it deliberately ignores the current
        // operation/resource scope so narrowing the scope cannot strand a reversal.
        if (!allowWrites.isSelected()) {
            setStatus("Turn on server writes to undo the last write.");
            return;
        }
        if (!ensureWriteTarget()) {
            return;
        }
        Optional<WriteJournal.Entry> candidate = writeJournal.lastUndoable();
        if (candidate.isEmpty()) {
            setStatus("Nothing to undo: no successful write with a captured before-image.");
            return;
        }
        WriteJournal.Entry entry = candidate.get();
        Optional<WriteJournal.Undo> plan = writeJournal.undoFor(entry);
        if (plan.isEmpty()) {
            setStatus("That write has no undo information.");
            return;
        }
        if (!confirmUndo(entry)) {
            setStatus("Undo cancelled.");
            return;
        }
        setStatus("Undoing " + entry.operation() + " \u2026");
        undoButton.setDisable(true);
        BackgroundTasks.run("scripting-undo", () -> undoWrite(entry, plan.get()), attempt -> {
            if (attempt.succeeded()) {
                writeJournal.markUndone(entry);
                refreshJournalView();
                setStatus(attempt.value());
            } else {
                // Keep an audit trail of a failed revert without copying a server body
                // into the journal; the status line carries the readable reason.
                FhirServerConfiguration failedServer = serverFor(entry);
                if (failedServer != null) {
                    recordUndo(failedServer, plan.get(), WriteJournal.Status.FAILED, "undo-failed");
                }
                setStatus("Undo failed: " + attempt.failure());
            }
            updateJournalActions();
        });
    }

    /** Confirmation seam, protected so a UI test can answer it without a native dialog. */
    protected boolean confirmUndo(WriteJournal.Entry entry) {
        Window owner = ownerWindow();
        if (owner == null) {
            return false;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle("Undo last write?");
        alert.setHeaderText("Revert the latest successful write?");
        alert.setContentText(entry.operation() + " " + entry.resourceType()
                + (entry.resourceId() == null ? "" : "/" + entry.resourceId())
                + " on " + entry.serverName() + ".\n\nThis is a separate, confirmed"
                + " recovery write outside the current write scope. It is best effort, not a"
                + " server transaction rollback, and later changes may be overwritten.");
        ButtonType undo = new ButtonType("Undo", ButtonBar.ButtonData.OTHER);
        alert.getButtonTypes().setAll(undo, ButtonType.CANCEL);
        alert.getDialogPane().getStylesheets().addAll(stylesheets);
        Button undoButton = (Button) alert.getDialogPane().lookupButton(undo);
        Button cancel = (Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (undoButton != null) {
            undoButton.setDefaultButton(false);
        }
        if (cancel != null) {
            alert.setOnShown(event -> {
                if (undoButton != null) {
                    undoButton.setDefaultButton(false);
                }
                cancel.setDefaultButton(true);
                cancel.requestFocus();
            });
        }
        return alert.showAndWait().filter(undo::equals).isPresent();
    }

    private String undoWrite(WriteJournal.Entry entry, WriteJournal.Undo plan)
            throws Exception {
        FhirServerConfiguration server = serverFor(entry);
        if (server == null) {
            throw new IllegalStateException("The server " + entry.serverName()
                    + " is no longer configured.");
        }
        if (server.writeProtected() || !serverService.supportsWrite(server)) {
            throw new IllegalStateException("The server " + server.name()
                    + " does not accept writes, so it cannot be reverted here.");
        }
        if ("create".equals(plan.operation())) {
            if (plan.resourceId() == null) {
                throw new IllegalStateException("The created resource id is unknown.");
            }
            serverService.delete(server, ServerOrigin.of(server.pluginId(), server.baseUrl(),
                    plan.resourceType(), plan.resourceId(), plan.versionId()));
            recordUndo(server, plan, WriteJournal.Status.SUCCEEDED, "");
            return "Undid create of " + plan.resourceType() + "/" + plan.resourceId() + ".";
        }
        IBaseResource before = plan.before();
        if (before == null) {
            throw new IllegalStateException("No before-image was captured for this write.");
        }
        boolean restoring = "delete".equals(plan.operation());
        if (restoring) {
            requireStillAbsent(server, plan);
        }
        // If-Match must name the version the server holds right now, which is the one the
        // reverted write produced. The before-image's own meta.versionId predates that write,
        // so sending it would look stale and a versioning server would refuse the undo with
        // 412 -- failing the recovery path exactly when it is needed. A restore has no version
        // to name at all, so it is conditional the other way round: If-None-Match: * makes the
        // server refuse the write if anything occupies the id by the time it arrives.
        String version = restoring ? null : plan.versionId();
        ServerOrigin origin = ServerOrigin.of(server.pluginId(), server.baseUrl(),
                plan.resourceType(), plan.resourceId(), version);
        serverService.update(server, before, restoring ? origin.requiringAbsence() : origin);
        recordUndo(server, plan, WriteJournal.Status.SUCCEEDED, "");
        return "Restored " + plan.resourceType() + "/" + plan.resourceId() + ".";
    }

    /**
     * Cancels restoring a deleted resource when something occupies its id again.
     *
     * <p>A restore cannot use If-Match: the version the delete removed no longer exists. So the
     * write itself carries {@code If-None-Match: *} (see
     * {@link ServerOrigin#requiringAbsence()}), which is what makes it safe. This read is what
     * lets the answer be readable rather than a bare 412: one that answers means the undo is no
     * longer safe, and a read that fails leaves the same question open. Both cases send nothing.</p>
     */
    private void requireStillAbsent(FhirServerConfiguration server, WriteJournal.Undo plan) {
        IBaseResource existing;
        try {
            existing = serverService.read(server, plan.resourceType(), plan.resourceId());
        } catch (Exception unreadable) {
            throw new IllegalStateException(plan.resourceType() + "/" + plan.resourceId()
                    + " could not be read (" + message(unreadable) + "), so there is no telling"
                    + " whether it was recreated. Nothing was sent.");
        }
        if (existing != null) {
            throw new IllegalStateException(plan.resourceType() + "/" + plan.resourceId()
                    + " exists again, so restoring the before-image would overwrite a resource"
                    + " created after the delete. Nothing was sent.");
        }
    }

    private void recordUndo(FhirServerConfiguration server, WriteJournal.Undo plan,
            WriteJournal.Status status, String detail) {
        writeJournal.record(server, "undo", plan.resourceType(), plan.resourceId(),
                plan.versionId(), status, detail, Duration.ZERO);
    }

    private FhirServerConfiguration serverFor(WriteJournal.Entry entry) {
        // Match both fields: two configured servers may share a base URL but hold different
        // credentials, and an undo must never attach another server's credentials.
        for (FhirServerConfiguration server : serverManager.servers()) {
            if (entry.serverBaseUrl().equals(server.baseUrl())
                    && entry.serverName().equals(server.name())) {
                return server;
            }
        }
        return null;
    }

    /** Reads the resource behind an unknown outcome, then lets the user mark it reviewed. */
    private void onReviewUnknownOutcome() {
        if (coordinator.isRunning()) {
            return;
        }
        Optional<WriteJournal.Entry> candidate = writeJournal.lastUnresolvedUnknown();
        if (candidate.isEmpty()) {
            setStatus("No unreviewed write with an unknown outcome.");
            return;
        }
        WriteJournal.Entry entry = candidate.get();
        setStatus("Reading the resource for the unknown outcome \u2026");
        BackgroundTasks.run("scripting-reconcile", () -> describeUnknownOutcome(entry),
                attempt -> showReconciliation(entry, attempt.succeeded() ? attempt.value()
                        : "Read failed: " + attempt.failure()));
    }

    private String describeUnknownOutcome(WriteJournal.Entry entry) {
        FhirServerConfiguration server = serverFor(entry);
        if (server == null) {
            return "The server " + entry.serverName() + " is no longer configured.";
        }
        if (entry.resourceType() != null && entry.resourceId() == null) {
            return describeByLookup(server, entry);
        }
        if (entry.resourceType() == null || entry.resourceId() == null) {
            return "The request had no resource id to read. Search by a business identifier"
                    + " before retrying; a create may have succeeded.";
        }
        try {
            IBaseResource resource = serverService.read(server, entry.resourceType(),
                    entry.resourceId());
            if (resource == null) {
                return "Read " + entry.resourceType() + "/" + entry.resourceId()
                        + ": not found. The write may not have been applied.";
            }
            IBaseMetaType meta = resource.getMeta();
            String version = meta == null ? null : meta.getVersionId();
            return "Read " + entry.resourceType() + "/" + entry.resourceId()
                    + ": found" + (version == null ? "" : " version " + version)
                    + ". Treat the write as applied.";
        } catch (Exception problem) {
            return "Read " + entry.resourceType() + "/" + entry.resourceId()
                    + " failed: " + message(problem);
        }
    }

    /**
     * Reconciles a create that timed out before the server returned an id.
     *
     * <p>The id of a created resource is the server's to choose, so an unknown create leaves
     * nothing to read and a blind retry can duplicate the record. The identifiers the
     * submitted resource carried are the only stable handle left, so the review searches for
     * them and reports how many resources now match: none means it probably did not land,
     * one means it did, and several mean it may have been applied more than once.</p>
     *
     * <p>Each search starts at the first page and follows the server's own next links up to
     * {@link #RECONCILE_MAX_PAGES} pages, because the match is not always on the first one. The
     * walk is bounded on purpose: this is an answer for a person deciding whether to reuse a
     * resource, not a sweep of everything an identifier matches. A search or page that fails is
     * remembered and the remaining ones are still tried, so one failure cannot decide the
     * verdict.</p>
     */
    private String describeByLookup(FhirServerConfiguration server, WriteJournal.Entry entry) {
        Optional<List<String>> identifiers = writeJournal.identifiersFor(entry);
        if (identifiers.isEmpty()) {
            return "The request had no resource id and no identifier to search by. Search"
                    + " manually before retrying; a create may have succeeded.";
        }
        List<String> searched = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String identifier : identifiers.get()) {
            SearchResultPage page;
            try {
                // An exact criterion, not a raw query: the plugin encodes it, and a raw one
                // would be mangled because a system URL contains the slash that raw() treats
                // as the end of a resource type prefix.
                page = serverService.search(server, new SearchRequest(entry.resourceType(),
                        List.of(new SearchCriterion("identifier", identifier)),
                        RECONCILE_PAGE_SIZE));
            } catch (Exception problem) {
                // One identifier failing says nothing about whether the create landed, and the
                // resource may carry an identifier that can still be searched.
                failures.add(identifier + ": " + message(problem));
                continue;
            }
            List<IBaseResource> seen = new ArrayList<>(page.resources());
            Integer total = page.total();
            for (int pages = 1; page.hasNextPage() && pages < RECONCILE_MAX_PAGES; pages++) {
                try {
                    page = serverService.pageAt(server, page.nextPageToken());
                } catch (Exception problem) {
                    // The match may be on the page that could not be read, so the verdict stays
                    // open instead of treating what was read as the whole result.
                    failures.add(identifier + " page " + (pages + 1) + ": "
                            + message(problem));
                    break;
                }
                if (page.total() != null) {
                    total = page.total();
                }
                seen.addAll(page.resources());
            }
            searched.add(identifier);
            String found = describeMatches(entry.resourceType(), seen, total,
                    page.hasNextPage(), identifier);
            if (found != null) {
                return "Searched " + entry.resourceType() + " by identifier " + identifier
                        + ": " + found + (failures.isEmpty() ? ""
                                : " But " + describedFailures(failures)
                                        + ", so a second copy cannot be ruled out.");
            }
        }
        String listed = String.join(", ", identifiers.get());
        if (searched.isEmpty()) {
            return "Could not search " + entry.resourceType() + " by identifier " + listed
                    + ": " + describedFailures(failures)
                    + ". Search manually before retrying; a create may have succeeded.";
        }
        return "Searched " + entry.resourceType() + " by identifier " + listed
                + (failures.isEmpty()
                        ? ": no match. The create may not have been applied."
                        : ": no match, but " + describedFailures(failures)
                                + ", so the create may still have been applied.");
    }

    /**
     * One line naming what an identifier lookup found, or {@code null} when it found none.
     *
     * <p>A hit only counts when the resource carries the identifier that was searched for. A
     * token search on a bare value matches that value under any system, so another authority's
     * record with the same number would otherwise be reported as this create, and the console
     * would tell the user the write had been applied when it had not.</p>
     */
    private static String describeMatches(String resourceType, List<IBaseResource> seen,
            Integer total, boolean moreToRead, String identifier) {
        List<IBaseResource> matches = new ArrayList<>();
        for (IBaseResource candidate : seen) {
            if (FhirApi.identifiersOf(candidate).contains(identifier)) {
                matches.add(candidate);
            }
        }
        // A page shorter than the server's own total, or one with a next link, has not shown
        // every match: a resource carrying this identifier may be on a page not read. Absence
        // from a truncated page is therefore not absence, and a single match does not rule out
        // a duplicate.
        int read = seen.size();
        boolean truncated = moreToRead || (total != null && total > read);
        if (matches.isEmpty()) {
            if (!truncated) {
                return null;
            }
            String readSoFar = total == null
                    ? "only the first " + read + " matches were read"
                    : "only " + read + " of " + total + " matches were read";
            return readSoFar + " and none of them carried this identifier, so the create may"
                    + " still have been applied. Search manually before retrying.";
        }
        List<String> found = new ArrayList<>();
        for (IBaseResource match : matches) {
            IIdType id = match.getIdElement();
            String version = match.getMeta() == null ? null : match.getMeta().getVersionId();
            found.add((id == null || !id.hasIdPart()
                    ? "(no id)" : resourceType + "/" + id.getIdPart())
                    + (version == null ? "" : " version " + version));
        }
        String more = truncated
                ? " More matches exist than were read"
                        + (total == null ? "" : " (" + read + " of " + total + ")")
                        + ", so a second copy cannot be ruled out."
                : "";
        if (found.size() == 1) {
            return "found " + found.get(0) + ". Treat the create as applied; do not retry it."
                    + more;
        }
        return found.size() + " matches (" + String.join(", ", found) + "). The create may"
                + " have been applied more than once; review each before retrying." + more;
    }

    /** The failed identifier searches, phrased for the review text. */
    private static String describedFailures(List<String> failures) {
        return (failures.size() == 1 ? "one identifier search failed"
                : failures.size() + " identifier searches failed")
                + " (" + String.join("; ", failures) + ")";
    }

    private void showReconciliation(WriteJournal.Entry entry, String readResult) {
        if (confirmReconciliation(entry, readResult)) {
            writeJournal.markResolved(entry);
            refreshJournalView();
            setStatus("Unknown outcome marked reviewed.");
        }
    }

    /** Confirmation seam, protected so a UI test can answer it without a native dialog. */
    protected boolean confirmReconciliation(WriteJournal.Entry entry, String readResult) {
        Window owner = ownerWindow();
        if (owner == null) {
            return false;
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("Unknown write outcome");
        alert.setHeaderText("Review " + entry.operation() + " on " + entry.serverName());
        alert.setContentText(readResult + "\n\nIf it was applied, do not retry it. If it was not,"
                + " check the resource before deciding. Marking reviewed does not change the"
                + " server; it only clears this journal item.");
        ButtonType reviewed = new ButtonType("Mark Reviewed", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(reviewed, ButtonType.CLOSE);
        alert.getDialogPane().getStylesheets().addAll(stylesheets);
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.filter(reviewed::equals).isPresent();
    }

    /** Marshals a budget prompt to the FX thread; scripts call from the worker. */
    private boolean confirmWriteBudgetOnFxThread(int completedWrites, String target) {
        if (Platform.isFxApplicationThread()) {
            return showBudgetConfirmation(completedWrites, target);
        }
        FutureTask<Boolean> prompt = new FutureTask<>(
                () -> showBudgetConfirmation(completedWrites, target));
        try {
            Platform.runLater(prompt);
        } catch (IllegalStateException toolkitUnavailable) {
            return false;
        }
        try {
            return prompt.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException failed) {
            return false;
        }
    }

    private boolean showBudgetConfirmation(int completedWrites, String target) {
        Window owner = ownerWindow();
        if (owner == null) {
            return false;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle("Write budget reached");
        alert.setHeaderText("This run has made " + completedWrites + " writes.");
        alert.setContentText("Continue writing to " + target + "?"
                + " Another batch of " + WRITE_BUDGET + " is allowed if you continue.");
        ButtonType keepWriting = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        ButtonType stop = new ButtonType("Stop Writes", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(keepWriting, stop);
        alert.getDialogPane().getStylesheets().addAll(stylesheets);
        return alert.showAndWait().filter(keepWriting::equals).isPresent();
    }

    private Window ownerWindow() {
        if (getDialogPane() == null || getDialogPane().getScene() == null) {
            return null;
        }
        Window owner = getDialogPane().getScene().getWindow();
        return owner != null && owner.isShowing() ? owner : null;
    }

    /** Supplies the application's guide opener; a no-op opener is the default. */
    public void setGuideOpener(BiConsumer<String, String> opener) {
        guideOpener = opener == null ? (guide, anchor) -> {
        } : opener;
    }

    /** The contextual guide menu, exposed for tests. */
    MenuButton guidesButton() {
        return guidesButton;
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    /** Runs the buffer — after the trust acknowledgement, and on the coordinator's worker. */
    private void onRun() {
        if (coordinator.isRunning()) {
            return;
        }
        if (!ensureWriteTarget()) {
            return;
        }
        if (!ensureTrust()) {
            return;
        }
        // The trust prompt can stay open indefinitely; re-check the grant after it.
        if (!ensureWriteTarget()) {
            return;
        }
        writePolicy.beginRun();
        journalRunStart = writeJournal.size();
        hideWriteSummary();
        bottomTabs.getSelectionModel().select(0);
        String source = editor.getText();
        // A buffer with a file behind it is a saved script: repeatability matters more
        // than continuity, so it runs in its own session and leaves none behind. The
        // unsaved editor stays on the interactive session until Reset.
        boolean savedScript = currentFile != null;
        setBusy(true);
        setStatus(savedScript ? "Running in a fresh session \u2026" : "Running \u2026");
        Consumer<ScriptRunResult> done = result -> Platform.runLater(() -> {
            writePolicy.endRun();
            setBusy(false);
            if (savedScript) {
                appendRaw("[fresh session \u2014 closed after this run]");
            }
            present(result);
        });
        if (savedScript) {
            coordinator.runInFreshSession(source, bindings, done);
        } else {
            coordinator.run(source, bindings, done);
        }
    }

    /**
     * Parses the buffer as a suite and runs every case in its own session.
     *
     * <p>Counts go to the dedicated report label rather than the output pane, so a
     * reader can tell a failed check from what the script printed. Nothing runs
     * without the same trust acknowledgement as an ordinary Run.</p>
     */
    private void onRunTests() {
        if (coordinator.isRunning()) {
            return;
        }
        // A refusal or parse error must not leave the previous run's results looking current.
        clearSuiteResults();
        TestSuite suite;
        try {
            suite = TestSuite.parse(suiteName(), editor.getText());
        } catch (IllegalArgumentException unreadable) {
            showSuiteReport("Could not read tests: " + unreadable.getMessage());
            setStatus("Could not read tests: " + unreadable.getMessage());
            return;
        }
        if (!ensureWriteTarget()) {
            return;
        }
        if (!ensureTrust()) {
            return;
        }
        // The trust prompt can stay open indefinitely; re-check the grant after it.
        if (!ensureWriteTarget()) {
            return;
        }
        writePolicy.beginRun();
        journalRunStart = writeJournal.size();
        hideWriteSummary();
        setBusy(true);
        showSuiteReport("Running " + suite.cases().size() + " test case(s) \u2026");
        setStatus("Running " + suite.cases().size() + " test case(s) \u2026");
        // A suite is isolated from the interactive write opt-in unless the user grants it
        // separately. In particular, turning writes on for Run must not silently let a
        // saved test create or delete fixtures.
        ScriptBindings suiteBindings = allowWrites.isSelected() && allowSuiteWrites.isSelected()
                ? bindings
                : createBindings(WritePolicy.READ_ONLY);
        coordinator.runSuite(suite, suiteBindings, result -> Platform.runLater(() -> {
            writePolicy.endRun();
            setBusy(false);
            presentSuite(result);
        }));
    }

    /** Shows the suite counts and every failure line, apart from console output. */
    private void presentSuite(SuiteRunResult result) {
        StringBuilder report = new StringBuilder();
        if (!result.message().isBlank()) {
            // The run stopped part-way: keep the reason and the counts of what did run.
            report.append(result.message()).append('\n');
        }
        report.append("Tests: ")
                .append(result.passedCount()).append(" passed, ")
                .append(result.failedCount()).append(" failed");
        if (result.stoppedCount() > 0) {
            report.append(", ").append(result.stoppedCount()).append(" stopped");
        }
        // A partial run must not present the executed count as the suite's size.
        if (result.planned() > result.total()) {
            report.append(" (").append(result.total()).append(" of ").append(result.planned())
                    .append(" case(s) ran, ").append(result.elapsed().toMillis()).append(" ms)");
        } else {
            report.append(" (").append(result.total()).append(" case(s), ")
                    .append(result.elapsed().toMillis()).append(" ms)");
        }
        if (result.cancelled()) {
            report.append(" \u2014 stopped");
        }
        if (result.cleanup() != null) {
            report.append("\nCleanup: ").append(result.cleanup().passed() ? "passed"
                    : result.cleanup().stopped() ? "stopped"
                    : "failed: " + result.cleanup().detail());
        }
        for (SuiteCaseResult testCase : result.cases()) {
            if (testCase.stopped()) {
                // Stop is the user's action, not a failing test: name the case without a
                // failure detail, so the counts and the lines below them agree.
                report.append('\n').append("STOPPED ").append(testCase.name());
            } else if (!testCase.passed()) {
                report.append('\n').append("FAILED ").append(testCase.name())
                        .append(": ").append(testCase.detail());
            }
        }
        String text = report.toString();
        showSuiteReport(text);
        presentSuiteDetails(result);
        showWriteSummary();
        setStatus(text.lines().findFirst().orElse(text));
    }

    /**
     * Writes every case's printed output and full failure detail to the Test details tab.
     * The output pane stays untouched, so its contents are only what a run printed.
     */
    private void presentSuiteDetails(SuiteRunResult result) {
        StringBuilder details = new StringBuilder();
        if (!result.message().isBlank()) {
            details.append(result.message()).append('\n');
        }
        if (result.cleanup() != null) {
            appendSuiteCaseDetails(details, result.cleanup());
        }
        for (SuiteCaseResult testCase : result.cases()) {
            appendSuiteCaseDetails(details, testCase);
        }
        suiteDetails.setText(details.toString());
        suiteDetails.positionCaret(0);
        bottomTabs.getSelectionModel().select(1);
    }

    private static void appendSuiteCaseDetails(StringBuilder details,
            SuiteCaseResult testCase) {
        details.append("case: ").append(testCase.name()).append(" \u2014 ")
                .append(testCase.status()).append('\n');
        for (ScriptRunResult.Entry entry : testCase.entries()) {
            details.append("  [").append(label(entry.kind())).append("] ")
                    .append(entry.text()).append('\n');
        }
        details.append('\n');
    }

    private void showSuiteReport(String text) {
        suiteReport.setText(text);
        suiteReport.setManaged(true);
        suiteReport.setVisible(true);
    }

    /** Drops an earlier suite's verdict line until the new run has something to say. */
    private void hideSuiteReport() {
        suiteReport.setText("");
        suiteReport.setManaged(false);
        suiteReport.setVisible(false);
    }

    /**
     * Removes the previous suite's verdict and details, and returns to the output tab.
     * Called when a suite run starts, and whenever the buffer they described is replaced
     * by opening a file or loading the example.
     */
    private void clearSuiteResults() {
        suiteDetails.clear();
        hideSuiteReport();
        hideWriteSummary();
        bottomTabs.getSelectionModel().select(0);
    }

    /** The suite name shown in the report: the file name, or "buffer" when untitled. */
    private String suiteName() {
        return currentFile == null ? "buffer" : currentFile.getFileName().toString();
    }

    /** Asks the running script to stop; the run's own result lands when it ends. */
    private void onStop() {
        coordinator.stop();
        setStatus("Stopping \u2026");
    }

    /** Drops all session state on the worker, then names that in the output. */
    private void onReset() {
        if (coordinator.isRunning()) {
            return;
        }
        setBusy(true);
        setStatus("Resetting the session \u2026");
        coordinator.reset(() -> Platform.runLater(() -> {
            setBusy(false);
            appendRaw("[session reset]");
            setStatus("Session reset — variables, methods and classes"
                    + " from the previous session are gone.");
        }));
    }

    /** Shows one run: every entry as a timed line, then a status that names the outcome. */
    private void present(ScriptRunResult result) {
        for (ScriptRunResult.Entry entry : result.entries()) {
            output.appendText(TIME.format(entry.at()) + " [" + label(entry.kind()) + "] "
                    + entry.text() + "\n");
        }
        output.positionCaret(output.getLength());
        long millis = result.elapsed().toMillis();
        showWriteSummary();
        switch (result.status()) {
            case COMPLETED -> {
                if (result.snippetCount() == 0) {
                    setStatus("Finished in " + millis + " ms \u2014 nothing to execute.");
                } else {
                    setStatus("Finished in " + millis + " ms \u2014 "
                            + result.snippetCount() + " snippet(s), no errors.");
                }
            }
            case COMPLETED_WITH_ERRORS -> setStatus("Finished in " + millis + " ms \u2014 "
                    + result.snippetCount() + " snippet(s), with errors (see the output).");
            case INCOMPLETE -> setStatus("Nothing was executed \u2014 the script is incomplete."
                    + " See the output.");
            case STOPPED -> setStatus("Stopped after " + millis + " ms. Stopping is best"
                    + " effort: a script that swallows interruption can ignore it.");
            case FAILED -> {
                ScriptRunResult.Entry reason = result.first(ScriptRunResult.Kind.ERROR);
                setStatus("Could not run: " + (reason == null ? "unknown reason." : reason.text()));
            }
        }
    }

    private static String label(ScriptRunResult.Kind kind) {
        return switch (kind) {
            case OUTPUT -> "out";
            case VALUE -> "value";
            case DIAGNOSTIC -> "compile";
            case EXCEPTION -> "exception";
            case ERROR -> "error";
        };
    }

    /** Names the session the next Run will use, so a saved buffer cannot look interactive. */
    private void updateRunModeLabel() {
        runModeLabel.setText(currentFile == null
                ? "Run: interactive session"
                : "Run: fresh session (saved script)");
    }

    private void appendRaw(String line) {
        output.appendText(line + "\n");
        output.positionCaret(output.getLength());
    }

    /** Shows what this run wrote, separately from the script's printed output. */
    private void showWriteSummary() {
        List<WriteJournal.Entry> entries = writeJournal.entriesFrom(journalRunStart);
        if (entries.isEmpty()) {
            hideWriteSummary();
            return;
        }
        writeSummaryLabel.setText(WriteJournal.summary(entries));
        writeSummaryLabel.setManaged(true);
        writeSummaryLabel.setVisible(true);
    }

    private void hideWriteSummary() {
        writeSummaryLabel.setText("");
        writeSummaryLabel.setManaged(false);
        writeSummaryLabel.setVisible(false);
    }

    /** Redraws one body-free journal line on the FX thread. */
    private void journalEntryAdded(WriteJournal.Entry entry) {
        if (Platform.isFxApplicationThread()) {
            refreshJournalView();
        } else {
            Platform.runLater(this::refreshJournalView);
        }
    }

    private void refreshJournalView() {
        StringBuilder text = new StringBuilder();
        for (WriteJournal.Entry entry : writeJournal.entries()) {
            String resource = entry.resourceType() == null ? ""
                    : entry.resourceId() == null ? entry.resourceType()
                    : entry.resourceType() + "/" + entry.resourceId();
            String version = entry.versionId() == null ? "" : " version " + entry.versionId();
            String outcome = switch (entry.status()) {
                case SUCCEEDED -> "ok";
                case FAILED -> "failed";
                case UNKNOWN -> "outcome unknown";
                case CANCELLED -> "cancelled";
            };
            String detail = entry.detail().isBlank() ? "" : " — " + entry.detail();
            String flags = (writeJournal.isUndone(entry) ? " [undone]" : "")
                    + (writeJournal.isResolved(entry) ? " [reviewed]" : "");
            text.append(TIME.format(entry.at())).append(" [").append(entry.operation())
                    .append("] ").append(resource).append(version).append(" — ").append(outcome)
                    .append(" (").append(entry.elapsed().toMillis()).append(" ms) — ")
                    .append(entry.serverName()).append(detail).append(flags).append('\n');
        }
        writeJournalView.setText(text.toString());
        writeJournalView.positionCaret(writeJournalView.getLength());
        updateJournalActions();
    }

    private void updateJournalActions() {
        undoButton.setDisable(!allowWrites.isSelected() || writeJournal.lastUndoable().isEmpty());
        reviewUnknownButton.setDisable(writeJournal.lastUnresolvedUnknown().isEmpty());
    }

    /** Clears the journal when the buffer is replaced, so entries match the visible script. */
    private void clearWriteJournal() {
        writeJournal.clear();
        writeJournalView.clear();
        journalRunStart = 0;
        updateJournalActions();
        hideWriteSummary();
    }

    private void setBusy(boolean busy) {
        runButton.setDisable(busy);
        runTestsButton.setDisable(busy);
        allowWrites.setDisable(busy);
        allowSuiteWrites.setDisable(busy || !allowWrites.isSelected());
        scopeButton.setDisable(busy);
        undoButton.setDisable(busy || !allowWrites.isSelected()
                || writeJournal.lastUndoable().isEmpty());
        reviewUnknownButton.setDisable(busy || writeJournal.lastUnresolvedUnknown().isEmpty());
        resetButton.setDisable(busy);
        openButton.setDisable(busy);
        saveButton.setDisable(busy);
        saveAsButton.setDisable(busy);
        exampleButton.setDisable(busy);
        stopButton.setDisable(!busy);
    }

    private void setStatus(String message) {
        statusLabel.setText(message == null || message.isBlank() ? " " : message);
    }

    /** The run-mode label, for tests. */
    Label runModeLabelForTest() {
        return runModeLabel;
    }

    /** The status line's text, for tests. */
    String statusTextForTest() {
        return statusLabel.getText();
    }

    /** The Example button, for tests. */
    Button exampleButtonForTest() {
        return exampleButton;
    }

    /** The write opt-in checkbox, for tests. */
    CheckBox allowWritesForTest() {
        return allowWrites;
    }

    /** The separate test-run write opt-in, for tests. */
    CheckBox allowSuiteWritesForTest() {
        return allowSuiteWrites;
    }

    /** The write-scope button, for tests. */
    Button scopeButtonForTest() {
        return scopeButton;
    }

    /** The undo action, for tests. */
    Button undoButtonForTest() {
        return undoButton;
    }

    /** The unknown-outcome review action, for tests. */
    Button reviewUnknownButtonForTest() {
        return reviewUnknownButton;
    }

    /** The write target label, for tests. */
    Label writeModeLabelForTest() {
        return writeModeLabel;
    }

    /** The Run button, for tests. */
    Button runButtonForTest() {
        return runButton;
    }

    /** The bindings the next run will use, for tests. */
    ScriptBindings bindingsForTest() {
        return bindings;
    }

    /** The Save button, for tests. */
    Button saveButtonForTest() {
        return saveButton;
    }

    /** The Open button, for tests. */
    Button openButtonForTest() {
        return openButton;
    }

    /** The buffer's file, or empty when the buffer is untitled; for tests. */
    Optional<Path> boundFileForTest() {
        return Optional.ofNullable(currentFile);
    }

    /** The editor's current text, for tests. */
    String editorTextForTest() {
        return editor.getText();
    }

    /** Replaces the editor text, for tests. */
    void setEditorTextForTest(String text) {
        editor.setText(text);
    }

    /** The Run Tests button, for tests. */
    Button runTestsButtonForTest() {
        return runTestsButton;
    }

    /** The suite report label, for tests. */
    Label suiteReportForTest() {
        return suiteReport;
    }

    /** The output pane's text, for tests. */
    String outputTextForTest() {
        return output.getText();
    }

    /** The write journal pane's text, for tests. */
    String writeJournalTextForTest() {
        return writeJournalView.getText();
    }

    /** The per-run write summary, for tests. */
    String writeSummaryForTest() {
        return writeSummaryLabel.getText();
    }

    /** The write journal, for tests. */
    WriteJournal writeJournalForTest() {
        return writeJournal;
    }

    /** The current live grant, for tests. */
    WritePolicy writePolicyForTest() {
        return writePolicy;
    }

    /** Runs the idle-expiry check on the FX thread, for tests. */
    void checkWriteExpiryForTest() {
        checkWriteExpiry();
    }

    /** The Test details tab's text, for tests. */
    String suiteDetailsTextForTest() {
        return suiteDetails.getText();
    }

    /** Renders a suite result without running one, for tests. */
    void presentSuiteForTest(SuiteRunResult result) {
        presentSuite(result);
    }



    // ------------------------------------------------------------------
    // Trust
    // ------------------------------------------------------------------

    /**
     * Acknowledges the trust warning only after the user has pressed
     * "Run Script" on it. Nothing executes until this returns true, and the
     * acknowledgement resets every time a file is opened from disk, because a
     * file is an untrusted source arriving from outside.
     */
    private boolean ensureTrust() {
        if (trustAcknowledged) {
            return true;
        }
        Alert warning = new Alert(Alert.AlertType.WARNING);
        warning.initOwner(getDialogPane().getScene().getWindow());
        warning.setTitle("Run this script?");
        warning.setHeaderText("Scripts are not sandboxed");
        String writeState = allowWrites.isSelected()
                ? "Server writes are ON for " + serverManager.active()
                        .map(FhirServerConfiguration::name).orElse("the active server") + ".\n\n"
                : "Server writes are off.\n\n";
        warning.setContentText(writeState
                + "Java scripts run with the same permissions as FHIRViewer itself:"
                + " they can read files, use the network, start processes and inspect the"
                + " environment. Only run scripts you trust — this includes any file you open.\n\n"
                + "FHIRViewer never runs scripts on its own; nothing executes"
                + " until you press Run or Run Tests.");
        ButtonType runAnyway = new ButtonType("Run Script", ButtonBar.ButtonData.OK_DONE);
        warning.getButtonTypes().setAll(runAnyway, ButtonType.CANCEL);
        warning.getDialogPane().getStylesheets().addAll(stylesheets);
        Optional<ButtonType> answer = warning.showAndWait();
        if (answer.isEmpty() || answer.get() != runAnyway) {
            setStatus("Run cancelled — scripts are not sandboxed.");
            return false;
        }
        trustAcknowledged = true;
        return true;
    }


    // ------------------------------------------------------------------
    // Files and example
    // ------------------------------------------------------------------

    /** Opens a `.jsh` file into the editor; the trust acknowledgement resets with the file. */
    private void onOpen() {
        File file = chooseOpenLocation();
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        openFile(file.toPath());
    }

    /**
     * The open-file chooser with the `.jsh` filter preselected. Protected so a UI smoke test
     * can answer it with a temporary file instead of opening a native dialog.
     */
    protected File chooseOpenLocation() {
        FileChooser chooser = new FileChooser();
        chooser.setInitialDirectory(lastDirectory);

        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Java / JShell scripts", "*.java", "*.jsh"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        return chooser.showOpenDialog(getOwner());
    }

    private void openFile(Path path) {
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            setStatus("Could not read " + path.getFileName() + ": "
                    + message(unreadable));
            return;
        }
        if (dirty(editor.getText(), savedContent) && !confirmDiscard()) {
            return;
        }
        editor.setText(content);
        savedContent = content;
        currentFile = path;
        updateRunModeLabel();
        // The verdicts on screen belong to the buffer that was just replaced.
        clearSuiteResults();
        clearWriteJournal();
        // A file arrives from outside: it must not inherit write access either.
        applyWrites(false);
        trustAcknowledged = false;
        setStatus("Opened " + path.getFileName()
                + " — running will ask for trust again; server writes are off.");
    }

    /**
     * Saves the buffer; Save asks where only when there is no file yet, Save As
     * always asks. Returns true only when the buffer reached disk.
     */
    private boolean onSave(boolean saveAs) {
        Path target = (!saveAs && currentFile != null) ? currentFile : chooseSaveLocation();
        if (target == null) {
            return false;
        }
        try {
            Files.writeString(target, editor.getText(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unwritable) {
            setStatus("Could not save: " + message(unwritable));
            return false;
        }
        savedContent = editor.getText();
        currentFile = target;
        updateRunModeLabel();
        setStatus("Saved " + target.getFileName() + ".");
        return true;
    }

    /**
     * The save-file chooser with the `.jsh` filter preselected. Protected so a UI smoke test
     * can answer it with a temporary path instead of opening a native dialog.
     */
    protected Path chooseSaveLocation() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save script");
        chooser.setInitialFileName(currentFile == null ? "untitled.jsh"
                : currentFile.getFileName().toString());
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Java / JShell scripts", "*.java", "*.jsh"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showSaveDialog(getOwner());
        if (file != null) lastDirectory = file.getParentFile();
        return file == null ? null : file.toPath();
    }

    /** Loads the bundled example; confirms first when it would replace unsaved edits. */
    private void onLoadExample() {
        String example = loadExampleScript();
        if (example == null || example.isBlank()) {
            setStatus("The bundled example script could not be read.");
            return;
        }
        if (!editor.getText().isBlank() && !editor.getText().equals(example)) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(getDialogPane().getScene().getWindow());
            confirm.setTitle("Load example");
            confirm.setHeaderText("Replace the current script?");
            confirm.setContentText("The bundled example replaces everything in the editor.");
            confirm.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            confirm.getDialogPane().getStylesheets().addAll(stylesheets);
            Optional<ButtonType> answer = confirm.showAndWait();
            if (answer.isEmpty() || answer.get() != ButtonType.OK) {
                return;
            }
        }
        editor.setText(example);
        savedContent = example;
        // The example is not the file that was open: keep the buffer untitled so Save asks
        // for a location instead of overwriting that file, and Run stays interactive.
        currentFile = null;
        updateRunModeLabel();
        // The verdicts on screen belong to the buffer that was just replaced.
        clearSuiteResults();
        clearWriteJournal();
        applyWrites(false);
        setStatus("Example loaded into an untitled buffer — press Run to execute it.");
    }

    /** The bundled example text, or null when the resource is missing. */
    static String loadExampleScript() {
        try (InputStream in = ScriptingConsoleDialog.class.getResourceAsStream(EXAMPLE_RESOURCE)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Close guard
    // ------------------------------------------------------------------

    /** True when the editor no longer matches what was last saved, opened, or loaded. */
    private boolean dirty() {
        return dirty(editor.getText(), savedContent);
    }

    private static boolean dirty(String current, String saved) {
        return !current.equals(saved);
    }

    /** Asks whether unsaved edits may be discarded; Save writes them first. */
    private boolean confirmDiscard() {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.setTitle("Unsaved script");
        alert.setHeaderText("The script has unsaved changes.");
        alert.setContentText("Save them before closing, or discard them.");
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        alert.getButtonTypes().setAll(save, discard, ButtonType.CANCEL);
        alert.getDialogPane().getStylesheets().addAll(stylesheets);
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return false;
        }
        if (choice.get() == save) {
            return onSave(false);
        }
        return true;
    }

    private static String message(Throwable problem) {
        String text = problem.getMessage();
        return text == null || text.isBlank() ? problem.getClass().getSimpleName() : text;
    }
}
