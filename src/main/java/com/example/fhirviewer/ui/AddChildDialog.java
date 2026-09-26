package com.example.fhirviewer.ui;

import java.util.List;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.fhir.ElementProperty;

/**
 * Asks which child element to add under a selected element: the list shows the
 * children the FHIR resource definition allows, each with its datatype and
 * cardinality. The chosen {@link ElementProperty} is handed back to the window,
 * which performs the edit through the editing service.
 */
final class AddChildDialog extends Dialog<ElementProperty> {

    AddChildDialog(String parentPath, List<ElementProperty> children) {
        setTitle("Add child element");
        setHeaderText("Children of " + parentPath);

        ListView<ElementProperty> list = new ListView<>();
        list.getItems().setAll(children);
        list.setCellFactory(view -> new ChildCell());
        list.setPrefSize(430, 360);

        VBox content = new VBox(8,
                new Label("The elements below are allowed by the FHIR definition of " + parentPath + "."),
                list);
        VBox.setVgrow(list, Priority.ALWAYS);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        list.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> okButton.setDisable(selected == null));

        setResultConverter(button -> button == ButtonType.OK && list.getSelectionModel().getSelectedItem() != null
                ? list.getSelectionModel().getSelectedItem()
                : null);
    }

    /** List cell that renders a child element as <code>name — type 0..*</code>. */
    private static final class ChildCell extends ListCell<ElementProperty> {

        @Override
        protected void updateItem(ElementProperty item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            setText(item.name() + "  —  " + item.typeCode() + "  " + cardinality(item));
            if (item.definition() != null && !item.definition().isBlank()) {
                setTooltip(new Tooltip(item.definition()));
            }
        }

        private static String cardinality(ElementProperty item) {
            return item.min() + ".." + (item.max() == Integer.MAX_VALUE ? "*" : Integer.toString(item.max()));
        }
    }
}