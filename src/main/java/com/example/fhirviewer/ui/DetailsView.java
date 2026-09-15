package com.example.fhirviewer.ui;

import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.model.ElementInfo;
import com.example.fhirviewer.model.ResourceNode;

/**
 * Shows the metadata of the element selected in the resource tree: element name,
 * path, datatype, cardinality, kind, value and the specification definition.
 */
public class DetailsView extends VBox {

    private final Label nameValue = newValueLabel();
    private final Label pathValue = newValueLabel();
    private final Label typeValue = newValueLabel();
    private final Label cardinalityValue = newValueLabel();
    private final Label kindValue = newValueLabel();
    private final Label valueValue = newValueLabel();
    private final Label definitionValue = newValueLabel();

    public DetailsView() {
        getStyleClass().add("card");
        setSpacing(10);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(95);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, valueColumn);

        int row = 0;
        addRow(grid, row++, "Element", nameValue);
        addRow(grid, row++, "Path", pathValue);
        addRow(grid, row++, "Type", typeValue);
        addRow(grid, row++, "Cardinality", cardinalityValue);
        addRow(grid, row++, "Kind", kindValue);
        addRow(grid, row++, "Value", valueValue);
        addRow(grid, row++, "Definition", definitionValue);

        getChildren().add(grid);
        showNothingSelected();
    }

    /** Displays the details of the selected element. */
    public void show(ResourceNode node) {
        if (node == null) {
            showNothingSelected();
            return;
        }
        ElementInfo info = node.getElementInfo();
        nameValue.setText(info.getName());
        pathValue.setText(info.getPath());
        typeValue.setText(info.getTypeCode());
        cardinalityValue.setText(info.getCardinality() + (info.isRequired() ? "   (required)" : ""));
        kindValue.setText(describeKind(info.getKind()));
        valueValue.setText(info.hasValueText() ? info.getValueText() : "-");
        definitionValue.setText(info.hasDefinition() ? info.getDefinition() : "-");
    }

    /** Resets the panel to its empty state. */
    public void showNothingSelected() {
        nameValue.setText("-");
        pathValue.setText("-");
        typeValue.setText("-");
        cardinalityValue.setText("-");
        kindValue.setText("-");
        valueValue.setText("-");
        definitionValue.setText("Select an element in the resource tree to see its details.");
    }

    private static String describeKind(ElementInfo.Kind kind) {
        return switch (kind) {
            case RESOURCE -> "Resource";
            case NESTED_RESOURCE -> "Nested resource";
            case PRIMITIVE -> "Primitive";
            case COMPLEX -> "Complex type";
            case REFERENCE -> "Reference";
            case EXTENSION -> "Extension";
        };
    }

    private static Label newValueLabel() {
        Label label = new Label("-");
        label.setWrapText(true);
        return label;
    }

    private static void addRow(GridPane grid, int row, String labelText, Label value) {
        Label label = new Label(labelText);
        label.getStyleClass().add("pretty-row-label");
        grid.add(label, 0, row);
        grid.add(value, 1, row);
    }
}