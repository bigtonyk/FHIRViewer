package com.example.fhirviewer.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;

import com.example.fhirviewer.server.FhirServerPlugin;
import com.example.fhirviewer.server.FhirServerPluginRegistry;
import com.example.fhirviewer.server.PluginConfig;
import com.example.fhirviewer.server.PluginJarScanner;
import com.example.fhirviewer.server.PluginSettings;
import com.example.fhirviewer.server.PluginSettingsStore;
import com.example.fhirviewer.server.SecretBoxException;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Dialog for discovering, enabling and configuring FHIR server plugins.
 *
 * <p>The dialog is a front end over three service-layer pieces and deliberately holds no
 * plugin logic of its own:</p>
 * <ul>
 *   <li>{@link PluginJarScanner} reads a folder of jars and reports which ones declare a
 *       server plugin, without loading any of them;</li>
 *   <li>{@link PluginConfig} is the file that says which plugin classes are enabled or
 *       disabled, and this dialog can write to it;</li>
 *   <li>{@link PluginSettingsStore} holds each plugin's server URL and encrypted password.</li>
 * </ul>
 *
 * <p>Passwords are only ever written through {@link PluginSettingsStore}, which encrypts
 * them; the passphrase is asked for in this dialog and is never itself stored. A plugin
 * the user has not unlocked shows its saved URL but not its password, and connecting with
 * it requires re-entering the passphrase.</p>
 *
 * <p>The passphrase is also handed to the {@code passphraseSink} on a successful save.
 * The fields are cleared the moment settings are written — which is right for the dialog
 * but leaves the session unable to read the password back — so without that hand-off a
 * credential saved here could never authenticate anything.</p>
 */
public class PluginManagerDialog extends Dialog<Void> {
    private static final Logger logger = Logger.getLogger(PluginManagerDialog.class.getName());

    private static final double MIN_WIDTH = 1120;
    private static final double PREF_WIDTH = 1300;
    private static final double PREF_HEIGHT = 470;
    private static final double MIN_HEIGHT = 380;

    /** Preference key holding the last scanned plugin folder. */
    private static final String PLUGIN_FOLDER_KEY = "pluginFolder";

    /** The preferences node this dialog stores its own settings under. */
    private static final String PREFERENCE_NODE = "com/example/fhirviewer/pluginManager";

    private final FhirServerPluginRegistry registry;
    private final PluginSettingsStore settingsStore;
    private final Path pluginConfigFile;
    private final Consumer<String> passphraseSink;

    private TextField folderField;
    private ListView<PluginJarScanner.ScannedJar> foundJarsList;
    private ListView<FhirServerPlugin> loadedPluginsList;
    private Label statusLabel;

    private TextField baseUrlField;
    private TextField userNameField;
    private PasswordField passwordField;
    private PasswordField passphraseField;
    private CheckBox loadOnStartCheck;
    private Button saveButton;
    private Button removeButton;
    private Label configuredForLabel;

    /**
     * @param passphraseSink receives the passphrase once settings have been saved with
     *                       it, so the session can keep unlocking them afterwards; may
     *                       be {@code null} in a caller that does not authenticate
     */
    public PluginManagerDialog(Window parentWindow, FhirServerPluginRegistry registry,
                               PluginSettingsStore settingsStore, Path pluginConfigFile,
                               Consumer<String> passphraseSink) {
        this.registry = registry;
        this.settingsStore = settingsStore;
        this.pluginConfigFile = pluginConfigFile;
        this.passphraseSink = passphraseSink;

        setTitle("FHIR Server Plugins");
        setHeaderText("Discover plugins, enable them on start-up, and save per-plugin settings");
        setResizable(true);

        getDialogPane().setContent(createContent());
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setMinWidth(MIN_WIDTH);
        getDialogPane().setPrefWidth(PREF_WIDTH);
        getDialogPane().setMinHeight(MIN_HEIGHT);
        getDialogPane().setPrefHeight(PREF_HEIGHT);

        folderField.setText(rememberedPluginFolder());

        refreshLoadedPlugins();
        refreshConfiguredLabel(null);
        initOwner(parentWindow);
        initModality(Modality.WINDOW_MODAL);
    }

    /**
     * The plugin folder remembered from the last run, or an empty string.
     *
     * <p>Uses an explicit node path under the user root rather than
     * {@code Preferences.userNodeForClass}, which no longer exists in current JDKs.
     * A preference backend is not guaranteed to be available (a locked or read-only
     * preferences directory makes the first call throw), so this never propagates an
     * exception into the constructor.</p>
     */
    private static String rememberedPluginFolder() {
        try {
            return preferenceNode().get(PLUGIN_FOLDER_KEY, "");
        } catch (RuntimeException | BackingStoreException e) {
            logger.log(java.util.logging.Level.FINE,
                    "could not read the remembered plugin folder", e);
            return "";
        }
    }

    /** Remembers the scanned folder so it survives a restart. */
    private void rememberPluginFolder(String folder) {
        try {
            preferenceNode().put(PLUGIN_FOLDER_KEY, folder == null ? "" : folder);
        } catch (RuntimeException | BackingStoreException e) {
            logger.log(java.util.logging.Level.FINE,
                    "could not remember the plugin folder", e);
        }
    }

    /** The single preferences node this dialog owns. */
    private static java.util.prefs.Preferences preferenceNode() throws BackingStoreException {
        return java.util.prefs.Preferences.userRoot().node(PREFERENCE_NODE);
    }

    /**
     * The dialog body.
     *
     * <p>Laid out as one row on top, two columns in the middle and a status line at the
     * bottom. The earlier arrangement stacked discovery, the loaded list and the settings
     * form as three full-width bands, which forced a tall window and left the loaded list
     * squeezed into whatever height was left over. Putting the settings form beside the
     * lists instead lets both lists use the full height.</p>
     */
    private BorderPane createContent() {
        BorderPane pane = new BorderPane();
        pane.setPadding(new Insets(12));
        pane.setTop(createDiscoveryRow());
        pane.setCenter(createColumns());
        pane.setBottom(createStatusBar());
        return pane;
    }

    /** The middle: scanned jars on the left, loaded plugins and their settings on the right. */
    private HBox createColumns() {
        VBox left = new VBox(6, heading("Jars found in the folder"), foundJarsList, createConfigBar());
        VBox.setVgrow(left, javafx.scene.layout.Priority.ALWAYS);

        VBox right = new VBox(6, heading("Loaded plugins"), createPluginsPane(), createSettingsPane());
        VBox.setVgrow(right, javafx.scene.layout.Priority.ALWAYS);

        HBox.setHgrow(left, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(right, javafx.scene.layout.Priority.ALWAYS);
        HBox.setMargin(right, new Insets(0, 0, 0, 12));
        return new HBox(0, left, right);
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("pretty-row-label");
        return label;
    }

    /** The top row: a folder to scan plus the two buttons that act on it. */
    private HBox createDiscoveryRow() {
        folderField = new TextField();
        folderField.setPromptText("Folder containing plugin .jar files");
        HBox.setHgrow(folderField, javafx.scene.layout.Priority.ALWAYS);

        Button browse = new Button("Browse...");
        browse.setOnAction(e -> chooseFolder());
        Button scan = new Button("Scan Folder");
        scan.setOnAction(e -> scanFolder());
        keepLabelsVisible(browse, scan);

        foundJarsList = new ListView<>();
        VBox.setVgrow(foundJarsList, javafx.scene.layout.Priority.ALWAYS);

        HBox row = new HBox(8, new Label("Plugin folder:"), folderField, browse, scan);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 0, 10, 0));
        return row;
    }

    /** The button that writes the selected jar's plugin classes into the config file. */
    private ButtonBar createConfigBar() {
        Button addToConfig = new Button("Enable Selected in Config");
        addToConfig.setOnAction(e -> enableSelected());
        keepLabelsVisible(addToConfig);
        ButtonBar configBar = new ButtonBar();
        configBar.getButtons().add(addToConfig);
        return configBar;
    }

    /** The middle-right: the plugins actually loaded in this run. */
    private VBox createPluginsPane() {
        loadedPluginsList = new ListView<>();
        VBox.setVgrow(loadedPluginsList, javafx.scene.layout.Priority.ALWAYS);
        // Driven by the selection model rather than by a mouse click.
        //
        // Mouse-only was the whole of the wiring, which meant the settings form only ever
        // reacted to one specific way of choosing a row. A keyboard selection, a selection
        // made programmatically, or any click that did not land as a mouse-click event on
        // the list left the form saying "No plugin selected." with Save disabled - so the
        // plugin could be chosen and appear not to take. Listening to the selection covers
        // every way a row can become selected, mouse or otherwise.
        // Driven by the selection model rather than by a mouse click.
        //
        // Mouse-only was the whole of the wiring, so the settings form reacted to exactly
        // one way of choosing a row. Select one any other way - keyboard, programmatic, a
        // click that did not arrive as a mouse-click event - and the form stayed on "No
        // plugin selected." with Save disabled: the plugin appears chosen, and nothing
        // happens. The reported "I can't select the Smile Plugin" is this. Listening to the
        // selection covers every way a row can become selected.
        loadedPluginsList.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, selected) -> refreshConfiguredLabel(selected));
        return new VBox(loadedPluginsList);
    }

    /** A one-line status area at the bottom of the dialog. */
    private VBox createStatusBar() {
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setPadding(new Insets(8, 0, 0, 0));
        VBox box = new VBox(statusLabel);
        box.setPadding(new Insets(0, 12, 0, 12));
        return box;
    }

    /** The per-plugin settings form: where the server is and how to authenticate. */
    private VBox createSettingsPane() {
        baseUrlField = new TextField();
        baseUrlField.setPromptText("https://your-server.example.com/fhir");
        userNameField = new TextField();
        userNameField.setPromptText("leave blank for anonymous access");
        passwordField = new PasswordField();
        passwordField.setPromptText("password, stored encrypted");
        passphraseField = new PasswordField();
        passphraseField.setPromptText("passphrase used to encrypt the password");
        loadOnStartCheck = new CheckBox("Load these settings at start-up");
        // The right-hand column is half the dialog width, so the caption has to wrap
        // rather than push the column wider than the dialog.
        loadOnStartCheck.setWrapText(true);
        loadOnStartCheck.setMaxWidth(Double.MAX_VALUE);
        configuredForLabel = new Label("No plugin selected.");
        configuredForLabel.setWrapText(true);

        HBox.setHgrow(baseUrlField, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(userNameField, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(passwordField, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(passphraseField, javafx.scene.layout.Priority.ALWAYS);

        HBox urlRow = labelledRow("Server URL:", baseUrlField);
        HBox credentialsRow = pairedRow("User name:", userNameField, "Password:", passwordField);
        HBox phraseRow = labelledRow("Passphrase:", passphraseField);

        saveButton = new Button("Save Settings");
        saveButton.setOnAction(e -> saveSettings());
        removeButton = new Button("Remove from Config");
        removeButton.setOnAction(e -> removeFromConfig());
        keepLabelsVisible(saveButton, removeButton);

        HBox buttons = new HBox(8, saveButton, removeButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, configuredForLabel, urlRow, credentialsRow, phraseRow,
                loadOnStartCheck, buttons);
        box.setPadding(new Insets(10, 0, 0, 0));
        return box;
    }

    /**
     * Two labelled fields sharing one row, so the credentials block is two columns wide
     * instead of stacking and making the dialog taller than it needs to be.
     */
    private static HBox pairedRow(String firstCaption, javafx.scene.control.TextInputControl first,
            String secondCaption, javafx.scene.control.TextInputControl second) {
        Label firstLabel = new Label(firstCaption);
        Label secondLabel = new Label(secondCaption);
        // Pin the captions so a narrow dialog elides the field text, not the labels.
        firstLabel.setMinWidth(Region.USE_PREF_SIZE);
        secondLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(8, firstLabel, first, secondLabel, second);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * A label plus a field that fills the remaining width.
     *
     * <p>The label is pinned to its preferred width so a narrow column elides the text in
     * the field rather than turning the caption itself into "...".</p>
     */
    private static HBox labelledRow(String caption, javafx.scene.control.TextInputControl field) {
        Label label = new Label(caption);
        label.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(8, label, field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Stops button labels collapsing to an ellipsis when the dialog is narrowed, which is
     * what an HBox does to its children once the pane is too small for their preferred size.
     */
    private static void keepLabelsVisible(Button... buttons) {
        for (Button button : buttons) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose the folder that holds plugin jars");
        if (!folderField.getText().isBlank()) {
            try {
                chooser.setInitialDirectory(Path.of(folderField.getText().trim()).toFile());
            } catch (RuntimeException e) {
                logger.log(java.util.logging.Level.FINE, "ignoring unusable folder path", e);
            }
        }
        java.io.File chosen = chooser.showDialog(getOwner());
        if (chosen != null) {
            folderField.setText(chosen.getAbsolutePath());
            rememberPluginFolder(chosen.getAbsolutePath());
            scanFolder();
        }
    }

    /** Scans the chosen folder. Scanning only reads jar headers; it loads no code. */
    private void scanFolder() {
        String text = folderField.getText() == null ? "" : folderField.getText().trim();
        if (text.isEmpty()) {
            status("Choose a folder to scan first.");
            return;
        }
        Path folder = Path.of(text);
        if (!java.nio.file.Files.isDirectory(folder)) {
            status("That is not a folder: " + folder);
            return;
        }
        List<PluginJarScanner.ScannedJar> found = PluginJarScanner.scan(folder);
        foundJarsList.getItems().setAll(found);
        long withPlugins = found.stream().filter(j -> !j.declaredClasses().isEmpty()).count();
        if (found.isEmpty()) {
            status("No .jar files in " + folder + ".");
        } else {
            status("Found " + found.size() + " jar(s); " + withPlugins + " declare a server plugin. "
                    + "Add the jar to the application class path, then enable its class below.");
        }
    }

    /** Writes the selected jar's plugin classes into the enabled list in the config file. */
    private void enableSelected() {
        PluginJarScanner.ScannedJar selected = foundJarsList.getSelectionModel().getSelectedItem();
        if (selected == null || selected.declaredClasses().isEmpty()) {
            status("Select a jar that declares a server plugin first.");
            return;
        }
        try {
            java.util.Set<String> enabled = new java.util.LinkedHashSet<>(currentConfig().enabledClasses());
            enabled.addAll(selected.declaredClasses());
            writeConfig(enabled, currentConfig().disabledClasses());
            status("Enabled " + selected.declaredClasses() + " in " + pluginConfigFile
                    + ". Restart to load " + selected.label() + ".");
        } catch (IOException e) {
            status("Could not write " + pluginConfigFile + ": " + e.getMessage());
        }
    }

    /** Fills the loaded-plugins list from the registry. */
    private void refreshLoadedPlugins() {
        loadedPluginsList.getItems().setAll(registry.plugins());
    }

    /**
     * Shows which plugin the settings form is currently editing, and loads that
     * plugin's saved values. A {@code null} selection clears the form.
     */
    private void refreshConfiguredLabel(FhirServerPlugin plugin) {
        if (plugin == null) {
            configuredForLabel.setText("No plugin selected.");
            baseUrlField.clear();
            userNameField.clear();
            passwordField.clear();
            passphraseField.clear();
            loadOnStartCheck.setSelected(false);
            saveButton.setDisable(true);
            removeButton.setDisable(true);
            return;
        }
        configuredForLabel.setText("Settings for " + plugin.displayName() + " (" + plugin.id() + "):");
        saveButton.setDisable(false);
        try {
            PluginSettings saved = settingsStore.read(plugin.id());
            baseUrlField.setText(saved == null || saved.baseUrl() == null ? "" : saved.baseUrl());
            userNameField.setText(saved == null || saved.userName() == null ? "" : saved.userName());
            // The stored password stays encrypted on disk; it is never echoed into the form.
            passwordField.clear();
            loadOnStartCheck.setSelected(settingsStore.loadsOnStart(plugin.id()));
            removeButton.setDisable(saved == null);
        } catch (IOException e) {
            status("Could not read settings: " + e.getMessage());
        }
    }

    /** The loaded-plugins list, so a test can see what this page offers for selection. */
    ListView<FhirServerPlugin> loadedPluginsList() {
        return loadedPluginsList;
    }

    /** The Save Settings button, so a test can assert a selection actually enables it. */
    Button saveSettingsButton() {
        return saveButton;
    }

    /**
     * Saves the settings form for the selected plugin, encrypting the password when one
     * is supplied. A blank password with a non-blank user name is rejected, because
     * storing that would silently produce a half-configured credential.
     */
    private void saveSettings() {
        FhirServerPlugin plugin = loadedPluginsList.getSelectionModel().getSelectedItem();
        if (plugin == null) {
            status("Select a plugin to save settings for.");
            return;
        }
        String url = text(baseUrlField);
        String user = text(userNameField);
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        String passphrase = passphraseField.getText() == null ? "" : passphraseField.getText();

        if (url.isEmpty()) {
            status("A server URL is required.");
            return;
        }
        if (password.isEmpty()) {
            if (!user.isEmpty()) {
                status("A user name needs a password, or leave both blank for anonymous access.");
                return;
            }
            try {
                settingsStore.save(new PluginSettings(plugin.id(), url, user, null), "");
                settingsStore.setLoadsOnStart(plugin.id(), loadOnStartCheck.isSelected());
                // The password is gone, so the session must forget the passphrase too, or
                // it would sit in memory for a credential that no longer exists.
                passphraseSink.accept(null);
                status("Saved anonymous settings for " + plugin.displayName()
                        + ". Any previously stored password was removed.");
            } catch (SecretBoxException e) {
                status("Could not save settings: " + e.getMessage());
            } catch (IOException e) {
                status("Could not save settings: " + e.getMessage());
            }
            return;
        }
        if (passphrase.isEmpty()) {
            status("A passphrase is required to encrypt the password.");
            return;
        }
        try {
            settingsStore.save(new PluginSettings(plugin.id(), url, user, password), passphrase);
            settingsStore.setLoadsOnStart(plugin.id(), loadOnStartCheck.isSelected());
            // Hand the passphrase to the session before clearing the field: these fields
            // are wiped on purpose, and without this the password could never be used.
            passphraseSink.accept(passphrase);
            passwordField.clear();
            passphraseField.clear();
            status("Saved settings for " + plugin.displayName() + "; the password is encrypted.");
        } catch (SecretBoxException e) {
            status("Could not encrypt the password: " + e.getMessage());
        } catch (IOException e) {
            status("Could not save settings: " + e.getMessage());
        }
    }

    /** Deletes a plugin's saved settings, leaving the plugin itself loaded. */
    private void removeFromConfig() {
        FhirServerPlugin plugin = loadedPluginsList.getSelectionModel().getSelectedItem();
        if (plugin == null) {
            status("Select a plugin first.");
            return;
        }
        try {
            settingsStore.remove(plugin.id());
            status("Removed the saved settings for " + plugin.displayName() + ".");
            refreshConfiguredLabel(plugin);
        } catch (IOException e) {
            status("Could not remove settings: " + e.getMessage());
        }
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }

    /** The configuration currently on disk, or the bundled default when it cannot be read. */
    private PluginConfig currentConfig() {
        try {
            return Files.isReadable(pluginConfigFile)
                    ? PluginConfig.load(pluginConfigFile)
                    : PluginConfig.defaults();
        } catch (IOException e) {
            status("Could not read " + pluginConfigFile + ": " + e.getMessage());
            return PluginConfig.defaults();
        }
    }

    /** Rewrites the config file with the given enabled and disabled class sets. */
    private void writeConfig(java.util.Set<String> enabled, java.util.Set<String> disabled)
            throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("# FHIRViewer plugin configuration.\n")
                .append("# Classes listed as enabled are loaded on start-up, whether or not they\n")
                .append("# appear in META-INF/services. Classes listed as disabled are never loaded.\n")
                .append(PluginConfig.ENABLED_KEY).append('=').append(String.join(",", enabled)).append('\n')
                .append(PluginConfig.DISABLED_KEY).append('=').append(String.join(",", disabled)).append('\n');
        Path parent = pluginConfigFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(pluginConfigFile, text.toString(), StandardCharsets.UTF_8);
    }

    private void status(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message);
        }
    }
}
