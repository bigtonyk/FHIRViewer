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
    private Consumer<ResourceNode> referenceHandler = node -> { };
    /** The full tree currently loaded, kept so the search filter can rebuild from it. */
    private ResourceNode rootNode;

    public ResourceTreeView() {
        setShowRoot(true);
        setCellFactory(view -> new ResourceNodeCell());
        getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected != null) {
                selectionHandler.accept(selected.getValue());
            }
        });
        setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<ResourceNode> selected = getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue() != null) {
                    referenceHandler.accept(selected.getValue());
                }
            }
        });
    }

    /** Sets the handler called when a tree element is selected. */
    public void setOnNodeSelected(Consumer<ResourceNode> handler) {
        this.selectionHandler = handler == null ? node -> { } : handler;
    }

    /**
     * Sets the handler called when an element is activated (double click). Used
     * for navigating FHIR references to their target in the loaded data set.
     */
    public void setOnReferenceActivated(Consumer<ResourceNode> handler) {
        this.referenceHandler = handler == null ? node -> { } : handler;
    }

    /**
     * Replaces the tree with a new root node.
     *
     * @param root the tree to display, or {@code null} to clear the view
     */
    public void show(ResourceNode root) {
        rootNode = root;
        if (root == null) {
            setRoot(null);
            return;
        }
        TreeItem<ResourceNode> rootItem = toTreeItem(root, null);
        setRoot(rootItem);
        rootItem.setExpanded(true);
    }

    /**
     * Filters the tree to the nodes whose text contains the query (case
     * insensitive), keeping the ancestors of every match so the results stay
     * reachable. An empty query restores the full tree.
     */
    public void applyFilter(String query) {
        if (rootNode == null) {
            return;
        }
        String needle = query == null ? "" : query.trim().toLowerCase();
        TreeItem<ResourceNode> rootItem = toTreeItem(rootNode, needle.isEmpty() ? null : needle);
        setRoot(rootItem);
        rootItem.setExpanded(true);
        if (!needle.isEmpty()) {
            expandMatching(rootItem);
        }
    }

    private void expandMatching(TreeItem<ResourceNode> item) {
        item.setExpanded(true);
        for (TreeItem<ResourceNode> child : item.getChildren()) {
            expandMatching(child);
        }
    }

    private TreeItem<ResourceNode> toTreeItem(ResourceNode node, String filter) {
        TreeItem<ResourceNode> item = new TreeItem<>(node);
        for (ResourceNode child : node.getChildren()) {
            TreeItem<ResourceNode> childItem = toTreeItem(child, filter);
            if (filter == null || matches(childItem, filter)) {
                item.getChildren().add(childItem);
            }
        }
        return item;
    }

    /** True when the item itself or one of its descendants matches the filter. */
    private boolean matches(TreeItem<ResourceNode> item, String filter) {
        ResourceNode node = item.getValue();
        if (node != null && node.getDisplayText().toLowerCase().contains(filter)) {
            return true;
        }
        for (TreeItem<ResourceNode> child : item.getChildren()) {
            if (matches(child, filter)) {
                return true;
            }
        }
        return false;
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