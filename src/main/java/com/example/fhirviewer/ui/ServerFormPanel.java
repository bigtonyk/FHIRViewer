package com.example.fhirviewer.ui;

import java.util.List;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import com.example.fhirviewer.server.FhirServerPlugin;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.ServerAuthKind;
import com.example.fhirviewer.server.ServerDefinition;

/**
 * The editable fields of one FHIR server: identity, connection, plugin and authentication.
 *
 * <p>Separated from {@link ServerManagerDialog} so the manager can hold a list beside these
 * without either file carrying the other's concerns, and so the field set can be built and
 * driven in a test without a dialog around it.</p>
 *
 * <p><b>Builds for editing, not just adding.</b> {@link #load} fills the fields from an
 * existing definition, which is what makes "edit" a mode of this form rather than a second
 * form that has to be kept in step with this one.</p>
 *
 * <p>The layout grows with the window. The label column is pinned and the control column
 * takes the rest, and the whole grid is later placed in a {@code ScrollPane} that fits to
 * width — without that, a wider window left the fields at their preferred size and the
 * right-hand text was cut off.</p>
 */
final class ServerFormPanel {

    private final TextField nameField = new TextField();
    private final TextField urlField = new TextField();
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<FhirServerPlugin> pluginBox = new ComboBox<>();
    private final ComboBox<ServerAuthKind> authBox = new ComboBox<>();
    private final TextField userField = new TextField();
    private final PasswordField secretField = new PasswordField();

    private final Label userLabel;
    private final Label secretLabel;
    private final Label credentialHint;

    ServerFormPanel(FhirServerService serverService) {
        versionBox.getItems().addAll("R4");
        versionBox.getSelectionModel().selectFirst();
        pluginBox.getItems().setAll(serverService.plugins());
        pluginBox.getSelectionModel().selectFirst();
        authBox.getItems().setAll(ServerAuthKind.values());
        authBox.getSelectionModel().select(ServerAuthKind.ANONYMOUS);
        renderPluginNames();

        nameField.setPromptText("My FHIR Server");
        urlField.setPromptText("https://example.com/fhir");
        userField.setPromptText("username");
        secretField.setPromptText("password");

        userLabel = rowLabel("User name");
        secretLabel = rowLabel("Password");
        credentialHint = new Label();
        credentialHint.getStyleClass().add("app-subtitle");
        credentialHint.setWrapText(true);
        credentialHint.setMaxWidth(Double.MAX_VALUE);

        // A password field is never echoed back, so a user cannot check what they typed.
        secretField.setTooltip(new Tooltip(
                "Stored encrypted on this computer, and unlocked with the passphrase from"
                        + " Tools > Server Plugins..."));

        // Recomputed on every change, so switching to Anonymous and back behaves.
        authBox.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, current) -> applyAuthVisibility());
        applyAuthVisibility();
    }

    private static Label rowLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("pretty-row-label");
        return label;
    }

    /** The grid holding the fields, already wired for growth. */
    GridPane build() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        // Pin the label column so labels never collapse to "..." while the control column
        // absorbs every extra pixel. ColumnConstraints has no (min, pref, Priority)
        // constructor, so the grow priority is set separately.
        ColumnConstraints labelColumn = new ColumnConstraints(110);
        labelColumn.setHgrow(Priority.NEVER);
        ColumnConstraints controlColumn = new ColumnConstraints();
        controlColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, controlColumn);

        int row = 0;
        addRow(grid, row++, "Name", nameField);
        addRow(grid, row++, "Base URL", urlField);
        addRow(grid, row++, "FHIR version", versionBox);
        addRow(grid, row++, "Server type", pluginBox);
        addRow(grid, row++, "Authentication", authBox);
        addRow(grid, row, "User name", userField);
        addRow(grid, row, "Password", secretField);

        // The hint spans both columns so it can use the full width rather than being
        // squeezed into the control column, which is what made it unreadable.
        GridPane.setColumnSpan(credentialHint, 2);
        GridPane.setHgrow(credentialHint, Priority.ALWAYS);
        grid.add(credentialHint, 0, ++row);
        return grid;
    }

    /** The fields a test drives, so it can fill the form as a user would. */
    List<Control> fields() {
        return List.of(nameField, urlField, versionBox, pluginBox, authBox, userField,
                secretField);
    }

    TextField nameField() {
        return nameField;
    }

    TextField urlField() {
        return urlField;
    }

    ComboBox<ServerAuthKind> authBox() {
        return authBox;
    }

    TextField userField() {
        return userField;
    }

    PasswordField secretField() {
        return secretField;
    }

    /** Fills the form from an existing server, so it can be edited rather than retyped. */
    void load(ServerDefinition definition) {
        nameField.setText(definition.name());
        urlField.setText(definition.baseUrl());
        versionBox.getSelectionModel().select(definition.fhirVersion());
        FhirServerPlugin plugin = pluginBox.getItems().stream()
                .filter(p -> p.id().equals(definition.pluginId()))
                .findFirst()
                .orElse(null);
        if (plugin != null) {
            pluginBox.getSelectionModel().select(plugin);
        }
        applyAuthVisibility();
    }

    /** Clears every field, for the "Add" button. */
    void clear() {
        nameField.clear();
        urlField.clear();
        versionBox.getSelectionModel().selectFirst();
        if (!pluginBox.getItems().isEmpty()) {
            pluginBox.getSelectionModel().selectFirst();
        }
        authBox.getSelectionModel().select(ServerAuthKind.ANONYMOUS);
        userField.clear();
        secretField.clear();
        applyAuthVisibility();
    }

    /** The authentication kind currently chosen. */
    ServerAuthKind authKind() {
        ServerAuthKind kind = authBox.getSelectionModel().getSelectedItem();
        return kind == null ? ServerAuthKind.ANONYMOUS : kind;
    }

    /** The user name typed, or {@code null} when the kind does not use one. */
    String userName() {
        if (!authKind().needsUserName()) {
            return null;
        }
        String typed = userField.getText();
        return typed == null || typed.isBlank() ? null : typed.trim();
    }

    /**
     * The typed secret, or {@code null} when none was typed.
     *
     * <p>A blank field means "leave the saved one alone" rather than "delete it", because a
     * password field is never echoed back and blanking it on every edit would silently
     * discard a working password. Clearing a password is done by choosing Anonymous.</p>
     */
    String secret() {
        if (authKind().isAnonymous()) {
            return null;
        }
        String typed = secretField.getText();
        return typed == null || typed.isBlank() ? null : typed;
    }

    /**
     * Builds the definition this form describes, carrying over the previous server's id.
     *
     * <p>The id is what credentials are filed under, so it has to survive an edit. A form
     * that rebuilt the definition from scratch would generate a new id, and every password
     * the user had saved for this server would be silently orphaned — the server would keep
     * working anonymously and the user would have no idea why.</p>
     *
     * @param previous the server being edited, or {@code null} when adding a new one
     * @throws IllegalArgumentException when a field is missing or the URL is unusable; the
     *                                  message is written to be shown to the user as-is
     */
    ServerDefinition toDefinition(ServerDefinition previous) {
        FhirServerPlugin plugin = pluginBox.getSelectionModel().getSelectedItem();
        ServerDefinition.Builder builder = ServerDefinition.named(
                        nameField.getText(), urlField.getText())
                .fhirVersion(selected(versionBox.getSelectionModel().getSelectedItem(), "R4"))
                .pluginId(plugin == null ? "" : plugin.id());
        if (previous != null) {
            builder.id(previous.id());
        }
        return builder.build();
    }

    /** Enables every control, or locks the form while a connection test is running. */
    void setEnabled(boolean enabled) {
        for (Control control : fields()) {
            control.setDisable(!enabled);
        }
        applyAuthVisibility();
    }

    private static String selected(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void applyAuthVisibility() {
        ServerAuthKind kind = authKind();
        boolean anonymous = kind.isAnonymous();
        boolean bearer = kind == ServerAuthKind.BEARER;

        // Anonymous hides both credential rows; Basic shows both; Bearer shows only the
        // secret, and relabels it because a token is not a password.
        userLabel.setVisible(!anonymous && kind.needsUserName());
        userField.setVisible(!anonymous && kind.needsUserName());
        credentialHint.setVisible(!anonymous);
        secretLabel.setVisible(!anonymous);
        secretField.setVisible(!anonymous);
        secretLabel.setText(bearer ? "Token" : "Password");
        secretField.setPromptText(bearer ? "access token" : "password");
        credentialHint.setText(bearer
                ? "Stored encrypted on this computer. Unlock it with the passphrase from"
                        + " Tools > Server Plugins..."
                : "Stored encrypted on this computer, and never written to the server list.");
    }

    private void renderPluginNames() {
        pluginBox.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(FhirServerPlugin item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item.displayName() + " — " + item.description());
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

    private void addRow(GridPane grid, int row, String label, Region control) {
        grid.add(rowLabel(label), 0, row);
        grid.add(control, 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
        control.setMaxWidth(Double.MAX_VALUE);
    }
}
