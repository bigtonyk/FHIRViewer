package com.example.fhirviewer.ui;

import com.example.fhirviewer.model.IgPackageInfo;
import com.example.fhirviewer.service.IgPackageException;
import com.example.fhirviewer.service.IgPackageManager;
import com.example.fhirviewer.service.PackageInstaller;
import com.example.fhirviewer.service.PackageRegistryService;
import com.example.fhirviewer.service.PackageStorage;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Callback;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Dialog for managing FHIR Implementation Guide packages.
 * Allows searching the FHIR package registry, downloading packages,
 * and loading them into the validation service.
 */
public class IgPackageDialog extends Dialog<String> {
    private static final Logger logger = Logger.getLogger(IgPackageDialog.class.getName());

    private final IgPackageManager packageManager;
    private final PackageRegistryService registryService;

    private TextField searchField;
    private Button searchButton;
    private Button refreshCatalogButton;
    private ListView<PackageRegistryService.PackageInfo> searchResultsList;
    private Button viewDetailsButton;
    private Button downloadLoadButton;

    private ListView<IgPackageInfo> loadedPackagesList;
    private Button unloadButton;
    private Button activateButton;
    private Button deactivateButton;
    private Button refreshButton;

    private TextField storageDirField;
    private Button browseButton;
    private Button loadFromFileButton;

    private Label statusLabel;

    public IgPackageDialog(Window parentWindow, IgPackageManager pm,
                          PackageRegistryService rs) {
        this.packageManager = pm;
        this.registryService = rs;

        setTitle("IG Package Manager");
        setHeaderText("Manage FHIR Implementation Guide Packages");

        BorderPane mainPane = createMainPane();
        getDialogPane().setContent(mainPane);

        ButtonType loadButtonType = new ButtonType("Load", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(loadButtonType, ButtonType.CANCEL);

        setResultConverter(dialogButton -> {
            if (dialogButton == loadButtonType) {
                loadSelectedPackage();
                return "LOAD";
            }
            return null;
        });

        storageDirField.setText(PackageStorage.getStorageDirectory().toString());

        refreshLoadedPackages();
        initOwner(parentWindow);
        initModality(Modality.WINDOW_MODAL);
    }

    private BorderPane createMainPane() {
        BorderPane mainPane = new BorderPane();
        VBox searchPanel = createSearchPanel();
        mainPane.setLeft(searchPanel);
        VBox loadedPanel = createLoadedPackagesPanel();
        mainPane.setCenter(loadedPanel);
        VBox storagePanel = createStoragePanel();
        mainPane.setRight(storagePanel);

        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-background-color: #f0f0f0; -fx-padding: 5px;");
        statusLabel.setPadding(new Insets(5));
        mainPane.setBottom(statusLabel);
        return mainPane;
    }

    private VBox createSearchPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(15));
        panel.setStyle("-fx-background-color: #f9f9f9; -fx-border-color: #ddd; -fx-border-width: 1px;");

        Label titleLabel = new Label("Search Registry");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        searchField = new TextField();
        searchField.setPromptText("Package id or keywords, e.g. hl7.fhir.us.core or us core");

        searchButton = new Button("Search");
        searchButton.setDefaultButton(true);
        searchButton.setPrefWidth(100);
        searchButton.setOnAction(e -> searchRegistry());
        searchField.setOnAction(e -> searchRegistry());

        refreshCatalogButton = new Button("Refresh Catalog");
        refreshCatalogButton.setTooltip(new Tooltip(
                "Forget cached registry metadata and re-read available versions."));
        refreshCatalogButton.setOnAction(e -> refreshPackageCatalog());

        searchResultsList = new ListView<>();
        searchResultsList.setPrefHeight(200);
        searchResultsList.setPlaceholder(new Label("No search results"));
        searchResultsList.setCellFactory(new Callback<ListView<PackageRegistryService.PackageInfo>,
                ListCell<PackageRegistryService.PackageInfo>>() {
            @Override
            public ListCell<PackageRegistryService.PackageInfo> call(
                    ListView<PackageRegistryService.PackageInfo> list) {
                return new PackageListCell();
            }
        });

        viewDetailsButton = new Button("View Details");
        viewDetailsButton.setDisable(true);
        viewDetailsButton.setOnAction(e -> viewPackageDetails());

        downloadLoadButton = new Button("Install...");
        downloadLoadButton.setTooltip(new Tooltip(
                "Download this version and its dependencies, then activate them for validation."));
        downloadLoadButton.setDisable(true);
        downloadLoadButton.setOnAction(e -> installSelectedPackage());

        searchResultsList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    viewDetailsButton.setDisable(newVal == null);
                    downloadLoadButton.setDisable(newVal == null);
                });

        HBox searchButtonRow = new HBox(10, searchButton, refreshCatalogButton);
        HBox buttonRow = new HBox(10, viewDetailsButton, downloadLoadButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        panel.getChildren().addAll(titleLabel, new Label("Package Name:"), searchField,
                searchButtonRow, new Label("Search Results:"), searchResultsList, buttonRow);
        return panel;
    }

    private VBox createLoadedPackagesPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(15));
        panel.setStyle("-fx-background-color: #f0f7ff; -fx-border-color: #b3d9ff; -fx-border-width: 1px;");

        Label titleLabel = new Label("Installed Packages");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label hint = new Label("Installed packages can be active or inactive.\n"
                + "Only active packages are used for automatic profile validation.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");

        loadedPackagesList = new ListView<>();
        loadedPackagesList.setPrefHeight(200);
        loadedPackagesList.setPlaceholder(new Label("No packages installed"));
        loadedPackagesList.setCellFactory(new Callback<ListView<IgPackageInfo>, ListCell<IgPackageInfo>>() {
            @Override
            public ListCell<IgPackageInfo> call(ListView<IgPackageInfo> list) {
                return new LoadedPackageListCell();
            }
        });

        unloadButton = new Button("Uninstall");
        unloadButton.setDisable(true);
        unloadButton.setOnAction(e -> unloadSelectedPackage());

        activateButton = new Button("Activate");
        activateButton.setDisable(true);
        activateButton.setOnAction(e -> setSelectedActive(true));

        deactivateButton = new Button("Deactivate");
        deactivateButton.setDisable(true);
        deactivateButton.setOnAction(e -> setSelectedActive(false));

        refreshButton = new Button("Refresh List");
        refreshButton.setOnAction(e -> refreshLoadedPackages());

        HBox buttonRow = new HBox(10, activateButton, deactivateButton, unloadButton, refreshButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        loadedPackagesList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    unloadButton.setDisable(newVal == null);
                    activateButton.setDisable(newVal == null || packageManager.isActive(newVal.name()));
                    deactivateButton.setDisable(newVal == null || !packageManager.isActive(newVal.name()));
                });

        panel.getChildren().addAll(titleLabel, hint, loadedPackagesList, buttonRow);
        return panel;
    }

    private VBox createStoragePanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(15));
        panel.setStyle("-fx-background-color: #fff5f5; -fx-border-color: #ffd6d6; -fx-border-width: 1px;");

        Label titleLabel = new Label("Package Storage");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        storageDirField = new TextField();
        storageDirField.setPrefWidth(300);
        storageDirField.setEditable(false);

        browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> browseForStorageDir());

        loadFromFileButton = new Button("Load Package from File");
        loadFromFileButton.setOnAction(e -> loadPackageFromFile());

        Label helpLabel = new Label("Downloaded packages are stored in the directory above.");
        helpLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        panel.getChildren().addAll(titleLabel, new Label("Storage Directory:"), storageDirField,
                browseButton, loadFromFileButton, helpLabel);
        return panel;
    }

    private void searchRegistry() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Please enter a package name to search for.");
            return;
        }
        statusLabel.setText("Searching registry...");
        searchButton.setDisable(true);
        new Thread(() -> {
            try {
                var results = registryService.searchPackages(query);
                javafx.application.Platform.runLater(() -> {
                    searchResultsList.getItems().setAll(results);
                    statusLabel.setText(results.size() + " packages found");
                    searchButton.setDisable(false);
                    if (results.isEmpty()) {
                        showAlert(Alert.AlertType.INFORMATION,
                                "No package matched '" + query + "'.\n"
                                + "Try a package id such as hl7.fhir.us.core, or keywords such as"
                                + " \"us core\", optionally with a version, e.g."
                                + " hl7.fhir.us.core#6.1.0.");
                    }
                });
            } catch (Exception e) {
                logger.severe("Error searching registry: " + e.getMessage());
                javafx.application.Platform.runLater(() -> {
                    statusLabel.setText("Error searching registry");
                    searchButton.setDisable(false);
                    showAlert(Alert.AlertType.ERROR,
                            "Error searching registry: " + e.getMessage());
                });
            }
        }).start();
    }

    /**
     * Installs the selected package: resolves its full dependency closure,
     * shows what will be installed, then downloads and activates everything
     * (Update 5).
     */
    private void installSelectedPackage() {
        PackageRegistryService.PackageInfo packageInfo =
                searchResultsList.getSelectionModel().getSelectedItem();
        if (packageInfo == null) {
            showAlert(Alert.AlertType.WARNING, "Please select a package to install.");
            return;
        }

        final Path storageDir = getStoragePath();
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            showAlert(Alert.AlertType.ERROR,
                    "Cannot create storage directory: " + e.getMessage());
            return;
        }

        downloadLoadButton.setDisable(true);
        statusLabel.setText("Resolving dependencies for " + packageInfo.getPackageId() + "...");
        Thread planner = new Thread(() -> {
            try {
                PackageInstaller installer = PackageInstaller.usingRegistry(
                        registryService, packageManager, storageDir);
                PackageInstaller.InstallPlan plan = installer.plan(
                        packageInfo.getPackageId(), packageInfo.getVersion());
                javafx.application.Platform.runLater(() -> confirmAndInstall(installer, plan));
            } catch (Exception e) {
                logger.warning("Install planning failed: " + e.getMessage());
                javafx.application.Platform.runLater(() -> {
                    downloadLoadButton.setDisable(false);
                    statusLabel.setText("Installation could not be planned");
                    showAlert(Alert.AlertType.ERROR, describeFailure(e), "Installation failed");
                });
            }
        }, "ig-package-plan");
        planner.setDaemon(true);
        planner.start();
    }

    /** Shows the installation summary and installs once the user confirms it. */
    private void confirmAndInstall(PackageInstaller installer, PackageInstaller.InstallPlan plan) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, plan.describe(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(getOwner());
        confirm.setTitle("Install packages");
        confirm.setHeaderText("Install " + plan.rootPackageId() + " "
                + plan.rootVersion() + "?");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            downloadLoadButton.setDisable(false);
            statusLabel.setText("Installation cancelled");
            return;
        }

        statusLabel.setText("Installing " + plan.toInstall().size() + " package(s)...");
        Thread worker = new Thread(() -> {
            PackageInstaller.InstallResult result = installer.install(plan);
            javafx.application.Platform.runLater(() -> {
                downloadLoadButton.setDisable(false);
                refreshLoadedPackages();
                searchResultsList.refresh();
                statusLabel.setText(result.describe());
                showAlert(result.isSuccess() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING,
                        result.describe(),
                        result.isSuccess() ? "Installation complete" : "Installation problems");
            });
        }, "ig-package-install");
        worker.setDaemon(true);
        worker.start();
    }

    /** Activates or deactivates the selected installed package (Update 7). */
    private void setSelectedActive(boolean active) {
        IgPackageInfo selected = loadedPackagesList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        boolean changed = packageManager.setActive(selected.name(), active);
        statusLabel.setText(changed
                ? selected.label() + (active
                        ? " is now active for validation"
                        : " is now inactive; it no longer resolves profiles")
                : "No change for " + selected.label());
        refreshLoadedPackages();
        searchResultsList.refresh();
    }

    /** Forgets cached registry metadata so versions are re-read (Update 19). */
    private void refreshPackageCatalog() {
        registryService.clearCache();
        searchResultsList.getItems().clear();
        statusLabel.setText("Package catalog refreshed; cached metadata cleared.");
    }

    /** Turns a failure into a message a user can act on (Update 18). */
    private static String describeFailure(Throwable e) {
        if (e instanceof IgPackageException packageFailure) {
            return packageFailure.getUserMessage();
        }
        if (e instanceof PackageRegistryService.PackageRegistryException registryFailure) {
            return registryFailure.getMessage();
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private void viewPackageDetails() {
        PackageRegistryService.PackageInfo packageInfo =
                searchResultsList.getSelectionModel().getSelectedItem();
        if (packageInfo == null) {
            return;
        }
        String details = "Package ID: " + packageInfo.getPackageId() + "\n"
                + "Name: " + packageInfo.getName() + "\n"
                + "Title: " + packageInfo.getTitle() + "\n"
                + "Version: " + packageInfo.getVersion() + "\n"
                + "FHIR Version: " + packageInfo.getFhirVersion() + "\n"
                + "Canonical: " + packageInfo.getCanonicalUrl() + "\n"
                + "Download: " + packageInfo.getDownloadUrl() + "\n"
                + "Installed: " + installedStateOf(packageInfo) + "\n"
                + "Description: " + packageInfo.getDescription();
        showAlert(Alert.AlertType.INFORMATION, details, "Package Details");
    }

    /** Tells the user whether this exact package version is already installed. */
    private String installedStateOf(PackageRegistryService.PackageInfo packageInfo) {
        IgPackageInfo installed = packageManager.getPackage(packageInfo.getPackageId());
        if (installed == null) {
            return "no";
        }
        if (installed.version().equals(packageInfo.getVersion())) {
            return "yes (" + (packageManager.isActive(packageInfo.getPackageId())
                    ? "active for validation" : "inactive") + ")";
        }
        return "no (version " + installed.version() + " is installed)";
    }

    private void loadPackageFromFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Package File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("NPM Packages", "*.tgz", "*.tar.gz"));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("All Files", "*"));

        File initDir = PackageStorage.getStorageDirectory().toFile();
        if (initDir.isDirectory()) {
            fileChooser.setInitialDirectory(initDir);
        }

        File selectedFile = fileChooser.showOpenDialog(getOwner());
        if (selectedFile == null) {
            return;
        }

        Path packagePath = selectedFile.toPath();
        statusLabel.setText("Loading " + selectedFile.getName() + "...");
        try {
            IgPackageInfo loaded = packageManager.loadPackageFromFile(packagePath);
            statusLabel.setText("Package installed and active: " + loaded.label());
            refreshLoadedPackages();
            searchResultsList.refresh();
        } catch (Exception e) {
            logger.warning("Error loading package: " + e.getMessage());
            statusLabel.setText("Error loading package");
            showAlert(Alert.AlertType.ERROR, describeFailure(e), "Installation failed");
        }
    }

    private void refreshLoadedPackages() {
        var packages = packageManager.getLoadedPackages();
        loadedPackagesList.getItems().setAll(packages);
        String base;
        if (packages.isEmpty()) {
            base = "No packages installed";
        } else {
            base = packages.size() + " installed (" + packageManager.getActivePackages().size()
                    + " active), " + PackageStorage.listStored(getStoragePath()).size()
                    + " file(s) in storage";
        }
        var unmet = packageManager.getUnmetDependencies();
        statusLabel.setText(unmet.isEmpty()
                ? base
                : base + " - unmet dependencies: " + String.join(", ", unmet));
        loadedPackagesList.refresh();
        unloadButton.setDisable(packages.isEmpty());
        activateButton.setDisable(packages.isEmpty());
        deactivateButton.setDisable(packages.isEmpty());
    }

    private void unloadSelectedPackage() {
        IgPackageInfo selected = loadedPackagesList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Uninstall " + selected.label() + "?\n\n"
                + "It stops being used for validation. The downloaded file stays in the "
                + "package storage directory and is installed again on the next start unless "
                + "you deactivate it or delete the file.",
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Confirm Uninstall");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.YES) {
            boolean unloaded = packageManager.unloadPackage(
                    selected.name() + "#" + selected.version());
            if (unloaded) {
                statusLabel.setText("Package uninstalled: " + selected.name());
            } else {
                showAlert(Alert.AlertType.WARNING, "Package could not be uninstalled.");
            }
            refreshLoadedPackages();
            searchResultsList.refresh();
        }
    }

    private void browseForStorageDir() {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Select Package Storage Directory");
        File initDir = PackageStorage.getStorageDirectory().toFile();
        if (initDir.isDirectory()) {
            dirChooser.setInitialDirectory(initDir);
        } else {
            dirChooser.setInitialDirectory(new File(System.getProperty("user.home")));
        }
        File selectedDir = dirChooser.showDialog(getOwner());
        if (selectedDir != null) {
            storageDirField.setText(selectedDir.getAbsolutePath());
            PackageStorage.setStorageDirectory(selectedDir.toPath());
            statusLabel.setText("Storage directory set to: " + selectedDir.getAbsolutePath());
        }
    }

    private void loadSelectedPackage() {
        // Result notification only; packages are loaded by the action buttons.
    }

    private Path getStoragePath() {
        String dir = storageDirField.getText().trim();
        return dir.isEmpty() ? PackageStorage.getStorageDirectory() : Paths.get(dir);
    }

    private void showAlert(Alert.AlertType type, String message) {
        showAlert(type, message, "IG Package Manager");
    }

    private void showAlert(Alert.AlertType type, String message, String title) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /** Custom list cell for registry search results. */
    private class PackageListCell
            extends ListCell<PackageRegistryService.PackageInfo> {
        @Override
        protected void updateItem(PackageRegistryService.PackageInfo info, boolean empty) {
            super.updateItem(info, empty);
            if (empty || info == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            IgPackageInfo installed = packageManager.getPackage(info.getPackageId());
            String state = "";
            if (installed != null && installed.version().equals(info.getVersion())) {
                state = "\n(installed"
                        + (packageManager.isActive(info.getPackageId())
                                ? ", active for validation)" : ", inactive)");
            } else if (installed != null) {
                state = "\n(other version installed: " + installed.version() + ")";
            }
            setText(info.getDisplayText() + state);
            setWrapText(true);
        }
    }

    /** Custom list cell for installed packages, showing whether each is active. */
    private class LoadedPackageListCell extends ListCell<IgPackageInfo> {
        @Override
        protected void updateItem(IgPackageInfo info, boolean empty) {
            super.updateItem(info, empty);
            if (empty || info == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            boolean active = packageManager.isActive(info.name());
            setText((active ? "[active]   " : "[inactive] ")
                    + info.name() + " " + info.version()
                    + (info.fhirVersion().isEmpty() ? "" : "  (FHIR " + info.fhirVersion() + ")")
                    + (active ? "" : "\nnot used for automatic profile validation"));
            setWrapText(true);
        }
    }
}


    