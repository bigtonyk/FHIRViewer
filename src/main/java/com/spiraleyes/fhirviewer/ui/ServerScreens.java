package com.spiraleyes.fhirviewer.ui;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.spiraleyes.fhirviewer.model.LoadedResource;
import com.spiraleyes.fhirviewer.model.ResourceFormat;
import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerService;
import com.spiraleyes.fhirviewer.server.PluginConfig;
import com.spiraleyes.fhirviewer.server.PluginSettingsStore;
import com.spiraleyes.fhirviewer.server.ServerCredentialSaver;
import com.spiraleyes.fhirviewer.server.ServerOrigin;
import com.spiraleyes.fhirviewer.server.ServerPassphrase;
import com.spiraleyes.fhirviewer.server.smart.SmartFhirAccess;
import com.spiraleyes.fhirviewer.server.smart.SmartLaunchStore;
import com.spiraleyes.fhirviewer.service.FhirService;

import javafx.stage.Stage;

/**
 * The server screens: manage servers, manage plugins, search a server, open a resource from
 * one, the SMART workbench, the connection status screen, the operation runner, the REST
 * console and the scripting console.
 *
 * <p>Extracted from {@code MainWindow} as the fourth step of the M1 split (R4 §7). Every one
 * of these is a dialog that either configures servers or asks one for something; none of them
 * owns document state, which is why the only window seam is {@link DocumentPort} — show what
 * a dialog returned, ask before replacing, hand out the current JSON.</p>
 *
 * <p>Two callbacks stay behind because they act on the window rather than on a dialog: the
 * server list is persisted after the manager changes it, and the menu enable state is
 * refreshed because it depends on the configured-server count. Everything the dialogs report
 * goes through the same status sink the window uses, so messages keep their one destination.</p>
 */
final class ServerScreens {

    private final Stage owner;
    private final FhirServerService serverService;
    private final FhirServerManager serverManager;
    private final FhirService fhirService;
    private final ThemeManager themeManager;
    private final SearchMemory searchMemory;
    private final ServerCapabilitiesCache capabilitiesCache;
    private final ServerPassphrase serverPassphrase;
    private final Consumer<String> status;
    private final Runnable persistServers;
    private final Runnable updateActions;
    private final Function<ServerOrigin, FhirServerConfiguration> serverFor;
    private final Supplier<Path> pluginSettingsFile;
    private final Supplier<Path> smartLaunchFile;
    private final DocumentPort document;

    ServerScreens(Stage owner, FhirServerService serverService, FhirServerManager serverManager,
            FhirService fhirService, ThemeManager themeManager, SearchMemory searchMemory,
            ServerCapabilitiesCache capabilitiesCache, ServerPassphrase serverPassphrase,
            Consumer<String> status, Runnable persistServers, Runnable updateActions,
            Function<ServerOrigin, FhirServerConfiguration> serverFor,
            Supplier<Path> pluginSettingsFile, Supplier<Path> smartLaunchFile,
            DocumentPort document) {
        this.owner = owner;
        this.serverService = serverService;
        this.serverManager = serverManager;
        this.fhirService = fhirService;
        this.themeManager = themeManager;
        this.searchMemory = searchMemory;
        this.capabilitiesCache = capabilitiesCache;
        this.serverPassphrase = serverPassphrase;
        this.status = status;
        this.persistServers = persistServers;
        this.updateActions = updateActions;
        this.serverFor = serverFor;
        this.pluginSettingsFile = pluginSettingsFile;
        this.smartLaunchFile = smartLaunchFile;
        this.document = document;
    }

    /**
     * Opens the server manager, which adds, edits and removes configured servers.
     *
     * <p>Returns no result and applies every change immediately, so there is nothing to
     * unwrap here. What the user did comes back on the dialog instead, because the dialog
     * mutates the manager directly and the caller still has to say something useful and
     * refresh the menu state that depends on how many servers there are.</p>
     */
    void manageServers() {
        ServerManagerDialog dialog = new ServerManagerDialog(serverService, serverManager,
                new ServerCredentialSaver(
                        new PluginSettingsStore(pluginSettingsFile.get()), serverPassphrase));
        dialog.initOwner(owner);
        DialogTheme.apply(dialog.getDialogPane(), themeManager);
        dialog.showAndWait();

        ServerManagerDialog.Result result = dialog.result();
        if (result.changed()) {
            persistServers.run();
            status.accept(result.message());
        }
        // The File and Tools menu items enable on the configured-server count, so the state
        // is refreshed even when the user closed without changing anything.
        updateActions.run();
    }

    /**
     * Opens the SMART Launch Workbench: CRUD on launch configurations, discovery,
     * authorization, and authorized reads through the application's real plugins.
     *
     * <p>The store is constructed fresh for the visit, the way the server credential store
     * is, so the dialog reads whatever is on disk now rather than a list this window has
     * been holding. Reads resolve through the same registry the server dialogs use, so
     * the plugins a phase runs against in production are the plugins the user
     * configured, not a second discovery.</p>
     */
    void smartWorkbench() {
        SmartLaunchDialog dialog = new SmartLaunchDialog(
                new SmartLaunchStore(smartLaunchFile.get()),
                new SmartFhirAccess(serverService.registry()));
        dialog.initOwner(owner);
        DialogTheme.apply(dialog.getDialogPane(), themeManager);
        dialog.showAndWait();

        SmartLaunchDialog.Result result = dialog.result();
        if (result.changed()) {
            // The store is written by the dialog on every change, so there is nothing to
            // persist here - the message reports what the dialog already saved, or the
            // session-only warning it could not.
            status.accept(result.message());
        }
        // The resource, if any, reaches the existing viewer exactly like a REST console
        // outcome does: the window — and the window alone — displays it, so there is
        // one rendering path and the unsaved-changes guard is honoured in one place.
        if (result.hasResource()) {
            if (!document.confirmDiscard("displaying a resource read through a SMART session")) {
                return;
            }
            document.show(new LoadedResource(result.resource(), ResourceFormat.JSON,
                    result.resourceLabel(), null), null);
            status.accept("Showing " + result.resourceLabel()
                    + ", read through the authorized SMART session.");
        }
    }

    /**
     * Opens the server plugin manager: scan a folder for plugin jars, load them,
     * save per-plugin settings, and manage the plugin config file.
     */
    void managePlugins() {
        PluginManagerDialog dialog = new PluginManagerDialog(
                owner,
                serverService.registry(),
                new PluginSettingsStore(pluginSettingsFile.get()),
                Path.of(PluginConfig.CONFIG_FILE_NAME),
                // The dialog clears its passphrase field once it has saved, so the session
                // keeps its own copy; without it a saved password could never be used.
                serverPassphrase::set);
        dialog.showAndWait();
    }

    /**
     * Searches a configured FHIR server and shows the picked resource in the viewer.
     *
     * <p>The result keeps the server it came from, so the server actions stay available for
     * it exactly as they are for a resource opened by type and id. Without that, a resource
     * found by search could be viewed but never refreshed or saved back.</p>
     */
    void search() {
        // Nothing configured yet: the natural next step is adding one.
        ensureConfigured();
        if (serverManager.servers().isEmpty()) {
            return;
        }
        ServerSearchDialog dialog = new ServerSearchDialog(serverService, serverManager,
                fhirService, themeManager, searchMemory, capabilitiesCache);
        dialog.initOwner(owner);
        dialog.showAndWait().ifPresent(picked -> {
            if (!document.confirmDiscard("displaying a resource from a server")) {
                return;
            }
            document.show(picked.resource(), picked.origin());
            status.accept("Showing " + picked.resource().getDisplayName() + " ("
                    + picked.resource().getSourceName() + ").");
        });
    }

    /**
     * Reads one resource from a chosen server and shows it in the editor.
     *
     * <p>The server list comes from the same loaded servers the search dialog uses, so
     * anything configured in this session is selectable. The resource is displayed with a
     * {@link ServerOrigin} so that a later "Save to FHIR Server" knows the type, id and
     * version to write back to.
     */
    void open() {
        ensureConfigured();
        if (serverManager.servers().isEmpty()) {
            return;
        }
        // Asked up front, not after the dialog: the user should not pick a server and a
        // resource only to be told their current work would be discarded.
        if (!document.confirmDiscard("displaying a resource from a server")) {
            return;
        }
        FhirServerConfiguration preselect = serverFor.apply(document.origin());
        OpenFromServerDialog dialog =
                new OpenFromServerDialog(serverService, serverManager, preselect, searchMemory,
                        capabilitiesCache);
        dialog.initOwner(owner);
        dialog.showAndWait().ifPresent(outcome -> {
            ServerOrigin origin = ServerOrigin.of(
                    outcome.server().pluginId(),
                    outcome.server().baseUrl(),
                    outcome.resourceType(),
                    outcome.resourceId(),
                    ServerResourceActions.versionOf(outcome.resource()));
            // No raw text: what the server sent is not kept, and the server's name is not
            // resource text - passing it here is what put a label where FHIR text belongs.
            LoadedResource loaded = new LoadedResource(
                    outcome.resource(),
                    ResourceFormat.JSON,
                    outcome.resourceType() + "/" + outcome.resourceId(),
                    null);
            document.show(loaded, origin);
        });
    }

    /**
     * Opens the connection screen: connect, disconnect, test, status and capabilities.
     *
     * <p>Preselects the server the displayed resource came from, because that is the one the
     * user is working with and asking them to pick it again would be noise.</p>
     */
    void connectionStatus() {
        ensureConfigured();
        if (serverManager.servers().isEmpty()) {
            return;
        }
        ServerStatusDialog dialog = new ServerStatusDialog(serverService, serverManager,
                document.origin() == null ? serverManager.active().orElse(null)
                        : serverFor.apply(document.origin()),
                themeManager);
        dialog.initOwner(owner);
        dialog.showAndWait();
    }

    /**
     * Opens the generic operation screen for a configured server.
     *
     * <p>Nothing here names an operation. The list is whatever the active plugin reports, so
     * a plugin written after this build appears in this dialog with no change to it.</p>
     */
    void operation() {
        ensureConfigured();
        if (serverManager.servers().isEmpty()) {
            return;
        }
        FhirServerConfiguration preselect = document.origin() == null
                ? serverManager.active().orElse(null)
                : serverFor.apply(document.origin());
        ServerOperationDialog dialog = new ServerOperationDialog(serverService, serverManager,
                preselect, themeManager, document.resource());
        dialog.initOwner(owner);
        dialog.showAndWait().ifPresent(outcome -> {
            if (!document.confirmDiscard("displaying a result from a server")) {
                return;
            }
            // Displayed through the one existing rendering path, with no origin: an
            // operation's answer is a result to look at, not something to save back.
            document.show(new LoadedResource(outcome.resource(), ResourceFormat.JSON,
                    outcome.label(), null), null);
            status.accept("Showing the result of the server operation.");
        });
    }

    /**
     * Opens the REST console: any REST call, with the answer shown raw.
     *
     * <p>Modelled on {@link #operation()} in every respect that matters. The dialog
     * returns a resource rather than opening anything itself, so there is still one
     * rendering path, and the unsaved-changes guard is asked before the displayed resource is
     * replaced rather than after.</p>
     *
     * <p>The console does not need a configured server — that is the point of it — so an
     * empty server list is not a reason to refuse; the user gets the custom-URL form instead.
     * </p>
     */
    void restConsole() {
        FhirServerConfiguration preselect = serverManager.active().orElse(null);
        RestConsoleDialog dialog = new RestConsoleDialog(serverManager.servers(), preselect,
                fhirService, document::currentJson);
        dialog.initOwner(owner);
        dialog.showAndWait().ifPresent(outcome -> {
            if (!outcome.hasResource()) {
                // Closed without opening anything: the user only wanted the raw answer,
                // which the console has already shown them.
                return;
            }
            if (!document.confirmDiscard("displaying a result from a REST call")) {
                return;
            }
            // No ServerOrigin: a console answer is something to look at, and attaching it to
            // a server would let a later "Save to FHIR Server" write back to a server the
            // user did not choose.
            document.show(new LoadedResource(outcome.resource(), ResourceFormat.JSON,
                    outcome.label(), null), null);
            status.accept("Showing the result of a REST request.");
        });
    }

    /** Opens the scripting console, sharing the application's FHIR services. */
    void scriptingConsole() {
        ScriptingConsoleDialog dialog = new ScriptingConsoleDialog(themeManager, fhirService,
                serverService, serverManager);
        dialog.initOwner(owner);
        dialog.showAndWait();
    }

    /**
     * Opens the server manager when there is nothing to choose from, and reports why the
     * action stops when the user closes it without configuring one.
     *
     * <p>Every screen above needs a configured server except the REST console, and the
     * natural next step for an empty list is adding one, so the manager is opened rather
     * than refusing outright. Four screens asked the same question in the same words; it is
     * asked once here.</p>
     */
    private void ensureConfigured() {
        if (!serverManager.servers().isEmpty()) {
            return;
        }
        manageServers();
        if (serverManager.servers().isEmpty()) {
            status.accept("No FHIR server is configured. Use Tools > FHIR Servers... to add one.");
        }
    }

}
