package com.spiraleyes.fhirviewer.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;

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

    private final TextField treeSearchField = new TextField();
    private final MenuButton themeMenu = new MenuButton("Theme");
    private final ThemeManager themeManager = new ThemeManager();

    private final Button saveButton = new Button("Save");
    private final Button undoButton = new Button("Undo");

    /**
     * The menu bar, with the items whose enabled state depends on the document or the server
     * list. Built by {@code MainMenuFactory} as the last step of the M1 split; the enabling
     * stays here, in {@code updateEditActions} and {@code updateServerActions}, because this
     * window is what knows when either changed.
     */
    private final MainMenuFactory.Result menus;

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
                fhirService.validationService().getPackageManager(), themeManager,
                (title, message) -> showAlert(Alert.AlertType.INFORMATION, title, message),
                this::showFailure,
                this::refreshProfileChoices,
                workspaceActions::markWorkspaceChanged);
        // The document session owns the loaded resource, its origin, the selection and the
        // edit history; the views stay here because buildLayout builds them. It is also the
        // DocumentPort the server classes talk to (R4 §7).
        DocumentViews documentViews = new DocumentViews() {
            @Override
            public ResourceTreeView tree() {
                return treeView;
            }

            @Override
            public DetailsView details() {
                return detailsView;
            }

            @Override
            public JsonView json() {
                return jsonView;
            }

            @Override
            public XmlView xml() {
                return xmlView;
            }

            @Override
            public PrettyView pretty() {
                return prettyView;
            }

            @Override
            public StatusView status() {
                return statusView;
            }

            @Override
            public BundleView bundle() {
                return bundleView;
            }

            @Override
            public FhirPathView fhirPath() {
                return fhirPathView;
            }

            @Override
            public TabPane documentTabs() {
                return documentTabs;
            }

            @Override
            public TabPane structureTabs() {
                return structureTabs;
            }

            @Override
            public Tab treeTab() {
                return treeTab;
            }

            @Override
            public Tab prettyTab() {
                return prettyTab;
            }

            @Override
            public Tab bundleTab() {
                return bundleTab;
            }

            @Override
            public Tab detailsTab() {
                return detailsTab;
            }

            @Override
            public Tab jsonTab() {
                return jsonTab;
            }

            @Override
            public Tab xmlTab() {
                return xmlTab;
            }

            @Override
            public Tab fhirPathTab() {
                return fhirPathTab;
            }

            @Override
            public boolean showUnpopulated() {
                return showUnpopulated.isSelected();
            }
        };
        this.documentSession = new DocumentSession(stage, fhirService, editorService,
                sourceEditorService, themeManager, documentViews, this::setStatus,
                this::showFailure, this::updateWindowTitle, this::updateEditActions,
                this::refreshProfileChoices, this::setBusy);
        this.serverResourceActions = new ServerResourceActions(stage, coordinator, serverService,
                serverManager, themeManager, this::setStatus, documentSession);
        this.serverScreens = new ServerScreens(stage, serverService, serverManager, fhirService,
                themeManager, searchMemory, capabilitiesCache, serverPassphrase, this::setStatus,
                this::persistServers, this::updateEditActions, serverResourceActions::serverFor,
                MainWindow::pluginSettingsFile, MainWindow::smartLaunchFile, documentSession);
        this.menus = new MainMenuFactory(documentSession, serverScreens, serverResourceActions,
                igPackageActions, workspaceActions, aboutDialog, documentViews, showUnpopulated,
                this::themeMenuItems, new MainMenuFactory.WindowActions() {
                    @Override
                    public void newResource() {
                        MainWindow.this.newResource();
                    }

                    @Override
                    public void closeWindow() {
                        MainWindow.this.closeWindow();
                    }

                    @Override
                    public void validate() {
                        MainWindow.this.validateDisplayedResource();
                    }

                    @Override
                    public void showUserGuide() {
                        MainWindow.this.showUserGuide();
                    }

                    @Override
                    public void showSmartGuide() {
                        MainWindow.this.showSmartGuide();
                    }

                    @Override
                    public void showPhase6Guide() {
                        MainWindow.this.showPhase6Guide();
                    }
                }).build();
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
                    && (!documentSession.confirmDiscard("closing the window")
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
                    if (selected == treeTab && documentSession.showingEntry()) {
                        documentSession.displayBundleEntry(null);
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
        undoButton.setOnAction(event -> documentSession.undoEdit());

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
        return menus.bar();
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
        detailsView.setOnValueEdited(documentSession::applyValueEdit);
        detailsView.setOnElementAdded(documentSession::addElementToSelection);
        detailsView.setOnChildAddRequested(documentSession::addChildToSelection);
        detailsView.setOnElementDeleted(node -> documentSession.deleteNode(node, true));
        jsonView.setOnApplyRequested(documentSession::applyJsonSource);
        xmlView.setOnApplyRequested(documentSession::applyXmlSource);
        fhirPathView.setExpressionEvaluator(
                expression -> fhirService.evaluateFHIRPath(documentSession.currentTarget(), expression));
        treeView.setOnNodeSelected(documentSession::select);
        treeView.setOnReferenceActivated(documentSession::navigateToReference);
        bundleView.setOnEntrySelected(documentSession::displayBundleEntry);
        // Selecting a validation message highlights the element it refers to.
        statusView.setOnIssueSelected(this::locateIssueInTree);
    }

    /**
     * Syncs the document views to the current tree selection again. Called when a
     * document tab is selected: hidden tabs are not laid out, so a scroll that was
     * requested while a tab was hidden would otherwise be lost.
     */
    private void resyncDocumentViews() {
        if (documentSession.selected() == null) {
            return;
        }
        prettyView.scrollToElement(documentSession.selected());
        jsonView.scrollToElement(documentSession.selected());
        xmlView.scrollToElement(documentSession.selected());
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

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    /** Creates a new, empty resource of a type chosen by the user. */
    private void newResource() {
        if (!documentSession.confirmDiscard("starting a new resource")) {
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>("Patient", templateFactory.resourceTypeNames());
        dialog.initOwner(stage);
        dialog.setTitle(APPLICATION_TITLE);
        dialog.setHeaderText("Create a new FHIR resource");
        dialog.setContentText("Resource type:");
        DialogTheme.apply(dialog.getDialogPane(), themeManager);
        Optional<String> chosen = dialog.showAndWait();
        if (chosen.isEmpty()) {
            return;
        }
        try {
            IBaseResource resource = templateFactory.createEmpty(chosen.get());
            LoadedResource created = new LoadedResource(resource, ResourceFormat.JSON, chosen.get(), null);
            documentSession.show(created, null);
            // Every element of a new resource is empty, so list them all: that is what
            // makes the resource editable in the tree and the Details tab.
            showUnpopulated.setSelected(true);
            documentSession.refreshTree();
            created.markDirty();
            updateWindowTitle();
            setStatus("New " + chosen.get() + " created. Select an element in the tree, enter a value in the"
                    + " Details tab and apply it, then save with File > Save As.");
        } catch (IllegalArgumentException e) {
            showFailure("Could not create a new " + chosen.get(), e);
        }
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
        if (!documentSession.confirmDiscard("exiting") || !workspaceActions.closeDecision()) {
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
        if (documentSession.resource() == null) {
            setStatus("Nothing to validate. Open a FHIR resource first.");
            return;
        }
        ProfileChoice choice = profileChoice.getValue();
        String canonical = choice == null ? null : choice.canonical();
        String against = choice == null || choice.canonical().isEmpty()
                ? ""
                : " against " + choice.label();
        setStatus("Validating " + documentSession.label() + against + " ...");
        setBusy(true);
        try {
            ValidationReport report = fhirService.validate(documentSession.resource(), canonical);
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
        String resourceType = documentSession.resource() == null ? null : documentSession.resource().fhirType();
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
        stage.setTitle(documentSession.windowTitle());
        updateEditActions();
    }

    /** Enables Save and Undo only when there is something for them to act on. */
    private void updateEditActions() {
        boolean loaded = documentSession.loaded() != null;
        boolean canUndo = !!documentSession.canUndo();
        boolean hasSelection = loaded && documentSession.selected() != null;
        menus.items().save().setDisable(!loaded);
        menus.items().saveAs().setDisable(!loaded);
        saveButton.setDisable(!loaded);
        menus.items().undo().setDisable(!canUndo);
        undoButton.setDisable(!canUndo);
        menus.items().addChild().setDisable(!hasSelection);
        // The resource itself cannot be deleted, only the elements inside it.
        menus.items().delete().setDisable(!hasSelection || documentSession.selected().getParent() == null);
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
        boolean fromServer = documentSession.origin() != null && documentSession.origin().isSaved();
        boolean hasServer = !serverManager.servers().isEmpty();
        if (menus.items().refreshFromServer() != null) {
            menus.items().refreshFromServer().setDisable(!fromServer);
        }
        if (menus.items().patchOnServer() != null) {
            menus.items().patchOnServer().setDisable(!fromServer);
        }
        if (menus.items().deleteFromServer() != null) {
            menus.items().deleteFromServer().setDisable(!fromServer);
        }
        if (menus.items().openFromServer() != null) {
            // With no server configured the action could only open the "add a server"
            // dialog, which is not what the item says it does, so it is disabled and the
            // user is pointed at the item that does add one.
            menus.items().openFromServer().setDisable(!hasServer);
        }
        if (menus.items().saveToServer() != null) {
            // A resource to write and somewhere to write it to. A resource with no
            // origin is still offered: a file or a sample can be pushed to a server, and
            // that is a create rather than an update.
            menus.items().saveToServer().setDisable(documentSession.resource() == null || !hasServer);
        }
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogTheme.apply(alert.getDialogPane(), themeManager);
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
        DialogTheme.apply(alert.getDialogPane(), themeManager);
        alert.showAndWait();
    }

}
