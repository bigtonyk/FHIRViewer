package com.example.fhirviewer.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.model.BundleEntryInfo;

/**
 * Lists the entries of a Bundle so the user can navigate into individual entry resources.
 *
 * <p>Selecting an entry reports it through the selection handler; clearing the list or
 * selecting "the Bundle itself" reports {@code null}, which means the whole Bundle
 * should be displayed.</p>
 */
public class BundleView extends VBox {

    private final Label header = new Label("No Bundle loaded.");
    private final CheckBox showWholeBundle = new CheckBox("Show the Bundle resource itself");
    private final ListView<BundleEntryInfo> entryList = new ListView<>();

    private Consumer<BundleEntryInfo> selectionHandler = entry -> { };
    private boolean updating;

    public BundleView() {
        setSpacing(6);
        setPadding(new Insets(8));

        header.setWrapText(true);
        showWholeBundle.setSelected(true);
        showWholeBundle.setDisable(true);
        entryList.setDisable(true);
        entryList.setCellFactory(view -> new EntryCell());
        VBox.setVgrow(entryList, Priority.ALWAYS);

        showWholeBundle.setOnAction(event -> onWholeBundleToggled());
        entryList.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (!updating && !showWholeBundle.isSelected() && selected != null) {
                selectionHandler.accept(selected);
            }
        });

        getChildren().addAll(header, showWholeBundle, entryList);
    }

    /** Sets the handler called when the shown resource should change. */
    public void setOnEntrySelected(Consumer<BundleEntryInfo> handler) {
        this.selectionHandler = handler == null ? entry -> { } : handler;
    }

    /**
     * Populates the view with the entries of a Bundle.
     *
     * @param entries the Bundle entries (may be empty)
     * @param label   a label describing the Bundle
     */
    public void showBundle(List<BundleEntryInfo> entries, String label) {
        updating = true;
        try {
            entryList.getItems().setAll(entries);
            entryList.getSelectionModel().clearSelection();
            header.setText(label + " contains " + entries.size()
                    + (entries.size() == 1 ? " entry." : " entries."));
            showWholeBundle.setDisable(false);
            showWholeBundle.setSelected(true);
            entryList.setDisable(true);
        } finally {
            updating = false;
        }
    }

    /** Resets the view when the loaded resource is not a Bundle. */
    public void clear() {
        updating = true;
        try {
            entryList.getItems().clear();
            entryList.getSelectionModel().clearSelection();
            entryList.setDisable(true);
            showWholeBundle.setSelected(true);
            showWholeBundle.setDisable(true);
            header.setText("Bundle navigation appears here when a Bundle resource is loaded.");
        } finally {
            updating = false;
        }
    }

    private void onWholeBundleToggled() {
        boolean wholeBundle = showWholeBundle.isSelected();
        entryList.setDisable(wholeBundle);
        if (wholeBundle) {
            entryList.getSelectionModel().clearSelection();
            selectionHandler.accept(null);
            return;
        }
        BundleEntryInfo selected = entryList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            selectionHandler.accept(selected);
        }
    }

    /** List cell that renders an entry as <code>[n] ResourceType/id</code>. */
    private static final class EntryCell extends ListCell<BundleEntryInfo> {

        @Override
        protected void updateItem(BundleEntryInfo item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(item.getDisplayText());
            getStyleClass().remove("app-subtitle");
            if (!item.hasResource()) {
                getStyleClass().add("app-subtitle");
            }
        }
    }
}