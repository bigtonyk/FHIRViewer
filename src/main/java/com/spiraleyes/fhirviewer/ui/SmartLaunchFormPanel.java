package com.spiraleyes.fhirviewer.ui;

import java.util.List;
import java.util.UUID;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import com.spiraleyes.fhirviewer.server.smart.SmartLaunchConfig;
import com.spiraleyes.fhirviewer.server.smart.SmartLaunchMode;

/**
 * The editable fields of one SMART launch: identity, server, client registration and the
 * access being asked for.
 *
 * <p>Separated from {@link SmartLaunchDialog} for the same reason {@link ServerFormPanel} is
 * separated from {@link ServerManagerDialog}: the dialog holds a list beside these fields
 * without either file carrying the other's concerns, and the field set can be built and
 * driven in a test without a dialog around it.</p>
 *
 * <p><b>There is no client secret field, and there never will be.</b> A SMART client on the
 * desktop is a public client - anything shipped inside the jar is readable - so the form asks
 * only for the public client id. The hint at the bottom of the grid says so, because a user
 * who has registered clients elsewhere will look for the secret field and deserves to read
 * why it is not here.</p>
 *
 * <p>Two rows belong to one launch mode each, following the credential rows of
 * {@link ServerFormPanel}: the form holds their labels as well as their controls, so
 * {@link #applyModeVisibility()} can show and hide both halves together. A launch parameter
 * only means anything in an EHR launch and an audience only in a standalone one, so showing
 * both at once would invite the user to fill in the wrong half - which
 * {@link SmartLaunchConfig#problems()} would then have to explain back to them.</p>
 */
final class SmartLaunchFormPanel {

    /**
     * The configuration selector: an editable combo listing the saved launches.
     *
     * <p>Editable for the same dual reason as {@link ServerFormPanel#serverBox()}: choosing
     * an existing configuration switches the form to it, and typing a name not in the list
     * starts a new one.
     */
    private final ComboBox<String> configBox = new ComboBox<>();
    private final TextField nameField = new TextField();
    private final TextField issuerField = new TextField();
    private final ComboBox<SmartLaunchMode> modeBox = new ComboBox<>();
    private final TextField clientIdField = new TextField();
    private final TextField redirectUriField = new TextField();
    private final TextField scopesField = new TextField();
    private final TextField launchField = new TextField();
    private final TextField audienceField = new TextField();

    private final Label launchLabel;
    private final Label audienceLabel;
    private final Label publicHint;

    /** True while this class is repopulating the selector, so that produces no "chosen" event. */
    private boolean suppressSelectionEvents;

    SmartLaunchFormPanel() {
        configBox.setEditable(true);
        // The blank entry is how a new configuration is started, exactly as in the server
        // form: without it the selector opens on the first saved launch and merely looking
        // at the workbench would start an edit.
        configBox.getItems().add("");
        modeBox.getItems().setAll(SmartLaunchMode.values());
        modeBox.getSelectionModel().select(SmartLaunchMode.STANDALONE);

        nameField.setPromptText("My SMART launch");
        issuerField.setPromptText("https://example.com/fhir");
        clientIdField.setPromptText("public client id");
        redirectUriField.setPromptText("Leave blank unless the flow registered one");
        scopesField.setPromptText("openid fhirUser launch/patient system/Patient.read");
        launchField.setPromptText("the launch parameter the EHR supplied");
        audienceField.setPromptText("Usually the issuer URL");

        launchLabel = rowLabel("Launch parameter");
        audienceLabel = rowLabel("Audience (aud)");
        launchField.setTooltip(new Tooltip(
                "Only an EHR launch carries one. Real EHR launch arrives in Phase 05; until "
                        + "then this is how a simulated launch supplies the same parameter."));
        audienceField.setTooltip(new Tooltip(
                "Only a standalone launch sends an audience. An EHR launch gets its resource "
                        + "server from the issuer instead, and sending both can be refused."));

        publicHint = new Label("This is a public desktop client: there is no client secret to "
                + "type, and none is stored. Only the public client id belongs here.");
        publicHint.getStyleClass().add("app-subtitle");
        publicHint.setWrapText(true);
        publicHint.setMaxWidth(Double.MAX_VALUE);

        // Recomputed on every change, so switching modes and back behaves - the hidden half
        // keeps whatever was typed, and problems() reports it rather than the form quietly
        // discarding a value the user may still want.
        modeBox.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, current) -> applyModeVisibility());
        applyModeVisibility();
    }

    /** A row label in the house style, so the grid matches every other form. */
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
        // Pinned label column, control column absorbs the rest - same as ServerFormPanel, so
        // the two forms resize identically.
        ColumnConstraints labelColumn = new ColumnConstraints(140);
        labelColumn.setHgrow(Priority.NEVER);
        ColumnConstraints controlColumn = new ColumnConstraints();
        controlColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, controlColumn);

        int row = 0;
        addRow(grid, row++, "Configuration", configBox);
        addRow(grid, row++, "Name", nameField);
        addRow(grid, row++, "FHIR server (iss)", issuerField);
        addRow(grid, row++, "Launch mode", modeBox);
        addRow(grid, row++, "Client ID", clientIdField);
        addRow(grid, row++, "Redirect URI", redirectUriField);
        addRow(grid, row++, "Scopes", scopesField);
        // Both conditional rows increment the counter, exactly as the credential rows in
        // ServerFormPanel: two additions without it would draw the labels on top of each
        // other in one cell.
        addRow(grid, row++, launchLabel, launchField);
        addRow(grid, row++, audienceLabel, audienceField);

        // The hint spans both columns so it can use the full width rather than being
        // squeezed into the control column.
        GridPane.setColumnSpan(publicHint, 2);
        GridPane.setHgrow(publicHint, Priority.ALWAYS);
        grid.add(publicHint, 0, ++row);
        return grid;
    }

    /** The fields a test drives, so it can fill the form as a user would. */
    List<Control> fields() {
        return List.of(configBox, nameField, issuerField, modeBox, clientIdField,
                redirectUriField, scopesField, launchField, audienceField);
    }

    /** The configuration selector, so the dialog can list the saved launches into it. */
    ComboBox<String> configBox() {
        return configBox;
    }

    /** The mode selector, so a test can switch modes the way a user does. */
    ComboBox<SmartLaunchMode> modeBox() {
        return modeBox;
    }

    /** True when the EHR-only launch parameter row is on screen. */
    boolean showsLaunchParameter() {
        return launchField.isVisible();
    }

    /** True when the standalone-only audience row is on screen. */
    boolean showsAudience() {
        return audienceField.isVisible();
    }

    // -- Test access ----------------------------------------------------------
    // Package-private and read-only, so a test can drive the form the way a user does -
    // the same arrangement ServerFormPanel offers.

    TextField nameField() {
        return nameField;
    }

    TextField issuerField() {
        return issuerField;
    }

    TextField clientIdField() {
        return clientIdField;
    }

    TextField redirectUriField() {
        return redirectUriField;
    }

    TextField scopesField() {
        return scopesField;
    }

    TextField launchField() {
        return launchField;
    }

    TextField audienceField() {
        return audienceField;
    }
    /**
     * Replaces the drop-down's contents with the saved configuration names.
     *
     * <p>The blank entry stays at the top and the order is the store's order, for the same
     * reasons as {@link ServerFormPanel#offerServers(List)}.</p>
     */
    void offerConfigs(List<String> names) {
        // Suppressed, because repopulating the drop-down selects its first entry - which
        // would otherwise tell the dialog a configuration had been chosen while the list
        // was only being populated.
        suppressSelectionEvents = true;
        try {
            configBox.getItems().clear();
            configBox.getItems().add("");
            if (names != null) {
                configBox.getItems().addAll(names);
            }
            configBox.getSelectionModel().selectFirst();
        } finally {
            suppressSelectionEvents = false;
        }
    }

    /** Selects a configuration by name, or the blank entry when {@code name} is {@code null}. */
    void selectConfig(String name) {
        selectConfigQuietly(name);
        if (configBox.isEditable()) {
            configBox.getEditor().setText(name == null ? "" : name);
        }
    }

    /** Selects without firing {@link #onConfigChosen(java.util.function.Consumer)} - for this class's own use. */
    void selectConfigQuietly(String name) {
        suppressSelectionEvents = true;
        try {
            configBox.getSelectionModel().select(name == null ? "" : name);
            if (configBox.isEditable()) {
                configBox.getEditor().setText(name == null ? "" : name);
            }
        } finally {
            suppressSelectionEvents = false;
        }
    }

    /** The configuration currently chosen in the selector, or {@code null} for "a new one". */
    String selectedConfig() {
        String value = configBox.getValue();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Called when the user picks a different configuration from the selector.
     *
     * <p>Only fires for a change the user made, never for the ones this class makes while
     * populating - opening the workbench must not start an edit just because it listed the
     * saved launches.</p>
     */
    void onConfigChosen(java.util.function.Consumer<String> handler) {
        configBox.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (!suppressSelectionEvents) {
                handler.accept(now);
            }
        });
    }

    /** Clears every field back to a new configuration. */
    void clear() {
        nameField.clear();
        issuerField.clear();
        clientIdField.clear();
        redirectUriField.clear();
        scopesField.clear();
        launchField.clear();
        audienceField.clear();
        modeBox.getSelectionModel().select(SmartLaunchMode.STANDALONE);
    }

    /** Fills the fields from a saved configuration, which is what makes edit a mode of this form. */
    void load(SmartLaunchConfig config) {
        nameField.setText(config.name());
        issuerField.setText(config.issuer());
        modeBox.getSelectionModel().select(config.mode());
        clientIdField.setText(nullToEmpty(config.clientId()));
        redirectUriField.setText(nullToEmpty(config.redirectUri()));
        scopesField.setText(nullToEmpty(config.scopes()));
        launchField.setText(nullToEmpty(config.launchParameter()));
        audienceField.setText(nullToEmpty(config.audience()));
        applyModeVisibility();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Shows the half of the mode-specific rows the selected mode can use, and hides the
     * other. The hidden values are kept: problems() explains a stray one, and a form that
     * silently discarded input would be worse than one that remembers it.
     */
    private void applyModeVisibility() {
        SmartLaunchMode mode = modeBox.getSelectionModel().getSelectedItem();
        if (mode == null) {
            mode = SmartLaunchMode.STANDALONE;
        }
        launchLabel.setVisible(mode.needsLaunchParameter());
        launchField.setVisible(mode.needsLaunchParameter());
        audienceLabel.setVisible(mode.allowsAudience());
        audienceField.setVisible(mode.allowsAudience());
    }
    /**
     * Builds the configuration the fields describe.
     *
     * <p>The id is carried over from the configuration being edited, or minted for a new
     * one: it is the persistence key, so reusing an old id for a new launch would overwrite
     * the block the old one is stored in.</p>
     *
     * @param previous the configuration being edited, or {@code null} when adding
     * @throws IllegalArgumentException when there is no name, since both the selector and
     *         the store key off it and a nameless launch could not be chosen again
     */
    SmartLaunchConfig toConfig(SmartLaunchConfig previous) {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A launch configuration needs a name.");
        }
        // Raw values, not filtered by the mode: a value typed in the wrong half of the form
        // is reported by problems() on Validate rather than silently dropped here.
        return SmartLaunchConfig.builder()
                .id(previous != null ? previous.id() : UUID.randomUUID().toString())
                .name(name)
                .issuer(issuerField.getText())
                .mode(mode())
                .clientId(clientIdField.getText())
                .redirectUri(redirectUriField.getText())
                .scopes(scopesField.getText())
                .launchParameter(launchField.getText())
                .audience(audienceField.getText())
                .build();
    }

    /** The launch mode currently selected; never {@code null}. */
    SmartLaunchMode mode() {
        SmartLaunchMode selected = modeBox.getSelectionModel().getSelectedItem();
        return selected == null ? SmartLaunchMode.STANDALONE : selected;
    }

    /** Enables every control, or locks the form while the dialog is busy. */
    void setEnabled(boolean enabled) {
        for (Control control : fields()) {
            control.setDisable(!enabled);
        }
        applyModeVisibility();
    }

    private static void addRow(GridPane grid, int row, String label, Region control) {
        addRow(grid, row, rowLabel(label), control);
    }

    /**
     * Adds a row using a label this class already holds.
     *
     * <p>Needed for the two mode-specific rows: {@link #applyModeVisibility()} shows and
     * hides those labels with their controls, and a label built inside {@code addRow} would
     * not be reachable from there - the exact gap the ServerFormPanel javadoc records for
     * the credential rows.</p>
     */
    private static void addRow(GridPane grid, int row, Label label, Region control) {
        grid.add(label, 0, row);
        grid.add(control, 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
        control.setMaxWidth(Double.MAX_VALUE);
    }
}



