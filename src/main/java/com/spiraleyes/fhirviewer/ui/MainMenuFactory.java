package com.spiraleyes.fhirviewer.ui;

import java.util.List;
import java.util.function.Supplier;

import com.spiraleyes.fhirviewer.model.ResourceFormat;
import com.spiraleyes.fhirviewer.service.FhirService;

import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCombination;

/**
 * Builds the window's menu bar and hands back the items whose enabled state changes with the
 * document.
 *
 * <p>The last step of the M1 split (R4 §7), because the menu items are not just construction:
 * nine of them are enable-state handles that {@code updateEditActions} and
 * {@code updateServerActions} toggle whenever the displayed resource or the server list
 * changes. Building them here and returning them in {@link Items} keeps the construction in one
 * place while the enable state stays with the window that knows when it changed.</p>
 *
 * <p>Handlers come from the classes that own them — the document session, the server screens,
 * the server actions, the IG menu, the workspace actions — plus {@link WindowActions} for the
 * six that are still window-level (new resource, exit, validate, the three guides).</p>
 */
final class MainMenuFactory {

    /** The window-level actions the menus invoke directly. */
    interface WindowActions {

        void newResource();

        void closeWindow();

        void validate();

        void showUserGuide();

        void showSmartGuide();

        void showPhase6Guide();
    }

    /** The menu items whose enabled state the window sets. */
    record Items(MenuItem save, MenuItem saveAs, MenuItem undo, MenuItem addChild, MenuItem delete,
            MenuItem openFromServer, MenuItem saveToServer, MenuItem refreshFromServer,
            MenuItem patchOnServer, MenuItem deleteFromServer) {
    }

    /** The built bar and the handles to its state-dependent items. */
    record Result(MenuBar bar, Items items) {
    }

    private final DocumentSession documentSession;
    private final ServerScreens serverScreens;
    private final ServerResourceActions serverResourceActions;
    private final IgPackageActions igPackageActions;
    private final WorkspaceActions workspaceActions;
    private final AboutDialog aboutDialog;
    private final DocumentViews views;
    private final CheckMenuItem showUnpopulated;
    private final Supplier<List<MenuItem>> themeItems;
    private final WindowActions window;

    MainMenuFactory(DocumentSession documentSession, ServerScreens serverScreens,
            ServerResourceActions serverResourceActions, IgPackageActions igPackageActions,
            WorkspaceActions workspaceActions, AboutDialog aboutDialog, DocumentViews views,
            CheckMenuItem showUnpopulated, Supplier<List<MenuItem>> themeItems,
            WindowActions window) {
        this.documentSession = documentSession;
        this.serverScreens = serverScreens;
        this.serverResourceActions = serverResourceActions;
        this.igPackageActions = igPackageActions;
        this.workspaceActions = workspaceActions;
        this.aboutDialog = aboutDialog;
        this.views = views;
        this.showUnpopulated = showUnpopulated;
        this.themeItems = themeItems;
        this.window = window;
    }

    Result build() {
        Items items = new Items(
                new MenuItem("Save"),
                new MenuItem("Save As..."),
                new MenuItem("Undo Change"),
                new MenuItem("Add Child Element..."),
                new MenuItem("Delete Element"),
                new MenuItem("Open from FHIR Server..."),
                new MenuItem("Save to FHIR Server..."),
                new MenuItem("Refresh from FHIR Server"),
                new MenuItem("Patch on FHIR Server..."),
                new MenuItem("Delete from FHIR Server..."));

        // Server actions start disabled: nothing is displayed and no server is known yet.
        items.refreshFromServer().setDisable(true);
        items.patchOnServer().setDisable(true);
        items.deleteFromServer().setDisable(true);

        MenuBar bar = new MenuBar(
                buildFileMenu(items),
                buildEditMenu(items),
                buildViewMenu(),
                buildToolsMenu(items),
                buildHelpMenu(),
                igPackageActions.menu(),
                buildWorkspaceMenu());
        return new Result(bar, items);
    }

    private Menu buildFileMenu(Items items) {
        Menu fileMenu = new Menu("File");

        MenuItem newItem = new MenuItem("New...");
        newItem.setAccelerator(KeyCombination.keyCombination("Shortcut+N"));
        newItem.setOnAction(event -> window.newResource());

        MenuItem open = new MenuItem("Open...");
        open.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        open.setOnAction(event -> documentSession.openFile());

        // Phase 7. Held as fields like the other state-dependent items, because their
        // enabled state depends on both the displayed resource and the configured
        // servers, neither of which is known when the menu is built.
        items.openFromServer().setOnAction(event -> serverScreens.open());
        items.saveToServer().setOnAction(event -> serverResourceActions.save());

        items.save().setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        items.save().setOnAction(event -> documentSession.save());

        items.saveAs().setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+S"));
        items.saveAs().setOnAction(event -> documentSession.saveAs());

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
        close.setOnAction(event -> documentSession.close());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(event -> window.closeWindow());

        fileMenu.getItems().addAll(
                newItem,
                open,
                items.openFromServer(),
                new SeparatorMenuItem(),
                items.save(),
                items.saveAs(),
                items.saveToServer(),
                new SeparatorMenuItem(),
                sampleMenu,
                exportJson,
                exportXml,
                new SeparatorMenuItem(),
                close,
                exit);
        return fileMenu;
    }

    private Menu buildEditMenu(Items items) {
        items.undo().setAccelerator(KeyCombination.keyCombination("Shortcut+Z"));
        items.undo().setOnAction(event -> documentSession.undoEdit());

        items.addChild().setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+A"));
        items.addChild().setOnAction(
                event -> documentSession.addChildToSelection(documentSession.selected()));

        items.delete().setAccelerator(KeyCombination.keyCombination("Delete"));
        items.delete().setOnAction(event -> documentSession.deleteSelectedNode());

        return new Menu("Edit", null, items.undo(), new SeparatorMenuItem(),
                items.addChild(), items.delete());
    }

    private Menu buildViewMenu() {
        Menu viewMenu = new Menu("View");

        MenuItem showTree = new MenuItem("Resource Tree");
        showTree.setOnAction(event -> views.structureTabs().getSelectionModel().select(views.treeTab()));
        MenuItem showBundle = new MenuItem("Bundle Navigator");
        showBundle.setOnAction(event -> views.structureTabs().getSelectionModel().select(views.bundleTab()));
        MenuItem showPretty = new MenuItem("Pretty");
        showPretty.setOnAction(event -> views.documentTabs().getSelectionModel().select(views.prettyTab()));
        MenuItem showDetails = new MenuItem("Details");
        showDetails.setOnAction(event -> views.documentTabs().getSelectionModel().select(views.detailsTab()));
        MenuItem showJson = new MenuItem("JSON");
        showJson.setOnAction(event -> views.documentTabs().getSelectionModel().select(views.jsonTab()));
        MenuItem showXml = new MenuItem("XML");
        showXml.setOnAction(event -> views.documentTabs().getSelectionModel().select(views.xmlTab()));
        MenuItem showFhirPath = new MenuItem("FHIRPath");
        showFhirPath.setOnAction(event -> views.documentTabs().getSelectionModel().select(views.fhirPathTab()));

        MenuItem expandAll = new MenuItem("Expand All");
        expandAll.setOnAction(event -> views.tree().expandAll());
        MenuItem collapseAll = new MenuItem("Collapse All");
        collapseAll.setOnAction(event -> views.tree().collapseAll());

        showUnpopulated.setOnAction(event -> documentSession.refreshTree());

        Menu themesMenu = new Menu("Theme");
        themesMenu.getItems().setAll(themeItems.get());

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

    private Menu buildToolsMenu(Items items) {
        MenuItem validate = new MenuItem("Validate Resource");
        validate.setAccelerator(KeyCombination.keyCombination("Shortcut+T"));
        validate.setOnAction(event -> window.validate());

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
        items.refreshFromServer().setOnAction(event -> serverResourceActions.refresh());
        items.patchOnServer().setOnAction(event -> serverResourceActions.patch());
        items.deleteFromServer().setOnAction(event -> serverResourceActions.delete());

        // Open from / Save to a server live in the File menu: they open and save the
        // displayed resource, which is what the File menu is for. What remains here is
        // the server administration, plus the actions on a resource already read from
        // one.
        return new Menu("Tools", null, validate, new SeparatorMenuItem(), searchServer,
                items.refreshFromServer(), items.patchOnServer(),
                items.deleteFromServer(), new SeparatorMenuItem(), manageServers, managePlugins,
                new SeparatorMenuItem(), serverStatus, runOperation,
                new SeparatorMenuItem(), restConsole,
                new SeparatorMenuItem(), scriptingConsole,
                new SeparatorMenuItem(), smartWorkbench);
    }

    private Menu buildHelpMenu() {
        MenuItem userGuide = new MenuItem("User Guide");
        userGuide.setOnAction(event -> window.showUserGuide());
        MenuItem smartGuide = new MenuItem("SMART on FHIR Launch Guide");
        smartGuide.setOnAction(event -> window.showSmartGuide());
        MenuItem scriptingGuide = new MenuItem("Scripting Guide");
        scriptingGuide.setOnAction(event -> window.showPhase6Guide());
        MenuItem about = new MenuItem("About");
        about.setOnAction(event -> aboutDialog.show());
        return new Menu("Help", null, userGuide, new SeparatorMenuItem(),
                smartGuide, new SeparatorMenuItem(), scriptingGuide, new SeparatorMenuItem(), about);
    }

    private Menu buildWorkspaceMenu() {
        Menu workspaceMenu = new Menu("Workspace");
        MenuItem wsNew = new MenuItem("New Workspace...");
        wsNew.setOnAction(event -> workspaceActions.newWorkspace());
        MenuItem wsOpen = new MenuItem("Open Workspace...");
        wsOpen.setOnAction(event -> workspaceActions.openWorkspace());
        MenuItem wsSave = new MenuItem("Save Workspace");
        wsSave.setOnAction(event -> workspaceActions.save());
        MenuItem wsSaveAs = new MenuItem("Save Workspace As...");
        wsSaveAs.setOnAction(event -> workspaceActions.saveAs());
        workspaceMenu.getItems().addAll(wsNew, wsOpen, new SeparatorMenuItem(), wsSave, wsSaveAs);
        return workspaceMenu;
    }
}
