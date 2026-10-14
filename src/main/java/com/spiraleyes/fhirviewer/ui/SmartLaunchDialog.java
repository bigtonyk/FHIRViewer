package com.spiraleyes.fhirviewer.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

import com.spiraleyes.fhirviewer.server.smart.SmartConfiguration;
import com.spiraleyes.fhirviewer.server.smart.SmartDiscoveryException;
import com.spiraleyes.fhirviewer.server.smart.SmartDiscoveryService;
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
     * What the user did, for the caller to report in the status bar.
     *
     * <p>Same shape as {@link ServerManagerDialog.Result}: the caller only needs to know
     * whether anything changed and what to say about it.</p>
     */
    public record Result(boolean changed, String message) {
        /** Nothing was changed. */
        public static final Result UNCHANGED = new Result(false, "");
    }

    private final SmartLaunchStore store;
    private final SmartLaunchFormPanel form = new SmartLaunchFormPanel();
    private final SmartCapabilitiesPane capabilitiesPane = new SmartCapabilitiesPane();
    private final SmartDiscoveryService discoveryService;
    private final List<SmartLaunchConfig> configs = new ArrayList<>();
    private final Label statusLabel = new Label();

    /** The discovery currently in flight, so a second press or a close can stop it. */
    private BackgroundTasks.Running discoveryTask;

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
        this(store, new SmartDiscoveryService());
    }

    /**
     * A dialog with a chosen discovery service, so a test can give it short deadlines.
     *
     * @param store            where the configurations are read from and written to
     * @param discoveryService the discovery implementation; never {@code null}
     */
    SmartLaunchDialog(SmartLaunchStore store, SmartDiscoveryService discoveryService) {
        this.store = store;
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");

        setTitle("SMART Launch Workbench");
        setHeaderText("Configure a SMART launch, discover what the server advertises, then "
                + "authorize. Authorization arrives with Phase 03 and stays disabled until "
                + "then.");
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
        // Dialog.close() is final, so this is where a discovery in flight is stopped: the
        // hidden event fires for a Close press and for a programmatic close alike, and a
        // result delivered to nodes that are no longer shown would be both useless and a
        // hold on the daemon thread for the length of its deadline.
        setOnHidden(event -> cancelDiscovery());
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
     * The four reserved panels: server capabilities, session and patient context,
     * diagnostics, and resource access.
     *
     * <p>Real panels with real titles, stating what will appear in them and that nothing
     * has run yet. An empty panel whose purpose is only written in the plan would read as
     * "broken"; a panel that names the phase that fills it reads as the honest
     * placeholder it is.</p>
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

        tabs.getTabs().addAll(
                capabilities,
                tab("Session", "Authorization state, granted scopes, patient id, encounter "
                        + "id, user identity and token expiry will be listed here. No session "
                        + "exists yet; authorization arrives in Phase 03. The raw access "
                        + "token is never shown in this panel."),
                tab("Diagnostics", "Structured, redacted diagnostic events from discovery and "
                        + "authorization will be listed here as the launch progresses. "
                        + "Nothing has run yet."),
                tab("Resource access", "Resources read through an authorized session will be "
                        + "shown here with the existing tree and Pretty View. Nothing can be "
                        + "read until a session exists."));
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

        // Discover is live as of Phase 02. Authorize stays disabled with a tooltip naming
        // the phase: a button with no implementation must never be pressable, or the
        // workbench would be showing a success the flow has not earned.
        discoverButton.setOnAction(event -> onDiscover());
        authorizeButton.setDisable(true);
        authorizeButton.setTooltip(new javafx.scene.control.Tooltip(
                "Not available yet: PKCE authorization arrives in Phase 03."));

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
        // not the store. Discover is live since Phase 02 and is locked only while a
        // request is in flight. Authorize stays disabled until Phase 03 - set at
        // construction and never touched here, which is the point.
        saveButton.setDisable(!(editing != null || addingNew));
        deleteButton.setDisable(editing == null);
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
        capabilitiesPane.show(configuration);
        List<String> problems = configuration.problems();
        if (problems.isEmpty()) {
            setStatus("Discovered " + configuration.capabilities().size()
                    + " capability(ies). The server can be launched against.");
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

    /** Stays disabled until Phase 03 wires authorization up; a test asserts exactly that. */
    Button authorizeButton() {
        return authorizeButton;
    }

    /** The status line's text, so a test can assert what the user was told. */
    String statusText() {
        return statusLabel.getText();
    }

    /** The Capabilities panel, so a test can assert what discovery put on screen. */
    SmartCapabilitiesPane capabilitiesPane() {
        return capabilitiesPane;
    }
}
