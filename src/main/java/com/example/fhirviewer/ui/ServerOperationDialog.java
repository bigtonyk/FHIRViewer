package com.example.fhirviewer.ui;

import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.FhirServerManager;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.ServerOperation;
import com.example.fhirviewer.server.ServerOperationException;
import com.example.fhirviewer.server.ServerOperationResult;

/**
 * The generic "run an operation this server plugin offers" screen.
 *
 * <p>This dialog contains no operation names. Everything in it comes from
 * {@link FhirServerService#supportedOperations}: a plugin that is not in this codebase, is
 * not a subclass of anything here and has never been heard of declares an endpoint and it
 * appears in this list, with a form generated from the parameters it declared. That is the
 * property the plan asks for at the end of Phase 6, and it is the reason there is no
 * {@code if (plugin instanceof SmilePlugin)} anywhere in the application.</p>
 *
 * <p>Discovery asks {@code supportedOperations} rather than {@code availableOperations}
 * because a plugin narrows the first list by what the connected server can actually do and
 * by whether the session has credentials. An operation the session cannot run is therefore
 * absent rather than present-and-refusing, which is what the plan means by unsupported
 * operations being absent or disabled.</p>
 *
 * <p>Results are classified by {@link ServerOperationResults} and, when the answer was a
 * FHIR resource, handed back to the main window's existing viewer. The dialog returns that
 * resource rather than opening anything itself, so there is still one rendering path.</p>
 */
public class ServerOperationDialog extends Dialog<ServerOperationDialog.Outcome> {

    /**
     * What the caller gets when the user opens a FHIR answer in the viewer.
     *
     * @param resource the FHIR resource the operation returned
     * @param label    a short name for it, including the server it came from
     */
    public record Outcome(IBaseResource resource, String label) {
    }

    private final FhirServerService serverService;
    private final FhirServerManager serverManager;
    /** The resource already open in the editor, offered as the body when it fits. */
    private final org.hl7.fhir.instance.model.api.IBaseResource displayedResource;

    private final ComboBox<FhirServerConfiguration> serverBox = new ComboBox<>();
    private final ListView<ServerOperation> operationList = new ListView<>();
    private final VBox parameterPane = new VBox(8);
    private final TextArea bodyArea = new TextArea();
    private final Label bodyLabel = new Label("Request body");

    /**
     * What the body is called, and the values a body of that kind usually takes.
     *
     * <p>The plan's point: the screen had a free-text body and no notion of what the body
     * <em>is</em>. For {@code $validate} it is a resource, for {@code $graphql} a query, for
     * Firely's {@code $import} a set of parameters - and the form could say none of that, so
     * every one of them looked like the same undifferentiated text box.</p>
     *
     * <p>A dropdown of the values the specification defines, which is a starting point rather
     * than a limit: it is editable, so a value not on the list can still be typed.</p>
     */
    private final ComboBox<String> bodyNameBox = new ComboBox<>();
    private final Label bodyValueLabel = new Label("Value");

    /**
     * The content type to send the body as.
     *
     * <p>Without this the body always went as the operation declared, which is JSON for most
     * of them - so an operation that accepts XML could not be sent XML at all, and the only
     * way to change it was to edit the declaration in a plugin.</p>
     */
    private final ComboBox<String> contentTypeBox = new ComboBox<>();
    private final Label statusLabel = new Label(" ");
    private final Label resultLabel = new Label(" ");
    private final TextArea resultArea = new TextArea();
    private final ProgressIndicator progress = new ProgressIndicator(18);
    private final Button cancelRunButton = new Button("Cancel");
    private final Button runButton = new Button("Run");
    /** Run / Cancel / spinner; re-added to the parameter pane after the generated fields. */
    private final HBox actions = new HBox(8);

    private ServerOperationForm form;
    private BackgroundTasks.Running running;
    private ServerOperationResult lastResult;
    private Button openButton;

    public ServerOperationDialog(FhirServerService serverService, FhirServerManager serverManager,
            FhirServerConfiguration preselect, ThemeManager themeManager) {
        this(serverService, serverManager, preselect, themeManager, null);
    }

    /**
     * Creates the dialog, offering the resource already open as the body when it fits.
     *
     * <p>That resource is the overwhelmingly common body for the operations that take one:
     * {@code $validate} on what you are looking at, {@code $convert} on it, a patch built from
     * it. Making the user find and copy it out of the editor is the difference between a
     * screen you use and one you do not.</p>
     */
    public ServerOperationDialog(FhirServerService serverService, FhirServerManager serverManager,
            FhirServerConfiguration preselect, ThemeManager themeManager,
            org.hl7.fhir.instance.model.api.IBaseResource displayedResource) {
        this.displayedResource = displayedResource;
        this.serverService = serverService;
        this.serverManager = serverManager;

        setTitle("Run a server operation");
        setHeaderText("Operations offered by the server's plugin");
        setResizable(true);

        serverBox.getItems().setAll(serverManager.servers());
        if (preselect != null && serverBox.getItems().contains(preselect)) {
            serverBox.getSelectionModel().select(preselect);
        } else {
            serverBox.getSelectionModel().selectFirst();
        }
        serverBox.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(FhirServerConfiguration item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + " — " + item.baseUrl());
            }
        });
        serverBox.setOnAction(event -> loadOperations());

        operationList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ServerOperation item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(item.displayName());
                // Wrapped rather than truncated. The operation names are sentences - "The
                // Patient everything-operation", "Bulk export" - and a ListView cell clips
                // at its edge, so a wider window alone still left "The Patient everything-
                // operat...". Wrapping shows the whole name; the row grows to fit.
                setWrapText(true);
                setTooltip(new Tooltip(item.id() + " — " + item.description()));
            }
        });
        // Wide enough for the longest declared name without wrapping in the common case, and
        // the dialog is sized to fit this rather than the other way round.
        operationList.setPrefWidth(360);
        operationList.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> showOperation(selected));

        statusLabel.getStyleClass().add("app-subtitle");
        statusLabel.setWrapText(true);
        resultLabel.getStyleClass().add("app-subtitle");
        resultLabel.setWrapText(true);
        resultArea.setEditable(false);
        resultArea.setWrapText(true);
        resultArea.getStyleClass().addAll("mono-text", "code-area");
        resultArea.setPrefRowCount(8);

        ButtonType openType = new ButtonType("Open in viewer", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(openType, ButtonType.CLOSE);
        getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        getDialogPane().setPrefWidth(1040);
        getDialogPane().setMinWidth(760);
        getDialogPane().setPrefHeight(680);
        openButton = (Button) getDialogPane().lookupButton(openType);
        if (openButton != null) {
            openButton.getStyleClass().add("button-primary");
            openButton.setDisable(true);
            // Returns the FHIR answer so the main window can display it with the existing
            // views. The dialog never renders a resource itself.
            openButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                Outcome outcome = outcome();
                if (outcome == null) {
                    event.consume();
                    return;
                }
                setResult(outcome);
                close();
            });
        }

        HBox layout = new HBox(12, operationList, buildParameterPane());
        layout.setPadding(new Insets(12));
        layout.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        HBox.setHgrow(layout, Priority.ALWAYS);

        // The server selector, which was built, populated, wired to reload the operation
        // list and read in three places - and never added to any layout. So it worked
        // perfectly and could not be seen: the server was fixed at whatever the main window
        // had preselected, and the reported symptom was "no way to select the server".
        // A control that is not in the scene graph is not a control.
        Label serverLabel = new Label("Server");
        serverLabel.getStyleClass().add("pretty-row-label");
        HBox serverRow = new HBox(10, serverLabel, serverBox);
        HBox.setHgrow(serverBox, Priority.ALWAYS);
        serverBox.setMaxWidth(Double.MAX_VALUE);

        VBox content = new VBox(10, serverRow, layout, statusLabel, resultLabel, resultArea);
        content.setPadding(new Insets(0, 12, 12, 12));
        VBox.setVgrow(content, Priority.ALWAYS);
        getDialogPane().setContent(content);

        // Work still in flight is abandoned rather than left to call back into a dialog
        // that is closing.
        setOnCloseRequest(event -> cancelRunning());

        loadOperations();
    }

    /** The right-hand pane: the form generated from the selected operation's descriptor. */
    private javafx.scene.Node buildParameterPane() {
        parameterPane.setPrefWidth(520);
        parameterPane.setMaxWidth(Double.MAX_VALUE);
        parameterPane.getStyleClass().add("card");

        bodyArea.setWrapText(false);
        bodyArea.getStyleClass().addAll("mono-text", "code-area");
        bodyArea.setPrefRowCount(6);

        runButton.getStyleClass().add("button-primary");
        runButton.setDisable(true);
        runButton.setOnAction(event -> run());

        cancelRunButton.getStyleClass().add("button-ghost");
        cancelRunButton.setDisable(true);
        cancelRunButton.setTooltip(new Tooltip(
                "Stop waiting for the server. The request may still reach it."));
        cancelRunButton.setOnAction(event -> cancelRunning());

        progress.setVisible(false);
        actions.getChildren().addAll(runButton, cancelRunButton, progress);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return parameterPane;
    }

    /**
     * Asks the active plugin which operations it offers, and lists them.
     *
     * <p>Discovery is a network call, because {@code supportedOperations} may consult the
     * connected server. It therefore runs through {@link BackgroundTasks} like everything
     * else, and the list is cleared first so a stale operation from the previously selected
     * server is never one click away from being run against the new one.</p>
     */
    private void loadOperations() {
        FhirServerConfiguration server = serverBox.getValue();
        if (server == null) {
            operationList.getItems().clear();
            return;
        }
        setBusy(true, "Asking " + server.name() + " which operations it offers ...");
        // Unchecked on purpose: a plugin narrows its list from configuration and session
        // alone, and BackgroundTasks.run turns any throwable into a readable failure
        // without a catch block that could only re-wrap it.
        running = BackgroundTasks.run("fhir-server-discovery",
                () -> serverService.supportedOperations(server), attempt -> {
                    setBusy(false, null);
                    if (!attempt.succeeded()) {
                        operationList.getItems().clear();
                        showStatus(attempt.failure(), true);
                        return;
                    }
                    List<ServerOperation> operations = attempt.value();
                    operationList.getItems().setAll(operations);
                    showStatus(operations.isEmpty()
                            ? noOperationsMessage(server)
                            : operations.size() + " operation(s) available on " + server.name() + ".",
                            false);
                });
    }

    /**
     * What to say when a plugin offers nothing to run.
     *
     * <p>An empty list is the interface's default, so it means "this plugin has not
     * declared any", not "something went wrong". Saying so plainly matters because the
     * alternative reads like a fault in the user's own setup: the screen works, the list
     * really is empty, and a user who cannot tell the difference goes looking for a
     * misconfiguration that is not there.
     *
     * <p>The two reasons a list can be empty are separated because they call for different
     * answers. A plugin that declares nothing is a limitation to know about; a plugin
     * that declared things this session cannot run is something to fix by unlocking
     * credentials, and saying "declares nothing" there would send the user to the wrong
     * place.</p>
     */
    private String noOperationsMessage(FhirServerConfiguration server) {
        int declared = serverService.availableOperations(server).size();
        if (declared > 0) {
            return server.name() + " declares " + declared + " operation(s), but none can run"
                    + " on this connection. Operations needing credentials are hidden until"
                    + " they are unlocked in Tools > Server Plugins...";
        }
        return "The " + server.pluginId() + " plugin declares no operations."
                + " Reading, searching and writing resources are unaffected; this screen"
                + " covers the extra endpoints a vendor adds to FHIR REST.";
    }

    /**
     * Rebuilds the form for the selected operation.
     *
     * <p>The previous values are deliberately not carried over: an operation's parameters
     * mean nothing to the next one, and a stale resource id silently applied to a different
     * endpoint is the kind of mistake a generated form should make impossible.</p>
     */
    private void showOperation(ServerOperation operation) {
        parameterPane.getChildren().clear();
        if (operation == null) {
            runButton.setDisable(true);
            return;
        }
        form = new ServerOperationForm(operation);
        Label heading = new Label(operation.displayName());
        heading.getStyleClass().add("pretty-section-title");
        heading.setWrapText(true);
        Label detail = new Label(operation.method() + " " + operation.pathTemplate()
                + (operation.description().isBlank() ? "" : " — " + operation.description()));
        detail.getStyleClass().add("app-subtitle");
        detail.setWrapText(true);
        parameterPane.getChildren().addAll(heading, detail);
        for (com.example.fhirviewer.server.ServerOperationParameter parameter
                : operation.parameters()) {
            parameterPane.getChildren().add(fieldFor(parameter));
        }
        if (form.acceptsBody()) {
            bodyLabel.setText(form.bodyHint());
            bodyLabel.getStyleClass().add("pretty-row-label");
            bodyArea.clear();
            // What the body is, what it may contain, and how it is sent. Three things the
            // screen previously could not say, so every body looked like the same text box.
            bodyNameBox.setEditable(true);
            bodyNameBox.getItems().setAll(bodyNamesFor(operation));
            bodyNameBox.getSelectionModel().selectFirst();
            bodyNameBox.getStyleClass().add("pretty-row-label");
            bodyValueLabel.setText("Value");
            bodyValueLabel.getStyleClass().add("pretty-row-label");
            contentTypeBox.setEditable(true);
            contentTypeBox.getItems().setAll(
                    operation.acceptedBodyTypes().isEmpty()
                            ? List.of("application/fhir+json")
                            : operation.acceptedBodyTypes());
            contentTypeBox.getSelectionModel().selectFirst();
            contentTypeBox.getStyleClass().add("pretty-row-label");

            GridPane bodyGrid = new GridPane();
            bodyGrid.setHgap(8);
            bodyGrid.setVgap(6);
            bodyGrid.add(new Label("Body is"), 0, 0);
            bodyGrid.add(bodyNameBox, 1, 0);
            bodyGrid.add(new Label("Sent as"), 0, 1);
            bodyGrid.add(contentTypeBox, 1, 1);
            ColumnConstraints grow = new ColumnConstraints();
            grow.setHgrow(javafx.scene.layout.Priority.ALWAYS);
            bodyGrid.getColumnConstraints().addAll(new ColumnConstraints(), grow);
            bodyGrid.setMaxWidth(Double.MAX_VALUE);

            parameterPane.getChildren().addAll(bodyLabel, bodyGrid, bodyValueLabel, bodyArea);
            // Whatever the body is, it is most often the resource already open in the
            // editor. That is the difference between "a form that can run an operation" and
            // "a screen you can actually run $validate on".
            prefillFromDisplayed(operation);
        }
        runButton.setDisable(false);
        // The action row is re-added last so it stays below the fields however many
        // parameters the operation declared.
        parameterPane.getChildren().addAll(actions);
        clearResult();
    }

    /**
     * What this operation's body may be called.
     *
     * <p>The specification gives a name to the body of some operations and not others, so the
     * list is derived from the operation's own id rather than hard-coded per plugin - the
     * vendor plugins get the same treatment without knowing about any of this.</p>
     */
    private static List<String> bodyNamesFor(
            com.example.fhirviewer.server.ServerOperation operation) {
        String id = operation.id();
        if (id.contains("$validate") || id.contains("$convert")
                || id.contains("$transaction") || id.contains("$patch")) {
            return List.of("Resource", "Profile");
        }
        if (id.contains("$graphql")) {
            return List.of("query", "variables", "operationName");
        }
        if (id.contains("import")) {
            return List.of("inputResources", "inputEncoding", "createNewResources",
                    "nameToUpdate", "deleteNameNotInBundle");
        }
        return List.of("resource");
    }

    /**
     * Offers the open resource as the body, when that is what the operation is asking for.
     *
     * <p>Only when the body is a resource and one is open. Silently pre-filling a body the
     * operation did not ask for would put a Patient into a GraphQL query, which is worse than
     * an empty box.</p>
     */
    private void prefillFromDisplayed(com.example.fhirviewer.server.ServerOperation operation) {
        if (displayedResource == null || !bodyNamesFor(operation).contains("resource")) {
            return;
        }
        String text;
        try {
            text = com.example.fhirviewer.fhir.FhirContextFactory.r4()
                    .newJsonParser().encodeResourceToString(displayedResource);
        } catch (RuntimeException problem) {
            // A resource the parser will not re-read is a reason to leave the box empty, not
            // to fail opening the screen.
            return;
        }
        bodyArea.setText(text);
        String contentType = contentTypeBox.getValue();
        if (contentType != null && contentType.endsWith("+xml")) {
            try {
                bodyArea.setText(com.example.fhirviewer.fhir.FhirContextFactory.r4()
                        .newXmlParser().encodeResourceToString(displayedResource));
            } catch (RuntimeException problem) {
                // Keep the JSON that did work rather than emptying the box.
            }
        }
        showStatus("Filled in with the resource open in the editor. Edit it, or clear it.",
                false);
    }

    /** One labelled input for a declared parameter. */
    private javafx.scene.Node fieldFor(
            com.example.fhirviewer.server.ServerOperationParameter parameter) {
        Label label = new Label(ServerOperationForm.label(parameter));
        label.getStyleClass().add("pretty-row-label");
        if (!parameter.description().isBlank()) {
            label.setTooltip(new Tooltip(parameter.description()));
        }
        TextArea input = new TextArea();
        input.setPrefRowCount(1);
        input.setWrapText(false);
        input.getStyleClass().addAll("mono-text", "code-area");
        input.setMaxWidth(Double.MAX_VALUE);
        // Read straight into the form, so what is shown and what will be sent are the
        // same values with no separate copy that could drift.
        input.textProperty().addListener((observable, previous, text) ->
                form.set(parameter.name(), text == null ? "" : text));
        return new VBox(2, label, input);
    }

    /**
     * Runs the selected operation on a background thread.
     *
     * <p>The form is validated first, on the JavaFX thread, so a missing field is reported
     * without a request being attempted. Only then is the call made, and only from a
     * background thread — the plan's rule that no network work happens on the UI thread.</p>
     */
    private void run() {
        FhirServerConfiguration server = serverBox.getValue();
        if (server == null || form == null) {
            return;
        }
        com.example.fhirviewer.server.ServerOperationInvocation invocation;
        try {
            invocation = form.toInvocation(form.acceptsBody() ? bodyArea.getText() : null,
                    form.acceptsBody() ? contentTypeBox.getValue() : null);
        } catch (IllegalStateException e) {
            showStatus(e.getMessage(), true);
            return;
        }
        clearResult();
        setBusy(true, "Running " + form.operation().displayName() + " on " + server.name() + " ...");
        runAttempt(() -> {
            try {
                return BackgroundTasks.Attempt.succeeded(
                        serverService.executeOperation(server, invocation));
            } catch (ServerOperationException e) {
                return BackgroundTasks.Attempt.failed(e.displayMessage());
            }
        }, attempt -> {
            setBusy(false, null);
            if (attempt.cancelled()) {
                showStatus("The operation was cancelled.", true);
                return;
            }
            if (!attempt.succeeded()) {
                showStatus(attempt.failure(), true);
                return;
            }
            showResult(attempt.value());
        });
    }

    /**
     * Shows what came back, in the pane its kind calls for.
     *
     * <p>A FHIR answer additionally enables "Open in viewer", which hands the resource back
     * to the main window. That is the one rendering path in the application, and routing an
     * operation's answer through it is what stops a second viewer appearing.</p>
     */
    private void showResult(ServerOperationResult result) {
        lastResult = result;
        ServerOperationResults.Presentation presentation = ServerOperationResults.classify(result);
        resultLabel.getStyleClass().remove("status-error");
        resultLabel.setText(ServerOperationResults.summary(result));
        resultArea.setText(textFor(presentation));
        if (openButton != null) {
            openButton.setDisable(presentation.resource() == null);
        }
        showStatus(presentation.issues().isEmpty()
                ? "The server answered."
                : "The server reported " + presentation.issues().size() + " issue(s).", false);
    }

    /** The body to show for a presentation, falling back to the raw body. */
    private String textFor(ServerOperationResults.Presentation presentation) {
        if (!presentation.issues().isEmpty()) {
            return String.join("\n", ServerOperationResults.diagnostics(lastResult));
        }
        if (presentation.kind() == ServerOperationResults.Kind.FHIR_RESOURCE
                || presentation.kind() == ServerOperationResults.Kind.BUNDLE
                || presentation.kind() == ServerOperationResults.Kind.OPERATION_OUTCOME) {
            return presentation.resource() == null
                    ? presentation.bodyOrEmpty()
                    : describeResource(presentation.resource());
        }
        if (presentation.bodyOrEmpty().isBlank()) {
            return "The server sent no body.";
        }
        return presentation.bodyOrEmpty();
    }

    /** A short, useful line about a FHIR answer, for the read-only result pane. */
    private String describeResource(IBaseResource resource) {
        String id = resource.getIdElement() == null || !resource.getIdElement().hasIdPart()
                ? null
                : resource.getIdElement().getIdPart();
        String label = resource.fhirType() + (id == null ? "" : "/" + id);
        return label + "\n\nUse \"Open in viewer\" to browse it in the resource tree.";
    }

    /**
     * Enables or disables the actions while a call is in flight.
     *
     * <p>Re-entrancy is the reason the buttons are disabled rather than only decorated: a
     * second click would race the first request and, on a write operation, send it twice.</p>
     */
    private void setBusy(boolean busy, String message) {
        runButton.setDisable(busy || form == null);
        cancelRunButton.setDisable(!busy);
        operationList.setDisable(busy);
        progress.setVisible(busy);
        if (openButton != null) {
            openButton.setDisable(busy || lastResult == null
                    || ServerOperationResults.classify(lastResult).resource() == null);
        }
        if (message != null) {
            showStatus(message, false);
        }
    }

    /** Shows a status line, optionally in the error style. */
    private void showStatus(String message, boolean error) {
        statusLabel.getStyleClass().remove("status-error");
        if (error) {
            statusLabel.getStyleClass().add("status-error");
        }
        statusLabel.setText(message == null || message.isBlank() ? " " : message);
    }

    /** Empties the result pane, so a stale answer is never read as the current one. */
    private void clearResult() {
        lastResult = null;
        resultLabel.getStyleClass().remove("status-error");
        resultLabel.setText(" ");
        resultArea.clear();
        if (openButton != null) {
            openButton.setDisable(true);
        }
    }

    /** Stops the call in flight, if any. */
    private void cancelRunning() {
        if (running != null) {
            running.cancel();
            running = null;
        }
    }

    /**
     * How many operations the screen is currently offering.
     *
     * <p>Exposed so a test can assert that discovery reached the list. The dialog's whole
     * purpose is to show what the plugin declared, and that this number is non-zero and
     * correct is the one thing about it worth checking without a display.</p>
     */
    int operationCount() {
        return operationList.getItems().size();
    }

    /** The ids currently offered, for a test that checks a particular one is present. */
    List<String> operationIds() {
        return operationList.getItems().stream().map(ServerOperation::id).toList();
    }

    /**
     * The server selector, so a test can confirm the user can actually change the server.
     *
     * <p>Exposed because the selector was once built, populated, wired and read, and never
     * added to any layout — so everything about it was correct except being visible. Only a
     * test that looks for it in the scene graph would notice.</p>
     */
    ComboBox<FhirServerConfiguration> serverBox() {
        return serverBox;
    }

    /**
     * The FHIR answer to hand back, or {@code null} when the last answer was not a resource.
     *
     * <p>The label names the server as well as the resource, because the result of a vendor
     * operation is otherwise indistinguishable from a file the user opened.</p>
     */
    private Outcome outcome() {
        if (lastResult == null) {
            return null;
        }
        ServerOperationResults.Presentation presentation = ServerOperationResults.classify(lastResult);
        if (presentation.resource() == null) {
            return null;
        }
        FhirServerConfiguration server = serverBox.getValue();
        IBaseResource resource = presentation.resource();
        String id = resource.getIdElement() == null || !resource.getIdElement().hasIdPart()
                ? null
                : resource.getIdElement().getIdPart();
        String label = resource.fhirType() + (id == null ? "" : "/" + id)
                + (server == null ? "" : " (from " + server.name() + ")");
        return new Outcome(resource, label);
    }

    /**
     * Starts work and remembers the handle, so {@link #cancelRunning()} can stop it.
     *
     * <p>Both callers in this dialog go through here rather than through
     * {@link BackgroundTasks} directly, which is what keeps the one piece of mutable state
     * the cancellation feature needs in one place.</p>
     */
    private <T> void runAttempt(java.util.concurrent.Callable<BackgroundTasks.Attempt<T>> work,
            java.util.function.Consumer<BackgroundTasks.Attempt<T>> done) {
        running = BackgroundTasks.runAttempt("fhir-server-operation", work, done);
    }
}
