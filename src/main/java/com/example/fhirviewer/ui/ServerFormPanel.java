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

    /**
     * The server selector: an editable combo listing the configured servers.
     *
     * <p>Editable rather than a plain drop-down because it has to do two jobs. Choosing an
     * existing server switches the form to it; typing a name not in the list starts a new
     * one. A read-only combo could only do the first, and a separate list beside the form
     * split the same choice across two places.</p>
     */
    private final ComboBox<String> serverBox = new ComboBox<>();
    private final TextField nameField = new TextField();
    private final TextField urlField = new TextField();

    /**
     * The vendor's administration API, when it is served from a different origin.
     *
     * <p>Optional, and blank is the normal and correct state. Almost every server serves its
     * administration endpoints from the FHIR root, so requiring this would be requiring
     * something almost nobody needs — which is why it sits below the fields that do
     * matter and says so.</p>
     */
    private final TextField adminUrlField = new TextField();
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<FhirServerPlugin> pluginBox = new ComboBox<>();
    private final ComboBox<ServerAuthKind> authBox = new ComboBox<>();
    private final TextField userField = new TextField();
    private final PasswordField secretField = new PasswordField();

    private final Label userLabel;
    private final Label secretLabel;
    private final Label credentialHint;

    ServerFormPanel(FhirServerService serverService) {
        serverBox.setEditable(true);
        // The blank entry is how a new server is started. Without it an editable combo opens
        // on the first configured server, so opening the dialog would silently select one
        // and a stray edit would rewrite it.
        serverBox.getItems().add("");
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
        addRow(grid, row++, "Server", serverBox);
        addRow(grid, row++, "Name", nameField);
        addRow(grid, row++, "Base URL", urlField);
        // Optional and usually blank. Its tooltip says why, because a field with no
        // explanation is one users fill in with the FHIR URL again.
        addRow(grid, row++, "Admin URL", adminUrlField);
        adminUrlField.setPromptText("Usually leave blank");
        adminUrlField.setTooltip(new Tooltip(
                "Only if this server's administration API is on a different address, "
                        + "such as Smile CDR's JSON Admin API on port 9000. Leave blank "
                        + "and operations go to the base URL above."));
        addRow(grid, row++, "FHIR version", versionBox);
        addRow(grid, row++, "Server type", pluginBox);
        addRow(grid, row++, "Authentication", authBox);
        // Both of these need the increment. Without it they were added to the *same* row,
        // so the two labels were drawn on top of each other and the label column clipped
        // them to an ellipsis - reported as "labels overlapping" and "a label showing
        // ....". Row positions are invisible to every other check here: the controls were
        // all present, enabled and correctly populated, they were just in the wrong place.
        addRow(grid, row++, userLabel, userField);
        addRow(grid, row++, secretLabel, secretField);

        // The hint spans both columns so it can use the full width rather than being
        // squeezed into the control column, which is what made it unreadable.
        GridPane.setColumnSpan(credentialHint, 2);
        GridPane.setHgrow(credentialHint, Priority.ALWAYS);
        grid.add(credentialHint, 0, ++row);
        return grid;
    }

    /** The fields a test drives, so it can fill the form as a user would. */
    List<Control> fields() {
        return List.of(serverBox, nameField, urlField, adminUrlField, versionBox, pluginBox,
                authBox, userField, secretField);
    }

    /** The server selector, so the dialog can list the configured servers into it. */
    ComboBox<String> serverBox() {
        return serverBox;
    }

    /**
     * Replaces the drop-down's contents with the configured server names.
     *
     * <p>The blank entry stays at the top. Order is the caller's configuration order rather
     * than alphabetical: the list on screen matches what the user arranged, and a name they
     * gave something deliberately is not re-sorted away from it.</p>
     */
    void offerServers(List<String> names) {
        // Suppressed, because repopulating the drop-down selects its first entry. Without
        // this the dialog would be told a server had been chosen while it was only listing
        // them, and would clear the form it was in the middle of showing.
        suppressSelectionEvents = true;
        try {
            serverBox.getItems().clear();
            serverBox.getItems().add("");
            if (names != null) {
                serverBox.getItems().addAll(names);
            }
            serverBox.getSelectionModel().selectFirst();
        } finally {
            suppressSelectionEvents = false;
        }
    }

    /** Selects a server by name, or the blank entry when {@code name} is {@code null}. */
    void selectServer(String name) {
        serverBox.getSelectionModel().select(name == null ? "" : name);
        if (serverBox.isEditable()) {
            serverBox.getEditor().setText(name == null ? "" : name);
        }
    }

    /** The server currently chosen in the selector, or {@code null} for "a new server". */
    String selectedServer() {
        String value = serverBox.getValue();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Called when the user picks a different server from the selector.
     *
     * <p>Only fires for a change the user made, not for the ones this class makes while
     * populating or resetting, or opening the dialog would immediately load a server and
     * make an ordinary "look at my settings" visit start an edit.</p>
     */
    void onServerChosen(java.util.function.Consumer<String> handler) {
        serverBox.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (!suppressSelectionEvents) {
                handler.accept(now);
            }
        });
    }

    /** True while this class is changing the selection itself, to avoid re-entering. */
    private boolean suppressSelectionEvents;

    /** Sets the selection without telling the dialog, for when it is driving. */
    void selectServerQuietly(String name) {
        suppressSelectionEvents = true;
        try {
            selectServer(name);
        } finally {
            suppressSelectionEvents = false;
        }
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
        adminUrlField.setText(
                definition.administrationBaseUrl() == null ? "" : definition.administrationBaseUrl());
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
        adminUrlField.clear();
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
                .pluginId(plugin == null ? "" : plugin.id())
                // Blank is the normal case and means "no separate administration origin".
                // Passing it through unconditionally keeps an invalid value here reported as
                // the same clear "must start with http:// or https://" the base URL gives.
                .administrationBaseUrl(adminUrlField.getText());
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
        addRow(grid, row, rowLabel(label), control);
    }

    /**
     * Adds a row using a label this class already holds.
     *
     * <p>Needed for the two credential rows. {@link #applyAuthVisibility()} shows and hides
     * those labels, and relabels the secret one to "Token" for a bearer credential - but
     * {@code addRow} builds its own label, so the ones this class holds were never in the
     * grid at all. The fields appeared and disappeared correctly while their labels did
     * nothing: "Password" stayed on screen for a token, and for an anonymous server a stray
     * label described fields that were not there.</p>
     */
    private void addRow(GridPane grid, int row, Label label, Region control) {
        grid.add(label, 0, row);
        grid.add(control, 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
        control.setMaxWidth(Double.MAX_VALUE);
    }
}
