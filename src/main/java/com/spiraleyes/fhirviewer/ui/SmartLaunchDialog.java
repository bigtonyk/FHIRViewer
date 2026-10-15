package com.spiraleyes.fhirviewer.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.spiraleyes.fhirviewer.server.PluginLoader;
import com.spiraleyes.fhirviewer.server.smart.BrowserLauncher;
import com.spiraleyes.fhirviewer.server.smart.DesktopBrowserLauncher;
import com.spiraleyes.fhirviewer.server.smart.SmartAuthorizationFlow;
import com.spiraleyes.fhirviewer.server.smart.SmartAuthorizationSession;
import com.spiraleyes.fhirviewer.server.smart.SmartConfiguration;
import com.spiraleyes.fhirviewer.server.smart.SmartDiscoveryException;
import com.spiraleyes.fhirviewer.server.smart.SmartDiscoveryService;
import com.spiraleyes.fhirviewer.server.smart.SmartFhirAccess;
import com.spiraleyes.fhirviewer.server.smart.SmartLaunchConfig;
import com.spiraleyes.fhirviewer.server.smart.SmartLaunchStore;
import com.spiraleyes.fhirviewer.server.smart.SmartUrls;

/**
 * The SMART Launch Workbench: create, edit, delete and validate the launches this application
 * can attempt, plus reserved space for the parts Phase 02 onward will fill.
 *
 * <p>Shaped after {@link ServerManagerDialog} - a selector over a form, Save and Delete
 * against a store, a status line that never lies - because a second dialog for the same kind
 * of job would be a second set of the same mistakes.</p>
 *
 * <p><b>Nothing here fakes progress.</b> As of Phase 02 Discover is live and runs real
 * discovery against the URL in the form; Authorize stays disabled with a tooltip naming the
 * phase that will enable it, because a workbench that showed a success the flow has not
 * earned would be worse than no workbench. The remaining reserved panels likewise say what
 * will appear there and that nothing has run yet. The only live validation is
 * {@link SmartLaunchConfig#problems()}, which checks the form against the model without
 * contacting any server — and the status line after a successful Validate says exactly
 * that.</p>
 *
 * <p>The store is given to this dialog rather than read by it on every action: the list in
 * memory is what the user is editing, and it is written back on each change so a crash loses
 * one launch rather than the session.</p>
 */
public final class SmartLaunchDialog extends Dialog<SmartLaunchDialog.Result> {

    /**
     * What the user did, for the caller to report in the status bar — plus, as of Phase
     * 04, any resource read through the authorized session and opened in the viewer.
     *
     * <p>Same shape as {@link ServerManagerDialog.Result}, with an optional resource on
     * top: the workbench collects a read but never renders it itself, so the resource
     * travels to the caller exactly like {@link RestConsoleDialog.Outcome} does, and the
     * existing tree and Pretty View display it. A plain Close after a read carries
     * nothing; only an explicit Open in viewer does, so closing the workbench never
     * opens something the user did not ask to see.</p>
     */
    public record Result(boolean changed, String message, IBaseResource resource,
            String resourceLabel) {
        /** Nothing was changed. */
        public static final Result UNCHANGED = new Result(false, "", null, null);

        /** A result with no resource, as before Phase 04. */
        public Result(boolean changed, String message) {
            this(changed, message, null, null);
        }

        /** True when a resource was read and opened in the viewer. */
        public boolean hasResource() {
            return resource != null;
        }
    }

    private final SmartLaunchStore store;
    private final SmartLaunchFormPanel form = new SmartLaunchFormPanel();
    private final SmartCapabilitiesPane capabilitiesPane = new SmartCapabilitiesPane();
    private final SmartSessionPane sessionPane = new SmartSessionPane();
    private final SmartResourcePane resourcePane = new SmartResourcePane();
    private final SmartDiscoveryService discoveryService;
    private final BrowserLauncher browserLauncher;
    private final SmartAuthorizationFlow authorizationFlow;
    private final SmartFhirAccess fhirAccess;
    private final List<SmartLaunchConfig> configs = new ArrayList<>();
    private final Label statusLabel = new Label();

    /** Discovery metadata from the last successful Discover, for Authorize. */
    private SmartConfiguration discovered;

    /** The live session, or {@code null} when none exists. */
    private SmartAuthorizationSession session;

    /** The discovery currently in flight, so a second press or a close can stop it. */
    private BackgroundTasks.Running discoveryTask;

    /** The authorization currently in flight, so a close can stop it. */
    private BackgroundTasks.Running authorizationTask;

    /**
     * The authorized read currently in flight, so a second press or a close can stop
     * it. Cancelled like the other two, from {@code setOnHidden} and before a re-read.
     */
    private BackgroundTasks.Running readTask;

    /**
     * The last resource read through the session, waiting for an explicit Open in
     * viewer. Kept off {@link #lastResult} until then: a plain Close after a read must
     * not open something the user did not ask to see.
     */
    private IBaseResource pendingResource;

    /** The label for {@link #pendingResource} in the existing viewer. */
    private String pendingLabel;

    private final Button newButton = new Button("New");
    private final Button saveButton = new Button("Save");
    private final Button deleteButton = new Button("Delete");
    private final Button validateButton = new Button("Validate");
    private final Button discoverButton = new Button("Discover");
    private final Button authorizeButton = new Button("Authorize");

    private SmartLaunchConfig editing;
    private boolean addingNew = true;
    private boolean changed;
    private Result lastResult = Result.UNCHANGED;

    /**
     * @param store where the configurations are read from and written to
     */
    public SmartLaunchDialog(SmartLaunchStore store) {
        this(store, new SmartDiscoveryService(), new DesktopBrowserLauncher(),
                new SmartAuthorizationFlow(), new SmartFhirAccess(PluginLoader.load()));
    }

    /**
     * A dialog that reads through the application's real plugins: authorized reads
     * resolve against the servers the user actually configured.
     *
     * @param store where the configurations are read from and written to
     * @param fhirAccess the authorized-read service; never {@code null}
     */
    public SmartLaunchDialog(SmartLaunchStore store, SmartFhirAccess fhirAccess) {
        this(store, new SmartDiscoveryService(), new DesktopBrowserLauncher(),
                new SmartAuthorizationFlow(), fhirAccess);
    }

    /**
     * A dialog with chosen services, so a test can give short deadlines.
     *
     * @param store where the configurations are read from and written to
     * @param discoveryService the discovery implementation; never {@code null}
     */
    SmartLaunchDialog(SmartLaunchStore store, SmartDiscoveryService discoveryService) {
        this(store, discoveryService, new DesktopBrowserLauncher(),
                new SmartAuthorizationFlow(), new SmartFhirAccess(PluginLoader.load()));
    }

    SmartLaunchDialog(SmartLaunchStore store, SmartDiscoveryService discoveryService,
            BrowserLauncher browserLauncher, SmartAuthorizationFlow authorizationFlow) {
        this(store, discoveryService, browserLauncher, authorizationFlow,
                new SmartFhirAccess(PluginLoader.load()));
    }

    SmartLaunchDialog(SmartLaunchStore store, SmartDiscoveryService discoveryService,
            BrowserLauncher browserLauncher, SmartAuthorizationFlow authorizationFlow,
            SmartFhirAccess fhirAccess) {
        this.store = store;
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");
        this.browserLauncher = Objects.requireNonNull(browserLauncher, "browserLauncher");
        this.authorizationFlow = Objects.requireNonNull(authorizationFlow, "authorizationFlow");
        this.fhirAccess = Objects.requireNonNull(fhirAccess, "fhirAccess");

        setTitle("SMART Launch Workbench");
        setHeaderText("Configure a SMART launch, discover what the server advertises, then "
                + "authorize in the system browser.");
        setResizable(true);
        // Wide enough for the form beside the reserved panels; the ScrollPane inside the
        // form handles anything the fields need beyond this.
        getDialogPane().setPrefWidth(1000);
        getDialogPane().setPrefHeight(680);
        getDialogPane().setMinWidth(760);
        getDialogPane().setMinHeight(520);

        ButtonType closeType = new ButtonType("Close", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().add(closeType);

        // Registered on the pane's own button and compared by identity, exactly as
        // ServerManagerDialog does: a ButtonType is not the button, and comparing against
        // the wrong object would make every press return an unchanged result.
        final Button closeButton = (Button) getDialogPane().lookupButton(closeType);
        closeButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (event.getTarget() == closeButton) {
                lastResult = changed
                        ? new Result(true, statusLabel.getText())
                        : Result.UNCHANGED;
            }
        });

        setResultConverter(button -> null);
        // Dialog.close() is final, so this is where work in flight is stopped: the
        // hidden event fires for a Close press and for a programmatic close alike.
        setOnHidden(event -> {
            cancelDiscovery();
            cancelAuthorization();
            cancelRead();
        });
        buildContent();
        setStatus("Choose a saved launch, or fill in the form to create one. Nothing has "
                + "been discovered or authorized yet.");
        loadFromStore();
        refreshList();
        updateButtonState();
    }

    /** What the user did, for the caller to report. Never {@code null}. */
    public Result result() {
        return lastResult;
    }
    private void buildContent() {
        form.onConfigChosen(this::onConfigChosen);
        resourcePane.readButton().setOnAction(event -> onReadPatient());
        resourcePane.openButton().setOnAction(event -> onOpenResource());

        // fitToWidth is what makes the form grow with the window: without it the grid stays
        // at its preferred width and the right-hand end of each field is cut off.
        ScrollPane formScroll = new ScrollPane(form.build());
        formScroll.setFitToWidth(true);
        formScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        formScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        VBox formSide = new VBox(6);
        Label formTitle = new Label("Launch configuration");
        formTitle.getStyleClass().add("pretty-row-label");
        formSide.getChildren().addAll(formTitle, formScroll, buildButtons(), statusLabel);
        VBox.setVgrow(formScroll, Priority.ALWAYS);

        statusLabel.getStyleClass().add("app-subtitle");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        // Only maxWidth, never prefWidth: MAX_VALUE preferred width would feed unbounded
        // size into the parent chain and squash the layout, which is the mistake the
        // server dialog records in its own comments.

        HBox.setHgrow(formSide, Priority.ALWAYS);

        TabPane reserved = buildReservedPanels();
        HBox.setHgrow(reserved, Priority.ALWAYS);
        reserved.setPrefWidth(430);

        HBox content = new HBox(12, formSide, reserved);
        BorderPane root = new BorderPane(content);
        root.setPadding(new Insets(12));
        root.setMaxWidth(Double.MAX_VALUE);
        getDialogPane().setContent(root);
    }

    /**
     * The panels beside the form: server capabilities, session and patient context,
     * diagnostics, and resource access.
     *
     * <p>The Diagnostics and Resource access entries arrived as honest placeholders in
     * Phase 01; Capabilities went live in Phase 02, the Session panel in Phase 03, and
     * Resource access in Phase 04, each replacing its placeholder text with a real
     * panel. Diagnostics is still reserved for Phase 06.</p>
     */
    private TabPane buildReservedPanels() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // The Capabilities panel is live as of Phase 02: it shows what discovery returned.
        // It sits in a ScrollPane that fits to width, so a long endpoint URL grows the
        // panel downward instead of stretching the dialog sideways.
        ScrollPane capabilitiesScroll = new ScrollPane(capabilitiesPane);
        capabilitiesScroll.setFitToWidth(true);
        capabilitiesScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        capabilitiesScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        Tab capabilities = new Tab("Capabilities");
        capabilities.setContent(capabilitiesScroll);

        ScrollPane sessionScroll = new ScrollPane(sessionPane);
        sessionScroll.setFitToWidth(true);
        sessionScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sessionScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        Tab sessionTab = new Tab("Session");
        sessionTab.setContent(sessionScroll);

        // The Resource access panel is live as of Phase 04: it performs the minimal
        // authorized read (the granted patient context) and hands the resource to the
        // caller for the existing viewer. Wrapped in a ScrollPane like the others, and
        // named as the reserved tab was so its purpose reads the same.
        ScrollPane resourcesScroll = new ScrollPane(resourcePane);
        resourcesScroll.setFitToWidth(true);
        resourcesScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        resourcesScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        Tab resourcesTab = new Tab("Resource access");
        resourcesTab.setContent(resourcesScroll);

        tabs.getTabs().addAll(
                capabilities,
                sessionTab,
                tab("Diagnostics", "Structured, redacted diagnostic events from discovery and "
                        + "authorization will be listed here as the launch progresses. "
                        + "Nothing has run yet."),
                resourcesTab);
        return tabs;
    }

    private static Tab tab(String title, String explanation) {
        Label body = new Label(explanation);
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.getStyleClass().add("app-subtitle");
        VBox holder = new VBox(body);
        holder.setPadding(new Insets(12));
        Tab tab = new Tab(title);
        tab.setContent(holder);
        return tab;
    }

    private VBox buildButtons() {
        saveButton.setOnAction(event -> onSave());
        deleteButton.setOnAction(event -> onDelete());
        validateButton.setOnAction(event -> onValidate());
        validateButton.getStyleClass().add("button-ghost");

        // Discover is live since Phase 02; Authorize is live since Phase 03 and
        // runs only after a successful Discover, because the flow needs the
        // authorization and token endpoints discovery learned.
        discoverButton.setOnAction(event -> onDiscover());
        authorizeButton.setOnAction(event -> onAuthorize());
        authorizeButton.setTooltip(new javafx.scene.control.Tooltip(
                "Authorize in the system browser. Needs a successful Discover first."));

        newButton.setOnAction(event -> onNew());
        for (Button button : List.of(newButton, saveButton, deleteButton, validateButton,
                discoverButton, authorizeButton)) {
            button.setMaxWidth(Double.MAX_VALUE);
        }

        HBox primary = new HBox(8, newButton, saveButton, deleteButton);
        primary.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(newButton, Priority.ALWAYS);
        HBox.setHgrow(saveButton, Priority.ALWAYS);
        HBox.setHgrow(deleteButton, Priority.ALWAYS);

        HBox secondary = new HBox(8, validateButton, discoverButton, authorizeButton);
        secondary.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(validateButton, Priority.ALWAYS);
        HBox.setHgrow(discoverButton, Priority.ALWAYS);
        HBox.setHgrow(authorizeButton, Priority.ALWAYS);

        return new VBox(6, primary, secondary);
    }
    private void onNew() {
        form.clear();
        editing = null;
        // Keep Save enabled: New has started the job of configuring a launch and the user
        // finishes it with Save.
        addingNew = true;
        // Back to the blank entry, selected quietly - selecting it noisily would fire the
        // chosen handler and clear the form that was just cleared.
        form.selectConfigQuietly(null);
        updateButtonState();
        setStatus("Fill in the details, then choose Save to add this launch.");
    }

    private void onConfigChosen(String chosen) {
        SmartLaunchConfig match = null;
        if (chosen != null && !chosen.isBlank()) {
            for (SmartLaunchConfig candidate : configs) {
                if (candidate.name().equals(chosen)) {
                    match = candidate;
                    break;
                }
            }
        }
        editing = match;
        if (match == null) {
            addingNew = true;
            form.clear();
            form.selectConfigQuietly(chosen);
        } else {
            addingNew = false;
            form.load(match);
        }
        updateButtonState();
    }

    private void refreshList() {
        List<String> names = configs.stream().map(SmartLaunchConfig::name).toList();
        // Resolved before offering the names, so deciding what to keep never races with the
        // selector being repopulated underneath it.
        String keep = editing != null && names.contains(editing.name()) ? editing.name() : null;
        form.offerConfigs(names);
        form.selectConfigQuietly(keep);
        if (keep == null) {
            editing = null;
            addingNew = true;
        }
        updateButtonState();
    }

    private void updateButtonState() {
        // Save works on whatever the form holds, so it is enabled both for a selected
        // launch and while a new one is being filled in. Delete only ever applies to a
        // launch that is already in the list. Validate always works: it reads the form,
        // not the store. Discover is locked only while a request is in flight.
        // Authorize needs a successful Discover behind it — without endpoints the
        // flow has nowhere to send the browser — and locks while authorizing.
        saveButton.setDisable(!(editing != null || addingNew));
        deleteButton.setDisable(editing == null);
        boolean busy = discoveryTask != null || authorizationTask != null;
        authorizeButton.setDisable(busy || discovered == null || !discovered.isUsable());
    }
    private void onSave() {
        // Captured first: editing the fields must not lose the id this is derived from.
        SmartLaunchConfig previous = editing;
        SmartLaunchConfig config;
        try {
            config = form.toConfig(previous);
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage());
            return;
        }
        boolean isNew = previous == null;
        if (isNew) {
            configs.add(config);
        } else {
            int at = configs.indexOf(previous);
            if (at >= 0) {
                configs.set(at, config);
            } else {
                configs.add(config);
            }
        }
        editing = config;
        addingNew = false;
        refreshList();
        changed = true;
        String warning = writeStore();
        setStatus((isNew ? "Saved " : "Updated ") + config.name() + ". " + warning
                + " Choose Validate to check it over.");
    }

    private void onDelete() {
        if (editing == null) {
            return;
        }
        String name = editing.name();
        configs.remove(editing);
        editing = null;
        addingNew = false;
        form.clear();
        refreshList();
        changed = true;
        String warning = writeStore();
        setStatus("Removed " + name + ". " + warning);
    }

    /**
     * Checks the form against the model, without contacting any server.
     *
     * <p>The honest boundary of Phase 01: {@link SmartLaunchConfig#problems()} knows what an
     * authorization request needs, and nothing more. The message after a clean run says so
     * explicitly, because "valid" alone would read as "the server accepts this".</p>
     */
    private void onValidate() {
        SmartLaunchConfig config;
        try {
            config = form.toConfig(editing);
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage());
            return;
        }
        List<String> problems = config.problems();
        if (problems.isEmpty()) {
            setStatus(config.name() + " looks complete: it has everything an authorization "
                    + "request needs. No server has been contacted - discovery arrives in "
                    + "Phase 02.");
        } else {
            setStatus(config.name() + ": " + String.join(" ", problems));
        }
    }
    /** Reads the saved configurations once, at construction. */
    private void loadFromStore() {
        try {
            configs.addAll(store.read());
            String selected = store.readSelectedName();
            if (selected != null) {
                for (SmartLaunchConfig config : configs) {
                    if (config.name().equals(selected)) {
                        // Noisy on purpose: this fires the chosen handler, which loads the
                        // launch into the form - the workbench reopens on what was last
                        // used rather than on an empty form beside a populated list.
                        form.selectConfig(selected);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            setStatus("The saved launches could not be read (" + e.getMessage()
                    + "). Starting with an empty list.");
        }
    }

    /**
     * Writes the list out.
     *
     * @return an empty string when it worked, or a warning for the status line when it did
     *         not - the change is kept in memory either way, which is exactly how the
     *         server list behaves
     */
    private String writeStore() {
        try {
            store.write(configs, editing != null ? editing.name() : null);
            return "";
        } catch (IOException e) {
            return "It will last only for this session: the file could not be written ("
                    + e.getMessage() + ").";
        }
    }

    /**
     * Reads this server's SMART configuration document, off the JavaFX thread.
     *
     * <p>The issuer is taken from the form rather than from the selected configuration, so
     * a user can discover against a URL they just typed without saving it first — which is
     * the whole point of a workbench. Nothing is persisted by pressing this: discovery
     * changes what the workbench knows, not what is stored.</p>
     */
    private void onDiscover() {
        String issuer = form.issuerField().getText() == null
                ? ""
                : form.issuerField().getText().trim();
        if (issuer.isEmpty()) {
            setStatus("Enter a FHIR server base URL (iss) first, then choose Discover.");
            return;
        }

        cancelDiscovery();
        capabilitiesPane.showNothingYet();
        setDiscoverBusy(true);
        // The origin, never the full URL: a URL can carry a query string, and a query
        // string can carry a patient identifier. originOf swallows anything unparseable,
        // so a URL the service will refuse is still safe to name here.
        setStatus("Discovering from " + SmartDiscoveryService.originOf(issuer) + " ...");

        discoveryTask = BackgroundTasks.runAttempt("smart-discovery",
                () -> BackgroundTasks.attempt(() -> discoveryService.discover(issuer)),
                attempt -> {
                    setDiscoverBusy(false);
                    discoveryTask = null;
                    if (attempt.cancelled()) {
                        capabilitiesPane.showNothingYet();
                        setStatus("Discovery was cancelled.");
                    } else if (!attempt.succeeded()) {
                        capabilitiesPane.showFailure(ServerErrors.redact(attempt.failure()));
                        setStatus("Discovery failed: " + ServerErrors.redact(attempt.failure()));
                    } else {
                        showDiscovered(attempt.value(), issuer);
                    }
                });
    }

    /** Fills the Capabilities panel and says what was learned, problems included. */
    private void showDiscovered(SmartConfiguration configuration, String issuer) {
        this.discovered = configuration;
        capabilitiesPane.show(configuration);
        List<String> problems = configuration.problems();
        if (problems.isEmpty()) {
            setStatus("Discovered " + configuration.capabilities().size()
                    + " capability(ies). The server can be launched against. "
                    + "Choose Authorize to sign in.");
        } else {
            setStatus("Discovered the configuration, but it has " + problems.size()
                    + " problem(s): " + problems.get(0)
                    + (problems.size() > 1 ? " (see the Capabilities panel)" : ""));
        }
        // A mismatch is worth naming in the status line too: the user typed one address and
        // the document claims another, and only they can say which is right. Wrapped because
        // normalize can refuse a URL the service may have accepted through another path.
        String declared = configuration.issuer();
        String typed;
        try {
            typed = SmartUrls.normalizeFhirBase(issuer);
        } catch (IllegalArgumentException refused) {
            typed = issuer;
        }
        if (declared != null && !declared.isBlank() && !declared.equalsIgnoreCase(typed)) {
            setStatus(statusLabel.getText() + " Note: the document's issuer (" + declared
                    + ") differs from the URL it was fetched from.");
        }
        updateButtonState();
    }

    /**
     * Authorizes the launch in the system browser: PKCE + loopback callback
     * + token exchange, off the JavaFX thread.
     *
     * <p>The configuration comes from the form, not the store, so a user can
     * authorize a launch they just typed without saving it first — the same
     * arrangement discovery uses. A session replaces the previous one; the
     * old session is logged out first so two tokens never coexist.</p>
     */
    private void onAuthorize() {
        SmartLaunchConfig config;
        try {
            config = form.toConfig(editing);
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage());
            return;
        }
        if (discovered == null || !discovered.isUsable()) {
            setStatus("Choose Discover first: authorization needs the endpoints discovery learns.");
            return;
        }
        cancelAuthorization();
        setAuthorizeBusy(true);
        setStatus("Waiting for the browser: sign in there, then return here. "
                + "Closing this dialog cancels the attempt.");

        SmartConfiguration endpoints = discovered;
        authorizationTask = BackgroundTasks.runAttempt("smart-authorize",
                () -> BackgroundTasks.attempt(() -> authorizationFlow.authorize(config, endpoints)),
                attempt -> {
                    setAuthorizeBusy(false);
                    authorizationTask = null;
                    if (attempt.cancelled()) {
                        setStatus("Authorization was cancelled.");
                    } else if (!attempt.succeeded()) {
                        setStatus("Authorization failed: "
                                + ServerErrors.redact(attempt.failure()));
                    } else {
                        showSession(attempt.value());
                    }
                    updateButtonState();
                });
    }

    /** Shows the new session and logs out the previous one, if there was one. */
    private void showSession(SmartAuthorizationSession next) {
        if (session != null && !session.isLoggedOut()) {
            session.logout();
        }
        session = next;
        sessionPane.show(next);
        clearPendingResource();
        resourcePane.show(next);
        setStatus(next.describe() + ". Granted scopes, patient context, and expiry "
                + "are in the Session panel. The granted patient can be read in the "
                + "Resource access panel.");
        changed = true;
    }

    /** Clears the session (logout): tokens are dropped, the panel resets. */
    void logoutSession() {
        if (session != null) {
            session.logout();
            session = null;
        }
        sessionPane.showNothingYet();
        clearPendingResource();
        resourcePane.showNothingYet();
        updateButtonState();
    }

    /** Drops a read result: it belonged to the session that just changed. */
    private void clearPendingResource() {
        pendingResource = null;
        pendingLabel = null;
    }

    /**
     * Reads the granted patient context through the authorized session, off the
     * JavaFX thread.
     *
     * <p>The button is enabled only when the session granted a patient, so pressing it
     * with nothing to read cannot happen through the UI; the guard stays anyway because
     * a session can expire between the button being enabled and pressed. The session is
     * captured up front and re-checked on completion: an authorize or logout that lands
     * mid-read must not have its predecessor's resource displayed as if it belonged to
     * the new session, which is exactly the concurrent-session overwrite Phase 04
     * forbids.</p>
     */
    private void onReadPatient() {
        SmartAuthorizationSession active = session;
        if (active == null || active.isLoggedOut()) {
            setStatus("Authorize first: a resource can only be read through an "
                    + "authorized session.");
            return;
        }
        cancelRead();
        resourcePane.setReading(true);
        setStatus("Reading the authorized patient from "
                + SmartDiscoveryService.originOf(active.fhirBaseUrl()) + " ...");
        readTask = BackgroundTasks.runAttempt("smart-fhir-read",
                () -> BackgroundTasks.attempt(() -> fhirAccess.patient(active)),
                attempt -> {
                    readTask = null;
                    resourcePane.setReading(false);
                    if (attempt.cancelled()) {
                        resourcePane.showFailure("The read was cancelled.");
                        setStatus("The read was cancelled.");
                        return;
                    }
                    if (!attempt.succeeded()) {
                        String message = ServerErrors.redact(attempt.failure());
                        resourcePane.showFailure(message);
                        setStatus("Read failed: " + message);
                        return;
                    }
                    Optional<IBaseResource> value = attempt.value();
                    if (value == null || value.isEmpty()) {
                        // The token granted a patient the server no longer serves — a
                        // 404, not a scope problem — and the pane must say which.
                        resourcePane.showFailure("The server says Patient "
                                + active.patient() + " does not exist.");
                        setStatus("The read finished, but the server has no Patient "
                                + active.patient() + ".");
                        return;
                    }
                    if (active.isLoggedOut() || active != session) {
                        resourcePane.showFailure("The session changed while reading. "
                                + "Read again if the current session can.");
                        setStatus("The read finished for a session that is no longer "
                                + "current; its resource was dropped, not displayed.");
                        return;
                    }
                    pendingResource = value.get();
                    pendingLabel = describeResource(pendingResource,
                            active.fhirBaseUrl());
                    resourcePane.showResource(pendingLabel);
                    setStatus("Read " + pendingLabel + ". Choose Open in viewer "
                            + "to display it with the existing tree and Pretty View.");
                });
    }

    /**
     * Hands the read resource to the caller and closes: exactly like
     * {@code RestConsoleDialog}, the workbench collects the resource and never renders
     * it, so the existing viewer does. Only this explicit button puts a resource on the
     * result; a plain Close after a read carries nothing.
     */
    private void onOpenResource() {
        if (pendingResource == null) {
            return;
        }
        lastResult = new Result(changed, "Read " + pendingLabel + " through the "
                + "authorized session.", pendingResource, pendingLabel);
        close();
    }

    /** A human name for a resource bound for the main window; never any content. */
    private static String describeResource(IBaseResource resource, String baseUrl) {
        String id = resource.getIdElement() != null
                && resource.getIdElement().hasIdPart()
                        ? resource.getIdElement().getIdPart() : null;
        return resource.fhirType() + (id == null ? "" : "/" + id)
                + " (from an authorized SMART session)";
    }

    /** Stops an authorized read in flight, if there is one. Safe when idle. */
    private void cancelRead() {
        if (readTask != null) {
            readTask.cancel();
            readTask = null;
        }
    }

    /** Stops an authorization in flight, if there is one. Safe when idle. */
    private void cancelAuthorization() {
        if (authorizationTask != null) {
            authorizationTask.cancel();
            authorizationTask = null;
        }
    }

    /** Locks the buttons while authorization runs. */
    private void setAuthorizeBusy(boolean busy) {
        authorizeButton.setDisable(busy);
        discoverButton.setDisable(busy);
        saveButton.setDisable(busy || !(editing != null || addingNew));
        deleteButton.setDisable(busy || editing == null);
    }

    /** Stops a discovery in flight, if there is one. Safe to call when there is none. */
    private void cancelDiscovery() {
        if (discoveryTask != null) {
            discoveryTask.cancel();
            discoveryTask = null;
        }
    }

    /** Locks the form while discovery runs, so the URL cannot change under the request. */
    private void setDiscoverBusy(boolean busy) {
        discoverButton.setDisable(busy);
        saveButton.setDisable(busy || !(editing != null || addingNew));
        deleteButton.setDisable(busy || editing == null);
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
    }

    // -- Test access ----------------------------------------------------------
    // Package-private and read-only, so the smoke test can drive the workbench the way a
    // user does rather than reimplementing the flow - the arrangement ServerManagerDialog
    // offers, for the same reason.

    /** The form, for filling in fields. */
    SmartLaunchFormPanel form() {
        return form;
    }

    /** The selector, so a test can pick a saved launch the way a user would. */
    javafx.scene.control.ComboBox<String> configBox() {
        return form.configBox();
    }

    Button newButton() {
        return newButton;
    }

    Button saveButton() {
        return saveButton;
    }

    Button deleteButton() {
        return deleteButton;
    }

    Button validateButton() {
        return validateButton;
    }

    /** Stays disabled until Phase 02 wires discovery up; a test asserts exactly that. */
    Button discoverButton() {
        return discoverButton;
    }

    /** Stays disabled until a usable discovery exists; a test asserts exactly that. */
    Button authorizeButton() {
        return authorizeButton;
    }

    /** The Capabilities panel, so a test can assert what discovery put on screen. */
    SmartCapabilitiesPane capabilitiesPane() {
        return capabilitiesPane;
    }

    /** The Session panel, so a test can assert what authorization put on screen. */
    SmartSessionPane sessionPane() {
        return sessionPane;
    }

    /** The Resource access panel, so a test can drive reads like a user. */
    SmartResourcePane resourcePane() {
        return resourcePane;
    }

    /** The live session, or {@code null} when none exists. */
    SmartAuthorizationSession session() {
        return session;
    }

    /** The status line's text, so a test can assert what the user was told. */
    String statusText() {
        return statusLabel.getText();
    }
}
