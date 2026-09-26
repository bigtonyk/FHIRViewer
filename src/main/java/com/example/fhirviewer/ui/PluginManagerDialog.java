package com.example.fhirviewer.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

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
 */
public class PluginManagerDialog extends Dialog<Void> {
    private static final Logger logger = Logger.getLogger(PluginManagerDialog.class.getName());

    private static final double MIN_WIDTH = 940;
    private static final double PREF_WIDTH = 1080;
    private static final double MIN_HEIGHT = 620;

    private final FhirServerPluginRegistry registry;
    private final PluginSettingsStore settingsStore;
    private final Path pluginConfigFile;

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

    public PluginManagerDialog(Window parentWindow, FhirServerPluginRegistry registry,
                               PluginSettingsStore settingsStore, Path pluginConfigFile) {
        this.registry = registry;
        this.settingsStore = settingsStore;
        this.pluginConfigFile = pluginConfigFile;

        setTitle("FHIR Server Plugins");
        setHeaderText("Discover plugins, enable them on start-up, and save per-plugin settings");
        setResizable(true);

        getDialogPane().setContent(createContent());
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setMinWidth(MIN_WIDTH);
        getDialogPane().setPrefWidth(PREF_WIDTH);
        getDialogPane().setMinHeight(MIN_HEIGHT);

        folderField.setText(String.valueOf(java.util.prefs.Preferences
                .userNodeForClass(PluginManagerDialog.class).get("pluginFolder", "")));

        refreshLoadedPlugins();
        refreshConfiguredLabel(null);
        initOwner(parentWindow);
        initModality(Modality.WINDOW_MODAL);
    }

    private BorderPane createContent() {
        BorderPane pane = new BorderPane();
        pane.setPadding(new Insets(12));
        pane.setTop(createDiscoveryPane());
        pane.setCenter(createPluginsPane());
        VBox footer = new VBox(createSettingsPane(), createStatusBar());
        pane.setBottom(footer);
        return pane;
    }

    /** The top row: a folder to scan, the Scan button, and the list of jars it holds. */
    private VBox createDiscoveryPane() {
        folderField = new TextField();
        folderField.setPromptText("Folder containing plugin .jar files");
        HBox.setHgrow(folderField, javafx.scene.layout.Priority.ALWAYS);

        Button browse = new Button("Browse...");
        browse.setOnAction(e -> chooseFolder());
        Button scan = new Button("Scan Folder");
        scan.setOnAction(e -> scanFolder());

        foundJarsList = new ListView<>();
        foundJarsList.setPrefHeight(140);
        VBox.setVgrow(foundJarsList, javafx.scene.layout.Priority.SOMETIMES);

        HBox row = new HBox(8, new Label("Plugin folder:"), folderField, browse, scan);
        row.setAlignment(Pos.CENTER_LEFT);

        Button addToConfig = new Button("Enable Selected in Config");
        addToConfig.setOnAction(e -> enableSelected());
        ButtonBar configBar = new ButtonBar();
        configBar.getButtons().add(addToConfig);

        VBox box = new VBox(8, row, foundJarsList, configBar);
        box.setPadding(new Insets(0, 0, 10, 0));
        return box;
    }

    /** The middle: the plugins actually loaded in this run. */
    private VBox createPluginsPane() {
        loadedPluginsList = new ListView<>();
        VBox.setVgrow(loadedPluginsList, javafx.scene.layout.Priority.ALWAYS);
        loadedPluginsList.setOnMouseClicked(e -> {
            FhirServerPlugin selected = loadedPluginsList.getSelectionModel().getSelectedItem();
            refreshConfiguredLabel(selected);
        });
        Label heading = new Label("Loaded plugins:");
        return new VBox(6, heading, loadedPluginsList);
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
        loadOnStartCheck = new CheckBox("Load this plugin's settings when the application starts");
        configuredForLabel = new Label("No plugin selected.");

        HBox.setHgrow(baseUrlField, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(userNameField, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(passwordField, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(passphraseField, javafx.scene.layout.Priority.ALWAYS);

        HBox urlRow = new HBox(8, new Label("Server URL:"), baseUrlField);
        HBox userRow = new HBox(8, new Label("User name:"), userNameField);
        HBox passRow = new HBox(8, new Label("Password:"), passwordField,
                new Label("Passphrase:"), passphraseField);

        saveButton = new Button("Save Settings");
        saveButton.setOnAction(e -> saveSettings());
        removeButton = new Button("Remove from Config");
        removeButton.setOnAction(e -> removeFromConfig());
        keepLabelsVisible(saveButton, removeButton);

        HBox buttons = new HBox(8, saveButton, removeButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, configuredForLabel, urlRow, userRow, passRow, loadOnStartCheck, buttons);
        box.setPadding(new Insets(10, 0, 0, 0));
        return box;
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
            java.util.prefs.Preferences.userNodeForClass(PluginManagerDialog.class)
                    .put("pluginFolder", chosen.getAbsolutePath());
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
                settingsStore.saveAnonymous(plugin.id(), url);
                settingsStore.setLoadsOnStart(plugin.id(), loadOnStartCheck.isSelected());
                status("Saved anonymous settings for " + plugin.displayName()
                        + ". Any previously stored password was removed.");
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
            settingsStore.save(plugin.id(), new PluginSettings(plugin.id(), url, user, password),
                    passphrase);
            settingsStore.setLoadsOnStart(plugin.id(), loadOnStartCheck.isSelected());
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
