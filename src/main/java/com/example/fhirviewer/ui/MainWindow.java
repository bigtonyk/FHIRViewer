package com.example.fhirviewer.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;

import com.example.fhirviewer.model.BundleEntryInfo;
import com.example.fhirviewer.model.ElementInfo;
import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.model.ResourceNode;
import com.example.fhirviewer.model.ValidationReport;
import com.example.fhirviewer.service.FhirService;
import com.example.fhirviewer.service.ResourceLoadException;
import com.example.fhirviewer.util.FileSupport;

import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * The main application window.
 *
 * <p>Layout: menus and toolbar on top, the resource tree and the Bundle navigator on
 * the left, the details/JSON/XML tabs on the right and the status and validation
 * messages at the bottom. All FHIR work is delegated to {@link FhirService}; the
 * JavaFX event handlers only coordinate the UI.</p>
 */
public class MainWindow {

    private static final String APPLICATION_TITLE = "FHIR Resource Viewer";
    private static final String READY_STATUS = "Ready. Use File > Open to load a FHIR JSON or XML resource.";

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final FhirService fhirService = new FhirService();

    private final ResourceTreeView treeView = new ResourceTreeView();
    private final DetailsView detailsView = new DetailsView();
    private final JsonView jsonView = new JsonView();
    private final XmlView xmlView = new XmlView();
    private final StatusView statusView = new StatusView();
    private final BundleView bundleView = new BundleView();

    private final TabPane structureTabs = new TabPane();
    private final TabPane documentTabs = new TabPane();
    private final Tab treeTab = new Tab("Resource Tree");
    private final Tab bundleTab = new Tab("Bundle");
    private final PrettyView prettyView = new PrettyView();
    private final Tab prettyTab = new Tab("Pretty");
    private final Tab detailsTab = new Tab("Details");
    private final Tab jsonTab = new Tab("JSON");
    private final Tab xmlTab = new Tab("XML");

    private final CheckMenuItem showUnpopulated =
            new CheckMenuItem("Show elements that are not populated");

    /** The node currently selected in the resource tree, if any. */
    private ResourceNode selectedNode;

    private final TextField treeSearchField = new TextField();
    private final Button themeToggle = new Button("◐ Dark theme");
    private final ThemeManager themeManager = new ThemeManager();

    /** The resource loaded from a file or sample. */
    private LoadedResource loadedResource;
    /** The resource currently shown: the loaded resource or one of its Bundle entries. */
    private IBaseResource displayedResource;
    private String displayedLabel = "";
    /** When a Bundle entry is being displayed, the entry it came from. */
    private BundleEntryInfo displayedEntry;

    public MainWindow(Stage stage) {
        this.stage = stage;
        buildLayout();
        wireInteractions();
        updateWindowTitle();
    }

    /** The root node of the window, for use in a {@link javafx.scene.Scene}. */
    public Parent getRoot() {
        return root;
    }

    private void buildLayout() {
        treeTab.setContent(treeView);
        treeTab.setClosable(false);
        bundleTab.setContent(bundleView);
        bundleTab.setClosable(false);
        structureTabs.getTabs().addAll(treeTab, bundleTab);

        prettyTab.setContent(prettyView);
        prettyTab.setClosable(false);
        detailsTab.setContent(detailsScrollPane());
        detailsTab.setClosable(false);
        jsonTab.setContent(jsonView);
        jsonTab.setClosable(false);
        xmlTab.setContent(xmlView);
        xmlTab.setClosable(false);
        documentTabs.getTabs().addAll(prettyTab, detailsTab, jsonTab, xmlTab);
        documentTabs.getSelectionModel().select(prettyTab);
        // Hidden tabs are not laid out, so a scroll performed while a tab is hidden
        // would be lost; sync the views again whenever a tab is selected.
        documentTabs.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> resyncDocumentViews());

        SplitPane splitPane = new SplitPane(structureTabs, documentTabs);
        splitPane.setDividerPositions(0.38);
        structureTabs.getStyleClass().add("sidebar");

        root.setTop(new VBox(buildMenuBar(), buildHeaderBar(), buildToolBar()));

        StackPane contentArea = new StackPane(splitPane);
        contentArea.getStyleClass().add("content-area");
        root.setCenter(contentArea);
        root.setBottom(statusView);
    }

    /**
     * The Details tab content: the details card in a scroll pane that stretches the
     * card to the width of the tab (values wrap, long ones are still scrollable).
     */
    private ScrollPane detailsScrollPane() {
        ScrollPane scrollPane = new ScrollPane(detailsView);
        scrollPane.setFitToWidth(true);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return scrollPane;
    }

    /**
     * The application header bar: application name, tree search field, and the
     * theme toggle on the right — the modern shell of the window.
     */
    private HBox buildHeaderBar() {
        Label logo = new Label();
        logo.getStyleClass().add("app-logo");

        Label appTitle = new Label("FHIR Viewer");
        appTitle.getStyleClass().add("app-title");

        treeSearchField.setPromptText("Search resource tree...");
        treeSearchField.getStyleClass().add("search-field");
        treeSearchField.setTooltip(new Tooltip("Filter the resource tree (case insensitive substring)."));
        treeSearchField.textProperty().addListener((observable, previous, text) -> treeView.applyFilter(text));

        Button openButton = new Button("Open");
        openButton.getStyleClass().add("button-primary");
        openButton.setOnAction(event -> openFile());
        Button validateButton = new Button("Validate");
        validateButton.getStyleClass().add("button-ghost");
        validateButton.setOnAction(event -> validateDisplayedResource());

        HBox leftActions = new HBox(8, openButton, validateButton);
        leftActions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        themeToggle.getStyleClass().add("button-ghost");
        themeToggle.setTooltip(new Tooltip("Switch between the light and the dark theme."));
        themeToggle.setOnAction(event -> toggleTheme());

        HBox header = new HBox(14, logo, appTitle, leftActions, treeSearchField, themeToggle);
        header.getStyleClass().add("header-bar");
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(treeSearchField, Priority.ALWAYS);
        treeSearchField.setMaxWidth(340);
        return header;
    }

    private MenuBar buildMenuBar() {
        Menu fileMenu = new Menu("File");

        MenuItem open = new MenuItem("Open...");
        open.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        open.setOnAction(event -> openFile());

        MenuItem samplePatient = new MenuItem("Patient (JSON)");
        samplePatient.setOnAction(event -> openSample(FhirService.SAMPLE_PATIENT));
        MenuItem sampleBundle = new MenuItem("Bundle (JSON, 2 entries)");
        sampleBundle.setOnAction(event -> openSample(FhirService.SAMPLE_BUNDLE));
        Menu sampleMenu = new Menu("Open Sample", null, samplePatient, sampleBundle);

        MenuItem exportJson = new MenuItem("Export as JSON...");
        exportJson.setOnAction(event -> export(ResourceFormat.JSON));
        MenuItem exportXml = new MenuItem("Export as XML...");
        exportXml.setOnAction(event -> export(ResourceFormat.XML));

        MenuItem close = new MenuItem("Close");
        close.setOnAction(event -> closeResource());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(event -> stage.close());

        fileMenu.getItems().addAll(
                open,
                sampleMenu,
                new SeparatorMenuItem(),
                exportJson,
                exportXml,
                new SeparatorMenuItem(),
                close,
                exit);
        return new MenuBar(fileMenu, buildViewMenu(), buildToolsMenu(), buildHelpMenu());
    }

    private Menu buildViewMenu() {
        Menu viewMenu = new Menu("View");

        MenuItem showTree = new MenuItem("Resource Tree");
        showTree.setOnAction(event -> structureTabs.getSelectionModel().select(treeTab));
        MenuItem showBundle = new MenuItem("Bundle Navigator");
        showBundle.setOnAction(event -> structureTabs.getSelectionModel().select(bundleTab));
        MenuItem showPretty = new MenuItem("Pretty");
        showPretty.setOnAction(event -> documentTabs.getSelectionModel().select(prettyTab));
        MenuItem showDetails = new MenuItem("Details");
        showDetails.setOnAction(event -> documentTabs.getSelectionModel().select(detailsTab));
        MenuItem showJson = new MenuItem("JSON");
        showJson.setOnAction(event -> documentTabs.getSelectionModel().select(jsonTab));
        MenuItem showXml = new MenuItem("XML");
        showXml.setOnAction(event -> documentTabs.getSelectionModel().select(xmlTab));

        MenuItem expandAll = new MenuItem("Expand All");
        expandAll.setOnAction(event -> treeView.expandAll());
        MenuItem collapseAll = new MenuItem("Collapse All");
        collapseAll.setOnAction(event -> treeView.collapseAll());

        showUnpopulated.setOnAction(event -> refreshTree());

        viewMenu.getItems().addAll(
                showTree,
                showBundle,
                new SeparatorMenuItem(),
                showPretty,
                showDetails,
                showJson,
                showXml,
                new SeparatorMenuItem(),
                expandAll,
                collapseAll,
                new SeparatorMenuItem(),
                showUnpopulated);
        return viewMenu;
    }

    private Menu buildToolsMenu() {
        MenuItem validate = new MenuItem("Validate Resource");
        validate.setAccelerator(KeyCombination.keyCombination("Shortcut+T"));
        validate.setOnAction(event -> validateDisplayedResource());
        return new Menu("Tools", null, validate);
    }

    private Menu buildHelpMenu() {
        MenuItem about = new MenuItem("About");
        about.setOnAction(event -> showAbout());
        return new Menu("Help", null, about);
    }

    private ToolBar buildToolBar() {
        Button expandButton = new Button("Expand All");
        expandButton.getStyleClass().add("button-ghost");
        expandButton.setOnAction(event -> treeView.expandAll());

        Button collapseButton = new Button("Collapse All");
        collapseButton.getStyleClass().add("button-ghost");
        collapseButton.setOnAction(event -> treeView.collapseAll());

        return new ToolBar(expandButton, collapseButton);
    }

    private void wireInteractions() {
        treeView.setOnNodeSelected(node -> {
            selectedNode = node;
            detailsView.show(node);
            prettyView.scrollToElement(node);
            jsonView.scrollToElement(node);
            xmlView.scrollToElement(node);
        });
        treeView.setOnReferenceActivated(this::navigateToReference);
        bundleView.setOnEntrySelected(this::displayBundleEntry);
    }

    /**
     * Syncs the document views to the current tree selection again. Called when a
     * document tab is selected: hidden tabs are not laid out, so a scroll that was
     * requested while a tab was hidden would otherwise be lost.
     */
    private void resyncDocumentViews() {
        if (selectedNode == null) {
            return;
        }
        prettyView.scrollToElement(selectedNode);
        jsonView.scrollToElement(selectedNode);
        xmlView.scrollToElement(selectedNode);
    }

    /**
     * Navigates a double clicked tree element when it is a FHIR Reference whose
     * target exists in the loaded data set: the matching Bundle entry is shown.
     */
    private void navigateToReference(ResourceNode node) {
        if (node == null
                || node.getElementInfo().getKind() != ElementInfo.Kind.REFERENCE
                || loadedResource == null || !loadedResource.isBundle()) {
            return;
        }
        String target = referenceTarget(node.getValueText());
        if (target == null) {
            return;
        }
        for (BundleEntryInfo entry : flattenEntries(fhirService.bundleEntries(loadedResource.getResource()))) {
            if (target.equalsIgnoreCase(entry.displayName())) {
                displayBundleEntry(entry);
                return;
            }
        }
        setStatus("The referenced resource " + target + " is not part of the loaded Bundle.");
    }

    /** Extracts <code>Type/id</code> from a rendered reference value. */
    private static String referenceTarget(String valueText) {
        if (valueText == null || valueText.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([A-Za-z]+/[A-Za-z0-9\\-.]{1,64})").matcher(valueText);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** All entries of the Bundle, including nested Bundle-in-Bundle entries. */
    private static List<BundleEntryInfo> flattenEntries(List<BundleEntryInfo> entries) {
        List<BundleEntryInfo> all = new java.util.ArrayList<>();
        for (BundleEntryInfo entry : entries) {
            all.add(entry);
            all.addAll(flattenEntries(entry.childEntries()));
        }
        return all;
    }

    /** Switches between the light and the dark theme. */
    private void toggleTheme() {
        if (stage.getScene() == null) {
            return;
        }
        themeManager.apply(stage.getScene(),
                themeManager.isDark() ? ThemeManager.Theme.LIGHT : ThemeManager.Theme.DARK);
        themeToggle.setText(themeManager.isDark() ? "☀ Light theme" : "◐ Dark theme");
        setStatus("Theme switched to " + (themeManager.isDark() ? "dark" : "light") + ".");
    }

    // ------------------------------------------------------------------
    // Opening and displaying resources
    // ------------------------------------------------------------------

    private void openFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open FHIR resource");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("FHIR resources", "*.json", "*.xml"),
                new FileChooser.ExtensionFilter("FHIR JSON", "*.json"),
                new FileChooser.ExtensionFilter("FHIR XML", "*.xml"),
                new FileChooser.ExtensionFilter("All files", "*.*"));

        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        openPath(file.toPath());
    }

    private void openPath(Path path) {
        runWithWaitCursor("Loading " + FileSupport.fileName(path) + " ...", () -> {
            LoadedResource resource = fhirService.openFile(path);
            display(resource);
        });
    }

    private void openSample(String classpathResource) {
        runWithWaitCursor("Loading sample " + classpathResource + " ...", () -> {
            LoadedResource resource = fhirService.openSample(classpathResource);
            display(resource);
        });
    }

    /**
     * Opens a resource named on the command line, once the window has been shown.
     *
     * @param location a path to a FHIR JSON or XML file
     */
    public void openOnStartup(String location) {
        try {
            Path path = Paths.get(location);
            if (!Files.isReadable(path)) {
                setStatus("The file " + location + " could not be read.");
                return;
            }
            openPath(path);
        } catch (RuntimeException e) {
            setStatus("The file " + location + " could not be read: " + e.getMessage());
        }
    }

    /** Displays a freshly loaded resource in every view. */
    private void display(LoadedResource resource) {
        loadedResource = resource;
        displayedResource = resource.getResource();
        displayedLabel = resource.getDisplayName();

        displayedEntry = null;
        ResourceNode tree = fhirService.buildTree(resource, showUnpopulated.isSelected());
        treeView.show(tree);
        detailsView.showNothingSelected();
        updateDocumentViews();
        statusView.clearValidation();
        updateBundleView();

        documentTabs.getSelectionModel().select(prettyTab);
        structureTabs.getSelectionModel().select(treeTab);

        setStatus("Loaded " + resource.getSourceName()
                + " - " + resource.getDisplayName()
                + " (" + resource.getFormat().getDisplayName()
                + ", " + countNodes(tree) + " elements shown)");
        updateWindowTitle();
    }

    /** Rebuilds the tree for the currently displayed resource (used by the View menu toggle). */
    private void refreshTree() {
        if (loadedResource == null) {
            return;
        }
        if (displayedEntry == null) {
            treeView.show(fhirService.buildTree(loadedResource, showUnpopulated.isSelected()));
        } else {
            treeView.show(fhirService.buildTree(displayedEntry, showUnpopulated.isSelected()));
        }
    }

    /** Shows the whole Bundle, or one of its entries, in every view. */
    private void displayBundleEntry(BundleEntryInfo entry) {
        if (loadedResource == null) {
            return;
        }
        if (entry == null) {
            displayedEntry = null;
            displayedResource = loadedResource.getResource();
            displayedLabel = loadedResource.getDisplayName();
            treeView.show(fhirService.buildTree(loadedResource, showUnpopulated.isSelected()));
            updateDocumentViews();
            detailsView.showNothingSelected();
            setStatus("Showing the Bundle resource itself: " + displayedLabel);
            updateWindowTitle();
            return;
        }
        if (!entry.hasResource()) {
            setStatus("Bundle entry [" + entry.index() + "] contains no resource to display.");
            return;
        }
        displayedEntry = entry;
        displayedResource = entry.resource();
        displayedLabel = entry.displayName();
        treeView.show(fhirService.buildTree(entry, showUnpopulated.isSelected()));
        updateDocumentViews();
        detailsView.showNothingSelected();
        documentTabs.getSelectionModel().select(prettyTab);
        setStatus("Showing Bundle entry [" + entry.index() + "] " + entry.displayName());
        updateWindowTitle();
    }

    private void updateBundleView() {
        if (loadedResource == null || !loadedResource.isBundle()) {
            bundleView.clear();
            return;
        }
        List<BundleEntryInfo> entries = fhirService.bundleEntries(loadedResource.getResource());
        bundleView.showBundle(entries, loadedResource.getDisplayName());
    }

    private void updateDocumentViews() {
        if (displayedResource == null) {
            prettyView.showNothing();
            jsonView.showNothing();
            xmlView.showNothing();
            return;
        }
        prettyView.show(displayedEntry == null
                ? fhirService.buildPrettyView(loadedResource)
                : fhirService.buildPrettyView(displayedEntry));
        jsonView.show(fhirService.toJson(displayedResource));
        xmlView.show(fhirService.toXml(displayedResource));
    }

    private int countNodes(ResourceNode node) {
        int count = 1;
        for (ResourceNode child : node.getChildren()) {
            count += countNodes(child);
        }
        return count;
    }
    // ------------------------------------------------------------------
    // Validation, export and window helpers
    // ------------------------------------------------------------------

    private void validateDisplayedResource() {
        if (displayedResource == null) {
            setStatus("Nothing to validate. Open a FHIR resource first.");
            return;
        }
        setStatus("Validating " + displayedLabel + " ...");
        setBusy(true);
        try {
            ValidationReport report = fhirService.validate(displayedResource);
            statusView.showValidation(report);
        } finally {
            setBusy(false);
        }
    }

    private void export(ResourceFormat format) {
        if (displayedResource == null) {
            setStatus("Nothing to export. Open a FHIR resource first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export " + format.getDisplayName());
        chooser.setInitialFileName(suggestedFileName(format));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(format.getDisplayName(), "*." + format.getExtension()));

        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            FileSupport.writeText(file.toPath(), fhirService.serialize(displayedResource, format));
            setStatus("Exported " + displayedLabel + " to " + file.getAbsolutePath());
        } catch (IOException e) {
            showFailure("Could not write " + file, e);
        }
    }

    private String suggestedFileName(ResourceFormat format) {
        String type = displayedResource.fhirType();
        IIdType idElement = displayedResource.getIdElement();
        String id = idElement != null && idElement.hasIdPart() ? idElement.getIdPart() : null;
        return (id == null ? type : type + "-" + id) + "." + format.getExtension();
    }

    private void closeResource() {
        loadedResource = null;
        displayedResource = null;
        displayedEntry = null;
        displayedLabel = "";
        selectedNode = null;
        treeView.show(null);
        prettyView.showNothing();
        jsonView.showNothing();
        xmlView.showNothing();
        detailsView.showNothingSelected();
        statusView.clearValidation();
        bundleView.clear();
        setStatus(READY_STATUS);
        updateWindowTitle();
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle("About " + APPLICATION_TITLE);
        alert.setHeaderText(APPLICATION_TITLE);
        alert.setContentText("FHIR version: " + fhirService.fhirVersion()
                + "\nJava runtime: " + System.getProperty("java.version")
                + "\nJavaFX: " + System.getProperty("javafx.version", "unknown")
                + "\n\nParsing, serialization and validation are provided by HAPI FHIR.");
        alert.showAndWait();
    }

    /** Runs a loading action, reporting failures in the status bar and in a dialog. */
    private void runWithWaitCursor(String statusMessage, Runnable action) {
        setStatus(statusMessage);
        setBusy(true);
        try {
            action.run();
        } catch (ResourceLoadException e) {
            showFailure("Could not load the resource", e);
        } finally {
            setBusy(false);
        }
    }

    private void setBusy(boolean busy) {
        if (stage.getScene() != null) {
            stage.getScene().setCursor(busy ? Cursor.WAIT : Cursor.DEFAULT);
        }
    }

    private void setStatus(String message) {
        statusView.setStatus(message);
    }

    private void updateWindowTitle() {
        stage.setTitle(displayedResource == null
                ? APPLICATION_TITLE
                : APPLICATION_TITLE + " - " + displayedLabel);
    }

    private void showFailure(String message, Throwable failure) {
        String detail = failure == null || failure.getMessage() == null
                ? String.valueOf(failure)
                : failure.getMessage();
        setStatus(message + " - " + detail);

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle(APPLICATION_TITLE);
        alert.setHeaderText(message);
        alert.setContentText(detail);
        alert.getDialogPane().setPrefWidth(620);
        alert.showAndWait();
    }
}
