package com.example.fhirviewer.ui;

import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.server.ConnectionResult;
import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.FhirServerManager;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.ServerCapabilities;
import com.example.fhirviewer.server.ServerOperation;

/**
 * The connection workflow for a configured FHIR server: connect, disconnect, test, status
 * and capabilities.
 *
 * <p>"Connect" here means test the connection and make the server the active one, which is
 * what {@link FhirServerManager} already models. There is no separate session to open and
 * close: every call the plugin makes builds its own {@link
 * com.example.fhirviewer.server.ServerSession} from the saved credential, so a viewer that
 * pretended to hold a connection open would be claiming something the architecture does not
 * do. The button says what actually happens — it tests, and then activates.</p>
 *
 * <p>Everything reported here comes from the active plugin, so a vendor server shows
 * whatever its own status endpoint said. The capabilities list and the operation list are
 * read through the service, never constructed by this dialog.</p>
 */
public class ServerStatusDialog extends Dialog<Void> {

    private final FhirServerService serverService;
    private final FhirServerManager serverManager;

    private final ComboBox<FhirServerConfiguration> serverBox = new ComboBox<>();
    private final TextArea report = new TextArea();
    private final Label statusLabel = new Label(" ");
    private final ProgressIndicator progress = new ProgressIndicator(18);
    private final Button connectButton = new Button("Connect");
    private final Button disconnectButton = new Button("Disconnect");
    private final Button capabilitiesButton = new Button("Capabilities");

    private FhirServerConfiguration connected;
    private BackgroundTasks.Running running;

    public ServerStatusDialog(FhirServerService serverService, FhirServerManager serverManager,
            FhirServerConfiguration preselect, ThemeManager themeManager) {
        this.serverService = serverService;
        this.serverManager = serverManager;

        setTitle("FHIR server connection");
        setHeaderText("Connect to a server and see what it reports");
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
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                boolean active = serverManager.isActive(item);
                setText(item.name() + " — " + item.baseUrl()
                        + (active ? "  (active)" : ""));
            }
        });
        serverBox.setOnAction(event -> onServerChanged());

        report.setEditable(false);
        report.setWrapText(true);
        report.getStyleClass().addAll("mono-text", "code-area");
        VBox.setVgrow(report, Priority.ALWAYS);

        statusLabel.getStyleClass().add("app-subtitle");
        statusLabel.setWrapText(true);

        connectButton.getStyleClass().add("button-primary");
        connectButton.setOnAction(event -> connect());
        disconnectButton.getStyleClass().add("button-ghost");
        disconnectButton.setOnAction(event -> disconnect());
        capabilitiesButton.getStyleClass().add("button-ghost");
        capabilitiesButton.setOnAction(event -> showCapabilities());

        HBox actions = new HBox(8, connectButton, disconnectButton, capabilitiesButton, progress);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        actions.setPadding(new Insets(0, 12, 0, 12));

        VBox content = new VBox(10, serverBox, actions, statusLabel, report);
        content.setPadding(new Insets(12));
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        getDialogPane().setPrefWidth(720);
        getDialogPane().setPrefHeight(560);
        setOnCloseRequest(event -> {
            if (running != null) {
                running.cancel();
            }
        });

        onServerChanged();
    }

    /** Resets the pane for a newly selected server. */
    private void onServerChanged() {
        FhirServerConfiguration server = serverBox.getValue();
        connected = null;
        boolean has = server != null;
        connectButton.setDisable(!has);
        disconnectButton.setDisable(true);
        capabilitiesButton.setDisable(!has);
        if (!has) {
            report.clear();
            showStatus("No FHIR server is configured. Use Tools > FHIR Servers... to add one.", true);
            return;
        }
        report.setText(server.name() + "\n" + server.baseUrl()
                + "\nServed by: " + servingPlugin(server)
                + "\nFHIR version: " + server.fhirVersion()
                + "\n\nNot connected. Choose Connect to test this server.");
        showStatus("Ready.", false);
    }

    /**
     * Tests the server and, if it answers, makes it the active one.
     *
     * <p>{@code testConnection} never throws, so the interesting branch here is the
     * reachable one, and it is still not treated as success on its own: a server can answer
     * its metadata request and still not be usable for anything, which is what the
     * capabilities read afterwards is for.</p>
     */
    private void connect() {
        FhirServerConfiguration server = serverBox.getValue();
        if (server == null) {
            return;
        }
        setBusy(true, "Testing " + server.baseUrl() + " ...");
        running = BackgroundTasks.run("fhir-server-connect",
                () -> serverService.testConnection(server), attempt -> {
                    setBusy(false, null);
                    if (!attempt.succeeded()) {
                        showStatus(attempt.failure(), true);
                        return;
                    }
                    onConnectionResult(server, attempt.value());
                });
    }

    /** Reports a test result and activates the server when it answered. */
    private void onConnectionResult(FhirServerConfiguration server, ConnectionResult result) {
        if (result == null || !result.isReachable()) {
            showStatus(result == null ? "No answer." : result.message(), true);
            report.setText(server.name() + "\n" + server.baseUrl()
                    + "\n\nNot reachable. " + (result == null ? "" : result.message()));
            return;
        }
        serverManager.setActive(server);
        connected = server;
        disconnectButton.setDisable(false);
        ServerCapabilities capabilities = result.capabilities();
        report.setText(server.name() + "\n" + server.baseUrl()
                + "\nServed by: " + servingPlugin(server)
                + "\n\nConnected."
                + (capabilities == null ? "" : "\n" + capabilities));
        showStatus("Connected to " + server.name() + ". "
                + (capabilities == null ? "" : capabilities.toString()), false);
        // The cell factory reads isActive, so the list is redrawn to show "(active)".
        serverBox.setCellFactory(serverBox.getCellFactory());
    }

    /**
     * The report pane's text, for tests.
     *
     * <p>Which plugin is serving the server is shown here and nowhere else, so this is the
     * only way a headless test can tell it is on screen at all.</p>
     */
    String reportTextForTest() {
        return report.getText();
    }

    /**
     * The line naming which plugin serves this server, in words rather than ids.
     *
     * <p>The plugin id alone was the whole of what this screen said, and
     * {@code standard-rest} is not something a user can act on. It matters because the
     * commonest report about the operation screen was "Firely only offers 2 operations" -
     * which was a Firely server saved with the default plugin, serving it as a plain FHIR
     * server. Naming the plugin makes that distinguishable from Firely genuinely offering
     * two.</p>
     *
     * <p>Returns the plugin's own display name when it can be found, falling back to the id,
     * which is better than nothing for a plugin that has been uninstalled.</p>
     */
    private String servingPlugin(FhirServerConfiguration server) {
        for (com.example.fhirviewer.server.FhirServerPlugin plugin
                : serverService.plugins()) {
            if (plugin.id().equals(server.pluginId())) {
                return plugin.displayName() + " (" + plugin.id() + ")";
            }
        }
        return server.pluginId() + " (plugin not found)";
    }
    private void disconnect() {
        FhirServerConfiguration server = connected;
        if (server == null) {
            return;
        }
        serverManager.setActive(null);
        connected = null;
        disconnectButton.setDisable(true);
        showStatus("Disconnected from " + server.name() + ".", false);
    }

    /**
     * Reads the server's capabilities and lists the operations its plugin offers.
     *
     * <p>Both come from the plugin, so a vendor server is described in its own terms and a
     * standard one in the specification's. Nothing here assumes either.</p>
     */
    private void showCapabilities() {
        FhirServerConfiguration server = serverBox.getValue();
        if (server == null) {
            return;
        }
        setBusy(true, "Reading capabilities of " + server.name() + " ...");
        running = BackgroundTasks.run("fhir-server-capabilities",
                () -> serverService.capabilities(server), attempt -> {
                    setBusy(false, null);
                    if (!attempt.succeeded()) {
                        showStatus(attempt.failure(), true);
                        return;
                    }
                    showCapabilities(server, attempt.value());
                });
    }

    /**
     * Writes the capabilities and the plugin's operation list into the report.
     *
     * <p>The operation list is the part that matters most: it is the same discovery the
     * operation screen uses, so what is written here and what the user can actually run
     * cannot drift apart.</p>
     */
    private void showCapabilities(FhirServerConfiguration server, ServerCapabilities capabilities) {
        StringBuilder text = new StringBuilder();
        text.append(server.name()).append('\n').append(server.baseUrl()).append('\n');
        text.append("FHIR version: ")
                .append(capabilities == null || capabilities.fhirVersion().isBlank()
                        ? server.fhirVersion() : capabilities.fhirVersion())
                .append('\n');
        if (capabilities == null) {
            text.append("\nThe server reported no capabilities.");
        } else {
            text.append("Resource types: ").append(capabilities.resourceTypes().size()).append('\n');
            text.append("Paging: ").append(capabilities.pagingSupported() ? "yes" : "not advertised").append('\n');
            text.append("Interactions: ").append(capabilities.interactions().isEmpty()
                    ? "not declared" : capabilities.interactions().size() + " declared").append('\n');
        }
        List<ServerOperation> operations = serverService.supportedOperations(server);
        text.append("\nOperations offered by the plugin (").append(operations.size()).append("):");
        if (operations.isEmpty()) {
            text.append("\n  none");
        } else {
            for (ServerOperation operation : operations) {
                text.append("\n  ").append(operation.displayName())
                        .append("  [").append(operation.category()).append(']');
            }
        }
        report.setText(text.toString());
        showStatus("Capabilities read from " + server.name() + ".", false);
    }

    private void setBusy(boolean busy, String message) {
        connectButton.setDisable(busy || serverBox.getValue() == null);
        disconnectButton.setDisable(busy || connected == null);
        capabilitiesButton.setDisable(busy || serverBox.getValue() == null);
        progress.setVisible(busy);
        if (message != null) {
            showStatus(message, false);
        }
    }

    private void showStatus(String message, boolean error) {
        statusLabel.getStyleClass().remove("status-error");
        if (error) {
            statusLabel.getStyleClass().add("status-error");
        }
        statusLabel.setText(message == null || message.isBlank() ? " " : message);
    }
}
