package com.example.fhirviewer.ui;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import javafx.concurrent.Task;
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
import javafx.scene.layout.VBox;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.model.LoadedResource;
import com.example.fhirviewer.model.ResourceFormat;
import com.example.fhirviewer.server.FhirServerManager;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.SearchCriterion;
import com.example.fhirviewer.server.SearchRequest;
import com.example.fhirviewer.server.SearchResultPage;
import com.example.fhirviewer.server.ServerCapabilities;
import com.example.fhirviewer.server.ServerOperationException;

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

    /** One background attempt: either a value or a readable failure, never an exception. */
    private record Attempt<T>(T value, String failure) {

        boolean succeeded() {
            return failure == null;
        }
    }

    private static final int PAGE_SIZE = 20;

    private final FhirServerService serverService;
    private final FhirServerManager serverManager;
    private final com.example.fhirviewer.service.FhirService fhirService;

    private final ComboBox<com.example.fhirviewer.server.FhirServerConfiguration> serverBox = new ComboBox<>();
    private final ComboBox<String> typeBox = new ComboBox<>();
    private final TextField parameterField = new TextField();
    private final TextField valueField = new TextField();
    private final Button connectButton = new Button("Load capabilities");
    private final Button searchButton = new Button("Search");
    private final Button nextButton = new Button("Next page");
    private final ListView<IBaseResource> results = new ListView<>();
    private final Label statusLabel = new Label(" ");
    private final Label pageInfo = new Label(" ");
    private Button openButton;

    private SearchRequest lastRequest;
    private String lastPageToken;

    public ServerSearchDialog(
            FhirServerService serverService,
            FhirServerManager serverManager,
            com.example.fhirviewer.service.FhirService fhirService,
            ThemeManager themeManager) {
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
        parameterField.setPromptText("e.g. name");
        valueField.setPromptText("e.g. Smith");

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
        getDialogPane().setPrefWidth(680);
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
            IBaseResource resource = ButtonType.OK.equals(button) ? selectedResource() : null;
            return resource == null ? null : asLoadedResource(resource);
        });
    }

    private GridPane criteriaGrid() {
        HBox actions = new HBox(8, connectButton, searchButton, nextButton);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;
        addRow(grid, row++, "Server", serverBox);
        addRow(grid, row++, "Resource type", typeBox);
        addRow(grid, row++, "Search parameter", parameterField);
        addRow(grid, row++, "Value", valueField);
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
        }
        if (lastRequest == null) {
            reportFailure("Search for something first.");
            return;
        }
        String busy = pageToken == null
                ? "Searching " + resourceType + " on " + server.baseUrl() + " ..."
                : "Fetching the next page ...";
        setBusy(true, busy);
        boolean fetchingPage = pageToken != null;
        run(() -> fetchingPage
                ? new Attempt<>(serverService.nextPage(server, lastRequest, pageToken), null)
                : new Attempt<>(serverService.search(server, lastRequest), null), attempt -> {
            setBusy(false, null);
            if (!attempt.succeeded()) {
                reportFailure(attempt.failure());
                return;
            }
            SearchResultPage page = attempt.value();
            results.getItems().setAll(page.resources());
            results.getSelectionModel().clearSelection();
            lastPageToken = page.nextPageToken();
            nextButton.setDisable(!page.hasNextPage());
            statusLabel.getStyleClass().remove("status-error");
            statusLabel.setText(page.resources().isEmpty()
                    ? "No resources matched the search."
                    : page.toString() + ".");
        });
    }

    /** The criteria from the two fields; empty when the user left both blank. */
    private List<SearchCriterion> criteria() {
        String name = parameterField.getText() == null ? "" : parameterField.getText().trim();
        String value = valueField.getText() == null ? "" : valueField.getText().trim();
        if (name.isBlank() && value.isBlank()) {
            return List.of();
        }
        if (name.isBlank() || value.isBlank()) {
            reportFailure("Enter both a search parameter and a value.");
            return null;
        }
        try {
            return List.of(new SearchCriterion(name, value));
        } catch (IllegalArgumentException e) {
            reportFailure(e.getMessage());
            return null;
        }
    }

    /** Runs work on a background thread and delivers the attempt on the JavaFX thread. */
    private <T> void run(Callable<Attempt<T>> work, java.util.function.Consumer<Attempt<T>> done) {
        Task<Attempt<T>> task = new Task<>() {
            @Override
            protected Attempt<T> call() {
                try {
                    return work.call();
                } catch (ServerOperationException e) {
                    return new Attempt<>(null, e.displayMessage());
                } catch (Exception e) {
                    return new Attempt<>(null, "Unexpected problem: " + ServerDialog.readableFailure(e));
                }
            }
        };
        task.setOnSucceeded(event -> done.accept(task.getValue()));
        task.setOnFailed(event -> {
            setBusy(false, null);
            reportFailure("Unexpected problem: " + ServerDialog.readableFailure(task.getException()));
        });
        Thread thread = new Thread(task, "fhir-server-search");
        thread.setDaemon(true);
        thread.start();
    }

    private void setBusy(boolean busy, String message) {
        connectButton.setDisable(busy);
        searchButton.setDisable(busy || typeBox.getValue() == null);
        nextButton.setDisable(busy || lastPageToken == null);
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

    /** The message a dialog shows for a background failure, readable and log free. */
    static String readableFailure(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 5
                && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
            depth++;
        }
        return current == null || current.getMessage() == null || current.getMessage().isBlank()
                ? current == null ? "unknown error" : current.getClass().getSimpleName()
                : current.getMessage();
    }
}
