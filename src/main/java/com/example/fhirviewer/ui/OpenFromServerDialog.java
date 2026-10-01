package com.example.fhirviewer.ui;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.FhirServerManager;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.SearchCriterion;
import com.example.fhirviewer.server.SearchRequest;
import com.example.fhirviewer.server.ServerCapabilities;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

/**
 * "Open from Server": picks a resource on a chosen FHIR server and hands it to the editor.
 *
 * <p>The dialog is deliberately server-agnostic. It talks only to
 * {@link FhirServerService}, so it works for every plugin the application loads without
 * knowing anything vendor specific. Server-specific tooling stays behind each plugin's
 * own vendor actions rather than leaking in here.</p>
 *
 * <p>The user can type a resource type and id directly, or load the types the server
 * advertises and pick from them. Every network call happens on a background thread and
 * only the result is handled on the JavaFX thread.</p>
 */
public class OpenFromServerDialog extends Dialog<OpenFromServerDialog.Outcome> {

    /** How many results a search asks for; one screenful, not a bulk export. */
    private static final int PAGE_SIZE = 20;

    /** The result of a successful open: the resource, and the server it came from. */
    public record Outcome(FhirServerConfiguration server, String resourceType, String resourceId,
            IBaseResource resource) {
    }

    /** One background attempt, bridged from the shared helper's own attempt type. */
    private record Attempt<T>(T value, String failure) {

        boolean succeeded() {
            return failure == null;
        }

        /** Adapts this dialog's attempt to the one {@link BackgroundTasks} speaks. */
        BackgroundTasks.Attempt<T> toShared() {
            return succeeded() ? BackgroundTasks.Attempt.succeeded(value)
                    : BackgroundTasks.Attempt.failed(failure);
        }

        /** Rebuilds this dialog's attempt from the shared one, keeping the message. */
        static <T> Attempt<T> from(BackgroundTasks.Attempt<T> shared) {
            return new Attempt<>(shared.succeeded() ? shared.value() : null,
                    shared.succeeded() ? null
                            : shared.cancelled() ? "The read was cancelled." : shared.failure());
        }
    }

    private final FhirServerService serverService;
    private final FhirServerManager serverManager;

    private final ComboBox<FhirServerConfiguration> serverBox = new ComboBox<>();
    private final TextField typeField = new TextField();
    private final TextField idField = new TextField();
    private final SearchCriteriaEditor criteriaEditor = new SearchCriteriaEditor();

    /** What was searched for last, so this screen comes back as it was left. */
    private final SearchMemory memory;
    private final ListView<String> typeList = new ListView<>();
    private final TableView<IBaseResource> results = new TableView<>();
    private final Label status = new Label(" ");
    private final Button loadTypes = new Button("Load types");
    private final Button readButton = new Button("Read");
    private final Button searchButton = new Button("Search");
    private final ProgressIndicator progress = new ProgressIndicator(18);

    /** The dialog's own Read button, so a background result can be returned from it. */
    private final ButtonType readType;

    /** The pending outcome, filled in by the background callbacks and returned on close. */
    private Outcome outcome;

    /** True while a background call runs, so a close cannot race an in-flight read. */
    private boolean busy;

    /** Holds the status label and the spinner; assigned while the content is built. */
    private Region busyRegion;

    public OpenFromServerDialog(FhirServerService serverService, FhirServerManager serverManager,
            FhirServerConfiguration preselected, SearchMemory memory) {
        this.serverService = Objects.requireNonNull(serverService, "serverService");
        this.serverManager = Objects.requireNonNull(serverManager, "serverManager");
        this.memory = Objects.requireNonNull(memory, "memory");

        setTitle("Open from Server");
        setResizable(true);
        // Room for the criteria editor's mode toggle and its Add/Remove buttons, whose text
        // was clipped at the old width.
        getDialogPane().setMinWidth(880);
        getDialogPane().setMinHeight(520);
        readType = new ButtonType("Read", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(readType, ButtonType.CANCEL);
        // The Read result arrives asynchronously, so the button only closes the dialog
        // and the converter hands back whatever the background call produced.
        setResultConverter(dialogButton -> readType.equals(dialogButton) ? outcome : null);
        progress.setVisible(false);
        getDialogPane().setContent(buildContent());
        initServers(preselected);
        restoreLastSearch();
    }

    /**
     * Puts the last search back into the form, so coming back to this screen after opening
     * a result shows what was searched for rather than an empty one.
     *
     * <p>The results are re-fetched rather than left empty. That was originally a deliberate
     * no, on the grounds that re-fetching spends a network request the user did not ask for.
     * In practice it made the screen look broken: the server box is filled by
     * {@link #initServers} whether or not memory has anything, so the one field that
     * survived a reopen was the one that had not been remembered, and an empty type list and
     * an empty results table read as "the search cleared".</p>
     *
     * <p>A preselected server still wins over the remembered one: the caller passed that
     * deliberately, usually because the open resource came from it, and silently overriding
     * an explicit choice would be worse than not remembering anything.</p>
     */
    private void restoreLastSearch() {
        SearchMemoryTrace.log("open-screen restore entered");
        SearchMemory.Search last = memory.last();
        SearchMemoryTrace.log("open-screen restore: got " + last);
        if (last == null) {
            SearchMemoryTrace.log("open-screen restore: NOTHING remembered");
            return;
        }
        if (serverBox.getSelectionModel().getSelectedItem() == null
                && last.serverName() != null) {
            for (FhirServerConfiguration server : serverBox.getItems()) {
                if (server.name().equals(last.serverName())) {
                    serverBox.getSelectionModel().select(server);
                    break;
                }
            }
        }
        if (last.resourceType() != null && !last.resourceType().isBlank()) {
            typeField.setText(last.resourceType());
        }
        List<SearchCriterion> criteria = last.criteria();
        if (criteria.isEmpty()) {
            return;
        }
        if (criteria.stream().allMatch(SearchCriterion::isRaw)) {
            criteriaEditor.setRaw(criteria.get(0).value());
        } else {
            criteriaEditor.setParameters(criteria);
        }
        rerunRememberedSearch();
    }

    /**
     * Runs the restored search against the chosen server.
     *
     * <p>Only when nothing was preselected. A preselected server is an explicit choice by
     * the caller - usually the one the open resource came from - and re-running against a
     * server the user did not pick would be worse than showing nothing.</p>
     */
    private void rerunRememberedSearch() {
        FhirServerConfiguration server = serverBox.getValue();
        String type = typeField.getText() == null ? "" : typeField.getText().trim();
        if (server == null || type.isEmpty()) {
            SearchMemoryTrace.log("open-screen rerun skipped: server=" + server
                    + " type='" + type + "'");
            return;
        }
        SearchMemoryTrace.log("open-screen rerun: " + server.name() + " " + type);
        search();
    }

    private void initServers(FhirServerConfiguration preselected) {
        serverBox.getItems().setAll(serverManager.servers());
        if (preselected != null && serverBox.getItems().contains(preselected)) {
            serverBox.setValue(preselected);
        } else if (!serverBox.getItems().isEmpty()) {
            serverBox.setValue(serverBox.getItems().get(0));
        }
        if (serverBox.getValue() == null) {
            status.setText("No servers are loaded. Add one first.");
            readButton.setDisable(true);
            loadTypes.setDisable(true);
        } else {
            serverBox.setCellFactory(view -> new ListCell<>() {
                @Override
                protected void updateItem(FhirServerConfiguration item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : serverLabel(item));
                }
            });
        }
        serverBox.valueProperty().addListener((obs, old, current) -> {
            typeList.getItems().clear();
            status.setText(" ");
        });
    }

    /** Names a server as "name (baseUrl)" so two servers are never indistinguishable. */
    static String serverLabel(FhirServerConfiguration server) {
        return server == null ? "(no server)" : server.name() + "  —  " + server.baseUrl();
    }

    private Region buildContent() {
        typeField.setPromptText("Resource type, for example Patient");
        idField.setPromptText("Resource id");

        readButton.setDefaultButton(true);
        readButton.setOnAction(event -> readTypedResource());
        searchButton.setOnAction(event -> search());

        // The cancel button is looked up by ButtonType (and cast back to Button), the
        // same way ServerDialog does it, rather than by ButtonData.
        Node cancelNode = getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelNode instanceof Button cancel) {
            cancel.addEventFilter(ActionEvent.ACTION, event -> {
                if (busy) {
                    // A read in flight must not leave a thread touching a closed dialog.
                    event.consume();
                }
            });
        }

        GridPane query = new GridPane();
        query.setHgap(8);
        query.setVgap(8);
        query.setPadding(new Insets(12));
        query.add(new Label("Server:"), 0, 0);
        query.add(serverBox, 1, 0);
        query.add(new Label("Type:"), 0, 1);
        query.add(typeField, 1, 1);
        query.add(new Label("Id:"), 0, 2);
        query.add(idField, 1, 2);
        // Spans both columns: the editor carries its own labels, mode switch and rows.
        Region editor = criteriaEditor.build();
        query.add(editor, 1, 3);
        GridPane.setColumnSpan(editor, 2);
        ColumnConstraints grow = new ColumnConstraints();
        grow.setHgrow(Priority.ALWAYS);
        query.getColumnConstraints().addAll(new ColumnConstraints(), grow);

        HBox actions = new HBox(8, loadTypes, searchButton, readButton);
        actions.setPadding(new Insets(0, 12, 0, 12));
        actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        typeField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                readTypedResource();
            }
        });

        // A type can be picked from the list or typed; either way the Read button
        // needs both, so a list selection only fills the type field.
        typeList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                typeField.setText(selected);
            }
        });
        typeList.setPrefHeight(180);
        // Search can only be offered once there is a type to search, whether it was
        // typed or picked from the list.
        typeField.textProperty().addListener((obs, old, text) -> searchButton.setDisable(
                busy || text == null || text.isBlank()));

        HBox statusBar = new HBox(8, progress, status);
        statusBar.setPadding(new Insets(6, 12, 6, 12));
        statusBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        busyRegion = statusBar;

        // Query, then the type list, then the actions: the left column answers "which
        // resource", and the results table on the right answers "which one".
        VBox left = new VBox(8, query, withPlaceholder(typeList), actions);
        VBox.setVgrow(query, Priority.NEVER);
        left.setPrefWidth(380);

        BorderPane pane = new BorderPane();
        pane.setLeft(left);
        pane.setCenter(withTablePlaceholder(resultsTable()));
        pane.setBottom(statusBar);
        keepLabelsVisible(readButton, loadTypes, searchButton);
        return pane;
    }

    /**
     * The table of search results.
     *
     * <p>Columns are read-only, and the type is shown alongside the id because a Bundle
     * can mix resource types and a bare list of ids gives no way to tell what was found.
     * A table rather than a list so the version is visible before the user commits to
     * reading one: that version is what a later write's conflict check will use, so
     * seeing it here is the difference between an informed open and a blind one.</p>
     */
    private TableView<IBaseResource> resultsTable() {
        TableColumn<IBaseResource, String> type = new TableColumn<>("Type");
        type.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().fhirType()));
        type.setPrefWidth(140);
        TableColumn<IBaseResource, String> id = new TableColumn<>("Id");
        id.setCellValueFactory(data -> new ReadOnlyStringWrapper(idOf(data.getValue())));
        TableColumn<IBaseResource, String> version = new TableColumn<>("Version");
        version.setCellValueFactory(data -> new ReadOnlyStringWrapper(
                data.getValue().getMeta() == null
                        ? "" : nullToEmpty(data.getValue().getMeta().getVersionId())));
        version.setPrefWidth(90);
        results.getColumns().setAll(type, id, version);
        results.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> readButton.setDisable(busy || selected == null));
        return results;
    }

    /**
     * Wraps a table in a stack with a centred hint shown while the table is empty.
     *
     * <p>{@code TableView} has no placeholder API either, and an empty table with no
     * heading reads as a failure rather than as "nothing searched yet". The hint is
     * mouse-transparent so it cannot swallow a click meant for the (empty) table.</p>
     */
    private static Region withTablePlaceholder(TableView<IBaseResource> table) {
        Label hint = new Label("Search results appear here. Select one and press Read.");
        hint.setWrapText(true);
        hint.setMouseTransparent(true);

        StackPane stack = new StackPane(table, hint);
        stack.setAlignment(Pos.CENTER);
        table.itemsProperty().addListener(
                (obs, old, items) -> hint.setVisible(items == null || items.isEmpty()));
        hint.setVisible(true);
        return stack;
    }

    /**
     * Wraps a list in a stack with a centred hint shown while the list is empty.
     *
     * <p>{@code ListView} has no placeholder API of its own, and an empty list on a
     * dialog reads as "broken" rather than "nothing loaded yet", so the hint is an
     * ordinary label kept in sync with the list contents.</p>
     */
    private static Region withPlaceholder(ListView<String> list) {
        Label hint = new Label("Server resource types appear here.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6;");
        hint.setMouseTransparent(true);

        StackPane stack = new StackPane(list, hint);
        stack.setAlignment(Pos.CENTER);
        Runnable sync = () -> hint.setVisible(list.getItems().isEmpty());
        list.getItems().addListener((javafx.collections.ListChangeListener<String>) change -> sync.run());
        sync.run();
        return stack;
    }

    /** Reads the typed type/id, so a user who knows the id never needs the list. */
    private void readTypedResource() {
        FhirServerConfiguration server = serverBox.getValue();
        IBaseResource selected = results.getSelectionModel().getSelectedItem();
        String type = typeField.getText() == null ? "" : typeField.getText().trim();
        String id = idField.getText() == null ? "" : idField.getText().trim();
        if (selected == null && (server == null || type.isEmpty() || id.isEmpty())) {
            status.setText("Choose a server, select a search result, or give both a type and an id.");
            return;
        }
        // A selected result wins: the user picked that row, and re-reading it by id could
        // return a newer version than the one they chose, which would be a different
        // resource from the one whose version they can see in the table.
        if (selected != null) {
            openSelected(server, selected);
            return;
        }
        setBusy(true, "Reading " + type + "/" + id + " ...");
        run(() -> new Attempt<>(serverService.read(server, type, id), null), attempt -> {
            setBusy(false, null);
            if (!attempt.succeeded()) {
                reportFailure(attempt.failure());
                return;
            }
            if (attempt.value() == null) {
                reportFailure("The server returned nothing for " + type + "/" + id + ".");
                return;
            }
            setResult(new Outcome(server, type, id, attempt.value()));
            close();
        });
    }

    /**
     * Hands a resource the user selected in the results table straight to the editor.
     *
     * <p>No second request: the search Bundle already carried the full resource, and
     * re-reading it would both cost a round trip and risk returning a version newer than
     * the one displayed.</p>
     */
    private void openSelected(FhirServerConfiguration server, IBaseResource resource) {
        if (server == null) {
            reportFailure("Choose a server first.");
            return;
        }
        setResult(new Outcome(server, resource.fhirType(), idOf(resource), resource));
        close();
    }

    /**
     * Runs a search on the selected server and fills the results table.
     *
     * <p>The parameter and value are optional: leaving both blank asks the server for
     * everything of that type, which is a legitimate way to browse. Half a criterion is
     * refused by the shared {@link SearchCriteriaBuilder}, so the two search screens
     * cannot disagree about what counts as a valid query.</p>
     */
    private void search() {
        FhirServerConfiguration server = serverBox.getValue();
        String type = typeField.getText() == null ? "" : typeField.getText().trim();
        if (server == null || type.isEmpty()) {
            status.setText("Choose a server and a resource type to search.");
            return;
        }
        List<SearchCriterion> criteria;
        try {
            criteria = criteriaEditor.criteria();
        } catch (IllegalArgumentException e) {
            reportFailure(e.getMessage());
            return;
        }
        SearchRequest request = new SearchRequest(type, criteria, PAGE_SIZE);
        // So reopening this screen after opening a result shows the same search again.
        memory.remember(new SearchMemory.Search(server.name(), type, criteria, PAGE_SIZE));
        SearchMemoryTrace.log("open-screen search: remembered " + server.name()
                + " " + type + " " + criteria);
        setBusy(true, "Searching " + type + " ...");
        run(() -> new Attempt<>(serverService.search(server, request), null), attempt -> {
            setBusy(false, null);
            if (!attempt.succeeded()) {
                reportFailure(attempt.failure());
                return;
            }
            List<IBaseResource> found = attempt.value() == null
                    ? List.of() : attempt.value().resources();
            results.getItems().setAll(found);
            status.setText(found.size() + " resource(s) returned by " + server.name() + ".");
        });
    }

    /** Loads the resource types the selected server advertises. */
    private void loadTypes() {
        FhirServerConfiguration server = serverBox.getValue();
        if (server == null) {
            return;
        }
        setBusy(true, "Loading resource types ...");
        run(() -> new Attempt<>(serverService.capabilities(server), null), attempt -> {
            setBusy(false, null);
            if (!attempt.succeeded()) {
                reportFailure(attempt.failure());
                return;
            }
            ServerCapabilities capabilities = attempt.value();
            typeList.getItems().setAll(capabilities.resourceTypes());
            status.setText(capabilities.resourceTypes().size() + " resource type(s) advertised by "
                    + server.name() + ".");
        });
    }

    private void reportFailure(String message) {
        status.setText(message == null || message.isBlank() ? "The operation failed." : message);
    }

    /**
     * Enables or disables the actions while a background call is in flight, and
     * shows the spinner. Re-entrancy is the reason the buttons are disabled here
     * rather than only visually: a second read would race the first one.
     */
    private void setBusy(boolean nowBusy, String message) {
        busy = nowBusy;
        progress.setVisible(nowBusy);
        if (busyRegion != null) {
            busyRegion.setVisible(true);
        }
        loadTypes.setDisable(nowBusy);
        // Search needs a type; Read needs either a selected result or a typed id, so
        // its enablement follows the selection rather than the server box.
        searchButton.setDisable(nowBusy || typeField.getText() == null
                || typeField.getText().isBlank());
        readButton.setDisable(nowBusy || results.getSelectionModel().getSelectedItem() == null);
        if (message != null) {
            status.setText(message);
        }
    }

    /** Runs work on a background thread and delivers the attempt on the JavaFX thread. */
    private <T> void run(java.util.concurrent.Callable<Attempt<T>> work,
            java.util.function.Consumer<Attempt<T>> done) {
        // One shared implementation; see BackgroundTasks for why this is not written out
        // per dialog any more. The local Attempt is bridged to the shared one in both
        // directions so this dialog's own call sites are unchanged by the refactor.
        BackgroundTasks.runAttempt("fhir-open-from-server",
                () -> work.call().toShared(),
                shared -> {
                    Attempt<T> result = Attempt.from(shared);
                    if (result.succeeded()) {
                        done.accept(result);
                    } else {
                        setBusy(false, null);
                        reportFailure(result.failure());
                    }
                });
    }

    /**
     * The server-assigned id, with any version stripped.
     *
     * <p>{@code IIdType} renders as {@code Patient/123/_history/2}, which is a history
     * reference rather than the plain id the server addresses a resource by. Taking
     * {@code getIdPart()} is what keeps a resource read from a search Bundle from being
     * written back as a versioned path.</p>
     */
    private static String idOf(IBaseResource resource) {
        if (resource == null || resource.getIdElement() == null) {
            return "";
        }
        return nullToEmpty(resource.getIdElement().getIdPart());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Stops a button from collapsing to an ellipsis when the dialog is made narrow. */
    private static void keepLabelsVisible(Button... buttons) {
        for (Button button : buttons) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
    }
}

