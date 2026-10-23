package com.spiraleyes.fhirviewer.ui;

import java.io.File;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import com.spiraleyes.fhirviewer.service.FhirService;
import com.spiraleyes.fhirviewer.service.scripting.ScriptRunResult;
import com.spiraleyes.fhirviewer.service.scripting.ScriptingCoordinator;
import com.spiraleyes.fhirviewer.service.scripting.api.FhirApi;
import com.spiraleyes.fhirviewer.service.scripting.api.LogApi;
import com.spiraleyes.fhirviewer.service.scripting.api.ScriptBindings;
import com.spiraleyes.fhirviewer.service.scripting.api.ServerApi;
import com.spiraleyes.fhirviewer.service.scripting.api.ValidationApi;
import com.spiraleyes.fhirviewer.service.scripting.api.ViewerApi;
import com.spiraleyes.fhirviewer.service.scripting.api.WritePolicy;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerService;

/**
 * The Scripting Console: a multiline Java editor over a read-only output
 * console, running the buffer through an embedded JShell.
 *
 * <p>Shaped after {@link RestConsoleDialog} Ã¢â‚¬â€ a workspace {@code Dialog} with a
 * SplitPane of two halves and a status line that never lies Ã¢â‚¬â€ rather than
 * after any modal form: the editor and the output belong on screen together,
 * and Run/Stop/Reset/Open/Save/Save As/Example/Clear Output are the content of
 * the job, not decoration around it. The dialog themes and sizes itself, the
 * way {@link UserGuideDialog} does, instead of going through
 * {@code applyDialogTheme}: that helper pins every dialog it touches to 620px,
 * and a scripting workspace at 620px is a form the panels collide in.</p>
 *
 * <p><b>Scripts are ordinary Java and are not sandboxed.</b> The first Run Ã¢â‚¬â€
 * and the first Run after opening a file, which arrives from outside Ã¢â‚¬â€ asks
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
    private final Label statusLabel = new Label();
    private final Button runButton = new Button("Run");
    private final Button stopButton = new Button("Stop");
    private final Button resetButton = new Button("Reset Session");
    private final Button openButton = new Button("Open\u2026");
    private final Button saveButton = new Button("Save");
    private final Button saveAsButton = new Button("Save As\u2026");
    private final Button exampleButton = new Button("Example");
    private final Button clearButton = new Button("Clear Output");

    private final ScriptingCoordinator coordinator = new ScriptingCoordinator();
    private final List<String> stylesheets;

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
        if (themeManager == null) {
            throw new IllegalArgumentException("A theme manager is required.");
        }
        if (fhirService == null || serverService == null || serverManager == null) {
            throw new IllegalArgumentException("The scripting services are required.");
        }

        bindings = new ScriptBindings(
                new FhirApi(serverService, serverManager, WritePolicy.READ_ONLY),
                new ViewerApi(fhirService),
                new ValidationApi(fhirService),
                new ServerApi(serverService, serverManager),
                new LogApi());

        this.stylesheets = List.copyOf(themeManager.stylesheets());

        setTitle("Scripting Console");
        setHeaderText("Java snippets, powered by the embedded JShell. "
                + "Scripts run with your user's permissions — this is not a sandbox.");
        setResizable(true);

        statusLabel.getStyleClass().add("app-subtitle");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        setStatus("Ready. Write a script, then press Run.");
        HBox toolbar = new HBox(8, runButton, stopButton, resetButton, 
                new Separator(Orientation.VERTICAL), openButton, saveButton, saveAsButton, 
                new Separator(Orientation.VERTICAL), exampleButton, 
                new Separator(Orientation.VERTICAL), clearButton);
        toolbar.setPadding(new Insets(0, 0, 4, 0));
        SplitPane split = new SplitPane(editor, output);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.55);

        VBox content = new VBox(8, toolbar, split, statusLabel);
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
        // press and a programmatic close alike Ã¢â‚¬â€ the arrangement SmartLaunchDialog
        // records in its own comment, for the same reason.
        setOnHidden(event -> coordinator.close());
        setOnCloseRequest(event -> {
            if (dirty() && !confirmDiscard()) {
                event.consume();
            }
        });
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    /** Runs the buffer Ã¢â‚¬â€ after the trust acknowledgement, and on the coordinator's worker. */
    private void onRun() {
        if (coordinator.isRunning()) {
            return;
        }
        if (!ensureTrust()) {
            return;
        }
        String source = editor.getText();
        setBusy(true);
        setStatus("Running \u2026");
        coordinator.run(source, bindings, result -> Platform.runLater(() -> {
            setBusy(false);
            present(result);
        }));
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
            setStatus("Session reset Ã¢â‚¬â€ variables, methods and classes"
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

    private void appendRaw(String line) {
        output.appendText(line + "\n");
        output.positionCaret(output.getLength());
    }

    private void setBusy(boolean busy) {
        runButton.setDisable(busy);
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
                + " environment. Only run scripts you trust Ã¢â‚¬â€ this includes any file you open.\n\n"
                + "FHIRViewer never runs scripts on its own; nothing executes"
                + " until you press Run.");
        ButtonType runAnyway = new ButtonType("Run Script", ButtonBar.ButtonData.OK_DONE);
        warning.getButtonTypes().setAll(runAnyway, ButtonType.CANCEL);
        warning.getDialogPane().getStylesheets().addAll(stylesheets);
        Optional<ButtonType> answer = warning.showAndWait();
        if (answer.isEmpty() || answer.get() != runAnyway) {
            setStatus("Run cancelled Ã¢â‚¬â€ scripts are not sandboxed.");
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
        chooser.setTitle("Open script");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("JShell scripts", "*.jsh"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(getOwner() != null ? getOwner() : getDialogPane().getScene().getWindow());
        if (file == null) {
            return;
        }
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
        trustAcknowledged = false;
        setStatus("Opened " + file.getName() + " Ã¢â‚¬â€ running will ask for trust again.");
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
        setStatus("Saved " + target.getFileName() + ".");
        return true;
    }

    /** The save-file chooser with the `.jsh` filter preselected. */
    private Path chooseSaveLocation() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save script");
        chooser.setInitialFileName(currentFile == null ? "untitled.jsh"
                : currentFile.getFileName().toString());
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("JShell scripts", "*.jsh"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showSaveDialog(getOwner());
        return file == null ? null : file.toPath();
    }

    /** Loads the bundled example; confirms first when it would replace unsaved edits. */
    private void onLoadExample() {
        String example = loadExampleScript();
        if (example == null || example.isBlank()) {
            System.out.println("EXAMPLE RESOURCE: " + (ScriptingConsoleDialog.class.getResource(EXAMPLE_RESOURCE) != null ? "found" : "NOT FOUND"));
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
        setStatus("Example loaded Ã¢â‚¬â€ press Run to execute it.");
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
