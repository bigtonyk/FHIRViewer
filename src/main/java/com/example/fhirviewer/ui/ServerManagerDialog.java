package com.example.fhirviewer.ui;

import java.util.List;
import java.util.Optional;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.server.ConnectionResult;
import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.FhirServerManager;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.ServerAuthKind;
import com.example.fhirviewer.server.ServerCredentialSaver;
import com.example.fhirviewer.server.ServerDefinition;
import com.example.fhirviewer.server.TransportSecurity;

/**
 * Manages the configured FHIR servers: a list of them, and a form that adds, edits and
 * deletes.
 *
 * <p>Replaces the add-only dialog this used to be. Adding was the only thing it could do, so
 * a configured server could be neither corrected nor removed without editing a file by hand:
 * the list was the application's, but there was no way to reach it.</p>
 *
 * <p><b>Everything is applied immediately, not on OK.</b> The dialog returns no result and
 * its only button is Close. Each of Add, Save and Delete acts on the selected server at once,
 * which is what a list-management screen is expected to do and avoids a Cancel that would
 * have to roll back three kinds of change.</p>
 *
 * <p>The form is a {@link ServerFormPanel} rather than part of this class, so the field set
 * and the list logic can change independently.</p>
 */
public class ServerManagerDialog extends Dialog<Void> {

    private final FhirServerService serverService;
    private final FhirServerManager serverManager;
    private final ServerCredentialSaver credentialSaver;

    private final ServerFormPanel form;
    private final Label statusLabel = new Label(" ");
    private final Button addButton = new Button("Add");
    private final Button saveButton = new Button("Save");
    private final Button deleteButton = new Button("Delete");
    private final Button testButton = new Button("Test connection");

    /** The server the form is editing, or {@code null} when adding a new one. */
    private ServerDefinition editing;

    /**
     * True while the form is filled in for a server that is not yet configured.
     *
     * <p>Separate from {@link #editing} because the two are not the same question. Pressing
     * Add clears {@code editing} — there is no server to edit — but Save must stay enabled,
     * or the first server could never be added at all. A single flag covering both meant Add
     * disabled the very button needed to complete the action it had just started.</p>
     */
    private boolean addingNew;

    /**
     * What the dialog did, so the caller can report it and refresh menu state.
     *
     * <p>Returned rather than inferred: the dialog mutates the manager directly, so without
     * this the caller cannot tell "the user closed without changing anything" from "the user
     * added a server", and would have to re-derive it by comparing lists.</p>
     */
    public record Result(boolean changed, String message) {
        /** Nothing was changed. */
        public static final Result UNCHANGED = new Result(false, "");
    }

    public ServerManagerDialog(FhirServerService serverService, FhirServerManager serverManager,
            ServerCredentialSaver credentialSaver) {
        this.serverService = serverService;
        this.serverManager = serverManager;
        this.credentialSaver = credentialSaver;
        this.form = new ServerFormPanel(serverService);

        setTitle("FHIR Servers");
        setHeaderText("Add, edit or remove the servers this application connects to.");
        setResizable(true);
        // The dialog grew to whatever the content wanted on some platforms, which cropped
        // the status line; sizing it here gives it room to start with and the ScrollPane
        // below handles anything larger.
        getDialogPane().setPrefWidth(640);
        getDialogPane().setPrefHeight(520);

        ButtonType closeType = new ButtonType("Close", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().add(closeType);

        // Registered on the pane's own button, compared by identity against the very
        // ButtonType added above. An earlier version added a custom button and compared it
        // to ButtonType.OK, which is a different object: every press produced null and the
        // dialog silently did nothing.
        final Button closeButton = (Button) getDialogPane().lookupButton(closeType);
        closeButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (event.getTarget() == closeButton) {
                lastResult = changed ? new Result(true, statusLabel.getText()) : Result.UNCHANGED;
            }
        });

        setResultConverter(button -> null);
        buildContent();
        refreshList();
    }

    private Result lastResult = Result.UNCHANGED;

    /** What the user did, for the caller to report. Never {@code null}. */
    public Result result() {
        return lastResult;
    }

    private void buildContent() {
        form.onServerChosen(this::onServerChosen);

        // fitToWidth is what makes the form grow with the window. Without it the grid stays
        // at its preferred width inside a wider dialog and the right-hand end of each field
        // is cut off, which is the symptom that prompted this layout.
        ScrollPane formScroll = new ScrollPane(form.build());
        formScroll.setFitToWidth(true);
        formScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        formScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // Grow is set once formSide exists, below, where the parent is known.

        VBox formSide = new VBox(6);
        Label formTitle = new Label("Server details");
        formTitle.getStyleClass().add("pretty-row-label");
        formSide.getChildren().addAll(formTitle, formScroll, buildButtons(), statusLabel);
        VBox.setVgrow(formScroll, Priority.ALWAYS);
        // The horizontal counterpart. Without it the scroll pane sits at its content's
        // preferred width, so the form hugs its fields and leaves the dialog empty.
        HBox.setHgrow(formScroll, Priority.ALWAYS);

        statusLabel.getStyleClass().add("app-subtitle");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        // Only maxWidth, never prefWidth. A preferred width is a size a parent adds up to
        // work out its own size, and MAX_VALUE there makes the whole chain above it unbounded
        // - which squashed this layout to the left. maxWidth says "grow to fill" and does not
        // feed the parent's arithmetic.

        // One column. The server list used to sit beside the form; it is now the selector at
        // the top of the form, so there is nothing to lay out beside it and the dialog is
        // both simpler and properly proportioned.
        BorderPane root = new BorderPane(formSide);
        root.setPadding(new Insets(12));
        // maxWidth, not prefWidth, for the same reason as the status line above.
        root.setMaxWidth(Double.MAX_VALUE);
        getDialogPane().setContent(root);
    }

    private HBox buildButtons() {
        addButton.setOnAction(event -> onAdd());
        saveButton.setOnAction(event -> onSave());
        deleteButton.setOnAction(event -> onDelete());
        testButton.setOnAction(event -> onTestConnection());
        testButton.getStyleClass().add("button-ghost");
        addButton.setMaxWidth(Double.MAX_VALUE);
        saveButton.setMaxWidth(Double.MAX_VALUE);
        deleteButton.setMaxWidth(Double.MAX_VALUE);
        testButton.setMaxWidth(Double.MAX_VALUE);

        HBox buttons = new HBox(8, addButton, saveButton, deleteButton, testButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(addButton, Priority.ALWAYS);
        HBox.setHgrow(saveButton, Priority.ALWAYS);
        HBox.setHgrow(deleteButton, Priority.ALWAYS);
        HBox.setHgrow(testButton, Priority.ALWAYS);
        return buttons;
    }

    private void refreshList() {
        List<ServerDefinition> current = serverManager.definitionsForDisplay();
        java.util.List<String> names = current.stream().map(ServerDefinition::name).toList();
        // Resolved *before* offering the names. Repopulating the drop-down selects its first
        // entry, which fires the chosen handler and re-points `editing`; reading it afterwards
        // would mean deciding whether to keep a selection using a value that had just been
        // changed underneath us.
        String keep = editing != null && names.contains(editing.name()) ? editing.name() : null;
        form.offerServers(names);
        form.selectServerQuietly(keep);
        if (keep == null) {
            editing = null;
            addingNew = true;
        }
        updateButtonState();
    }

    /**
     * Loads a server the user picked from the selector.
     *
     * <p>A blank choice means "a new server", so the form is cleared rather than loaded. A
     * name that is not configured is treated the same way: the user is typing a new one, and
     * refusing to switch would make the editable selector useless.</p>
     */
    private void onServerChosen(String chosen) {
        ServerDefinition match = null;
        if (chosen != null && !chosen.isBlank()) {
            for (ServerDefinition candidate : serverManager.definitionsForDisplay()) {
                if (candidate.name().equals(chosen)) {
                    match = candidate;
                    break;
                }
            }
        }
        editing = match;
        if (match == null) {
            addingNew = true;
            form.clear();
            form.selectServerQuietly(chosen);
        } else {
            addingNew = false;
            // Load the chosen server's details. Without this the selector changed the name
            // and nothing else: the form kept whatever it had, so switching servers looked
            // like it had done nothing.
            form.load(match);
        }
        updateButtonState();
    }

    private void updateButtonState() {
        // Save works on whatever the form holds, so it is enabled both for a selected server
        // and while a new one is being filled in. Delete only ever applies to a server that
        // is already configured, so it needs a selection.
        saveButton.setDisable(!hasEditableTarget());
        deleteButton.setDisable(editing == null);
        testButton.setDisable(false);
    }

    /** True when the form describes something Save can act on. */
    private boolean hasEditableTarget() {
        return editing != null || addingNew;
    }

    private void onAdd() {
        form.clear();
        editing = null;
        // Keep Save enabled: Add has started the job of configuring a server, and the user
        // finishes it with Save. Disabling it here left no way to add a server at all.
        addingNew = true;
        // Back to the blank entry, which is how the selector says "a new server". Selecting
        // it would otherwise fire the chosen handler and clear the form we just cleared.
        form.selectServerQuietly(null);
        updateButtonState();
        setStatus("Fill in the details, then choose Save to add this server.");
    }

    private void onSave() {
        // Captured before the save, because editing replaces the server this is derived
        // from and the id has to be carried across or the credentials are orphaned.
        ServerDefinition previous = editing;
        ServerDefinition definition;
        try {
            definition = form.toDefinition(previous);
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage());
            return;
        }
        boolean isNew = previous == null;
        boolean stored = isNew
                ? serverManager.add(definition)
                : serverManager.replace(previous, definition);
        if (!stored) {
            setStatus("A server named " + definition.name() + " is already configured.");
            return;
        }
        String credentialMessage = saveCredentials(definition);
        editing = definition;
        addingNew = false;
        refreshList();
        changed = true;
        setStatus((isNew ? "Added " : "Updated ") + definition.name() + ". " + credentialMessage
                + " It is saved and will be there next time.");
    }

    /**
     * Stores the credentials the form collected, if any.
     *
     * @return a sentence for the status line, never {@code null}
     */
    private String saveCredentials(ServerDefinition definition) {
        if (credentialSaver == null) {
            return "";
        }
        ServerAuthKind kind = form.authKind();
        ServerCredentialSaver.Outcome outcome =
                credentialSaver.save(definition, kind, form.userName(), form.secret());
        StringBuilder message = new StringBuilder(outcome.message()).append(' ');
        // Said alongside the result rather than instead of it, so the warning survives the
        // "Added <server>" text that is written after this returns. A user who does not
        // know their password will cross the network in the clear has no way to find out
        // except by reading this line.
        String plaintext = TransportSecurity.warningFor(definition.baseUrl());
        if (plaintext != null && !kind.isAnonymous()) {
            message.append(plaintext);
        }
        return message.toString();
    }

    private void onDelete() {
        if (editing == null) {
            return;
        }
        String name = editing.name();
        serverManager.remove(editing);
        editing = null;
        addingNew = false;
        form.clear();
        refreshList();
        changed = true;
        setStatus("Removed " + name + ".");
    }

    /**
     * Runs the connection test on a background thread and reports the result in the status
     * line.
     *
     * <p>Tested against whatever the form currently holds rather than the selected server,
     * so a server can be checked before it is added. The form is locked while the request
     * is in flight, because a user who keeps typing would be testing something other than
     * what is on screen.</p>
     */
    private void onTestConnection() {
        ServerDefinition candidate;
        try {
            candidate = form.toDefinition(editing);
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage());
            return;
        }
        setBusy(true);
        setStatus("Testing " + candidate.baseUrl() + " ...");
        // Shared with every other screen; see BackgroundTasks.
        BackgroundTasks.run("fhir-server-test",
                () -> serverService.testConnection(candidate), attempt -> {
                    setBusy(false);
                    if (!attempt.succeeded()) {
                        setStatus(attempt.cancelled()
                                ? "The connection test was cancelled."
                                : "The connection test failed: " + attempt.failure());
                        return;
                    }
                    ConnectionResult result = attempt.value();
                    if (result == null) {
                        setStatus("No answer.");
                    } else if (result.isReachable()) {
                        setStatus(result.message() == null || result.message().isBlank()
                                ? "Connected." : result.message());
                    } else {
                        setStatus("Could not connect: "
                                + (result.message() == null ? "no reason given." : result.message()));
                    }
                });
    }

    private void setBusy(boolean busy) {
        form.setEnabled(!busy);
        addButton.setDisable(busy);
        saveButton.setDisable(busy || !hasEditableTarget());
        deleteButton.setDisable(busy || editing == null);
        testButton.setDisable(busy);
        testButton.setText(busy ? "Testing ..." : "Test connection");
    }

    private void setStatus(String message) {
        statusLabel.getStyleClass().remove("status-error");
        if (message == null || message.isBlank()) {
            statusLabel.setText(" ");
            return;
        }
        statusLabel.setText(message);
    }

    /** True when the user added, edited or removed something. */
    private boolean changed;

    // -- Test access ----------------------------------------------------------
    // Package-private and read-only, so the smoke test can drive the screen the way a user
    // does rather than reimplementing the flow. That is the only way to catch a handler
    // wired to the wrong button, which is exactly the class of defect that made the
    // original add dialog silently do nothing.

    /** The form, for filling in fields. */
    ServerFormPanel form() {
        return form;
    }

    /** The selector, so a test can pick a configured server the way a user would. */
    ComboBox<String> serverBox() {
        return form.serverBox();
    }

    Button addButton() {
        return addButton;
    }

    Button saveButton() {
        return saveButton;
    }

    Button deleteButton() {
        return deleteButton;
    }

    Button testButton() {
        return testButton;
    }

    /** The status line's text, so a test can assert what the user was told. */
    String statusText() {
        return statusLabel.getText();
    }
}

