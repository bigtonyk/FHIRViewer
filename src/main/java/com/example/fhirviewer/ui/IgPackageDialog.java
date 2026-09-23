package com.example.fhirviewer.ui;

import com.example.fhirviewer.model.IgPackageInfo;
import com.example.fhirviewer.service.IgPackageManager;
import com.example.fhirviewer.service.PackageRegistryService;
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
import java.util.prefs.Preferences;

/**
 * Dialog for managing FHIR Implementation Guide packages.
 * Allows searching the FHIR package registry, downloading packages,
 * and loading them into the validation service.
 */
public class IgPackageDialog extends Dialog<String> {
    private static final Logger logger = Logger.getLogger(IgPackageDialog.class.getName());
    private static final String DEFAULT_STORAGE_DIR = System.getProperty("user.home") + "/.fhirviewer/packages";

    private final IgPackageManager packageManager;
    private final PackageRegistryService registryService;
    private final Preferences prefs;

    private TextField searchField;
    private Button searchButton;
    private ListView<PackageRegistryService.PackageInfo> searchResultsList;
    private Button viewDetailsButton;
    private Button downloadLoadButton;

    private ListView<IgPackageInfo> loadedPackagesList;
    private Button unloadButton;
    private Button refreshButton;

    private TextField storageDirField;
    private Button browseButton;
    private Button loadFromFileButton;

    private Label statusLabel;

    public IgPackageDialog(Window parentWindow, IgPackageManager pm,
                          PackageRegistryService rs) {
        this.packageManager = pm;
        this.registryService = rs;
        this.prefs = Preferences.userNodeForPackage(getClass());

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

        String storedDir = prefs.get("storageDirectory", DEFAULT_STORAGE_DIR);
        storageDirField.setText(storedDir);

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
        searchField.setPromptText("Enter package name (e.g., us-core, cpg)");

        searchButton = new Button("Search");
        searchButton.setDefaultButton(true);
        searchButton.setPrefWidth(100);
        searchButton.setOnAction(e -> searchRegistry());
        searchField.setOnAction(e -> searchRegistry());

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

        downloadLoadButton = new Button("Download & Load");
        downloadLoadButton.setDisable(true);
        downloadLoadButton.setOnAction(e -> downloadAndLoadPackage());

        searchResultsList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    viewDetailsButton.setDisable(newVal == null);
                    downloadLoadButton.setDisable(newVal == null);
                });

        HBox searchButtonRow = new HBox(10, searchButton);
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

        Label titleLabel = new Label("Loaded Packages");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        loadedPackagesList = new ListView<>();
        loadedPackagesList.setPrefHeight(200);
        loadedPackagesList.setPlaceholder(new Label("No packages loaded"));
        loadedPackagesList.setCellFactory(new Callback<ListView<IgPackageInfo>, ListCell<IgPackageInfo>>() {
            @Override
            public ListCell<IgPackageInfo> call(ListView<IgPackageInfo> list) {
                return new LoadedPackageListCell();
            }
        });

        unloadButton = new Button("Unload Selected");
        unloadButton.setDisable(true);
        unloadButton.setOnAction(e -> unloadSelectedPackage());

        refreshButton = new Button("Refresh List");
        refreshButton.setOnAction(e -> refreshLoadedPackages());

        HBox buttonRow = new HBox(10, unloadButton, refreshButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        loadedPackagesList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> unloadButton.setDisable(newVal == null));

        panel.getChildren().addAll(titleLabel, loadedPackagesList, buttonRow);
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
                                "No packages found matching '" + query + "'");
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

    private void downloadAndLoadPackage() {
        PackageRegistryService.PackageInfo packageInfo =
                searchResultsList.getSelectionModel().getSelectedItem();
        if (packageInfo == null) {
            showAlert(Alert.AlertType.WARNING, "Please select a package to download.");
            return;
        }

        Path storageDir = getStoragePath();
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            showAlert(Alert.AlertType.ERROR,
                    "Cannot create storage directory: " + e.getMessage());
            return;
        }

        statusLabel.setText("Downloading " + packageInfo.getPackageId() + "...");

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Download and load package: " + packageInfo.getPackageId()
                        + " v" + packageInfo.getVersion(),
                ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                new Thread(() -> {
                    try {
                        Path packageFile =
                                registryService.downloadPackage(packageInfo, storageDir);
                        if (packageFile != null) {
                            javafx.application.Platform.runLater(() ->
                                    statusLabel.setText("Downloaded. Loading package..."));
                            IgPackageInfo loaded =
                                    packageManager.loadPackageFromFile(packageFile);
                            javafx.application.Platform.runLater(() -> {
                                statusLabel.setText("Package loaded: " + loaded.name()
                                        + " " + loaded.version());
                                refreshLoadedPackages();
                                searchResultsList.getSelectionModel().clearSelection();
                            });
                        } else {
                            javafx.application.Platform.runLater(() -> {
                                statusLabel.setText("Download failed");
                                showAlert(Alert.AlertType.ERROR, "Download returned null");
                            });
                        }
                    } catch (Exception e) {
                        logger.severe("Download error: " + e.getMessage());
                        javafx.application.Platform.runLater(() -> {
                            statusLabel.setText("Download error");
                            showAlert(Alert.AlertType.ERROR,
                                    "Download error: " + e.getMessage());
                        });
                    }
                }).start();
            }
        });
    }

    private void viewPackageDetails() {
        PackageRegistryService.PackageInfo packageInfo =
                searchResultsList.getSelectionModel().getSelectedItem();
        if (packageInfo == null) {
            return;
        }
        String details = "Package ID: " + packageInfo.getPackageId() + "\n"
                + "Name: " + packageInfo.getName() + "\n"
                + "Version: " + packageInfo.getVersion() + "\n"
                + "FHIR Version: " + packageInfo.getFhirVersion() + "\n"
                + "Canonical: " + packageInfo.getCanonicalUrl() + "\n"
                + "Description: " + packageInfo.getDescription();
        showAlert(Alert.AlertType.INFORMATION, details, "Package Details");
    }

    private void loadPackageFromFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Package File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("NPM Packages", "*.tgz", "*.tar.gz"));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("All Files", "*"));

        String storedDir = prefs.get("storageDirectory", DEFAULT_STORAGE_DIR);
        File initDir = new File(storedDir);
        if (!initDir.exists()) {
            initDir = new File(System.getProperty("user.home"));
        }
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
            statusLabel.setText("Package loaded: " + loaded.name() + " " + loaded.version());
            refreshLoadedPackages();
        } catch (Exception e) {
            logger.severe("Error loading package: " + e.getMessage());
            statusLabel.setText("Error loading package");
            showAlert(Alert.AlertType.ERROR, "Error loading package: " + e.getMessage());
        }
    }

    private void refreshLoadedPackages() {
        var packages = packageManager.getLoadedPackages();
        loadedPackagesList.getItems().setAll(packages);
        if (packages.isEmpty()) {
            statusLabel.setText("No packages loaded");
        } else {
            statusLabel.setText(packages.size() + " packages loaded");
        }
    }

    private void unloadSelectedPackage() {
        IgPackageInfo selected = loadedPackagesList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Unload package: " + selected.name() + " " + selected.version() + "?",
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Confirm Unload");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.YES) {
            boolean unloaded = packageManager.unloadPackage(
                    selected.name() + "#" + selected.version());
            if (unloaded) {
                statusLabel.setText("Package unloaded: " + selected.name());
            } else {
                showAlert(Alert.AlertType.WARNING, "Package could not be unloaded.");
            }
            refreshLoadedPackages();
        }
    }

    private void browseForStorageDir() {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Select Package Storage Directory");
        String storedDir = prefs.get("storageDirectory", DEFAULT_STORAGE_DIR);
        File initDir = new File(storedDir);
        if (initDir.isDirectory()) {
            dirChooser.setInitialDirectory(initDir);
        } else {
            dirChooser.setInitialDirectory(new File(System.getProperty("user.home")));
        }
        File selectedDir = dirChooser.showDialog(getOwner());
        if (selectedDir != null) {
            String dirPath = selectedDir.getAbsolutePath();
            storageDirField.setText(dirPath);
            prefs.put("storageDirectory", dirPath);
            statusLabel.setText("Storage directory set to: " + dirPath);
        }
    }

    private void loadSelectedPackage() {
        // Result notification only; packages are loaded by the action buttons.
    }

    private Path getStoragePath() {
        String dir = storageDirField.getText().trim();
        if (dir.isEmpty()) {
            dir = DEFAULT_STORAGE_DIR;
        }
        return Paths.get(dir);
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
    private static class PackageListCell
            extends ListCell<PackageRegistryService.PackageInfo> {
        @Override
        protected void updateItem(PackageRegistryService.PackageInfo info, boolean empty) {
            super.updateItem(info, empty);
            if (empty || info == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(info.getDisplayText());
                setWrapText(true);
            }
        }
    }

    /** Custom list cell for loaded packages. */
    private static class LoadedPackageListCell extends ListCell<IgPackageInfo> {
        @Override
        protected void updateItem(IgPackageInfo info, boolean empty) {
            super.updateItem(info, empty);
            if (empty || info == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(info.name() + " " + info.version()
                        + (info.fhirVersion().isEmpty() ? "" : " (FHIR " + info.fhirVersion() + ")"));
            }
        }
    }
}


    