package com.example.fhirviewer.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import com.example.fhirviewer.fhir.ElementProperty;
import com.example.fhirviewer.model.BundleEntryInfo;
import com.example.fhirviewer.model.ElementInfo;
import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.model.ResourceNode;
import com.example.fhirviewer.model.ValidationIssue;
import com.example.fhirviewer.model.ValidationReport;
import com.example.fhirviewer.service.FhirService;
import com.example.fhirviewer.service.PackageStorage;
import com.example.fhirviewer.service.ResourceEditorService;
import com.example.fhirviewer.service.ResourceLoadException;
import com.example.fhirviewer.service.ResourceTemplateFactory;
import com.example.fhirviewer.service.SourceEditorService;
import com.example.fhirviewer.service.ValidationService;
import com.example.fhirviewer.server.FhirServerManager;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.ServerDefinition;
import com.example.fhirviewer.util.FileSupport;

import javafx.application.HostServices;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
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

    private static final Logger logger = Logger.getLogger(MainWindow.class.getName());
    private static final String APPLICATION_TITLE = "FHIR Resource Viewer";
    private static final String READY_STATUS = "Ready. Use File > Open to load a FHIR JSON or XML resource.";
    /** How many edits can be undone; older snapshots are dropped. */
    private static final int MAX_UNDO_DEPTH = 20;

    private final Stage stage;
    private final HostServices hostServices;
    private final BorderPane root = new BorderPane();
    private final FhirService fhirService = new FhirService();

    private final ResourceTreeView treeView = new ResourceTreeView();
    private final DetailsView detailsView = new DetailsView();
    private final JsonView jsonView = new JsonView();
    private final XmlView xmlView = new XmlView();
    private final StatusView statusView = new StatusView();
    private final BundleView bundleView = new BundleView();
    private final FhirPathView fhirPathView = new FhirPathView();

    /** Applies edits to the live FHIR model; the UI never touches HAPI FHIR directly. */
    private final ResourceEditorService editorService = new ResourceEditorService();
    /** Applies edited JSON/XML source text after parsing and validation. */
    private final SourceEditorService sourceEditorService = new SourceEditorService();
    /** Creates the empty resource behind File > New. */
    private final ResourceTemplateFactory templateFactory = ResourceTemplateFactory.r4();

    /** Talks to FHIR servers through plugins; the UI never sees HTTP or URLs. */
    private final FhirServerService serverService = new FhirServerService();
    /** The servers configured in this session and the active one. */
    private final FhirServerManager serverManager = new FhirServerManager();

    /**
     * Snapshots of the loaded resource taken before every edit, newest first. Undo shows
     * a snapshot again as the loaded resource, so the resource object is replaced rather
     * than mutated back; the depth is capped so a long editing session cannot grow
     * without bound.
     */
    private final Deque<IBaseResource> undoStack = new ArrayDeque<>();

    private final TabPane structureTabs = new TabPane();
    private final TabPane documentTabs = new TabPane();
    private final Tab treeTab = new Tab("Resource Tree");
    private final Tab bundleTab = new Tab("Bundle");
    private final PrettyView prettyView = new PrettyView();
    private final Tab prettyTab = new Tab("Pretty");
    private final Tab detailsTab = new Tab("Details");
    private final Tab jsonTab = new Tab("JSON");
    private final Tab xmlTab = new Tab("XML");
    private final Tab fhirPathTab = new Tab("FHIRPath");

    private final CheckMenuItem showUnpopulated =
            new CheckMenuItem("Show elements that are not populated");

    /** The node currently selected in the resource tree, if any. */
    private ResourceNode selectedNode;

    private final TextField treeSearchField = new TextField();
    private final MenuButton themeMenu = new MenuButton("Theme");
    private final ThemeManager themeManager = new ThemeManager();

    // Editing actions, created up front so their enabled state can be updated at any time.
    private final MenuItem newMenuItem = new MenuItem("New...");
    private final MenuItem saveMenuItem = new MenuItem("Save");
    private final MenuItem saveAsMenuItem = new MenuItem("Save As...");
    private final MenuItem undoMenuItem = new MenuItem("Undo Change");
    private final MenuItem addChildMenuItem = new MenuItem("Add Child Element...");
    private final MenuItem deleteMenuItem = new MenuItem("Delete Element");
    private final Button saveButton = new Button("Save");
    private final Button undoButton = new Button("Undo");

    /**
     * Which profile validation runs against: automatic (the resource's own
     * {@code meta.profile}), the base FHIR R4 definition, or an installed IG
     * profile chosen by the user (Updates 11 and 12).
     */
    private final ComboBox<ProfileChoice> profileChoice = new ComboBox<>();

    /** One entry of the profile picker. */
    private record ProfileChoice(String label, String canonical) {

        @Override
        public String toString() {
            return label;
        }
    }

    /** The resource loaded from a file or sample. */
    private LoadedResource loadedResource;
    /** The resource currently shown: the loaded resource or one of its Bundle entries. */
    private IBaseResource displayedResource;
    private String displayedLabel = "";
    /** When a Bundle entry is being displayed, the entry it came from. */
    private BundleEntryInfo displayedEntry;

    /** Last directory used by a file chooser, so dialogs reopen in the same folder. */
    private File lastDirectory;

    /**
     * True while the window is being closed by an action that has already asked about
     * unsaved changes, so the close request handler does not ask a second time.
     */
    private boolean closingFromAction;

    public MainWindow(Stage stage, HostServices hostServices) {
        this.stage = stage;
        this.hostServices = hostServices;
        buildLayout();
        wireInteractions();
        startIgPackageAutoLoad();
        // Closing the window is the last chance to save an edited resource.
        stage.setOnCloseRequest(event -> {
            if (closingFromAction) {
                return;
            }
            if (!confirmUnsavedChanges("closing the window")) {
                event.consume();
            }
        });
        updateWindowTitle();
    }

    /** The root node of the window, for use in a {@link javafx.scene.Scene}. */
    public Parent getRoot() {
        return root;
    }

    /**
     * Loads IG packages downloaded in an earlier session, so validation can use
     * them without re-loading each file. Runs on a background thread to keep
     * startup responsive; the validator is rebuilt whenever the loaded set
     * changes (see ValidationService), so validation before the load finishes
     * is corrected by the next validation.
     */
    private void startIgPackageAutoLoad() {
        Thread loader = new Thread(() -> {
            try {
                int loaded = fhirService.validationService().getPackageManager()
                        .loadAllFrom(PackageStorage.getStorageDirectory());
                if (loaded > 0) {
                    logger.info("Auto-loaded " + loaded + " IG package(s) from storage");
                }
            } catch (RuntimeException e) {
                logger.warning("IG package auto-load failed: " + e.getMessage());
            }
        }, "ig-package-auto-load");
        loader.setDaemon(true);
        loader.start();
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
        fhirPathTab.setContent(fhirPathView);
        fhirPathTab.setClosable(false);
        documentTabs.getTabs().addAll(prettyTab, detailsTab, jsonTab, xmlTab, fhirPathTab);
        documentTabs.getSelectionModel().select(prettyTab);
        // Hidden tabs are not laid out, so a scroll performed while a tab is hidden
        // would be lost; sync the views again whenever a tab is selected.
        documentTabs.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> resyncDocumentViews());

        SplitPane splitPane = new SplitPane(structureTabs, documentTabs);
        splitPane.setDividerPositions(0.38);
        structureTabs.getStyleClass().add("sidebar");

        // Returning to the Resource Tree tab from the Bundle tab resets the
        // views to the whole Bundle, so the tree no longer shows the last
        // selected entry (issue: "back to Resource Tree shows only the last entry").
        structureTabs.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> {
                    if (selected == treeTab && displayedEntry != null) {
                        displayBundleEntry(null);
                    }
                });

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

        saveButton.getStyleClass().add("button-ghost");
        saveButton.setTooltip(new Tooltip("Write the loaded resource back to its file."));
        saveButton.setOnAction(event -> saveResource());

        undoButton.getStyleClass().add("button-ghost");
        undoButton.setTooltip(new Tooltip("Undo the last change made to the resource."));
        undoButton.setOnAction(event -> undoEdit());

        HBox leftActions = new HBox(8, openButton, saveButton, undoButton, validateButton);
        leftActions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        profileChoice.getStyleClass().add("button-ghost");
        profileChoice.setTooltip(new Tooltip("Choose the profile to validate against. "
                + "\"Automatic\" uses the resource's meta.profile; an installed IG profile "
                + "can be selected even when the resource has no meta.profile."));
        profileChoice.setPrefWidth(260);
        profileChoice.setMaxWidth(260);
        refreshProfileChoices();

        themeMenu.getStyleClass().add("button-ghost");
        themeMenu.setTooltip(new Tooltip("Pick one of the available application themes."));
        themeMenu.getItems().setAll(themeMenuItems());

        HBox header = new HBox(14, logo, appTitle, leftActions, profileChoice, treeSearchField, themeMenu);
        header.getStyleClass().add("header-bar");
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(treeSearchField, Priority.ALWAYS);
        treeSearchField.setMaxWidth(340);
        return header;
    }

    private MenuBar buildMenuBar() {
        Menu fileMenu = new Menu("File");

        newMenuItem.setAccelerator(KeyCombination.keyCombination("Shortcut+N"));
        newMenuItem.setOnAction(event -> newResource());

        MenuItem open = new MenuItem("Open...");
        open.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        open.setOnAction(event -> openFile());

        saveMenuItem.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        saveMenuItem.setOnAction(event -> saveResource());

        saveAsMenuItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+S"));
        saveAsMenuItem.setOnAction(event -> saveResourceAs());

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
        exit.setOnAction(event -> closeWindow());

        fileMenu.getItems().addAll(
                newMenuItem,
                open,
                new SeparatorMenuItem(),
                saveMenuItem,
                saveAsMenuItem,
                new SeparatorMenuItem(),
                sampleMenu,
                exportJson,
                exportXml,
                new SeparatorMenuItem(),
                close,
                exit);
        
        Menu igMenu = buildIgMenu();
        return new MenuBar(fileMenu, buildEditMenu(), buildViewMenu(), 
                buildToolsMenu(), buildHelpMenu(), igMenu);
    }

    private Menu buildEditMenu() {
        undoMenuItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Z"));
        undoMenuItem.setOnAction(event -> undoEdit());

        addChildMenuItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+A"));
        addChildMenuItem.setOnAction(event -> addChildToSelection(selectedNode));

        deleteMenuItem.setAccelerator(KeyCombination.keyCombination("Delete"));
        deleteMenuItem.setOnAction(event -> deleteSelectedNode());

        return new Menu("Edit", null, undoMenuItem, new SeparatorMenuItem(), addChildMenuItem, deleteMenuItem);
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
        MenuItem showFhirPath = new MenuItem("FHIRPath");
        showFhirPath.setOnAction(event -> documentTabs.getSelectionModel().select(fhirPathTab));

        MenuItem expandAll = new MenuItem("Expand All");
        expandAll.setOnAction(event -> treeView.expandAll());
        MenuItem collapseAll = new MenuItem("Collapse All");
        collapseAll.setOnAction(event -> treeView.collapseAll());

        showUnpopulated.setOnAction(event -> refreshTree());

        Menu themesMenu = new Menu("Theme");
        themesMenu.getItems().setAll(themeMenuItems());

        viewMenu.getItems().addAll(
                showTree,
                showBundle,
                new SeparatorMenuItem(),
                showPretty,
                showDetails,
                showJson,
                showXml,
                showFhirPath,
                new SeparatorMenuItem(),
                expandAll,
                collapseAll,
                new SeparatorMenuItem(),
                themesMenu,
                showUnpopulated);
        return viewMenu;
    }

    private Menu buildToolsMenu() {
        MenuItem validate = new MenuItem("Validate Resource");
        validate.setAccelerator(KeyCombination.keyCombination("Shortcut+T"));
        validate.setOnAction(event -> validateDisplayedResource());

        MenuItem searchServer = new MenuItem("Search FHIR Server...");
        searchServer.setAccelerator(KeyCombination.keyCombination("Shortcut+K"));
        searchServer.setOnAction(event -> searchServer());

        MenuItem manageServers = new MenuItem("FHIR Servers...");
        manageServers.setOnAction(event -> manageServers());

        return new Menu("Tools", null, validate, new SeparatorMenuItem(), searchServer, manageServers);
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
        detailsView.setOnValueEdited(this::applyValueEdit);
        detailsView.setOnElementAdded(this::addElementToSelection);
        detailsView.setOnChildAddRequested(this::addChildToSelection);
        detailsView.setOnElementDeleted(node -> deleteNode(node, true));
        jsonView.setOnApplyRequested(this::applyJsonSource);
        xmlView.setOnApplyRequested(this::applyXmlSource);
        fhirPathView.setExpressionEvaluator(
                expression -> fhirService.evaluateFHIRPath(currentTarget(), expression));
        treeView.setOnNodeSelected(node -> {
            selectedNode = node;
            detailsView.show(node);
            updateEditActions();
            // Show the pretty detail of the selected element, the same way
            // selecting a Bundle entry shows the detail of that entry.
            if (node != null && displayedResource != null) {
                prettyView.show(fhirService.buildPrettyView(displayedResource, node));
                prettyView.scrollToElement(node);
            }
            jsonView.scrollToElement(node);
            xmlView.scrollToElement(node);
        });
        treeView.setOnReferenceActivated(this::navigateToReference);
        bundleView.setOnEntrySelected(this::displayBundleEntry);
        // Selecting a validation message highlights the element it refers to.
        statusView.setOnIssueSelected(this::locateIssueInTree);
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

    /** Builds one menu item per available theme. */
    private List<MenuItem> themeMenuItems() {
        List<MenuItem> items = new java.util.ArrayList<>();
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            MenuItem item = new MenuItem(theme.getDisplayName());
            item.setOnAction(event -> applyTheme(theme));
            items.add(item);
        }
        return items;
    }

    /** Applies the given theme and reports the change in the status bar. */
    private void applyTheme(ThemeManager.Theme theme) {
        if (stage.getScene() == null) {
            return;
        }
        themeManager.apply(stage.getScene(), theme);
        themeMenu.setText("Theme: " + theme.getDisplayName());
        setStatus("Theme switched to " + theme.getDisplayName() + ".");
    }

    // ------------------------------------------------------------------
    // Opening and displaying resources
    // ------------------------------------------------------------------

    private void openFile() {
        if (!confirmUnsavedChanges("opening another resource")) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open FHIR resource");
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("FHIR resources", "*.json", "*.xml"),
                new FileChooser.ExtensionFilter("FHIR JSON", "*.json"),
                new FileChooser.ExtensionFilter("FHIR XML", "*.xml"),
                new FileChooser.ExtensionFilter("All files", "*.*"));

        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        openPath(file.toPath());
    }

    private void openPath(Path path) {
        runWithWaitCursor("Loading " + FileSupport.fileName(path) + " ...", () -> {
            LoadedResource resource = fhirService.openFile(path);
            display(resource);
        });
    }

    private void openSample(String classpathResource) {
        if (!confirmUnsavedChanges("opening a sample")) {
            return;
        }
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
        selectedNode = null;
        // A different resource starts a new editing history.
        undoStack.clear();

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
        refreshProfileChoices();
        updateWindowTitle();
    }

    /** Rebuilds the tree for the currently displayed resource (used by the View menu toggle). */
    private void refreshTree() {
        if (loadedResource == null) {
            return;
        }
        String selectedPath = selectedNode == null ? null : selectedNode.getPath();
        if (displayedEntry == null) {
            treeView.show(fhirService.buildTree(loadedResource, showUnpopulated.isSelected()));
        } else {
            treeView.show(fhirService.buildTree(displayedEntry, showUnpopulated.isSelected()));
        }
        // Keep the element the user was looking at selected.
        treeView.selectPath(selectedPath);
    }

    /** Shows the whole Bundle, or one of its entries, in every view. */
    private void displayBundleEntry(BundleEntryInfo entry) {
        if (loadedResource == null) {
            return;
        }
        selectedNode = null;
        if (entry == null) {
            displayedEntry = null;
            displayedResource = loadedResource.getResource();
            displayedLabel = loadedResource.getDisplayName();
            treeView.show(fhirService.buildTree(loadedResource, showUnpopulated.isSelected()));
            updateDocumentViews();
            detailsView.showNothingSelected();
            setStatus("Showing the Bundle resource itself: " + displayedLabel);
            refreshProfileChoices();
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
        refreshProfileChoices();
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
            fhirPathView.recalculate();
            return;
        }
        prettyView.show(displayedEntry == null
                ? fhirService.buildPrettyView(loadedResource)
                : fhirService.buildPrettyView(displayedEntry));
        jsonView.show(fhirService.toJson(displayedResource));
        xmlView.show(fhirService.toXml(displayedResource));
        // The FHIRPath entries are evaluated against the resource that just changed,
        // so their results stay in step with the tree, the Pretty View and JSON/XML.
        fhirPathView.recalculate();
    }

    private int countNodes(ResourceNode node) {
        int count = 1;
        for (ResourceNode child : node.getChildren()) {
            count += countNodes(child);
        }
        return count;
    }
    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    /** Creates a new, empty resource of a type chosen by the user. */
    private void newResource() {
        if (!confirmUnsavedChanges("starting a new resource")) {
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>("Patient", templateFactory.resourceTypeNames());
        dialog.initOwner(stage);
        dialog.setTitle(APPLICATION_TITLE);
        dialog.setHeaderText("Create a new FHIR resource");
        dialog.setContentText("Resource type:");
        applyDialogTheme(dialog.getDialogPane());
        Optional<String> chosen = dialog.showAndWait();
        if (chosen.isEmpty()) {
            return;
        }
        try {
            IBaseResource resource = templateFactory.createEmpty(chosen.get());
            LoadedResource created = new LoadedResource(resource, ResourceFormat.JSON, chosen.get(), null);
            display(created);
            // Every element of a new resource is empty, so list them all: that is what
            // makes the resource editable in the tree and the Details tab.
            showUnpopulated.setSelected(true);
            refreshTree();
            created.markDirty();
            updateWindowTitle();
            setStatus("New " + chosen.get() + " created. Select an element in the tree, enter a value in the"
                    + " Details tab and apply it, then save with File > Save As.");
        } catch (IllegalArgumentException e) {
            showFailure("Could not create a new " + chosen.get(), e);
        }
    }

    /** Saves the loaded resource, asking for a file when it does not have one yet. */
    private boolean saveResource() {
        if (loadedResource == null) {
            setStatus("Nothing to save. Open or create a FHIR resource first.");
            return false;
        }
        Path target = loadedResource.getSourcePath();
        if (target == null) {
            return saveResourceAs();
        }
        return writeResource(target, loadedResource.getFormat());
    }

    /** Saves the loaded resource to a file chosen by the user. */
    private boolean saveResourceAs() {
        if (loadedResource == null) {
            setStatus("Nothing to save. Open or create a FHIR resource first.");
            return false;
        }
        ResourceFormat format = loadedResource.getFormat();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save resource as");
        chooser.setInitialFileName(suggestedFileName(loadedResource.getResource(), format));
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("FHIR JSON", "*.json"),
                new FileChooser.ExtensionFilter("FHIR XML", "*.xml"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        chooser.setSelectedExtensionFilter(
                chooser.getExtensionFilters().get(format == ResourceFormat.XML ? 1 : 0));

        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return false;
        }
        lastDirectory = file.getParentFile();
        Path path = file.toPath();
        // The chosen file name decides the format, so "Save As patient.xml" writes XML.
        ResourceFormat written = ResourceFormat.fromFileName(file.getName()).orElse(format);
        if (!writeResource(path, written)) {
            return false;
        }
        loadedResource.setSourcePath(path);
        loadedResource.setSourceName(FileSupport.fileName(path));
        loadedResource.setFormat(written);
        updateWindowTitle();
        return true;
    }

    /**
     * Writes the whole loaded resource (a Bundle included) to a file and marks it as
     * saved.
     */
    private boolean writeResource(Path path, ResourceFormat format) {
        try {
            FileSupport.writeText(path, fhirService.serialize(loadedResource.getResource(), format));
            loadedResource.markClean();
            updateWindowTitle();
            setStatus("Saved " + loadedResource.getDisplayName() + " to " + path);
            return true;
        } catch (IOException | RuntimeException e) {
            showFailure("Could not save " + path, e);
            return false;
        }
    }

    /**
     * Applies an edited value to the element selected in the tree.
     *
     * @param node the element that was edited
     * @param text the text the user entered
     */
    private void applyValueEdit(ResourceNode node, String text) {
        IBaseResource target = currentTarget();
        if (node == null || target == null) {
            setStatus("Select an element in the resource tree first.");
            return;
        }
        String path = node.getElementInfo().getPath();
        IBaseResource snapshot = pushUndoSnapshot();
        try {
            if (node.getElementInfo().getKind() == ElementInfo.Kind.REFERENCE) {
                editorService.setReference(target, path, referenceValue(text));
            } else {
                editorService.setPrimitive(target, path, text);
            }
            refreshAfterEdit(path);
            setStatus("Updated " + path + " of " + displayedLabel + ".");
        } catch (RuntimeException e) {
            discardUndoSnapshot(snapshot);
            showFailure("Could not update " + path, e);
        }
    }

    /** Adds a new entry to the repeating element selected in the tree. */
    private void addElementToSelection(ResourceNode node) {
        IBaseResource target = currentTarget();
        if (node == null || target == null) {
            setStatus("Select a repeating element in the resource tree first.");
            return;
        }
        String path = node.getElementInfo().getPath();
        IBaseResource snapshot = pushUndoSnapshot();
        try {
            int existing = editorService.valueCount(target, path);
            editorService.addElement(target, path);
            // The new entry is the last one of the repeating element, so select it: its
            // value can then be filled in straight away.
            refreshAfterEdit(path + "[" + existing + "]", path);
            setStatus("Added a new " + node.getElementInfo().getName() + " entry to " + displayedLabel + ".");
        } catch (RuntimeException e) {
            discardUndoSnapshot(snapshot);
            showFailure("Could not add a " + node.getElementInfo().getName() + " entry", e);
        }
    }

    /**
     * Opens the dialog that lists the child elements the FHIR resource definition
     * allows under the element selected in the tree, then adds the picked element.
     * A primitive element is asked for an initial value right after it is added.
     */
    private void addChildToSelection(ResourceNode node) {
        IBaseResource target = currentTarget();
        if (node == null || target == null) {
            setStatus("Select an element in the resource tree first.");
            return;
        }
        String parentPath = addableParentPath(node);
        List<ElementProperty> children;
        try {
            children = editorService.childElements(target, parentPath);
        } catch (RuntimeException e) {
            showFailure("Could not list the child elements of " + parentPath, e);
            return;
        }
        if (children.isEmpty()) {
            setStatus("The FHIR definition has no child element that can be added to " + parentPath + ".");
            return;
        }
        AddChildDialog dialog = new AddChildDialog(parentPath, children);
        dialog.initOwner(stage);
        applyDialogTheme(dialog.getDialogPane());
        Optional<ElementProperty> picked = dialog.showAndWait();
        if (picked.isEmpty()) {
            return;
        }
        addChildElement(target, parentPath, picked.get());
    }

    /**
     * The path the Add child dialog works on. A repeating element without an entry
     * yet, such as {@code Patient.telecom} with no values, receives its first entry
     * first, so the dialog lists the children the new entry can take. A repeating
     * element with entries works on the last entry, matching the way the tree
     * groups the entries of a repeating element.
     */
    private String addableParentPath(ResourceNode node) {
        String path = node.getElementInfo().getPath();
        IBaseResource target = currentTarget();
        if (target == null) {
            return path;
        }
        boolean repeatingGroup = node.getElementInfo().getKind() == ElementInfo.Kind.COMPLEX
                && node.getElementInfo().isRepeating()
                && !path.endsWith("]");
        if (!repeatingGroup) {
            return path;
        }
        int entries = editorService.valueCount(target, path);
        if (entries > 0) {
            return path + "[" + (entries - 1) + "]";
        }
        IBaseResource snapshot = pushUndoSnapshot();
        try {
            editorService.addElement(target, path);
            refreshAfterEdit(path + "[0]", path);
            return path + "[0]";
        } catch (RuntimeException e) {
            discardUndoSnapshot(snapshot);
            return path;
        }
    }

    /** Adds the child element the user picked in the Add child dialog. */
    private void addChildElement(IBaseResource target, String parentPath, ElementProperty child) {
        String childPath = parentPath + "." + child.name();
        IBaseResource snapshot = pushUndoSnapshot();
        try {
            editorService.addNode(target, parentPath, child.name());
            // A new entry of a repeating element is addressed with its index, exactly
            // the way the tree names it, so the value can be filled in straight away.
            String newPath = child.isRepeating()
                    ? childPath + "[" + (editorService.valueCount(target, childPath) - 1) + "]"
                    : childPath;
            promptForInitialValue(target, newPath, child);
            refreshAfterEdit(newPath, childPath, parentPath);
            setStatus("Added " + newPath + " to " + displayedLabel + ".");
        } catch (RuntimeException e) {
            discardUndoSnapshot(snapshot);
            showFailure("Could not add " + childPath, e);
        }
    }

    /**
     * Asks for an initial value right after a primitive or reference element was
     * added, so the new element is not left empty. An empty answer keeps the
     * element as it is; complex elements continue in the tree instead.
     */
    private void promptForInitialValue(IBaseResource target, String newPath, ElementProperty child) {
        IBase added = editorService.resolveElement(target, newPath);
        boolean reference = added instanceof IBaseReference;
        if (!reference && !(added instanceof IPrimitiveType<?>)) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(stage);
        dialog.setTitle(APPLICATION_TITLE);
        dialog.setHeaderText((reference ? "Reference target for " : "Initial value for ") + newPath);
        dialog.setContentText(reference
                ? "Target, for example Practitioner/123 (empty keeps the element without a target):"
                : "Value (empty keeps the element without a value):");
        applyDialogTheme(dialog.getDialogPane());
        Optional<String> value = dialog.showAndWait();
        if (value.isEmpty() || value.get().isBlank()) {
            return;
        }
        try {
            if (reference) {
                editorService.setReference(target, newPath, referenceValue(value.get()));
            } else {
                editorService.setPrimitive(target, newPath, value.get());
            }
        } catch (RuntimeException e) {
            showFailure("Could not set the value of " + newPath, e);
        }
    }

    /** Deletes the element selected in the tree, asking for confirmation first. */
    private void deleteSelectedNode() {
        deleteNode(selectedNode, true);
    }

    /**
     * Deletes the given element from the displayed resource.
     *
     * @param confirm when {@code true} a confirmation is shown for elements that
     *                remove more than a single value
     */
    private void deleteNode(ResourceNode node, boolean confirm) {
        IBaseResource target = currentTarget();
        if (node == null || target == null) {
            setStatus("Select an element in the resource tree first.");
            return;
        }
        if (node.getParent() == null) {
            setStatus("The resource itself cannot be deleted; use File > Close instead.");
            return;
        }
        String path = node.getElementInfo().getPath();
        if (confirm && !confirmRemoval(node, path)) {
            return;
        }
        IBaseResource snapshot = pushUndoSnapshot();
        try {
            editorService.deleteNode(target, path);
            String parentPath = node.getParent().getElementInfo().getPath();
            refreshAfterEdit(parentPath, path);
            setStatus("Deleted " + path + " from " + displayedLabel + ".");
        } catch (RuntimeException e) {
            discardUndoSnapshot(snapshot);
            showFailure("Could not delete " + path, e);
        }
    }

    /** Asks before deleting an element that removes a whole subtree of values. */
    private boolean confirmRemoval(ResourceNode node, String path) {
        if (node.getChildren().isEmpty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(APPLICATION_TITLE);
        alert.setHeaderText("Delete " + path + "?");
        alert.setContentText("This removes the element with its "
                + (countNodes(node) - 1) + " child elements from " + displayedLabel
                + ". The change can be undone.");
        applyDialogTheme(alert.getDialogPane());
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK;
    }

    /** The resource the tree and the document tabs currently show, or {@code null}. */
    private IBaseResource currentTarget() {
        return loadedResource == null ? null : displayedResource;
    }

    /**
     * The reference target inside an entered value. The tree renders a reference as
     * <code>Type/id (display)</code>, so a trailing display is dropped before the value is
     * written; a plain <code>Type/id</code> or an absolute URL is used as entered.
     */
    private static String referenceValue(String text) {
        String value = text == null ? "" : text.trim();
        int display = value.indexOf(" (");
        return display > 0 ? value.substring(0, display).trim() : value;
    }

    /**
     * Marks the resource as changed and rebuilds every view for it. The first path that
     * still exists in the rebuilt tree is selected again, so an edit does not lose the
     * user's place.
     */
    private void refreshAfterEdit(String... pathsToSelect) {
        if (loadedResource == null) {
            return;
        }
        loadedResource.markDirty();
        treeView.show(displayedEntry == null
                ? fhirService.buildTree(loadedResource, showUnpopulated.isSelected())
                : fhirService.buildTree(displayedEntry, showUnpopulated.isSelected()));
        // The documents are rebuilt before the selection is restored, because selecting
        // a node renders the element view of that node.
        updateDocumentViews();
        if (displayedEntry == null) {
            // While an entry is displayed the Bundle navigator must keep naming that entry,
            // so it is only rebuilt when the whole Bundle is shown.
            updateBundleView();
        }
        statusView.clearValidation();
        for (String path : pathsToSelect) {
            if (treeView.selectPath(path)) {
                break;
            }
        }
        updateWindowTitle();
    }

    /** Records the state of the resource before an edit, so the edit can be undone. */
    private IBaseResource pushUndoSnapshot() {
        if (loadedResource == null) {
            return null;
        }
        IBaseResource snapshot = editorService.cloneResource(loadedResource.getResource());
        undoStack.push(snapshot);
        while (undoStack.size() > MAX_UNDO_DEPTH) {
            undoStack.removeLast();
        }
        updateEditActions();
        return snapshot;
    }

    /** Forgets a snapshot when the edit it was taken for failed. */
    private void discardUndoSnapshot(IBaseResource snapshot) {
        if (snapshot != null) {
            undoStack.remove(snapshot);
            updateEditActions();
        }
    }

    /** Restores the resource as it was before the last edit. */
    private void undoEdit() {
        if (undoStack.isEmpty() || loadedResource == null) {
            setStatus("There is nothing to undo.");
            return;
        }
        Deque<IBaseResource> remaining = new ArrayDeque<>(undoStack);
        IBaseResource snapshot = remaining.pop();
        LoadedResource restored = new LoadedResource(
                snapshot,
                loadedResource.getFormat(),
                loadedResource.getSourceName(),
                null,
                loadedResource.getSourcePath());
        // Every snapshot is the state before one edit, so popping the last one restores
        // exactly what was loaded from disk.
        if (remaining.isEmpty()) {
            restored.markClean();
        } else {
            restored.markDirty();
        }
        display(restored);
        undoStack.clear();
        undoStack.addAll(remaining);
        updateEditActions();
        setStatus(remaining.isEmpty()
                ? "Undid every change; " + restored.getDisplayName() + " matches the saved resource again."
                : "Undid the last change to " + restored.getDisplayName() + ".");
    }

    /**
     * Asks what to do about unsaved changes before an action replaces or closes the
     * loaded resource.
     *
     * @return {@code true} when the action may continue
     */
    private boolean confirmUnsavedChanges(String action) {
        if (loadedResource == null || !loadedResource.isDirty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(APPLICATION_TITLE);
        alert.setHeaderText("Unsaved changes");
        alert.setContentText("Save the changes to " + loadedResource.getDisplayName() + " before " + action + "?");
        applyDialogTheme(alert.getDialogPane());

        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(save, discard, cancel);

        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == cancel) {
            return false;
        }
        if (choice.get() == save) {
            // Saving can still be cancelled (Save As), so only continue when it worked.
            return saveResource() && loadedResource != null && !loadedResource.isDirty();
        }
        return true;
    }

    /** Closes the window, after giving unsaved changes a chance to be saved. */
    private void closeWindow() {
        if (!confirmUnsavedChanges("exiting")) {
            return;
        }
        closingFromAction = true;
        stage.close();
    }

    /** Gives a dialog the same stylesheets as the main window, so it follows the theme. */
    private void applyDialogTheme(DialogPane pane) {
        pane.getStylesheets().addAll(themeManager.stylesheets());
        pane.setPrefWidth(620);
    }

    // ------------------------------------------------------------------
    // FHIR server connectivity
    // ------------------------------------------------------------------

    /** Adds a FHIR server through the server dialog and keeps it for this session. */
    private void manageServers() {
        ServerDialog dialog = new ServerDialog(serverService, themeManager);
        dialog.initOwner(stage);
        dialog.showAndWait().ifPresent(definition -> {
            boolean stored = serverManager.add(definition);
            setStatus(stored
                    ? "Server " + definition.name() + " (" + definition.baseUrl() + ") added; use Tools >"
                            + " Search FHIR Server to search it."
                    : "A server named " + definition.name() + " is already configured.");
        });
    }

    /** Searches a configured FHIR server and shows the picked resource in the viewer. */
    private void searchServer() {
        if (serverManager.servers().isEmpty()) {
            // Nothing configured yet: the natural next step is adding one.
            manageServers();
        }
        if (serverManager.servers().isEmpty()) {
            setStatus("No FHIR server is configured. Use Tools > FHIR Servers... to add one.");
            return;
        }
        ServerSearchDialog dialog = new ServerSearchDialog(serverService, serverManager, fhirService, themeManager);
        dialog.initOwner(stage);
        dialog.showAndWait().ifPresent(resource -> {
            if (!confirmUnsavedChanges("displaying a resource from a server")) {
                return;
            }
            display(resource);
            setStatus("Showing " + resource.getDisplayName() + " (" + resource.getSourceName() + ").");
        });
    }

    // ------------------------------------------------------------------
    // Validation, export and window helpers
    // ------------------------------------------------------------------

    private void validateDisplayedResource() {
        if (displayedResource == null) {
            setStatus("Nothing to validate. Open a FHIR resource first.");
            return;
        }
        ProfileChoice choice = profileChoice.getValue();
        String canonical = choice == null ? null : choice.canonical();
        String against = choice == null || choice.canonical().isEmpty()
                ? ""
                : " against " + choice.label();
        setStatus("Validating " + displayedLabel + against + " ...");
        setBusy(true);
        try {
            ValidationReport report = fhirService.validate(displayedResource, canonical);
            statusView.showValidation(report);
        } finally {
            setBusy(false);
        }
    }

    /**
     * Rebuilds the profile picker for the displayed resource: automatic,
     * base FHIR R4, and every installed profile that applies to the resource
     * type (Update 12).
     */
    private void refreshProfileChoices() {
        ProfileChoice previous = profileChoice.getValue();
        List<ProfileChoice> choices = new java.util.ArrayList<>();
        choices.add(new ProfileChoice("Automatic (meta.profile)", ""));
        String resourceType = displayedResource == null ? null : displayedResource.fhirType();
        if (resourceType != null && !resourceType.isEmpty()) {
            choices.add(new ProfileChoice("FHIR R4 " + resourceType,
                    ValidationService.BASE_DEFINITION_ONLY));
            for (var profile : fhirService.validationService().getPackageManager()
                    .profilesFor(resourceType)) {
                choices.add(new ProfileChoice(profile.label(), profile.canonical()));
            }
        }
        profileChoice.getItems().setAll(choices);
        for (ProfileChoice candidate : choices) {
            if (previous != null && candidate.label().equals(previous.label())) {
                profileChoice.getSelectionModel().select(candidate);
                return;
            }
        }
        profileChoice.getSelectionModel().select(0);
    }

    /**
     * Selects the element a validation message points at in the resource tree,
     * so a problem can be inspected in place (Update 13).
     */
    private void locateIssueInTree(ValidationIssue issue) {
        for (String path : candidatePaths(issue.location())) {
            if (treeView.selectPath(path)) {
                setStatus("Selected " + path + " in the resource tree.");
                return;
            }
        }
    }

    /**
     * Possible tree paths for a validation location. The validator reports
     * locations such as {@code Patient.name[0].family} or
     * {@code Patient/123: Patient.name}; the tree uses element paths without
     * the resource type prefix.
     */
    private static List<String> candidatePaths(String location) {
        if (location == null || location.isBlank()) {
            return List.of();
        }
        String path = location.trim();
        int colon = path.lastIndexOf(": ");
        if (colon >= 0) {
            path = path.substring(colon + 2).trim();
        }
        List<String> candidates = new java.util.ArrayList<>();
        candidates.add(path);
        int dot = path.indexOf('.');
        if (dot > 0 && !path.substring(0, dot).contains("[")) {
            candidates.add(path.substring(dot + 1));
        }
        int slash = path.indexOf('/');
        if (slash > 0) {
            candidates.add(path.substring(slash + 1));
        }
        return candidates.stream().filter(value -> !value.isBlank()).toList();
    }

    private void export(ResourceFormat format) {
        if (displayedResource == null) {
            setStatus("Nothing to export. Open a FHIR resource first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export " + format.getDisplayName());
        chooser.setInitialFileName(suggestedFileName(displayedResource, format));
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(format.getDisplayName(), "*." + format.getExtension()));

        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        try {
            FileSupport.writeText(file.toPath(), fhirService.serialize(displayedResource, format));
            setStatus("Exported " + displayedLabel + " to " + file.getAbsolutePath());
        } catch (IOException e) {
            showFailure("Could not write " + file, e);
        }
    }

    /** Applies the text of the JSON editor to the current resource. */
    private void applyJsonSource(String text) {
        applySourceText(text, ResourceFormat.JSON);
    }

    /** Applies the text of the XML editor to the current resource. */
    private void applyXmlSource(String text) {
        applySourceText(text, ResourceFormat.XML);
    }

    /**
     * Parses and validates edited source text, then replaces the current resource.
     *
     * <p>On failure nothing is replaced: the error is shown, the edited text is left
     * intact for correction, and the tree, Pretty View, FHIRPath results and
     * serialized views keep showing the current resource.</p>
     */
    private void applySourceText(String text, ResourceFormat format) {
        if (loadedResource == null || currentTarget() == null) {
            setStatus("Nothing to apply. Open a FHIR resource first.");
            return;
        }
        // Recorded before the parse so a successful replacement can be undone.
        IBaseResource snapshot = pushUndoSnapshot();
        Deque<IBaseResource> history = new ArrayDeque<>(undoStack);
        try {
            String sourceName = loadedResource.getSourceName() == null
                    ? displayedLabel + "." + format.getExtension()
                    : loadedResource.getSourceName();
            SourceEditorService.AppliedSource applied = sourceEditorService.apply(text, format, sourceName);
            replaceCurrentResource(applied, format);
            restoreUndoHistory(history);
        } catch (RuntimeException e) {
            restoreUndoHistory(history);
            discardUndoSnapshot(snapshot);
            showFailure("Could not apply the edited " + format.getDisplayName(), e);
        }
    }

    /**
     * Swaps the loaded resource for a parsed replacement and refreshes every view
     * from it. The caller keeps the editing history, so applying source text can be
     * undone like any other edit.
     */
    private void replaceCurrentResource(SourceEditorService.AppliedSource applied, ResourceFormat editedFormat) {
        IBaseResource replacement = applied.resource();
        LoadedResource next = new LoadedResource(
                replacement,
                applied.format(),
                loadedResource.getSourceName(),
                sourceEditorService.serialize(replacement, applied.format()),
                loadedResource.getSourcePath());
        next.markDirty();
        // display() rebuilds the tree, the Pretty View, the serialized views, the
        // Bundle navigator and the title from the replacement resource.
        display(next);
        setStatus("Applied the edited " + editedFormat.getDisplayName() + " to "
                + next.getDisplayName() + " (" + applied.report().getSummary() + ").");
    }

    /** Puts back the editing history that {@link #display} resets when it reloads a view. */
    private void restoreUndoHistory(Deque<IBaseResource> history) {
        undoStack.clear();
        undoStack.addAll(history);
        updateEditActions();
    }

    /** The default file name for an export, for example <code>Patient-123.json</code>. */
    private String suggestedFileName(IBaseResource resource, ResourceFormat format) {
        String type = resource.fhirType();
        IIdType idElement = resource.getIdElement();
        String id = idElement != null && idElement.hasIdPart() ? idElement.getIdPart() : null;
        return (id == null ? type : type + "-" + id) + "." + format.getExtension();
    }

    private void closeResource() {
        if (!confirmUnsavedChanges("closing the resource")) {
            return;
        }
        loadedResource = null;
        displayedResource = null;
        displayedEntry = null;
        displayedLabel = "";
        selectedNode = null;
        undoStack.clear();
        treeView.show(null);
        prettyView.showNothing();
        jsonView.showNothing();
        xmlView.showNothing();
        fhirPathView.recalculate();
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
        // The dialog lives in its own scene; give it the same stylesheets so
        // the design tokens (-surface, -text-strong, ...) resolve there too.
        alert.getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        alert.getDialogPane().setContent(aboutContent());
        alert.getDialogPane().setPrefWidth(680);
        alert.showAndWait();
    }

    /**
     * The About dialog content: the GPL v3 notice, the project links and the
     * licenses of the bundled open-source libraries, scrollable because the
     * license list is long.
     */
    private Node aboutContent() {
        AboutInfo about = new AboutInfo(
                fhirService.fhirVersion(),
                System.getProperty("java.version"),
                System.getProperty("javafx.version", "unknown"));

        VBox content = new VBox(4);
        content.getStyleClass().add("about-content");
        for (AboutInfo.Line line : about.lines()) {
            switch (line.kind()) {
                case SECTION -> {
                    Label label = new Label(line.text());
                    label.getStyleClass().add("about-section-title");
                    content.getChildren().add(label);
                }
                case TEXT -> {
                    Label label = new Label(line.text());
                    label.setWrapText(true);
                    label.getStyleClass().add("about-line");
                    content.getChildren().add(label);
                }
                case MUTED -> {
                    Label label = new Label(line.text());
                    label.setWrapText(true);
                    label.getStyleClass().addAll("about-line", "about-muted");
                    content.getChildren().add(label);
                }
                case LINK -> {
                    Hyperlink link = new Hyperlink(line.text());
                    link.setWrapText(true);
                    link.setOnAction(event -> openInBrowser(line.target()));
                    content.getChildren().add(link);
                }
            }
        }

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(420);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    /** Opens a project or contact link, reporting failures in the status bar. */
    private void openInBrowser(String target) {
        try {
            if (hostServices != null) {
                hostServices.showDocument(target);
            }
        } catch (RuntimeException e) {
            setStatus("Could not open " + target);
        }
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
        if (displayedResource == null) {
            stage.setTitle(APPLICATION_TITLE);
        } else {
            // The asterisk is the usual "there are unsaved changes" marker.
            boolean dirty = loadedResource != null && loadedResource.isDirty();
            stage.setTitle(APPLICATION_TITLE + " - " + displayedLabel + (dirty ? " *" : ""));
        }
        updateEditActions();
    }

    /** Enables Save and Undo only when there is something for them to act on. */
    private void updateEditActions() {
        boolean loaded = loadedResource != null;
        boolean canUndo = !undoStack.isEmpty();
        boolean hasSelection = loaded && selectedNode != null;
        saveMenuItem.setDisable(!loaded);
        saveAsMenuItem.setDisable(!loaded);
        saveButton.setDisable(!loaded);
        undoMenuItem.setDisable(!canUndo);
        undoButton.setDisable(!canUndo);
        addChildMenuItem.setDisable(!hasSelection);
        // The resource itself cannot be deleted, only the elements inside it.
        deleteMenuItem.setDisable(!hasSelection || selectedNode.getParent() == null);
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        alert.getDialogPane().setPrefWidth(620);
        alert.showAndWait();
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
        // Same stylesheets as the main scene, so dialogs follow the active theme.
        alert.getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        alert.getDialogPane().setPrefWidth(620);
        alert.showAndWait();
    }

    /**
     * Builds the Implementation Guide menu for managing FHIR NPM packages.
     */
    private Menu buildIgMenu() {
        Menu igMenu = new Menu("Implementation Guide");
        
        MenuItem managePackagesItem = new MenuItem("Manage Packages...");
        managePackagesItem.setOnAction(event -> openIgPackageManager());
        
        MenuItem loadedPackagesItem = new MenuItem("View Loaded Packages");
        loadedPackagesItem.setOnAction(event -> showLoadedPackagesInfo());
        
        igMenu.getItems().addAll(managePackagesItem, 
                new SeparatorMenuItem(), 
                loadedPackagesItem);
        
        return igMenu;
    }

    /**
     * Opens the IG Package Manager dialog.
     */
    private void openIgPackageManager() {
        try {
            IgPackageDialog dialog = new IgPackageDialog(stage,
                    fhirService.validationService().getPackageManager(),
                    new com.example.fhirviewer.service.PackageRegistryService());
            dialog.showAndWait();
            // Packages may have been installed or activated: update the profile picker.
            refreshProfileChoices();
        } catch (Exception e) {
            showFailure("Failed to open IG Package Manager", e);
        }
    }

    /**
     * Shows information about currently loaded packages.
     */
    private void showLoadedPackagesInfo() {
        try {
            Class<?> validationServiceClass = 
                    Class.forName("com.example.fhirviewer.service.ValidationService");
            java.lang.reflect.Method getPackageManager = 
                    validationServiceClass.getMethod("getPackageManager");
            Object packageManager = getPackageManager.invoke(fhirService.validationService());
            
            java.lang.reflect.Method getLoadedPackages = 
                    packageManager.getClass().getMethod("getLoadedPackages");
            java.util.List<?> packages = (java.util.List<?>) getLoadedPackages.invoke(packageManager);
            
            if (packages.isEmpty()) {
                showAlert(Alert.AlertType.INFORMATION, "Loaded Packages", 
                        "No Implementation Guide packages are currently loaded.\\n\\n" +
                        "To load packages:\\n" +
                        "1. Go to Implementation Guide > Manage Packages\\n" +
                        "2. Search for packages or load from file\\n" +
                        "3. Download and load the desired packages");
            } else {
                StringBuilder message = new StringBuilder();
                message.append("Loaded Implementation Guide Packages\\n");
                message.append("=====================================\\n\\n");
                for (Object pkg : packages) {
                    java.lang.reflect.Method getName = pkg.getClass().getMethod("name");
                    java.lang.reflect.Method getVersion = pkg.getClass().getMethod("version");
                    java.lang.reflect.Method getFhirVersion = pkg.getClass().getMethod("fhirVersion");
                    message.append("- ").append(getName.invoke(pkg))
                           .append(" ").append(getVersion.invoke(pkg))
                           .append(" (").append(getFhirVersion.invoke(pkg)).append(")\\n");
                }
                message.append("\\nThese packages are available for validation.");
                showAlert(Alert.AlertType.INFORMATION, "Loaded Packages", message.toString());
            }
        } catch (Exception e) {
            showFailure("Failed to get package information", e);
        }
    }
}
