package com.example.fhirviewer.ui;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.model.ElementInfo;
import com.example.fhirviewer.model.ResourceNode;

/**
 * Shows the metadata of the element selected in the resource tree: element name, path,
 * datatype, cardinality, kind, value and the specification definition.
 *
 * <p>The value row doubles as the editing surface. <code>Apply</code> reports the entered
 * text through the handler the window installs, and <code>Add entry</code> reports the
 * selected element so a new entry can be added to a repeating element. This class
 * performs no FHIR work of its own and no element name translation: it hands the selected
 * node and the entered text back to the window.</p>
 */
public class DetailsView extends VBox {

    /** Style class of the value field, see <code>/css/app.css</code>. */
    private static final String EDIT_FIELD_CLASS = "edit-value-field";

    private static final String APPLY_TOOLTIP =
            "Write the entered value to the element selected in the resource tree.";
    private static final String ADD_TOOLTIP =
            "Add a new entry to the repeating element selected in the resource tree.";
    private static final String ADD_CHILD_TOOLTIP =
            "Add a child element to the selected element, choosing from the FHIR resource definition.";
    private static final String DELETE_TOOLTIP =
            "Delete the element selected in the resource tree from the resource.";

    private final Label nameValue = newValueLabel();
    private final Label pathValue = newValueLabel();
    private final Label typeValue = newValueLabel();
    private final Label cardinalityValue = newValueLabel();
    private final Label kindValue = newValueLabel();
    private final Label definitionValue = newValueLabel();

    private final TextField valueField = new TextField();
    private final Button applyButton = new Button("Apply");
    private final Button addButton = new Button("Add entry");
    private final Button addChildButton = new Button("Add child...");
    private final Button deleteButton = new Button("Delete element");

    private BiConsumer<ResourceNode, String> editHandler = (node, text) -> { };
    private Consumer<ResourceNode> addHandler = node -> { };
    private Consumer<ResourceNode> addChildHandler = node -> { };
    private Consumer<ResourceNode> deleteHandler = node -> { };

    /** The element currently shown, or {@code null} when nothing is selected. */
    private ResourceNode current;

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

        valueField.getStyleClass().add(EDIT_FIELD_CLASS);
        valueField.setPromptText("(no value)");
        valueField.setTooltip(new Tooltip(APPLY_TOOLTIP));
        // Enter applies the value, so an element can be edited from the keyboard alone.
        valueField.setOnAction(event -> applyValue());

        applyButton.getStyleClass().add("button-primary");
        applyButton.setTooltip(new Tooltip(APPLY_TOOLTIP));
        applyButton.setOnAction(event -> applyValue());

        addButton.getStyleClass().add("button-ghost");
        addButton.setTooltip(new Tooltip(ADD_TOOLTIP));
        addButton.setOnAction(event -> addEntry());

        addChildButton.getStyleClass().add("button-ghost");
        addChildButton.setTooltip(new Tooltip(ADD_CHILD_TOOLTIP));
        addChildButton.setOnAction(event -> requestAddChild());

        deleteButton.getStyleClass().add("button-ghost");
        deleteButton.setTooltip(new Tooltip(DELETE_TOOLTIP));
        deleteButton.setOnAction(event -> requestDelete());

        HBox valueEditor = new HBox(8, valueField, applyButton, addButton);
        valueEditor.getStyleClass().add("details-editor");
        HBox.setHgrow(valueField, Priority.ALWAYS);
        valueField.setMaxWidth(Double.MAX_VALUE);

        HBox structureActions = new HBox(8, addChildButton, deleteButton);
        structureActions.getStyleClass().add("details-editor");

        int row = 0;
        addRow(grid, row++, "Element", nameValue);
        addRow(grid, row++, "Path", pathValue);
        addRow(grid, row++, "Type", typeValue);
        addRow(grid, row++, "Cardinality", cardinalityValue);
        addRow(grid, row++, "Kind", kindValue);
        addRow(grid, row++, "Structure", structureActions);
        addRow(grid, row++, "Value", valueEditor);
        addRow(grid, row++, "Definition", definitionValue);

        getChildren().add(grid);
        showNothingSelected();
    }

    /** Sets the handler called when the user applies an edited value. */
    public void setOnValueEdited(BiConsumer<ResourceNode, String> handler) {
        this.editHandler = handler == null ? (node, text) -> { } : handler;
    }

    /** Sets the handler called when the user asks for a new entry of a repeating element. */
    public void setOnElementAdded(Consumer<ResourceNode> handler) {
        this.addHandler = handler == null ? node -> { } : handler;
    }

    /** Sets the handler called when the user asks to add a child element. */
    public void setOnChildAddRequested(Consumer<ResourceNode> handler) {
        this.addChildHandler = handler == null ? node -> { } : handler;
    }

    /** Sets the handler called when the user asks to delete the selected element. */
    public void setOnElementDeleted(Consumer<ResourceNode> handler) {
        this.deleteHandler = handler == null ? node -> { } : handler;
    }

    /** Displays the details of the selected element and configures the editing controls. */
    public void show(ResourceNode node) {
        current = node;
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
        definitionValue.setText(info.hasDefinition() ? info.getDefinition() : "-");

        valueField.setText(info.hasValueText() ? info.getValueText() : "");
        boolean editable = isValueEditable(node, info);
        valueField.setDisable(!editable);
        applyButton.setDisable(!editable);
        addButton.setDisable(!info.isRepeating());
        // Every composite element can receive a child element; the resource itself can
        // receive top level elements, so the root is included.
        addChildButton.setDisable(info.getKind() == ElementInfo.Kind.PRIMITIVE);
        // The resource itself is not deletable; only elements inside it are.
        deleteButton.setDisable(node.getParent() == null);
    }

    /** Resets the panel to its empty state. */
    public void showNothingSelected() {
        current = null;
        nameValue.setText("-");
        pathValue.setText("-");
        typeValue.setText("-");
        cardinalityValue.setText("-");
        kindValue.setText("-");
        definitionValue.setText("Select an element in the resource tree to see its details.");
        valueField.clear();
        valueField.setDisable(true);
        applyButton.setDisable(true);
        addButton.setDisable(true);
        addChildButton.setDisable(true);
        deleteButton.setDisable(true);
    }

    private void applyValue() {
        if (current != null) {
            editHandler.accept(current, valueField.getText());
        }
    }

    private void addEntry() {
        if (current != null) {
            addHandler.accept(current);
        }
    }

    private void requestAddChild() {
        if (current != null) {
            addChildHandler.accept(current);
        }
    }

    private void requestDelete() {
        if (current != null) {
            deleteHandler.accept(current);
        }
    }

    /**
     * True when the value field can be used for the element: primitives and references
     * always, plus elements that are defined but not populated yet (the tree shows those
     * when "Show elements that are not populated" is enabled). Element types that cannot
     * hold a text value are rejected by the editing service and reported in the status bar.
     */
    private static boolean isValueEditable(ResourceNode node, ElementInfo info) {
        if (info.getKind() == ElementInfo.Kind.PRIMITIVE || info.getKind() == ElementInfo.Kind.REFERENCE) {
            return true;
        }
        return node.isLeaf()
                && !info.hasValueText()
                && info.getKind() != ElementInfo.Kind.RESOURCE
                && info.getKind() != ElementInfo.Kind.NESTED_RESOURCE;
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

    private static void addRow(GridPane grid, int row, String labelText, Node value) {
        Label label = new Label(labelText);
        label.getStyleClass().add("pretty-row-label");
        grid.add(label, 0, row);
        grid.add(value, 1, row);
    }
}
