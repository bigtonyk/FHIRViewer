package com.example.fhirviewer.ui;

import java.util.Optional;

import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import com.example.fhirviewer.server.ConnectionResult;
import com.example.fhirviewer.server.FhirServerPlugin;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.ServerDefinition;

/**
 * The "Add FHIR server" dialog.
 *
 * <p>Fields follow the plan: server name, FHIR base URL, FHIR version, integration
 * (plugin) and authentication. <em>Test connection</em> runs on a background thread and
 * reports the result inline, so the dialog never freezes the UI thread while the network
 * is busy.</p>
 *
 * <p>The dialog returns a {@link ServerDefinition}. Authentication secrets are not part
 * of it yet because the server layer currently supports anonymous access only.</p>
 */
public class ServerDialog extends Dialog<ServerDefinition> {

    private final FhirServerService serverService;
    private final TextField nameField = new TextField();
    private final TextField urlField = new TextField();
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<FhirServerPlugin> pluginBox = new ComboBox<>();
    private final Button testButton = new Button("Test connection");
    private final Label statusLabel = new Label(" ");
    private final Button saveButton;

    public ServerDialog(FhirServerService serverService, ThemeManager themeManager) {
        this.serverService = serverService;
        setTitle("Add FHIR server");
        setHeaderText("Connect to a FHIR server");
        setResizable(true);

        nameField.setPromptText("My HAPI Server");
        urlField.setPromptText("https://example.com/fhir");
        versionBox.getItems().addAll("R4");
        versionBox.getSelectionModel().selectFirst();
        pluginBox.getItems().setAll(serverService.plugins());
        pluginBox.getSelectionModel().selectFirst();
        renderPluginNames();

        testButton.getStyleClass().add("button-ghost");
        testButton.setTooltip(new Tooltip("Ask the server for its CapabilityStatement."));
        testButton.setOnAction(event -> testConnection());

        statusLabel.getStyleClass().add("app-subtitle");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;
        addRow(grid, row++, "Server name", nameField);
        addRow(grid, row++, "FHIR base URL", urlField);
        addRow(grid, row++, "FHIR version", versionBox);
        addRow(grid, row++, "Integration", pluginBox);
        addRow(grid, row++, "Authentication", new Label("None (anonymous)"));
        grid.add(testButton, 1, row++);
        grid.add(statusLabel, 0, row++, 2, 1);

        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(560);
        getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        saveButton = (Button) getDialogPane().lookupButton(saveType);
        if (saveButton != null) {
            saveButton.getStyleClass().add("button-primary");
            // Saving only closes the dialog when the definition validates; problems are
            // reported inline instead of crashing the result converter.
            saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                try {
                    definition();
                } catch (IllegalArgumentException e) {
                    event.consume();
                    reportFailure(e.getMessage());
                }
            });
        }
        Button cancel = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancel != null) {
            cancel.getStyleClass().add("button-ghost");
        }

        setResultConverter(button -> ButtonType.OK.equals(button) ? definition() : null);
    }

    /** The server the user described, validated by the model layer. */
    public ServerDefinition definition() {
        FhirServerPlugin plugin = pluginBox.getValue();
        return ServerDefinition.named(nameField.getText(), urlField.getText())
                .fhirVersion(versionBox.getValue())
                .pluginId(plugin == null ? "" : plugin.id())
                .build();
    }

    private void renderPluginNames() {
        pluginBox.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(FhirServerPlugin item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName() + " — " + item.description());
            }
        });
        pluginBox.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(FhirServerPlugin item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        });
    }

    private void addRow(GridPane grid, int row, String label, javafx.scene.layout.Region control) {
        Label labelNode = new Label(label);
        labelNode.getStyleClass().add("pretty-row-label");
        grid.add(labelNode, 0, row);
        grid.add(control, 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
        control.setMaxWidth(Double.MAX_VALUE);
    }

    private void testConnection() {
        ServerDefinition candidate;
        try {
            candidate = definition();
        } catch (IllegalArgumentException e) {
            reportFailure(e.getMessage());
            return;
        }
        setTesting(true);
        statusLabel.getStyleClass().remove("status-error");
        statusLabel.setText("Testing " + candidate.baseUrl() + " ...");
        Task<ConnectionResult> task = new Task<>() {
            @Override
            protected ConnectionResult call() {
                return serverService.testConnection(candidate);
            }
        };
        task.setOnSucceeded(event -> {
            setTesting(false);
            ConnectionResult result = task.getValue();
            statusLabel.getStyleClass().remove("status-error");
            if (result != null && !result.isReachable()) {
                statusLabel.getStyleClass().add("status-error");
            }
            statusLabel.setText(result == null ? "No answer." : result.message());
        });
        task.setOnFailed(event -> {
            setTesting(false);
            reportFailure(readableFailure(task.getException()));
        });
        Thread thread = new Thread(task, "fhir-server-test");
        thread.setDaemon(true);
        thread.start();
    }

    /** The message a dialog shows for a background failure, readable and log free. */
    static String readableFailure(Throwable failure) {
        return ServerSearchDialog.readableFailure(failure);
    }

    private void setTesting(boolean testing) {
        testButton.setDisable(testing);
        if (saveButton != null) {
            saveButton.setDisable(testing);
        }
        testButton.setText(testing ? "Testing ..." : "Test connection");
    }

    private void reportFailure(String message) {
        statusLabel.getStyleClass().remove("status-error");
        statusLabel.getStyleClass().add("status-error");
        statusLabel.setText(message);
    }
}
