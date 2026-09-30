package com.example.fhirviewer.ui;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.server.FhirServerManager;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.SearchCriterion;
import com.example.fhirviewer.server.SearchPageLinks;
import com.example.fhirviewer.server.SearchRequest;
import com.example.fhirviewer.server.SearchResultPage;
import com.example.fhirviewer.server.ServerCapabilities;

/**
 * The FHIR server search dialog, following the plan's workflow: select a server, load
 * its capabilities, pick a resource type, enter a search parameter, search, pick a
 * result, and hand the resource to the existing viewer.
 *
 * <p>Everything that touches the network runs on a background thread; the dialog reports
 * progress and results on the JavaFX thread. One search parameter is supported for now
 * (name/value), which keeps the UI simple while staying generic for every resource type
 * the server's CapabilityStatement advertises.</p>
 *
 * <p>The dialog returns a {@link LoadedResource}, so the caller can display the resource
 * in the existing Pretty/JSON/tree views without a second rendering path.</p>
 */
public class ServerSearchDialog extends Dialog<LoadedResource> {

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
                            : shared.cancelled() ? "The search was cancelled." : shared.failure());
        }
    }

    private static final int PAGE_SIZE = 20;

    private final FhirServerService serverService;
    private final FhirServerManager serverManager;
    private final com.example.fhirviewer.service.FhirService fhirService;

    private final ComboBox<com.example.fhirviewer.server.FhirServerConfiguration> serverBox = new ComboBox<>();
    private final ComboBox<String> typeBox = new ComboBox<>();
    private final SearchCriteriaEditor criteriaEditor = new SearchCriteriaEditor();

    /** What was searched for last, so this screen comes back as it was left. */
    private final SearchMemory memory;
    private final Button connectButton = new Button("Load capabilities");
    private final Button searchButton = new Button("Search");
    private final Button nextButton = new Button("Next page");
    // The plan asks for pagination, and a Bundle advertises five relations. Only "next"
    // existed; the other three are the same call with a different server-minted URL, so
    // they are buttons rather than new code.
    private final Button firstButton = new Button("First");
    private final Button previousButton = new Button("Previous");
    private final Button lastButton = new Button("Last");
    private final ListView<IBaseResource> results = new ListView<>();
    private final Label statusLabel = new Label(" ");
    private final Label pageInfo = new Label(" ");
    private Button openButton;

    private SearchRequest lastRequest;
    private String lastPageToken;
    /** The links of the Bundle currently shown, which decide which page buttons are live. */
    private SearchPageLinks lastLinks = SearchPageLinks.none();

    public ServerSearchDialog(
            FhirServerService serverService,
            FhirServerManager serverManager,
            com.example.fhirviewer.service.FhirService fhirService,
            ThemeManager themeManager,
            SearchMemory memory) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.serverService = serverService;
        this.serverManager = serverManager;
        this.fhirService = fhirService;

        setTitle("Search a FHIR server");
        setHeaderText("Find resources on a connected FHIR server");
        setResizable(true);

        serverBox.getItems().setAll(serverManager.servers());
        serverBox.getSelectionModel().selectFirst();
        renderServerNames();
        typeBox.setDisable(true);
        typeBox.setPromptText("Load capabilities first");


        connectButton.getStyleClass().add("button-ghost");
        connectButton.setTooltip(new Tooltip("Read the server's CapabilityStatement to list its resource types."));
        connectButton.setOnAction(event -> loadCapabilities());

        searchButton.getStyleClass().add("button-primary");
        searchButton.setDisable(true);
        searchButton.setOnAction(event -> search(null));

        nextButton.getStyleClass().add("button-ghost");
        nextButton.setDisable(true);
        nextButton.setTooltip(new Tooltip("Fetch the page after this one."));
        nextButton.setOnAction(event -> search(lastPageToken));

        firstButton.getStyleClass().add("button-ghost");
        firstButton.setDisable(true);
        firstButton.setTooltip(new Tooltip("Go back to the first page of this search."));
        firstButton.setOnAction(event -> search(lastLinks.first()));

        previousButton.getStyleClass().add("button-ghost");
        previousButton.setDisable(true);
        previousButton.setTooltip(new Tooltip("Fetch the page before this one."));
        previousButton.setOnAction(event -> search(lastLinks.previous()));

        lastButton.getStyleClass().add("button-ghost");
        lastButton.setDisable(true);
        lastButton.setTooltip(new Tooltip("Go to the last page of this search."));
        lastButton.setOnAction(event -> search(lastLinks.last()));

        results.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(IBaseResource item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String id = item.getIdElement() == null || !item.getIdElement().hasIdPart()
                        ? null
                        : item.getIdElement().getIdPart();
                String label = item.fhirType() + (id == null ? "" : "/" + id);
                String summary = fhirService.summaryText(item);
                setText(summary.isBlank() ? label : label + " — " + summary);
            }
        });
        results.setPrefHeight(260);
        results.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (openButton != null) {
                openButton.setDisable(selected == null);
            }
        });
        results.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && openButton != null && !openButton.isDisabled()) {
                openButton.fire();
            }
        });
        VBox.setVgrow(results, Priority.ALWAYS);

        statusLabel.getStyleClass().add("app-subtitle");
        statusLabel.setWrapText(true);
        pageInfo.getStyleClass().add("app-subtitle");

        ButtonType openType = new ButtonType("Open in viewer", ButtonBar.ButtonData.OK_DONE);
        VBox content = new VBox(10, criteriaGrid(), statusLabel, pageInfo, results);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(openType, ButtonType.CANCEL);
        // Wide enough for the criteria editor's buttons. "Parameters" / "Search string" and
        // "Add parameter" / "Remove last" were clipped at the old width, which is a worse
        // failure than a wide dialog: a button reading "Add param" is a guess.
        getDialogPane().setPrefWidth(840);
        getDialogPane().setMinWidth(760);
        getDialogPane().setPrefHeight(640);
        getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        openButton = (Button) getDialogPane().lookupButton(openType);
        if (openButton != null) {
            openButton.getStyleClass().add("button-primary");
            openButton.setDisable(true);
            openButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                if (selectedResource() == null) {
                    event.consume();
                }
            });
        }
        Button cancel = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancel != null) {
            cancel.getStyleClass().add("button-ghost");
        }

        setResultConverter(button -> {
            // openType, not ButtonType.OK: those are different objects, and the pane was
            // never given the predefined OK type. Comparing against OK made this answer
            // null for every press of "Open in viewer", so a picked resource was
            // discarded and the window never received anything to show.
            IBaseResource resource = openType.equals(button) ? selectedResource() : null;
            return resource == null ? null : asLoadedResource(resource);
        });

        restoreLastSearch();
    }

    /**
     * Puts the last search back into the form, so coming back to this screen after opening
     * a result shows what was searched for rather than an empty one.
     *
     * <p>Only the form is restored. The results are not re-fetched: doing that would spend a
     * network request and could pull a page the user did not ask for again. Pressing Search
     * runs it again.</p>
     *
     * <p>A remembered server that is no longer configured is skipped rather than forced —
     * a renamed or deleted server should leave the rest of the form usable.</p>
     */
    private void restoreLastSearch() {
        SearchMemory.Search last = memory.last();
        if (last == null) {
            return;
        }
        if (last.serverName() != null) {
            for (com.example.fhirviewer.server.FhirServerConfiguration server
                    : serverBox.getItems()) {
                if (server.name().equals(last.serverName())) {
                    serverBox.getSelectionModel().select(server);
                    break;
                }
            }
        }
        if (last.resourceType() != null && !last.resourceType().isBlank()) {
            // The type list is filled from the server's capabilities, which may not have
            // loaded yet; the editable box keeps the value either way.
            typeBox.setValue(last.resourceType());
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
    }

    private GridPane criteriaGrid() {
        HBox actions = new HBox(8, connectButton, searchButton,
                firstButton, previousButton, nextButton, lastButton);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;
        addRow(grid, row++, "Server", serverBox);
        addRow(grid, row++, "Resource type", typeBox);
        // Replaces the single name/value pair. The editor holds both the parameter rows and
        // the raw search-string mode, so the two screens cannot drift into disagreeing
        // about what a valid search is.
        Region editor = criteriaEditor.build();
        grid.add(editor, 0, row++);
        GridPane.setColumnSpan(editor, 2);
        grid.add(actions, 1, row);
        return grid;
    }

    private void addRow(GridPane grid, int row, String label, javafx.scene.layout.Region control) {
        Label labelNode = new Label(label);
        labelNode.getStyleClass().add("pretty-row-label");
        grid.add(labelNode, 0, row);
        grid.add(control, 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
        control.setMaxWidth(Double.MAX_VALUE);
    }

    private void renderServerNames() {
        serverBox.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(com.example.fhirviewer.server.FhirServerConfiguration item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + " — " + item.baseUrl());
            }
        });
        serverBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(com.example.fhirviewer.server.FhirServerConfiguration item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
    }

    /** The resource the user picked in the result list, or {@code null}. */
    public IBaseResource selectedResource() {
        return results.getSelectionModel().getSelectedItem();
    }

    /** Wraps the picked resource for the existing viewer; no second rendering path. */
    private LoadedResource asLoadedResource(IBaseResource resource) {
        String id = resource.getIdElement() == null || !resource.getIdElement().hasIdPart()
                ? null
                : resource.getIdElement().getIdPart();
        String label = resource.fhirType() + (id == null ? "" : "/" + id);
        com.example.fhirviewer.server.FhirServerConfiguration server = serverBox.getValue();
        String source = server == null ? label : label + " (from " + server.name() + ")";
        return new LoadedResource(resource, ResourceFormat.JSON, source, null);
    }

    private void loadCapabilities() {
        com.example.fhirviewer.server.FhirServerConfiguration server = serverBox.getValue();
        if (server == null) {
            return;
        }
        setBusy(true, "Reading capabilities of " + server.baseUrl() + " ...");
        run(() -> new Attempt<>(serverService.capabilities(server), null), attempt -> {
            setBusy(false, null);
            if (!attempt.succeeded()) {
                reportFailure(attempt.failure());
                return;
            }
            ServerCapabilities capabilities = attempt.value();
            typeBox.getItems().setAll(capabilities.resourceTypes());
            if (!typeBox.getItems().isEmpty()) {
                typeBox.getSelectionModel().selectFirst();
                typeBox.setDisable(false);
                searchButton.setDisable(false);
            } else {
                typeBox.setDisable(true);
                searchButton.setDisable(true);
            }
            statusLabel.getStyleClass().remove("status-error");
            statusLabel.setText("FHIR " + capabilities.fhirVersion() + ", "
                    + capabilities.resourceTypes().size() + " resource types"
                    + (capabilities.pagingSupported() ? ", paging supported." : "."));
        });
    }

    /**
     * Runs a search. With a {@code null} page token this starts (or repeats) a search;
     * otherwise it fetches the page the token names.
     */
    private void search(String pageToken) {
        com.example.fhirviewer.server.FhirServerConfiguration server = serverBox.getValue();
        if (server == null) {
            return;
        }
        String resourceType = typeBox.getValue();
        if (resourceType == null || resourceType.isBlank()) {
            reportFailure("Choose a resource type first.");
            return;
        }
        if (pageToken == null) {
            List<SearchCriterion> criteria = criteria();
            if (criteria == null) {
                return;
            }
            lastRequest = new SearchRequest(resourceType, criteria, PAGE_SIZE);
            lastPageToken = null;
            // So reopening this screen after opening a result shows the same search again.
            memory.remember(new SearchMemory.Search(server.name(), resourceType, criteria,
                    PAGE_SIZE));
        }
        if (lastRequest == null) {
            reportFailure("Search for something first.");
            return;
        }
        String busy = pageToken == null
                ? "Searching " + resourceType + " on " + server.baseUrl() + " ..."
                : "Fetching another page ...";
        setBusy(true, busy);
        boolean fetchingPage = pageToken != null;
        run(() -> fetchingPage
                ? new Attempt<>(serverService.pageAt(server, pageToken), null)
                : new Attempt<>(serverService.search(server, lastRequest), null), attempt -> {
            setBusy(false, null);
            if (!attempt.succeeded()) {
                reportFailure(attempt.failure());
                return;
            }
            SearchResultPage page = attempt.value();
            results.getItems().setAll(page.resources());
            results.getSelectionModel().clearSelection();
            // Every relation the server sent is kept. Each button follows the URL the
            // server minted rather than rebuilding one, which is the only way to page
            // correctly on a server that counts differently from this application.
            lastLinks = page.links();
            lastPageToken = page.nextPageToken();
            pageInfo.setText(describePage(page));
            updatePageButtons(false);
            statusLabel.getStyleClass().remove("status-error");
            statusLabel.setText(page.resources().isEmpty()
                    ? "No resources matched the search."
                    : page.toString() + ".");
        });
    }

    /**
     * The criteria the user described; empty when a parameter search is left blank.
     *
     * <p>Delegates to the shared editor so this screen and {@code OpenFromServerDialog}
     * cannot disagree about what a valid search is.</p>
     */
    private List<SearchCriterion> criteria() {
        try {
            return criteriaEditor.criteria();
        } catch (IllegalArgumentException e) {
            reportFailure(e.getMessage());
            return null;
        }
    }

    /**
     * Runs work on a background thread and delivers the attempt on the JavaFX thread.
     *
     * <p>Delegates to {@link BackgroundTasks}, which is the single implementation of this
     * pattern. It used to be re-declared here, in {@link OpenFromServerDialog} and in
     * {@code MainWindow}; keeping one copy is what makes the cancellation handle available
     * to every screen rather than only the new ones.</p>
     */
    private <T> void run(Callable<Attempt<T>> work, java.util.function.Consumer<Attempt<T>> done) {
        // One shared implementation; see BackgroundTasks for why this is not written out
        // per dialog any more. The local Attempt is bridged to the shared one in both
        // directions so this dialog's own call sites are unchanged by the refactor.
        BackgroundTasks.runAttempt("fhir-server-search",
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
     * Enables exactly the page buttons the server's own links justify.
     *
     * <p>A button is live only when the Bundle carried that relation. Showing "Previous" on
     * a first page would invite a click that either does nothing or, worse, restarts the
     * search and silently discards the criteria the user typed.</p>
     *
     * @param busy true while a request is in flight, which disables all of them
     */
    private void updatePageButtons(boolean busy) {
        firstButton.setDisable(busy || !lastLinks.hasFirst());
        previousButton.setDisable(busy || !lastLinks.hasPrevious());
        nextButton.setDisable(busy || !lastLinks.hasNext());
        lastButton.setDisable(busy || !lastLinks.hasLast());
    }

    /** The line describing the page on screen: what the server said, never a rebuilt URL. */
    private String describePage(SearchResultPage page) {
        StringBuilder text = new StringBuilder();
        if (page.total() != null) {
            text.append("Server reported ").append(page.total()).append(" matching resource(s). ");
        }
        text.append("This page: ").append(page.resources().size()).append('.');
        if (page.links().isEmpty()) {
            text.append(" The server sent no paging links.");
        }
        return text.toString();
    }

    private void setBusy(boolean busy, String message) {
        connectButton.setDisable(busy);
        searchButton.setDisable(busy || typeBox.getValue() == null);
        // The page buttons follow the server's links rather than the last token, so a
        // server that offers no next page leaves Next disabled and Previous live.
        updatePageButtons(busy);
        if (openButton != null) {
            openButton.setDisable(busy || selectedResource() == null);
        }
        if (message != null) {
            statusLabel.getStyleClass().remove("status-error");
            statusLabel.setText(message);
        }
    }

    private void reportFailure(String message) {
        statusLabel.getStyleClass().remove("status-error");
        statusLabel.getStyleClass().add("status-error");
        statusLabel.setText(message);
    }
}
