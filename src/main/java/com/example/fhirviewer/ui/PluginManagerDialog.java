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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;
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
    private Label statusLabel;

    private TextField baseUrlField;
    private TextField userNameField;
    private PasswordField passwordField;
    private PasswordField passphraseField;
    private CheckBox loadOnStartCheck;
    private ComboBox<FhirServerPlugin> pluginChoice;
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
        // The current selection rather than null. Selecting above fires the listener, and
        // clearing the form here would leave a chosen plugin showing "No plugin selected."
        // with Save disabled - a plugin picked, and a form that denies it.
        refreshConfiguredLabel(pluginChoice.getSelectionModel().getSelectedItem());
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

        VBox right = new VBox(6, heading("Plugin settings"), createPluginsPane(), createSettingsPane());
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

    /** Which plugin the settings form below is editing. */
    private VBox createPluginsPane() {
        pluginChoice = new ComboBox<>();
        pluginChoice.setMaxWidth(Double.MAX_VALUE);
        pluginChoice.setPromptText("no plugins loaded");

        // The display name, not toString(). A closed drop-down shows a single line, and the
        // default rendering would put the class name and hash on it - too long to fit, and
        // no more readable than the plain name it is replacing.
        pluginChoice.setConverter(new StringConverter<FhirServerPlugin>() {
            @Override
            public String toString(FhirServerPlugin plugin) {
                return plugin == null ? "" : plugin.displayName();
            }

            @Override
            public FhirServerPlugin fromString(String text) {
                return null;
            }
        });
        pluginChoice.setCellFactory(view -> new ListCell<FhirServerPlugin>() {
            @Override
            protected void updateItem(FhirServerPlugin item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        });

        // Driven by the selection model rather than by a mouse click.
        //
        // Mouse-only was the whole of the wiring, so the settings form reacted to exactly
        // one way of choosing a row. Select one any other way - keyboard, programmatic, a
        // drop-down pick that arrives as a value change rather than as a click on a list -
        // and the form stayed on "No plugin selected." with Save disabled: the plugin looks
        // chosen and nothing happens. That is how "I can't select the Smile Plugin" reads.
        // Listening to the selection covers every way a choice can be made, in either control.
        pluginChoice.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, selected) -> refreshConfiguredLabel(selected));

        Label label = new Label("Plugin:");
        label.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(pluginChoice, javafx.scene.layout.Priority.ALWAYS);
        HBox row = new HBox(8, label, pluginChoice);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(row);
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
        // The drop-down above is one line tall, so the form takes the height the column has
        // left. The column used to spend nearly all of it on a list of three rows.
        VBox.setVgrow(box, javafx.scene.layout.Priority.ALWAYS);
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

    /** Fills the plugin drop-down from the registry, opening on the first plugin. */
    private void refreshLoadedPlugins() {
        pluginChoice.getItems().setAll(registry.plugins());
        // Open on a plugin rather than on "No plugin selected." The form exists to edit
        // whichever plugin is chosen, and starting with nothing chosen meant the first thing
        // anyone saw was a greyed-out Save button and a form whose instructions were above
        // it rather than in it. An empty registry selects nothing, which is the honest state
        // there and leaves the form cleared and disabled as before.
        if (pluginChoice.getSelectionModel().getSelectedItem() == null) {
            pluginChoice.getSelectionModel().selectFirst();
        }
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

    /** The plugin drop-down, so a test can see what this page offers for selection. */
    ComboBox<FhirServerPlugin> pluginChoice() {
        return pluginChoice;
    }

    /** The Save Settings button, so a test can assert a selection actually enables it. */
    Button saveSettingsButton() {
        return saveButton;
    }

    /** The passphrase field, so a test can fill it the way a user would. */
    PasswordField passphraseInput() {
        return passphraseField;
    }

    /**
     * Takes a passphrase into the session without writing anything to the store.
     *
     * <p>Proves it first against whatever password this plugin has saved. Adopting a value
     * that does not unlock what is already on disk would look like it had worked, then fail
     * later as an anonymous request and a 401 with nothing to explain it.
     *
     * <p>A plugin with nothing stored cannot be checked - there is nothing to unlock - and
     * there the value is simply taken. That is the normal case: the password lives against
     * the server rather than under the plugin id, so there is usually nothing here to test
     * against, and this is the branch that removes the second entry of the password.
     */
    private void adoptPassphrase(FhirServerPlugin plugin, String passphrase) {
        boolean hasStoredPassword = false;
        try {
            PluginSettings saved = settingsStore.read(plugin.id());
            hasStoredPassword = saved != null && saved.password() != null
                    && !saved.password().isBlank();
            if (hasStoredPassword) {
                settingsStore.unlock(plugin.id(), passphrase);
            }
        } catch (SecretBoxException wrong) {
            status("That passphrase does not unlock the password saved for "
                    + plugin.displayName() + ".");
            return;
        } catch (IOException e) {
            status("Could not read settings: " + e.getMessage());
            return;
        }
        passphraseSink.accept(passphrase);
        passphraseField.clear();
        status(hasStoredPassword
                ? "Passphrase accepted; it unlocks the password saved for "
                        + plugin.displayName() + "."
                : "Passphrase set for this session. It will be used the next time a password"
                        + " is saved.");
    }

    /**
     * Saves the settings form for the selected plugin, encrypting the password when one
     * is supplied. A blank password with a non-blank user name is rejected, because
     * storing that would silently produce a half-configured credential.
     */
    private void saveSettings() {
        FhirServerPlugin plugin = pluginChoice.getSelectionModel().getSelectedItem();
        if (plugin == null) {
            status("Select a plugin to save settings for.");
            return;
        }
        String url = text(baseUrlField);
        String user = text(userNameField);
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        String passphrase = passphraseField.getText() == null ? "" : passphraseField.getText();

        // A passphrase on its own, with no password to encrypt.
        //
        // This is the way in for someone who only needs the session to hold a passphrase,
        // which is exactly what the server dialog asks for when it reports NEEDS_PASSPHRASE.
        // Before this branch existed the passphrase could only be established by typing a
        // password here first and then typing the same password again on the server - two
        // entries of one secret, with no way to do it in one.
        //
        // Nothing is written. There is no password to encrypt, and a write would either
        // clear the one that is stored or re-encrypt ciphertext as though it were plaintext.
        // The session adopts the value and the field is cleared, as it is after a save.
        // A passphrase on its own, with no password to encrypt.
        //
        // This is the way in for someone who only needs the session to hold a passphrase,
        // which is exactly what the server dialog asks for when it reports NEEDS_PASSPHRASE.
        // Before this branch existed the passphrase could only be established by typing a
        // password here first and then typing the same password again on the server - two
        // entries of one secret, with no way to do it in one.
        //
        // Nothing is written. There is no password to encrypt, and a write would either
        // clear the one that is stored or re-encrypt ciphertext as though it were plaintext.
        // The session adopts the value and the field is cleared, as it is after a save.
        if (password.isEmpty() && !passphrase.isEmpty()) {
            adoptPassphrase(plugin, passphrase);
            return;
        }

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
        FhirServerPlugin plugin = pluginChoice.getSelectionModel().getSelectedItem();
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
