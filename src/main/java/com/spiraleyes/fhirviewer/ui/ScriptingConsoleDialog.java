package com.spiraleyes.fhirviewer.ui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
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

import com.spiraleyes.fhirviewer.service.FhirService;
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
import com.spiraleyes.fhirviewer.service.scripting.api.WritePolicy;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerService;
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

    /** The bundled example the Example button loads; the session tests run it directly. */
    static final String EXAMPLE_RESOURCE = "/scripts/scripting-example.jsh";

    private final TextArea editor = new TextArea();
    private final TextArea output = new TextArea();
    private final TextArea suiteDetails = new TextArea();
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
    private final MenuButton guidesButton = new MenuButton("Guides");

    /** Opens a named guide at an anchor; the server screens supply the real opener. */
    private BiConsumer<String, String> guideOpener = (guide, anchor) -> {
    };

    private final ScriptingCoordinator coordinator;
    private final List<String> stylesheets;

    private static File lastDirectory;
    private Path currentFile;

    private String savedContent = "";
    private boolean trustAcknowledged;

    /** The script API objects exposed to every run, built from the application services. */
    private final ScriptBindings bindings;

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
        SmartApi smartApi = smartSessions == null ? new SmartApi() : smartSessions.api();
        bindings = new ScriptBindings(
                new FhirApi(serverService, serverManager, WritePolicy.READ_ONLY),
                new ViewerApi(fhirService),
                new ValidationApi(fhirService),
                new ServerApi(serverService, serverManager),
                new LogApi(), new TestApi(), smartApi);

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
        runTestsButton.setTooltip(new Tooltip("Runs each // case: block as a test in a session"
                + " of its own; counts appear above the panes and each case's output goes"
                + " to the Test details tab."));
        suiteReport.getStyleClass().add("app-subtitle");
        suiteReport.setWrapText(true);
        suiteReport.setMaxWidth(Double.MAX_VALUE);
        suiteReport.setManaged(false);
        suiteReport.setVisible(false);
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
                stopButton, resetButton, openButton, saveButton, saveAsButton, exampleButton,
                clearButton, guidesButton);
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
        Tab outputTab = new Tab("Output", output);
        outputTab.setClosable(false);
        Tab detailsTab = new Tab("Test details", suiteDetails);
        detailsTab.setClosable(false);
        bottomTabs = new TabPane(outputTab, detailsTab);
        bottomTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        SplitPane split = new SplitPane(editor, bottomTabs);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.55);

        VBox content = new VBox(8, toolbar, suiteReport, split, statusLabel);
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
        setOnHidden(event -> coordinator.close());
        setOnCloseRequest(event -> {
            if (dirty() && !confirmDiscard()) {
                event.consume();
            }
        });
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
        if (!ensureTrust()) {
            return;
        }
        bottomTabs.getSelectionModel().select(0);
        String source = editor.getText();
        // A buffer with a file behind it is a saved script: repeatability matters more
        // than continuity, so it runs in its own session and leaves none behind. The
        // unsaved editor stays on the interactive session until Reset.
        boolean savedScript = currentFile != null;
        setBusy(true);
        setStatus(savedScript ? "Running in a fresh session \u2026" : "Running \u2026");
        Consumer<ScriptRunResult> done = result -> Platform.runLater(() -> {
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
        if (!ensureTrust()) {
            return;
        }
        setBusy(true);
        showSuiteReport("Running " + suite.cases().size() + " test case(s) \u2026");
        setStatus("Running " + suite.cases().size() + " test case(s) \u2026");
        coordinator.runSuite(suite, bindings, result -> Platform.runLater(() -> {
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
        for (SuiteCaseResult testCase : result.cases()) {
            details.append("case: ").append(testCase.name()).append(" \u2014 ")
                    .append(testCase.status()).append('\n');
            for (ScriptRunResult.Entry entry : testCase.entries()) {
                details.append("  [").append(label(entry.kind())).append("] ")
                        .append(entry.text()).append('\n');
            }
            details.append('\n');
        }
        suiteDetails.setText(details.toString());
        suiteDetails.positionCaret(0);
        bottomTabs.getSelectionModel().select(1);
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

    private void setBusy(boolean busy) {
        runButton.setDisable(busy);
        runTestsButton.setDisable(busy);
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

    /** The Example button, for tests. */
    Button exampleButtonForTest() {
        return exampleButton;
    }

    /** The Save button, for tests. */
    Button saveButtonForTest() {
        return saveButton;
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
        warning.setContentText("Java scripts run with the same permissions as FHIRViewer itself:"
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
        FileChooser chooser = new FileChooser();
        chooser.setInitialDirectory(lastDirectory);

        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Java / JShell scripts", "*.java", "*.jsh"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(getOwner());
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        String content;
        try {
            content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            setStatus("Could not read " + file.getName() + ": "
                    + message(unreadable));
            return;
        }
        if (dirty(editor.getText(), savedContent) && !confirmDiscard()) {
            return;
        }
        editor.setText(content);
        savedContent = content;
        currentFile = file.toPath();
        updateRunModeLabel();
        // The verdicts on screen belong to the buffer that was just replaced.
        clearSuiteResults();
        trustAcknowledged = false;
        setStatus("Opened " + file.getName() + " — running will ask for trust again.");
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
