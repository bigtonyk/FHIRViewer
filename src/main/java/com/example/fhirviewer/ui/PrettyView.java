package com.example.fhirviewer.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.model.ResourceNode;
import com.example.fhirviewer.pretty.PrettyBlock;
import com.example.fhirviewer.pretty.PrettyDocument;
import com.example.fhirviewer.pretty.PrettyRow;

/**
 * The Pretty View: renders the presentation model of a resource
 * ({@link PrettyDocument}) as an easy to read, human friendly document.
 *
 * <p>Like the rest of the UI this class contains no FHIR knowledge; it only
 * knows how to lay out sections, rows and nested blocks. All styling comes
 * from <code>/css/app.css</code> (classes <code>pretty-*</code>).</p>
 */
public class PrettyView extends ScrollPane implements ElementNavigationTarget {

    private static final String EMPTY_MESSAGE = "Open a FHIR resource to see it here.";

    private final VBox content = new VBox(12);

    public PrettyView() {
        content.getStyleClass().add("pretty-content");
        setContent(content);
        setFitToWidth(false);  // Allow horizontal scrolling for long content
        setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);  // Allow the ScrollPane to grow
        getStyleClass().add("pretty-view");
        showNothing();
    }

    /** Renders the supplied document. */
    public void show(PrettyDocument document) {
        if (document == null) {
            showNothing();
            return;
        }
        content.getChildren().setAll(headerNode(document));
        for (PrettyBlock section : document.sections()) {
            content.getChildren().add(sectionNode(section, 0));
        }
    }

    /** Resets the view to its empty state. */
    public void showNothing() {
        Label empty = new Label(EMPTY_MESSAGE);
        empty.getStyleClass().add("pretty-empty");
        content.getChildren().setAll(empty);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** The document header: resource type, id and the resource level metadata rows. */
    private Node headerNode(PrettyDocument document) {
        VBox header = new VBox(6);
        header.getStyleClass().add("pretty-header");

        Label title = new Label(document.resourceType()
                + (document.resourceId() == null || document.resourceId().isBlank()
                        ? ""
                        : " — " + document.resourceId()));
        title.getStyleClass().add("pretty-header-title");
        header.getChildren().add(title);

        if (!document.headerRows().isEmpty()) {
            header.getChildren().add(rowGrid(document.headerRows()));
        }
        return header;
    }

    /** One section with its heading, rows and nested child sections. */
    private Node sectionNode(PrettyBlock block, int depth) {
        VBox section = new VBox(4);
        section.getStyleClass().add(depth > 0 ? "pretty-section-nested" : "pretty-section");
        if (!block.title().isEmpty()) {
            Label heading = new Label(block.title());
            heading.getStyleClass().add("pretty-section-title");
            section.getChildren().add(heading);
        }
        if (!block.rows().isEmpty()) {
            section.getChildren().add(rowGrid(block.rows()));
        }
        for (PrettyBlock child : block.children()) {
            section.getChildren().add(sectionNode(child, depth + 1));
        }
        return section;
    }

    /** Label/value rows as a two column grid with wrapping values. */
    private GridPane rowGrid(Iterable<PrettyRow> rows) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(3);

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(110);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, valueColumn);

        int row = 0;
        for (PrettyRow entry : rows) {
            if (entry.label().isEmpty()) {
                Label value = valueLabel(entry.value());
                grid.add(value, 0, row, 2, 1);
            } else {
                Label label = new Label(entry.label());
                label.getStyleClass().add("pretty-row-label");
                grid.add(label, 0, row);
                grid.add(valueLabel(entry.value()), 1, row);
            }
            row++;
        }
        return grid;
    }

    private Label valueLabel(String value) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add("pretty-row-value");
        return label;
    }

    @Override
    public void scrollToElement(ResourceNode node) {
        if (node == null || node.getElementInfo().getName() == null) {
            return;
        }
        String name = node.getElementInfo().getName();
        
        Platform.runLater(() -> {
            // Find the label with matching text
            for (Node child : content.getChildren()) {
                if (child instanceof Label label) {
                    if (label.getText() != null && label.getText().contains(name)) {
                        // Scroll this label into view by setting the vvalue
                        double targetY = label.getBoundsInParent().getMinY();
                        double viewportHeight = getViewportBounds().getHeight();
                        double contentHeight = content.getBoundsInParent().getHeight();
                        
                        if (contentHeight > viewportHeight) {
                            double v = Math.max(0, Math.min(targetY / (contentHeight - viewportHeight), 1.0));
                            setVvalue(v);
                        }
                        return;
                    }
                }
                // Also check nested containers
                if (child instanceof GridPane grid) {
                    for (Node gridChild : grid.getChildren()) {
                        if (gridChild instanceof Label gridLabel && 
                            gridLabel.getText() != null && gridLabel.getText().contains(name)) {
                            double targetY = gridLabel.getBoundsInParent().getMinY();
                            double viewportHeight = getViewportBounds().getHeight();
                            double contentHeight = content.getBoundsInParent().getHeight();
                            
                            if (contentHeight > viewportHeight) {
                                double v = Math.max(0, Math.min(targetY / (contentHeight - viewportHeight), 1.0));
                                setVvalue(v);
                            }
                            return;
                        }
                    }
                }
            }
        });
    }
}