package com.example.fhirviewer.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
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

    /** Style class of the label the resource tree selection jumped to. */
    private static final String HIGHLIGHT_CLASS = "pretty-highlight";

    /** Node property key under which a label remembers the element it was built from. */
    private static final Object ELEMENT_NAME_KEY = new Object();

    private final VBox content = new VBox(12);

    /** The label highlighted by the last tree selection, if any. */
    private Label highlighted;

    public PrettyView() {
        content.getStyleClass().add("pretty-content");
        setContent(content);
        // Stretch the document to the width of the pane; long values wrap because
        // every rendered label has wrapping enabled.
        setFitToWidth(true);
        setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);  // Allow the ScrollPane to grow
        getStyleClass().add("pretty-view");
        showNothing();
    }

    /** Renders the supplied document. */
    public void show(PrettyDocument document) {
        // The previous highlight belongs to the document that was replaced.
        clearHighlight();
        if (document == null) {
            showNothing();
            return;
        }
        List<Node> children = new ArrayList<>();
        children.add(headerNode(document));
        if (!document.rows().isEmpty()) {
            // Document level rows: the top level primitive elements of the resource.
            VBox summary = new VBox(4);
            summary.getStyleClass().add("pretty-section");
            summary.getChildren().add(rowGrid(document.rows()));
            children.add(summary);
        }
        for (PrettyBlock section : document.sections()) {
            children.add(sectionNode(section, 0));
        }
        content.getChildren().setAll(children);
    }

    /** Resets the view to its empty state. */
    public void showNothing() {
        clearHighlight();
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
        tagWithElement(title, document.resourceType());
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
            tagWithElement(heading, block.elementName());
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
                tagWithElement(value, entry.elementName());
                grid.add(value, 0, row, 2, 1);
            } else {
                Label label = new Label(entry.label());
                label.getStyleClass().add("pretty-row-label");
                tagWithElement(label, entry.elementName());
                grid.add(label, 0, row);
                Label value = valueLabel(entry.value());
                tagWithElement(value, entry.elementName());
                grid.add(value, 1, row);
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

    /** Remembers which FHIR element a rendered label belongs to. */
    private static void tagWithElement(Label label, String elementName) {
        if (elementName != null && !elementName.isBlank()) {
            label.getProperties().put(ELEMENT_NAME_KEY, elementName);
        }
    }

    @Override
    public void scrollToElement(ResourceNode node) {
        if (node == null || node.getElementInfo().getName() == null
                || node.getElementInfo().getName().isBlank()) {
            return;
        }
        String elementName = node.getElementInfo().getName();
        Platform.runLater(() -> {
            Label target = findLabel(elementName);
            if (target == null) {
                target = findAncestorLabel(node);
            }
            if (target == null) {
                // Nothing in the document corresponds to this element; drop the
                // highlight of the previous selection instead of keeping it stale.
                clearHighlight();
                return;
            }
            highlight(target);
            if (isVisible() && getWidth() > 0) {
                scrollToShow(target);
            }
        });
    }

    /**
     * Looks for the rendered label of the closest ancestor that is not a resource.
     *
     * <p>The pretty view summarizes values (a <code>HumanName</code> becomes the
     * single row <code>Name: Alex Nguyen</code>), so an element such as
     * <code>family</code> has no row of its own but is part of the rendered
     * <code>Name</code> row.</p>
     */
    private Label findAncestorLabel(ResourceNode node) {
        for (ResourceNode ancestor = node.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor.getElementInfo().isResource()) {
                return null;
            }
            Label target = findLabel(ancestor.getElementInfo().getName());
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    /**
     * Finds the rendered label that belongs to the selected tree element.
     *
     * <p>The rendering tags every label with the FHIR element it was built from
     * (see {@link #tagWithElement}), so the match is exact. When a rendered label
     * carries no tag (a header title, for example) the text is compared the way
     * the model spells it: <code>birthDate</code> is rendered as
     * <code>Birth Date</code>, so the comparison ignores case and punctuation.
     * A section title may carry a trailing index or a resource identity, so a
     * prefix match that ends on a word boundary is accepted as well.</p>
     */
    private Label findLabel(String elementName) {
        String needle = normalize(elementName);
        if (needle.isEmpty()) {
            return null;
        }
        for (Label label : labels()) {
            if (needle.equals(elementNameOf(label))) {
                return label;
            }
        }
        // Section titles such as "Entry 1" or "Name 2 — Patient/x" start with the
        // element name and continue with a number or another non letter.
        for (Label label : labels()) {
            if (!label.getStyleClass().contains("pretty-section-title")) {
                continue;
            }
            String text = elementNameOf(label);
            if (text.startsWith(needle) && text.length() > needle.length()
                    && !Character.isLetter(text.charAt(needle.length()))) {
                return label;
            }
        }
        return null;
    }

    /** Every label of the rendered document, in document order. */
    private List<Label> labels() {
        List<Label> found = new ArrayList<>();
        collectLabels(content, found);
        return found;
    }

    private static void collectLabels(Parent parent, List<Label> found) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Label label) {
                found.add(label);
            }
            if (child instanceof Parent nested) {
                collectLabels(nested, found);
            }
        }
    }

    /** Marks the label as the target of the current tree selection. */
    private void highlight(Label label) {
        if (highlighted == label) {
            return;
        }
        clearHighlight();
        highlighted = label;
        highlighted.getStyleClass().add(HIGHLIGHT_CLASS);
    }

    /** Removes the highlight of a previous tree selection. */
    private void clearHighlight() {
        if (highlighted != null) {
            highlighted.getStyleClass().remove(HIGHLIGHT_CLASS);
            highlighted = null;
        }
    }

    /** Scrolls so the given label appears near the top of the viewport. */
    private void scrollToShow(Label label) {
        Bounds bounds = content.sceneToLocal(label.localToScene(label.getBoundsInLocal()));
        if (bounds == null) {
            return;
        }
        double viewportHeight = getViewportBounds().getHeight();
        double scrollable = content.getHeight() - viewportHeight;
        if (scrollable <= 0) {
            setVvalue(0);
            return;
        }
        double offset = Math.max(0, bounds.getMinY() - 8);
        setVvalue(Math.min(1, offset / scrollable));
    }

    /** The element a label was rendered from, or its text in model spelling. */
    private static String elementNameOf(Label label) {
        String tagged = (String) label.getProperties().get(ELEMENT_NAME_KEY);
        if (tagged != null && !tagged.isBlank()) {
            return normalize(tagged);
        }
        return normalize(label.getText());
    }

    /** Lower case letters and digits only, so <code>Birth Date</code> and
     * <code>birthDate</code> compare equal. */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                builder.append(Character.toLowerCase(character));
            }
        }
        return builder.toString();
    }
}