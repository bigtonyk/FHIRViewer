package com.example.fhirviewer.ui;

import java.util.function.Consumer;

import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.Tooltip;

import com.example.fhirviewer.model.ResourceNode;

/**
 * Displays the FHIR resource hierarchy and reports the selected element.
 *
 * <p>The tree is rebuilt from a {@link ResourceNode} tree produced by the FHIR layer;
 * this class contains no FHIR knowledge at all.</p>
 */
public class ResourceTreeView extends TreeView<ResourceNode> {

    private Consumer<ResourceNode> selectionHandler = node -> { };

    public ResourceTreeView() {
        setShowRoot(true);
        setCellFactory(view -> new ResourceNodeCell());
        getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected != null) {
                selectionHandler.accept(selected.getValue());
            }
        });
    }

    /** Sets the handler called when a tree element is selected. */
    public void setOnNodeSelected(Consumer<ResourceNode> handler) {
        this.selectionHandler = handler == null ? node -> { } : handler;
    }

    /**
     * Replaces the tree with a new root node.
     *
     * @param root the tree to display, or {@code null} to clear the view
     */
    public void show(ResourceNode root) {
        if (root == null) {
            setRoot(null);
            return;
        }
        TreeItem<ResourceNode> rootItem = toTreeItem(root);
        setRoot(rootItem);
        rootItem.setExpanded(true);
    }

    /** Expands every node in the tree. */
    public void expandAll() {
        setExpanded(getRoot(), true);
    }

    /** Collapses all but the root node. */
    public void collapseAll() {
        setExpanded(getRoot(), false);
    }

    private TreeItem<ResourceNode> toTreeItem(ResourceNode node) {
        TreeItem<ResourceNode> item = new TreeItem<>(node);
        for (ResourceNode child : node.getChildren()) {
            item.getChildren().add(toTreeItem(child));
        }
        return item;
    }

    private void setExpanded(TreeItem<ResourceNode> item, boolean expanded) {
        if (item == null) {
            return;
        }
        item.setExpanded(expanded);
        for (TreeItem<ResourceNode> child : item.getChildren()) {
            setExpanded(child, expanded);
        }
    }

    /** Tree cell that renders the element text and shows metadata as a tooltip. */
    private static final class ResourceNodeCell extends TreeCell<ResourceNode> {

        @Override
        protected void updateItem(ResourceNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            setText(item.getDisplayText());
            setTooltip(new Tooltip(
                    item.getPath()
                            + "\nType: " + item.getElementInfo().getTypeCode()
                            + "\nCardinality: " + item.getElementInfo().getCardinality()));
        }
    }
}