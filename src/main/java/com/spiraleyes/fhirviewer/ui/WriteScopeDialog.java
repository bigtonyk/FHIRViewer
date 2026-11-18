package com.spiraleyes.fhirviewer.ui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.spiraleyes.fhirviewer.service.scripting.api.WriteScope;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/** Chooses the operation and resource-type limits for the next write grant. */
final class WriteScopeDialog extends Dialog<WriteScope> {

    private final CheckBox create = new CheckBox("Create");
    private final CheckBox update = new CheckBox("Update");
    private final CheckBox patch = new CheckBox("Patch");
    private final CheckBox delete = new CheckBox("Delete");
    private final TextField types = new TextField();

    private WriteScopeDialog(Window owner, WriteScope initial) {
        initOwner(owner);
        setTitle("Write scope");
        setHeaderText("Limit what this write grant may change");

        WriteScope scope = initial == null ? WriteScope.all() : initial;
        create.setSelected(scope.operations().contains(WriteScope.Operation.CREATE));
        update.setSelected(scope.operations().contains(WriteScope.Operation.UPDATE));
        patch.setSelected(scope.operations().contains(WriteScope.Operation.PATCH));
        delete.setSelected(scope.operations().contains(WriteScope.Operation.DELETE));
        types.setText(String.join(", ", scope.resourceTypes()));
        types.setPromptText("blank = all resource types, for example Patient, Observation");

        Label hint = new Label("Resource types are comma-separated. Leave the field blank"
                + " to allow every type. An empty operation list permits no writes.");
        hint.setWrapText(true);
        hint.getStyleClass().add("app-subtitle");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.add(create, 0, 0);
        grid.add(update, 1, 0);
        grid.add(patch, 0, 1);
        grid.add(delete, 1, 1);
        grid.add(new Label("Resource types"), 0, 2);
        grid.add(types, 1, 2);
        grid.add(hint, 0, 3, 2, 1);
        getDialogPane().setContent(grid);

        ButtonType apply = new ButtonType("Apply Scope", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().setAll(apply, ButtonType.CANCEL);
        setResultConverter(button -> button == apply ? selectedScope() : null);
    }

    private WriteScope selectedScope() {
        Set<WriteScope.Operation> operations = EnumSet.noneOf(WriteScope.Operation.class);
        if (create.isSelected()) {
            operations.add(WriteScope.Operation.CREATE);
        }
        if (update.isSelected()) {
            operations.add(WriteScope.Operation.UPDATE);
        }
        if (patch.isSelected()) {
            operations.add(WriteScope.Operation.PATCH);
        }
        if (delete.isSelected()) {
            operations.add(WriteScope.Operation.DELETE);
        }
        List<String> resourceTypes = new ArrayList<>();
        for (String type : types.getText().split("[,\\s]+")) {
            if (!type.isBlank()) {
                resourceTypes.add(type.trim());
            }
        }
        return WriteScope.of(operations, resourceTypes);
    }

    /** Shows the dialog and returns the chosen scope, or empty when the user cancels. */
    static Optional<WriteScope> choose(Window owner, WriteScope initial) {
        if (owner == null) {
            return Optional.empty();
        }
        return new WriteScopeDialog(owner, initial).showAndWait();
    }
}
