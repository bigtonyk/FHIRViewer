package com.spiraleyes.fhirviewer.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import com.spiraleyes.fhirviewer.fhir.ElementProperty;
import com.spiraleyes.fhirviewer.model.BundleEntryInfo;
import com.spiraleyes.fhirviewer.model.ElementInfo;
import com.spiraleyes.fhirviewer.model.LoadedResource;
import com.spiraleyes.fhirviewer.model.ResourceFormat;
import com.spiraleyes.fhirviewer.model.ResourceNode;
import com.spiraleyes.fhirviewer.model.ValidationIssue;
import com.spiraleyes.fhirviewer.model.ValidationReport;
import com.spiraleyes.fhirviewer.service.FhirService;
import com.spiraleyes.fhirviewer.service.PackageStorage;
import com.spiraleyes.fhirviewer.service.ResourceEditorService;
import com.spiraleyes.fhirviewer.service.ResourceTemplateFactory;
import com.spiraleyes.fhirviewer.service.SourceEditorService;
import com.spiraleyes.fhirviewer.service.ValidationService;
import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.workspace.FhirPathWorkspaceStateProvider;
import com.spiraleyes.fhirviewer.workspace.TreeTabsWorkspaceStateProvider;
import com.spiraleyes.fhirviewer.workspace.WorkspaceService;
import com.spiraleyes.fhirviewer.server.FhirServerPlugin;
import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.server.FhirServerService;
import com.spiraleyes.fhirviewer.server.PluginConfig;
import com.spiraleyes.fhirviewer.server.PluginLoader;
import com.spiraleyes.fhirviewer.server.PluginSettingsStore;
import com.spiraleyes.fhirviewer.server.ServerCredentials;
import com.spiraleyes.fhirviewer.server.ServerCredentialSaver;
import com.spiraleyes.fhirviewer.server.ServerPassphrase;
import com.spiraleyes.fhirviewer.server.ServerDefinition;
import com.spiraleyes.fhirviewer.server.ServerOperationException;
import com.spiraleyes.fhirviewer.server.ServerOrigin;
import com.spiraleyes.fhirviewer.server.smart.SmartFhirAccess;
import com.spiraleyes.fhirviewer.server.smart.SmartLaunchStore;

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
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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

    private static final Logger logger = LoggerFactory.getLogger(MainWindow.class);
    private static final String APPLICATION_TITLE = "FHIR Resource Viewer";
    private static final String READY_STATUS = "Ready. Use File > Open to load a FHIR JSON or XML resource.";
    /** How many edits can be undone; older snapshots are dropped. */
    private static final int MAX_UNDO_DEPTH = 20;

    private final Stage stage;

    /**
     * The About dialog's content, logo and links. Extracted from this class as the first step
     * of the M1 split, so the window keeps only the menu item that opens it.
     */
    private final AboutDialog aboutDialog;
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

    /**
     * The passphrase the user typed into the plugin manager, kept for the rest of the
     * session so a later search can still decrypt a saved password. It lives here
     * rather than in the dialog because the dialog clears its field once it has saved.
     *
     * <p>Declared before {@link #serverService} because field initialisers run in order
     * and the service reads this holder while it is being built.
     */
    private final ServerPassphrase serverPassphrase = new ServerPassphrase();
    /** Talks to FHIR servers through plugins; the UI never sees HTTP or URLs. */
    private final FhirServerService serverService = new FhirServerService(
            PluginLoader.load(),
            ServerCredentials.from(new PluginSettingsStore(pluginSettingsFile()), serverPassphrase));
    /** The servers configured in this session and the active one. */
    private final FhirServerManager serverManager = new FhirServerManager();

    /**
     * Captures and restores workspace state across sessions, wired to the live
     * subsystem instances so connections, validation packages and other feature
     * state survive save/open.
     */
    private final WorkspaceService workspaceService = new WorkspaceService(
            serverManager,
            serverService.registry(),
            fhirService.validationService().getPackageManager(),
            // The tree selection, the two tab selections and the FHIRPath entries live in
            // this window, so the providers that capture and apply them are wired here.
            new TreeTabsWorkspaceStateProvider(this::captureTreeTabs, this::applyTreeTabs),
            new FhirPathWorkspaceStateProvider(this::captureFhirPath, this::applyFhirPath));

    /**
     * New, Open, Save and the questions asked before a workspace is replaced or the window
     * closes. Created in the constructor rather than here, because the dialogs are modal to
     * the {@link Stage} and that does not exist yet when field initializers run.
     */
    private final WorkspaceActions workspaceActions;

    /**
     * The Implementation Guide menu. Extracted as the second step of the M1 split: it needs the
     * package manager, the profile picker and the workspace's change signal, nothing else.
     */
    private final IgPackageActions igPackageActions;

    /**
     * Save to, patch, delete and refresh from a server, plus the conflict question. Extracted
     * as the third step of the M1 split; it reaches the document through a port.
     */
    private final ServerResourceActions serverResourceActions;

    /**
     * The server screens — manager, plugins, search, open, SMART workbench, status, operation,
     * REST and scripting consoles. Extracted as the fourth step of the M1 split.
     */
    private final ServerScreens serverScreens;

    /**
     * Open, open sample, save, save as and export. The file-level half of the M1 fifth step;
     * the editor core (display, undo, close) stays here until it can be launched.
     */
    private final DocumentSession documentSession;

    /**
     * The last search, so both search screens reopen showing it. A search that had to be
     * typed again after opening a result was the complaint that led to this.
     */
    private final SearchMemory searchMemory = new SearchMemory();

/**
 * What each server advertises, shared by the two search screens.
 *
 * <p>Session-scoped and not persisted: a capability statement describes the software, not
 * the data, so it holds for as long as the viewer runs. Sharing it means opening one screen
 * after the other does not re-read the same CapabilityStatement twice.</p>
 */
private final ServerCapabilitiesCache capabilitiesCache = new ServerCapabilitiesCache();

    /**
     * Decides and performs every write to a FHIR server.
     *
     * <p>Held as a field so the File menu action and the conflict handler share one
     * instance, and so the rules about validation, create-versus-update, conflicts and
     * the rebased origin are in one testable place rather than spread through the
     * handlers below. It validates through {@code FhirService}, so a push is checked
     * against the same rules as the Validate menu item.</p>
     */
    private ServerResourceCoordinator coordinator;

    /**
     * Where plugin settings are kept.
     *
     * <p>Named in one place so the service that reads them and the dialog that writes
     * them cannot disagree about the file.
     */
    private static java.nio.file.Path pluginSettingsFile() {
        return java.nio.file.Path.of(
                System.getProperty("user.home", "."),
                ".fhirviewer", "plugin-settings.properties");
    }

    /**
     * Where the SMART launch configurations are kept.
     *
     * <p>Named in one place for the same reason as {@link #pluginSettingsFile()}: the
     * workbench writes this file and nothing else should have an opinion about where it
     * lives. The store itself guarantees no secret reaches it.</p>
     */
    private static java.nio.file.Path smartLaunchFile() {
        return java.nio.file.Path.of(
                System.getProperty("user.home", "."),
                ".fhirviewer", "smart-launches.properties");
    }

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
     * The resource actions that only make sense for a resource that came from a server.
     *
     * <p>Held as fields because their enabled state depends on the displayed resource's
     * origin, which changes on every display. Offering "Delete from FHIR Server" for a file
     * that was opened from disk would be a button that always fails.</p>
     */
    private MenuItem refreshFromServerItem;
    private MenuItem patchOnServerItem;
    private MenuItem deleteFromServerItem;

    /**
     * The File menu's server actions.
     *
     * <p>Both depend on state that is not known while the menu is being built: opening
     * needs at least one configured server, and saving needs a displayed resource. Held
     * as fields so {@link #updateServerActions()} can set their state whenever either
     * changes, exactly as the existing Save and Undo items do.</p>
     */
    private MenuItem openFromServerItem;
    private MenuItem saveToServerItem;

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
    /**
     * Where the displayed resource came from on a FHIR server, or {@code null} when it
     * came from a file, a sample or the clipboard.
     *
     * <p>This is deliberately not part of {@link LoadedResource}, which is file-shaped and
     * whose {@code sourcePath} means a path on disk. Holding it here, next to
     * {@code displayedResource}, keeps the file-open paths untouched while still letting
     * "Save to FHIR Server" know the type, id and version it must write.
     */
    private ServerOrigin displayedOrigin;

    /**
     * True while the window is being closed by an action that has already asked about
     * unsaved changes, so the close request handler does not ask a second time.
     *
     * <p>Cleared again when that close returns. A close request can be vetoed by any
     * handler, and a flag left set would make every later close and Exit skip both
     * prompts.</p>
     */
    private boolean closingFromAction;

    public MainWindow(Stage stage, HostServices hostServices) {
        this.stage = stage;
        this.aboutDialog = new AboutDialog(stage, hostServices, themeManager,
                fhirService.fhirVersion(), this::setStatus);
        this.coordinator = new ServerResourceCoordinator(serverService, fhirService::validate);
        this.workspaceActions = new WorkspaceActions(stage, workspaceService, themeManager,
                this::setStatus);
        this.igPackageActions = new IgPackageActions(stage,
                fhirService.validationService().getPackageManager(),
                (title, message) -> showAlert(Alert.AlertType.INFORMATION, title, message),
                this::showFailure,
                this::refreshProfileChoices,
                workspaceActions::markWorkspaceChanged);
        // One port over this window's document state, shared by the extracted classes: they
        // show a resource, ask before replacing it and hand out its JSON, and nothing else
        // about the document leaves this class (R4 §7).
        DocumentPort document = new DocumentPort() {
            @Override
            public IBaseResource resource() {
                return displayedResource;
            }

            @Override
            public ServerOrigin origin() {
                return displayedOrigin;
            }

            @Override
            public LoadedResource loaded() {
                return loadedResource;
            }

            @Override
            public String label() {
                return displayedLabel;
            }

            @Override
            public void show(LoadedResource resource, ServerOrigin origin) {
                display(resource, origin);
            }

            @Override
            public boolean confirmDiscard(String action) {
                return confirmUnsavedChanges(action);
            }

            @Override
            public String currentJson() {
                return currentResourceJson();
            }

            @Override
            public void close() {
                closeResource();
            }

            @Override
            public void showValidation(ValidationReport report) {
                statusView.showValidation(report);
            }

            @Override
            public void busy(boolean busy) {
                setBusy(busy);
            }
        };
        this.serverResourceActions = new ServerResourceActions(stage, coordinator, serverService,
                serverManager, themeManager, this::setStatus, document);
        this.serverScreens = new ServerScreens(stage, serverService, serverManager, fhirService,
                themeManager, searchMemory, capabilitiesCache, serverPassphrase, this::setStatus,
                this::persistServers, this::updateEditActions, serverResourceActions::serverFor,
                MainWindow::pluginSettingsFile, MainWindow::smartLaunchFile, document);
        this.documentSession = new DocumentSession(stage, fhirService, this::setStatus,
                this::showFailure, this::updateWindowTitle, document);
        loadSavedServers();
        buildLayout();
        // The workspace the previous session had open. Without this the window held a
        // service that had never opened anything: Save could only fail with "No workspace
        // open", and the close prompt had no changes of its own to ask about.
        workspaceActions.openLastWorkspace();
        // Opening a workspace restores the configured servers and the package activation
        // set, so the menu and picker state that depends on both is refreshed now rather
        // than when the user next happens to trigger one of them.
        updateEditActions();
        updateServerActions();
        refreshProfileChoices();
        wireInteractions();
        startIgPackageAutoLoad();
        // Closing the window is the last chance to save an edited resource.
        stage.setOnCloseRequest(event -> {
            if (!closingFromAction
                    && (!confirmUnsavedChanges("closing the window")
                            || !workspaceActions.closeDecision())) {
                event.consume();
                return;
            }
            // The window is going away, so the recovery snapshot thread has nothing left to
            // watch; without this every window closed and reopened left one behind.
            workspaceActions.close();
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
                logger.warn("IG package auto-load failed: " + e.getMessage());
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
        openButton.setOnAction(event -> documentSession.openFile());
        Button validateButton = new Button("Validate");
        validateButton.getStyleClass().add("button-ghost");
        validateButton.setOnAction(event -> validateDisplayedResource());

        saveButton.getStyleClass().add("button-ghost");
        saveButton.setTooltip(new Tooltip("Write the loaded resource back to its file."));
        saveButton.setOnAction(event -> documentSession.save());

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
        open.setOnAction(event -> documentSession.openFile());

        // Phase 7. Held as fields like the other state-dependent items, because their
        // enabled state depends on both the displayed resource and the configured
        // servers, neither of which is known when the menu is built.
        openFromServerItem = new MenuItem("Open from FHIR Server...");
        openFromServerItem.setOnAction(event -> serverScreens.open());

        saveToServerItem = new MenuItem("Save to FHIR Server...");
        saveToServerItem.setOnAction(event -> serverResourceActions.save());

        saveMenuItem.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        saveMenuItem.setOnAction(event -> documentSession.save());

        saveAsMenuItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+S"));
        saveAsMenuItem.setOnAction(event -> documentSession.saveAs());

        MenuItem samplePatient = new MenuItem("Patient (JSON)");
        samplePatient.setOnAction(event -> documentSession.openSample(FhirService.SAMPLE_PATIENT));
        MenuItem sampleBundle = new MenuItem("Bundle (JSON, 2 entries)");
        sampleBundle.setOnAction(event -> documentSession.openSample(FhirService.SAMPLE_BUNDLE));
        Menu sampleMenu = new Menu("Open Sample", null, samplePatient, sampleBundle);

        MenuItem exportJson = new MenuItem("Export as JSON...");
        exportJson.setOnAction(event -> documentSession.export(ResourceFormat.JSON));
        MenuItem exportXml = new MenuItem("Export as XML...");
        exportXml.setOnAction(event -> documentSession.export(ResourceFormat.XML));

        MenuItem close = new MenuItem("Close");
        close.setOnAction(event -> closeResource());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(event -> closeWindow());

        fileMenu.getItems().addAll(
                newMenuItem,
                open,
                openFromServerItem,
                new SeparatorMenuItem(),
                saveMenuItem,
                saveAsMenuItem,
                saveToServerItem,
                new SeparatorMenuItem(),
                sampleMenu,
                exportJson,
                exportXml,
                new SeparatorMenuItem(),
                close,
                exit);
        
        Menu workspaceMenu = new Menu("Workspace");
        MenuItem wsNew = new MenuItem("New Workspace...");
        wsNew.setOnAction(e -> newWorkspace());
        MenuItem wsOpen = new MenuItem("Open Workspace...");
        wsOpen.setOnAction(e -> openWorkspace());
        MenuItem wsSave = new MenuItem("Save Workspace");
        wsSave.setOnAction(e -> saveWorkspace());
        MenuItem wsSaveAs = new MenuItem("Save Workspace As...");
        wsSaveAs.setOnAction(e -> saveWorkspaceAs());
        workspaceMenu.getItems().addAll(wsNew, wsOpen, new SeparatorMenuItem(), wsSave, wsSaveAs);

        Menu igMenu = igPackageActions.menu();
        return new MenuBar(fileMenu, buildEditMenu(), buildViewMenu(),
                buildToolsMenu(), buildHelpMenu(), igMenu, workspaceMenu);
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
        searchServer.setOnAction(event -> serverScreens.search());

        MenuItem manageServers = new MenuItem("FHIR Servers...");
        manageServers.setOnAction(event -> serverScreens.manageServers());

        MenuItem managePlugins = new MenuItem("Server Plugins...");
        managePlugins.setOnAction(event -> serverScreens.managePlugins());

        // Phase 6. Both are generic: the operation list comes from the active plugin, so a
        // future vendor's operations appear here without this menu knowing they exist.
        MenuItem serverStatus = new MenuItem("Server Status and Capabilities...");
        serverStatus.setOnAction(event -> serverScreens.connectionStatus());

        MenuItem runOperation = new MenuItem("Run Server Operation...");
        runOperation.setOnAction(event -> serverScreens.operation());

        // The REST console is a peer of the plugin path, not a subordinate of it: it calls
        // endpoints no plugin declares, which is the whole reason it exists.
        MenuItem restConsole = new MenuItem("REST Console...");
        restConsole.setOnAction(event -> serverScreens.restConsole());

        MenuItem scriptingConsole = new MenuItem("Scripting Console...");
        scriptingConsole.setOnAction(event -> serverScreens.scriptingConsole());

        // SMART on FHIR launches. The workbench itself opens like every other screen: a
        // dialog from the Tools menu. Discovery and authorization inside it stay disabled
        // until Phases 02 and 03 wire them up.
        MenuItem smartWorkbench = new MenuItem("SMART Launch Workbench...");
        smartWorkbench.setOnAction(event -> serverScreens.smartWorkbench());

        // Resource actions, enabled only when the displayed resource came from a server.
        MenuItem refreshFromServer = new MenuItem("Refresh from FHIR Server");
        refreshFromServer.setDisable(true);
        refreshFromServer.setOnAction(event -> serverResourceActions.refresh());

        MenuItem patchOnServer = new MenuItem("Patch on FHIR Server...");
        patchOnServer.setDisable(true);
        patchOnServer.setOnAction(event -> serverResourceActions.patch());

        MenuItem deleteFromServer = new MenuItem("Delete from FHIR Server...");
        deleteFromServer.setDisable(true);
        deleteFromServer.setOnAction(event -> serverResourceActions.delete());

        this.refreshFromServerItem = refreshFromServer;
        this.patchOnServerItem = patchOnServer;
        this.deleteFromServerItem = deleteFromServer;

        // Open from / Save to a server live in the File menu: they open and save the
        // displayed resource, which is what the File menu is for. What remains here is
        // the server administration, plus the actions on a resource already read from
        // one.
        return new Menu("Tools", null, validate, new SeparatorMenuItem(), searchServer,
                refreshFromServer, patchOnServer,
                deleteFromServer, new SeparatorMenuItem(), manageServers, managePlugins,
                new SeparatorMenuItem(), serverStatus, runOperation,
                new SeparatorMenuItem(), restConsole,
                new SeparatorMenuItem(), scriptingConsole,
                new SeparatorMenuItem(), smartWorkbench);
    }

    private Menu buildHelpMenu() {
        MenuItem userGuide = new MenuItem("User Guide");
        userGuide.setOnAction(event -> showUserGuide());
        MenuItem smartGuide = new MenuItem("SMART on FHIR Launch Guide");
        smartGuide.setOnAction(event -> showSmartGuide());
        MenuItem scriptingGuide = new MenuItem("Scripting Guide");
        scriptingGuide.setOnAction(event -> showPhase6Guide());
        MenuItem about = new MenuItem("About");
        about.setOnAction(event -> aboutDialog.show());
        return new Menu("Help", null, userGuide, new SeparatorMenuItem(),
                smartGuide, new SeparatorMenuItem(), scriptingGuide, new SeparatorMenuItem(), about);
    }

    /** Opens the SMART on FHIR Launch Guide, bundled alongside the server-functions guide. */
    private void showSmartGuide() {
        UserGuideDialog dialog = new UserGuideDialog(themeManager,
                UserGuideContent.loadSmartGuide());
        dialog.initOwner(stage);
        dialog.show();
    }

    /** Opens the Scripting Guide. */
    private void showPhase6Guide() {
        UserGuideDialog dialog = new UserGuideDialog(themeManager,
                UserGuideContent.loadPhase6Guide());
        dialog.initOwner(stage);
        dialog.show();
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
            documentSession.openPath(path);
        } catch (RuntimeException e) {
            setStatus("The file " + location + " could not be read: " + e.getMessage());
        }
    }

    /** Displays a freshly loaded resource in every view. */
    private void display(LoadedResource resource) {
        display(resource, null);
    }

    /**
     * Displays a resource and records where it came from.
     *
     * <p>A {@code null} origin means "not from a server", which is the case for every
     * file, sample and clipboard load. Clearing it on those paths is what stops a
     * resource read from a server from being written back to that server after the user
     * opens an unrelated file.
     */
    private void display(LoadedResource resource, ServerOrigin origin) {
        loadedResource = resource;
        displayedResource = resource.getResource();
        displayedLabel = resource.getDisplayName();
        displayedOrigin = origin;
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
     * The same resource as JSON, for the REST console's body copy; {@code null} when
     * nothing is open.
     */
    private String currentResourceJson() {
        IBaseResource resource = currentTarget();
        return resource == null ? null : fhirService.toJson(resource);
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
            return documentSession.save() && loadedResource != null && !loadedResource.isDirty();
        }
        return true;
    }

    private void newWorkspace() {
        workspaceActions.newWorkspace();
    }

    private void openWorkspace() {
        workspaceActions.openWorkspace();
    }

    private void saveWorkspace() {
        workspaceActions.save();
    }

    private void saveWorkspaceAs() {
        workspaceActions.saveAs();
    }

    private void closeWindow() {
        if (!confirmUnsavedChanges("exiting") || !workspaceActions.closeDecision()) {
            return;
        }
        // The close request handler runs inside stage.close() and must not ask about the
        // same unsaved work twice, so the flag covers exactly that call. A close another
        // handler vetoes must not leave it set: the next Exit would then discard edits
        // without a question.
        try {
            closingFromAction = true;
            stage.close();
        } finally {
            closingFromAction = false;
        }
    }

    /** Gives a dialog the same stylesheets as the main window, so it follows the theme. */
    private void applyDialogTheme(DialogPane pane) {
        pane.getStylesheets().addAll(themeManager.stylesheets());
        pane.setPrefWidth(620);
    }

    // ------------------------------------------------------------------
    // FHIR server connectivity
    // ------------------------------------------------------------------

    /**
     * Restores the servers configured in an earlier session.
     *
     * <p>Before this, {@code FhirServerManager} was in-memory only, so "Open from
     * Server" worked for servers added in this one session and silently offered an empty
     * list on the next morning. Loaded before the layout so the first menu state is
     * already correct and "Open from FHIR Server" is not briefly greyed out.</p>
     *
     * <p>A damaged file is logged and skipped rather than reported: failing to start
     * because a settings file is unreadable would be far worse than starting with no
     * servers, which one dialog re-adds.</p>
     */
    private void loadSavedServers() {
        int loaded = serverManager.load(FhirServerManager.defaultStoreFile());
        if (loaded > 0) {
            logger.info("Restored " + loaded + " configured FHIR server(s)");
        }
        // "Load on start" plugins provision their server here. The flag used to be
        // write-only — saved and shown back, but never read — so a built-in ticked
        // in the plugin dialog never gained its server after a restart.
        int provisioned = com.spiraleyes.fhirviewer.server.StartupServers.provision(
                serverManager, serverService.plugins(),
                new PluginSettingsStore(pluginSettingsFile()));
        if (provisioned > 0) {
            logger.info("Added " + provisioned + " load-on-start server(s)");
            persistServers();
        }
    }
    /**
     * Writes the server list out, logging rather than interrupting on failure.
     *
     * <p>Called after an add, which is not worth an error dialog over: the server is in
     * the list for this session, and the user can see the log if it does not survive.
     * A save the user asked for explicitly is the case that should be reported.</p>
     */
    private void persistServers() {
        try {
            serverManager.save(FhirServerManager.defaultStoreFile());
            // The server list is part of what a workspace captures, so a workspace that
            // gained or changed a server now has something worth saving.
            workspaceActions.markWorkspaceChanged();
        } catch (IOException e) {
            logger.warn("Could not save the FHIR server list: " + e.getMessage());
            setStatus("The server was added for this session only: the list could not be saved ("
                    + e.getMessage() + ").");
        }
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

    /** The default file name for an export, for example <code>Patient-123.json</code>. */    private void closeResource() {
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

    private void showUserGuide() {
        UserGuideDialog dialog = new UserGuideDialog(themeManager);
        dialog.initOwner(stage);
        dialog.show();
    }

    /**
     * The tree selection and the two tab selections, as a workspace records them.
     *
     * <p>An element path identifies the selected node across a save and a load; the tabs are
     * recorded by title, because the tab objects themselves belong to this window.</p>
     */
    private Map<String, Object> captureTreeTabs() {
        Map<String, Object> state = new LinkedHashMap<>();
        TreeItem<ResourceNode> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null) {
            state.put("selected", selected.getValue().getPath());
        }
        state.put("structureTab", selectedTabTitle(structureTabs));
        state.put("documentTab", selectedTabTitle(documentTabs));
        return state;
    }

    /**
     * Applies a restored tree selection and tab choice.
     *
     * <p>Both are best effort: the element a workspace recorded may not exist in the resource
     * loaded now, and a tab title may have changed since, so a miss leaves the current state
     * alone instead of failing the open.</p>
     */
    private void applyTreeTabs(Map<String, Object> state) {
        selectTabTitled(structureTabs, state.get("structureTab"));
        selectTabTitled(documentTabs, state.get("documentTab"));
        Object selected = state.get("selected");
        if (selected instanceof String path) {
            treeView.selectPath(path);
        }
    }

    /** The expressions the FHIRPath tab holds, for a workspace to record. */
    private List<String> captureFhirPath() {
        return fhirPathView.expressions();
    }

    /** Puts a workspace's expressions back into the FHIRPath tab. */
    private void applyFhirPath(List<String> expressions, String contextRef) {
        fhirPathView.restoreExpressions(expressions);
    }

    /** The title of the selected tab, or {@code null} when the pane has none. */
    private static String selectedTabTitle(TabPane pane) {
        Tab selected = pane.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.getText();
    }

    /** Selects the tab whose title matches, leaving the selection alone when none does. */
    private static void selectTabTitled(TabPane pane, Object title) {
        if (!(title instanceof String wanted)) {
            return;
        }
        for (Tab tab : pane.getTabs()) {
            if (wanted.equals(tab.getText())) {
                pane.getSelectionModel().select(tab);
                return;
            }
        }
    }

    /** Runs a loading action, reporting failures in the status bar and in a dialog. */    private void setBusy(boolean busy) {
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
        updateServerActions();
    }

    /**
     * Enables the server-backed resource actions for the displayed resource.
     *
     * <p>Every one of them needs an origin: a resource opened from a file or a sample has
     * no server behind it, and "Delete from FHIR Server" on such a resource could only fail.
     * A resource that <em>was</em> read from a server also has to be one the server has
     * actually saved, since there is no id to address it by otherwise.</p>
     */
    private void updateServerActions() {
        boolean fromServer = displayedOrigin != null && displayedOrigin.isSaved();
        boolean hasServer = !serverManager.servers().isEmpty();
        if (refreshFromServerItem != null) {
            refreshFromServerItem.setDisable(!fromServer);
        }
        if (patchOnServerItem != null) {
            patchOnServerItem.setDisable(!fromServer);
        }
        if (deleteFromServerItem != null) {
            deleteFromServerItem.setDisable(!fromServer);
        }
        if (openFromServerItem != null) {
            // With no server configured the action could only open the "add a server"
            // dialog, which is not what the item says it does, so it is disabled and the
            // user is pointed at the item that does add one.
            openFromServerItem.setDisable(!hasServer);
        }
        if (saveToServerItem != null) {
            // A resource to write and somewhere to write it to. A resource with no
            // origin is still offered: a file or a sample can be pushed to a server, and
            // that is a create rather than an update.
            saveToServerItem.setDisable(displayedResource == null || !hasServer);
        }
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

}
