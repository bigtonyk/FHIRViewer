package com.example.fhirviewer.ui;

import java.util.List;
import java.util.function.Function;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.service.FHIRPathService.Result;

/**
 * The FHIRPath tab: a user managed list of expressions together with the result
 * each one produced against the resource the document tabs show.
 *
 * <p>The view owns the entries; the window supplies the evaluator, so no FHIR
 * knowledge lives here. Recalculating replaces the result of every entry with a
 * fresh evaluation against the current resource, which keeps the tab honest
 * after every tree edit. Valid, invalid and empty results are styled
 * differently (see <code>/css/app.css</code>, classes <code>fhirpath-*</code>).</p>
 */
public class FhirPathView extends VBox {

    private static final String ADD_TOOLTIP =
            "Evaluate the entered expression against the current resource and keep it in the list.";
    private static final String DELETE_TOOLTIP = "Remove the selected expressions from the list.";
    private static final String RECALCULATE_TOOLTIP =
            "Evaluate every expression in the list against the current resource again.";
    private static final String PLACEHOLDER =
            "Add a FHIRPath expression, for example Patient.name[0].family.";

    private final TextField expressionField = new TextField();
    private final Button addButton = new Button("Add Entry");
    private final Button deleteButton = new Button("Delete Selected");
    private final Button recalculateButton = new Button("Recalculate All");
    private final ListView<Result> entries = new ListView<>();

    /** Evaluates one expression against the current resource; installed by the window. */
    private Function<String, Result> evaluator =
            expression -> Result.invalid(expression, "No FHIR resource is loaded.");

    public FhirPathView() {
        getStyleClass().addAll("card", "fhirpath-view");
        setSpacing(8);
        setPadding(new Insets(8));

        expressionField.setPromptText("FHIRPath expression, e.g. Patient.name[0].family");
        expressionField.setTooltip(new Tooltip(ADD_TOOLTIP));
        expressionField.setOnAction(event -> addEntry());

        addButton.getStyleClass().add("button-primary");
        addButton.setTooltip(new Tooltip(ADD_TOOLTIP));
        addButton.setOnAction(event -> addEntry());

        deleteButton.getStyleClass().add("button-ghost");
        deleteButton.setTooltip(new Tooltip(DELETE_TOOLTIP));
        deleteButton.disableProperty().bind(Bindings.isEmpty(entries.getSelectionModel().getSelectedItems()));
        deleteButton.setOnAction(event -> deleteSelected());

        recalculateButton.getStyleClass().add("button-ghost");
        recalculateButton.setTooltip(new Tooltip(RECALCULATE_TOOLTIP));
        recalculateButton.disableProperty().bind(Bindings.isEmpty(entries.getItems()));
        recalculateButton.setOnAction(event -> recalculate());

        HBox toolbar = new HBox(8, expressionField, addButton, deleteButton, recalculateButton);
        toolbar.getStyleClass().add("fhirpath-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(expressionField, Priority.ALWAYS);

        entries.setPlaceholder(new Label(PLACEHOLDER));
        entries.setCellFactory(view -> new ResultCell());
        entries.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(entries, Priority.ALWAYS);

        getChildren().addAll(toolbar, entries);
    }

    /** Installs the evaluator that turns an expression into a result for the current resource. */
    public void setExpressionEvaluator(Function<String, Result> evaluator) {
        this.evaluator = evaluator == null
                ? expression -> Result.invalid(expression, "No FHIR resource is loaded.")
                : evaluator;
    }

    /** Adds the entered expression to the list and evaluates it straight away. */
    public void addEntry() {
        String expression = expressionField.getText();
        expressionField.clear();
        addEntry(expression);
    }

    /**
     * Adds an expression to the list and evaluates it against the current resource.
     * An expression that is already in the list is evaluated again in place.
     */
    public void addEntry(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        Result result = evaluator.apply(expression);
        entries.getItems().removeIf(existing -> existing.expression().equals(result.expression()));
        entries.getItems().add(result);
        entries.getSelectionModel().clearSelection();
        entries.getSelectionModel().selectLast();
        entries.scrollTo(entries.getItems().size() - 1);
    }

    /** Removes the selected expressions from the list. */
    public void deleteSelected() {
        List<Result> selected = List.copyOf(entries.getSelectionModel().getSelectedItems());
        entries.getItems().removeAll(selected);
    }

    /** Evaluates every entry in the list against the current resource again. */
    public void recalculate() {
        if (entries.getItems().isEmpty()) {
            return;
        }
        List<Result> recalculated = entries.getItems().stream()
                .map(Result::expression)
                .map(evaluator)
                .toList();
        entries.getItems().setAll(recalculated);
    }

    /** True when the list holds at least one expression. */
    public boolean hasEntries() {
        return !entries.getItems().isEmpty();
    }

    /** List cell that renders an entry as the expression plus its result or error. */
    private static final class ResultCell extends ListCell<Result> {

        private final Label expressionLabel = new Label();
        private final Label resultLabel = new Label();
        private final HBox box = new HBox(10, expressionLabel, resultLabel);

        ResultCell() {
            expressionLabel.getStyleClass().add("fhirpath-expression");
            resultLabel.getStyleClass().add("fhirpath-result");
            resultLabel.setWrapText(true);
        }

        @Override
        protected void updateItem(Result item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            expressionLabel.setText(item.expression());
            switch (item.status()) {
                case VALID -> {
                    resultLabel.getStyleClass().setAll("fhirpath-result", "fhirpath-status-valid");
                    resultLabel.setText(item.resultText());
                }
                case INVALID -> {
                    resultLabel.getStyleClass().setAll("fhirpath-result", "fhirpath-status-invalid");
                    resultLabel.setText("invalid: " + item.error());
                }
                case EMPTY -> {
                    resultLabel.getStyleClass().setAll("fhirpath-result", "fhirpath-status-empty");
                    resultLabel.setText("no result");
                }
            }
            setGraphic(box);
        }
    }
}