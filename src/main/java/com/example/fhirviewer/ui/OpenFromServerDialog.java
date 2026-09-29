package com.example.fhirviewer.ui;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.FhirServerManager;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.ServerCapabilities;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

/**
 * "Open from Server": picks a resource on a chosen FHIR server and hands it to the editor.
 *
 * <p>The dialog is deliberately server-agnostic. It talks only to
 * {@link FhirServerService}, so it works for every plugin the application loads without
 * knowing anything vendor specific. Server-specific tooling stays behind each plugin's
 * own vendor actions rather than leaking in here.</p>
 *
 * <p>The user can type a resource type and id directly, or load the types the server
 * advertises and pick from them. Every network call happens on a background thread and
 * only the result is handled on the JavaFX thread.</p>
 */
public class OpenFromServerDialog extends Dialog<OpenFromServerDialog.Outcome> {

    /** The result of a successful open: the resource, and the server it came from. */
    public record Outcome(FhirServerConfiguration server, String resourceType, String resourceId,
            IBaseResource resource) {
    }

    /** One background attempt, bridged from the shared helper's own attempt type. */
    private record Attempt<T>(T value, String failure) {

        boolean succeeded() {
            return failure == null;
        }

        /** Adapts this dialog's attempt to the one {@link BackgroundTasks} speaks. */
        BackgroundTasks.Attempt<T> toShared() {
            return succeeded() ? BackgroundTasks.Attempt.succeeded(value)
                    : BackgroundTasks.Attempt.failed(failure);
        }

        /** Rebuilds this dialog's attempt from the shared one, keeping the message. */
        static <T> Attempt<T> from(BackgroundTasks.Attempt<T> shared) {
            return new Attempt<>(shared.succeeded() ? shared.value() : null,
                    shared.succeeded() ? null
                            : shared.cancelled() ? "The read was cancelled." : shared.failure());
        }
    }

    private final FhirServerService serverService;
    private final FhirServerManager serverManager;

    private final ComboBox<FhirServerConfiguration> serverBox = new ComboBox<>();
    private final TextField typeField = new TextField();
    private final TextField idField = new TextField();
    private final ListView<String> typeList = new ListView<>();
    private final Label status = new Label(" ");
    private final Button loadTypes = new Button("Load types");
    private final Button readButton = new Button("Read");
    private final ProgressIndicator progress = new ProgressIndicator(18);

    /** The dialog's own Read button, so a background result can be returned from it. */
    private final ButtonType readType;

    /** The pending outcome, filled in by the background callbacks and returned on close. */
    private Outcome outcome;

    /** True while a background call runs, so a close cannot race an in-flight read. */
    private boolean busy;

    /** Holds the status label and the spinner; assigned while the content is built. */
    private Region busyRegion;

    public OpenFromServerDialog(FhirServerService serverService, FhirServerManager serverManager,
            FhirServerConfiguration preselected) {
        this.serverService = Objects.requireNonNull(serverService, "serverService");
        this.serverManager = Objects.requireNonNull(serverManager, "serverManager");

        setTitle("Open from Server");
        setResizable(true);
        getDialogPane().setMinWidth(760);
        getDialogPane().setMinHeight(520);
        readType = new ButtonType("Read", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(readType, ButtonType.CANCEL);
        // The Read result arrives asynchronously, so the button only closes the dialog
        // and the converter hands back whatever the background call produced.
        setResultConverter(dialogButton -> readType.equals(dialogButton) ? outcome : null);
        progress.setVisible(false);
        getDialogPane().setContent(buildContent());
        initServers(preselected);
    }

    private void initServers(FhirServerConfiguration preselected) {
        serverBox.getItems().setAll(serverManager.servers());
        if (preselected != null && serverBox.getItems().contains(preselected)) {
            serverBox.setValue(preselected);
        } else if (!serverBox.getItems().isEmpty()) {
            serverBox.setValue(serverBox.getItems().get(0));
        }
        if (serverBox.getValue() == null) {
            status.setText("No servers are loaded. Add one first.");
            readButton.setDisable(true);
            loadTypes.setDisable(true);
        } else {
            serverBox.setCellFactory(view -> new ListCell<>() {
                @Override
                protected void updateItem(FhirServerConfiguration item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : serverLabel(item));
                }
            });
        }
        serverBox.valueProperty().addListener((obs, old, current) -> {
            typeList.getItems().clear();
            status.setText(" ");
        });
    }

    /** Names a server as "name (baseUrl)" so two servers are never indistinguishable. */
    static String serverLabel(FhirServerConfiguration server) {
        return server == null ? "(no server)" : server.name() + "  —  " + server.baseUrl();
    }

    private Region buildContent() {
        typeField.setPromptText("Resource type, for example Patient");
        idField.setPromptText("Resource id");

        readButton.setDefaultButton(true);
        readButton.setOnAction(event -> readTypedResource());

        // The cancel button is looked up by ButtonType (and cast back to Button), the
        // same way ServerDialog does it, rather than by ButtonData.
        Node cancelNode = getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelNode instanceof Button cancel) {
            cancel.addEventFilter(ActionEvent.ACTION, event -> {
                if (busy) {
                    // A read in flight must not leave a thread touching a closed dialog.
                    event.consume();
                }
            });
        }

        GridPane query = new GridPane();
        query.setHgap(8);
        query.setVgap(8);
        query.setPadding(new Insets(12));
        query.add(new Label("Server:"), 0, 0);
        query.add(serverBox, 1, 0);
        query.add(new Label("Type:"), 0, 1);
        query.add(typeField, 1, 1);
        query.add(new Label("Id:"), 0, 2);
        query.add(idField, 1, 2);
        ColumnConstraints grow = new ColumnConstraints();
        grow.setHgrow(Priority.ALWAYS);
        query.getColumnConstraints().addAll(new ColumnConstraints(), grow);

        HBox actions = new HBox(8, loadTypes, readButton);
        actions.setPadding(new Insets(0, 12, 0, 12));
        actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        VBox left = new VBox(10, query, actions);
        VBox.setVgrow(query, Priority.NEVER);

        typeField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                readTypedResource();
            }
        });

        // A type can be picked from the list or typed; either way the Read button
        // needs both, so a list selection only fills the type field.
        typeList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                typeField.setText(selected);
            }
        });

        HBox statusBar = new HBox(8, progress, status);
        statusBar.setPadding(new Insets(6, 12, 6, 12));
        statusBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        busyRegion = statusBar;

        BorderPane pane = new BorderPane();
        pane.setLeft(left);
        pane.setCenter(withPlaceholder(typeList));
        pane.setBottom(statusBar);
        keepLabelsVisible(readButton, loadTypes);
        return pane;
    }

    /**
     * Wraps a list in a stack with a centred hint shown while the list is empty.
     *
     * <p>{@code ListView} has no placeholder API of its own, and an empty list on a
     * dialog reads as "broken" rather than "nothing loaded yet", so the hint is an
     * ordinary label kept in sync with the list contents.</p>
     */
    private static Region withPlaceholder(ListView<String> list) {
        Label hint = new Label("Server resource types appear here.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6;");
        hint.setMouseTransparent(true);

        StackPane stack = new StackPane(list, hint);
        stack.setAlignment(Pos.CENTER);
        Runnable sync = () -> hint.setVisible(list.getItems().isEmpty());
        list.getItems().addListener((javafx.collections.ListChangeListener<String>) change -> sync.run());
        sync.run();
        return stack;
    }

    /** Reads the typed type/id, so a user who knows the id never needs the list. */
    private void readTypedResource() {
        FhirServerConfiguration server = serverBox.getValue();
        String type = typeField.getText() == null ? "" : typeField.getText().trim();
        String id = idField.getText() == null ? "" : idField.getText().trim();
        if (server == null || type.isEmpty() || id.isEmpty()) {
            status.setText("Choose a server, and give both a resource type and an id.");
            return;
        }
        setBusy(true, "Reading " + type + "/" + id + " ...");
        run(() -> new Attempt<>(serverService.read(server, type, id), null), attempt -> {
            setBusy(false, null);
            if (!attempt.succeeded()) {
                reportFailure(attempt.failure());
                return;
            }
            if (attempt.value() == null) {
                reportFailure("The server returned nothing for " + type + "/" + id + ".");
                return;
            }
            setResult(new Outcome(server, type, id, attempt.value()));
            close();
        });
    }

    /** Loads the resource types the selected server advertises. */
    private void loadTypes() {
        FhirServerConfiguration server = serverBox.getValue();
        if (server == null) {
            return;
        }
        setBusy(true, "Loading resource types ...");
        run(() -> new Attempt<>(serverService.capabilities(server), null), attempt -> {
            setBusy(false, null);
            if (!attempt.succeeded()) {
                reportFailure(attempt.failure());
                return;
            }
            ServerCapabilities capabilities = attempt.value();
            typeList.getItems().setAll(capabilities.resourceTypes());
            status.setText(capabilities.resourceTypes().size() + " resource type(s) advertised by "
                    + server.name() + ".");
        });
    }

    private void reportFailure(String message) {
        status.setText(message == null || message.isBlank() ? "The operation failed." : message);
    }

    /**
     * Enables or disables the actions while a background call is in flight, and
     * shows the spinner. Re-entrancy is the reason the buttons are disabled here
     * rather than only visually: a second read would race the first one.
     */
    private void setBusy(boolean nowBusy, String message) {
        busy = nowBusy;
        progress.setVisible(nowBusy);
        if (busyRegion != null) {
            busyRegion.setVisible(true);
        }
        loadTypes.setDisable(nowBusy);
        readButton.setDisable(nowBusy || serverBox.getValue() == null);
        if (message != null) {
            status.setText(message);
        }
    }

    /** Runs work on a background thread and delivers the attempt on the JavaFX thread. */
    private <T> void run(java.util.concurrent.Callable<Attempt<T>> work,
            java.util.function.Consumer<Attempt<T>> done) {
        // One shared implementation; see BackgroundTasks for why this is not written out
        // per dialog any more. The local Attempt is bridged to the shared one in both
        // directions so this dialog's own call sites are unchanged by the refactor.
        BackgroundTasks.runAttempt("fhir-open-from-server",
                () -> work.call().toShared(),
                shared -> {
                    Attempt<T> result = Attempt.from(shared);
                    if (result.succeeded()) {
                        done.accept(result);
                    } else {
                        setBusy(false, null);
                        reportFailure(result.failure());
                    }
                });
    }

    /** Stops a button from collapsing to an ellipsis when the dialog is made narrow. */
    private static void keepLabelsVisible(Button... buttons) {
        for (Button button : buttons) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
    }
}

