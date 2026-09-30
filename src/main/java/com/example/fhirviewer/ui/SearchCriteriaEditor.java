package com.example.fhirviewer.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.control.TextField;

import com.example.fhirviewer.server.SearchCriterion;

/**
 * Collects a search: either a list of name/value parameters, or one raw search string.
 *
 * <p>Two ways in, because a FHIR search genuinely has two shapes. A parameter list is
 * easier to type and cannot be got subtly wrong. A raw string is how a search is written
 * down — copied from a browser address bar, a specification example or a colleague — and
 * the point of offering it is that prefixes, modifiers, chains, {@code _sort} and anything
 * a vendor has added all work, because nothing here parses it.</p>
 *
 * <p>One of the two is chosen at a time rather than both being available at once, so a
 * search is never half one thing and half the other. {@link com.example.fhirviewer.server.SearchRequest}
 * refuses a mixed request for the same reason.</p>
 *
 * <p>Shared by both search screens so they cannot drift into disagreeing about what a valid
 * search is, which is why {@link SearchCriteriaBuilder} exists.</p>
 */
final class SearchCriteriaEditor {

    private final ToggleButton parametersMode = new ToggleButton("Parameters");
    private final ToggleButton rawMode = new ToggleButton("Search string");
    private final ToggleGroup modes = new ToggleGroup();

    private final VBox rows = new VBox(6);
    private final List<Row> rowList = new ArrayList<>();
    private final TextField rawField = new TextField();
    private final Button addButton = new Button("Add parameter");
    private final Button removeButton = new Button("Remove last");
    private final Label hint = new Label();

    SearchCriteriaEditor() {
        modes.getToggles().addAll(parametersMode, rawMode);
        parametersMode.setSelected(true);

        parametersMode.setOnAction(event -> applyMode());
        rawMode.setOnAction(event -> applyMode());

        rawField.setPromptText("name=Smith&birthdate=1990-01-01");
        rawField.setTooltip(new Tooltip(
                "Sent to the server exactly as typed. You may include the resource type and a"
                        + " leading '?' - Patient?name=Smith works as well as name=Smith."));

        addButton.setOnAction(event -> addRow("", ""));
        removeButton.setOnAction(event -> removeLastRow());
        removeButton.getStyleClass().add("button-ghost");

        hint.getStyleClass().add("app-subtitle");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        addRow("", "");
        applyMode();
    }

    /** The editor as a region, ready to drop into a grid. */
    Region build() {
        HBox modeRow = new HBox(8, parametersMode, rawMode);
        modeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        HBox buttons = new HBox(8, addButton, removeButton);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // A JavaFX Button shrinks its text rather than overflowing it, so a narrow window
        // silently turns "Add parameter" into "Add param". These are the same width whatever
        // the dialog is doing, and the dialog is sized to fit them instead.
        for (javafx.scene.control.Button button : List.of(addButton, removeButton)) {
            button.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        }

        VBox all = new VBox(8, modeRow, rows, buttons, rawField, hint);
        all.setPadding(new Insets(0));
        VBox.setVgrow(rows, Priority.NEVER);
        return all;
    }

    /** True when the raw search-string mode is chosen. */
    boolean isRawMode() {
        return rawMode.isSelected();
    }

    /**
     * The search the user has described.
     *
     * @throws IllegalArgumentException when a half-filled parameter row is left, or the raw
     *                                  string is empty; the message is written to be shown
     *                                  to the user as-is
     */
    List<SearchCriterion> criteria() {
        if (isRawMode()) {
            return SearchCriteriaBuilder.raw(rawField.getText());
        }
        List<SearchCriterion> criteria = new ArrayList<>();
        for (Row row : rowList) {
            String name = row.name.getText() == null ? "" : row.name.getText().trim();
            String value = row.value.getText() == null ? "" : row.value.getText().trim();
            if (name.isEmpty() && value.isEmpty()) {
                // A blank row is how a search with no parameters is written: browse a type.
                continue;
            }
            if (name.isEmpty()) {
                throw new IllegalArgumentException(
                        "A search parameter needs a name as well as a value.");
            }
            criteria.add(new SearchCriterion(name, value));
        }
        return List.copyOf(criteria);
    }

    /** Replaces the whole form with one raw search string. */
    void setRaw(String query) {
        rawMode.setSelected(true);
        rawField.setText(query == null ? "" : query);
        applyMode();
    }

    /** Replaces the rows with the given parameters, and selects parameter mode. */
    void setParameters(List<SearchCriterion> criteria) {
        parametersMode.setSelected(true);
        clearRows();
        if (criteria == null || criteria.isEmpty()) {
            addRow("", "");
        } else {
            for (SearchCriterion criterion : criteria) {
                addRow(criterion.name(), criterion.value());
            }
        }
        applyMode();
    }

    private void applyMode() {
        boolean raw = isRawMode();
        rows.setVisible(!raw);
        addButton.setDisable(raw);
        rawField.setVisible(raw);
        // Both conditions, not just the mode: run last, this would otherwise re-enable
        // Remove on a single row, where there is nothing left to remove.
        removeButton.setDisable(raw || rowList.size() <= 1);
        // Switching must not carry a half-typed parameter into a raw search, so the mode
        // being left is cleared. Losing an abandoned draft beats sending the wrong search.
        if (raw) {
            clearRows();
            hint.setText("Sent to the server exactly as typed, including prefixes,"
                    + " modifiers and _sort.");
        } else {
            rawField.clear();
            if (rowList.isEmpty()) {
                addRow("", "");
            }
            hint.setText("Add a row per parameter. Leave both blank to browse every"
                    + " resource of the type.");
        }
    }

    private void clearRows() {
        rowList.clear();
        rows.getChildren().clear();
    }

    private void removeLastRow() {
        if (rowList.size() > 1) {
            rowList.remove(rowList.size() - 1);
            rebuildRows();
        }
    }

    /**
     * Appends a name/value row.
     *
     * <p>Rows are rebuilt from the list rather than added in place, so the model and what is
     * on screen cannot drift: remove-then-rebuild and add-then-rebuild go through the same
     * code and the same row count is always what the user sees.</p>
     */
    private void addRow(String name, String value) {
        rowList.add(new Row(name, value));
        rebuildRows();
    }

    private void rebuildRows() {
        rows.getChildren().clear();
        for (Row row : rowList) {
            rows.getChildren().add(rowGrid(row));
        }
        removeButton.setDisable(rowList.size() <= 1 || isRawMode());
    }

    /** One name/value pair, held separately from its controls so the rows can be rebuilt. */
    private static final class Row {
        private final TextField name = new TextField();
        private final TextField value = new TextField();

        Row(String name, String value) {
            this.name.setText(name == null ? "" : name);
            this.value.setText(value == null ? "" : value);
            this.name.setPromptText("name");
            this.value.setPromptText("value");
        }
    }

    private GridPane rowGrid(Row row) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        ColumnConstraints labelColumn = new ColumnConstraints(60);
        labelColumn.setHgrow(Priority.NEVER);
        ColumnConstraints controlColumn = new ColumnConstraints();
        controlColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, controlColumn);

        Label nameLabel = new Label("Name");
        nameLabel.getStyleClass().add("pretty-row-label");
        grid.add(nameLabel, 0, 0);
        grid.add(row.name, 1, 0);
        GridPane.setHgrow(row.name, Priority.ALWAYS);
        row.name.setMaxWidth(Double.MAX_VALUE);

        Label valueLabel = new Label("Value");
        valueLabel.getStyleClass().add("pretty-row-label");
        grid.add(valueLabel, 0, 1);
        grid.add(row.value, 1, 1);
        GridPane.setHgrow(row.value, Priority.ALWAYS);
        row.value.setMaxWidth(Double.MAX_VALUE);
        return grid;
    }

    // -- Test access ----------------------------------------------------------

    /** How many parameter rows are on screen. */
    int rowCount() {
        return rowList.size();
    }

    /** The name field of a row, so a test can fill it in. */
    TextField nameFieldAt(int index) {
        return rowList.get(index).name;
    }

    /** The value field of a row, so a test can fill it in. */
    TextField valueFieldAt(int index) {
        return rowList.get(index).value;
    }

    /** The raw search field. */
    TextField rawField() {
        return rawField;
    }

    Button addButton() {
        return addButton;
    }

    Button removeButton() {
        return removeButton;
    }

    ToggleButton parametersMode() {
        return parametersMode;
    }

    ToggleButton rawMode() {
        return rawMode;
    }
}
