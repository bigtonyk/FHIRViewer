package com.example.fhirviewer.ui;

import java.util.List;
import java.util.Optional;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

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

    /**
     * What the dialog hands back, kept so {@link #resultFor(String)} can ask it the same
     * question a press would. JavaFX does not expose the converter it was given, and
     * rebuilding one here would test the copy rather than the real thing.
     */
    private javafx.util.Callback<ButtonType, ServerDefinition> resultConverter;

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
        // Pin the label column to its preferred width so labels never collapse to "..."; the
        // control column takes all the remaining (and any extra) space. ColumnConstraints has
        // no (min, pref, Priority) constructor, so hgrow is set separately.
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(Region.USE_PREF_SIZE);
        labelColumn.setMaxWidth(Region.USE_PREF_SIZE);
        ColumnConstraints controlColumn = new ColumnConstraints(240, Region.USE_PREF_SIZE, 0);
        controlColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, controlColumn);
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
        getDialogPane().setMinWidth(680);
        getDialogPane().setPrefWidth(760);
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

        // Compared against the button that is actually registered, not ButtonType.OK.
        // Those are different objects: OK is a predefined ButtonType the pane never
        // received, so comparing against it meant this converter answered null for
        // every press of Save and the dialog silently returned no server at all.
        this.resultConverter = button -> saveType.equals(button) ? definition() : null;
        setResultConverter(resultConverter);
    }

    /**
     * What the dialog hands back for the button carrying this label.
     *
     * <p>Looks the button up among the ones the pane actually holds and runs the dialog's
     * own result converter against it, which is exactly what a press does. It cannot use
     * {@code showAndWait}, because that blocks the JavaFX thread a test runs it on and
     * would deadlock, and it does not need to: the converter is a pure function of the
     * button, so asking it the question a press would ask is the same assertion.
     *
     * <p>Exists because of a defect this now catches. The dialog registered its own "Save"
     * {@code ButtonType} but the converter compared against {@code ButtonType.OK}, a
     * different object the pane never received, so every press produced null. The window
     * therefore never added a server and every Tools item that needed one did nothing -
     * while the screen built perfectly and every other test still passed.</p>
     *
     * @param buttonLabel the label the user sees, for example {@code "Save"}
     * @return what the dialog would hand back, or {@code null} for a cancel
     */
    ServerDefinition resultFor(String buttonLabel) {
        for (ButtonType type : getDialogPane().getButtonTypes()) {
            if (type.getText().equals(buttonLabel)) {
                return resultConverter.call(type);
            }
        }
        throw new IllegalArgumentException("No button labelled '" + buttonLabel + "'");
    }

    /** The server the user described, validated by the model layer. */
    public ServerDefinition definition() {
        FhirServerPlugin plugin = pluginBox.getValue();
        return ServerDefinition.named(nameField.getText(), urlField.getText())
                .fhirVersion(versionBox.getValue())
                .pluginId(plugin == null ? "" : plugin.id())
                .build();
    }

    /**
     * The dialog's own text fields, so a test can fill them as a user would.
     *
     * <p>Read-only and package-private, for the same reason as {@link #resultFor}: the
     * smoke test asks a real dialog rather than reimplementing the flow, which is the only
     * way to catch a result converter that disagrees with the buttons the pane holds.</p>
     */
    List<TextField> textFieldsByPrompt() {
        return List.of(nameField, urlField);
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
        // Shared with every other screen; see BackgroundTasks.
        BackgroundTasks.run("fhir-server-test",
                () -> serverService.testConnection(candidate), attempt -> {
                    setTesting(false);
                    if (!attempt.succeeded()) {
                        reportFailure(attempt.cancelled()
                                ? "The connection test was cancelled."
                                : attempt.failure());
                        return;
                    }
                    ConnectionResult result = attempt.value();
                    statusLabel.getStyleClass().remove("status-error");
                    if (result != null && !result.isReachable()) {
                        statusLabel.getStyleClass().add("status-error");
                    }
                    statusLabel.setText(result == null ? "No answer." : result.message());
                });
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
