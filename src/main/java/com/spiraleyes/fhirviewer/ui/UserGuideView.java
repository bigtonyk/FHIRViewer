package com.spiraleyes.fhirviewer.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Renders a parsed {@link UserGuideDocument} as JavaFX nodes: headings,
 * paragraphs, lists, tables, quotes and rules with inline bold, italic,
 * code and links.
 *
 * <p>Native controls rather than a web view, so no extra dependency is
 * needed and the guide works offline and in the packaged image. All
 * styling comes from {@code /css/app.css} (classes {@code guide-*}),
 * which read the theme tokens, so light and dark themes restyle the
 * guide with the rest of the application.</p>
 */
public class UserGuideView extends ScrollPane {

    private final VBox content = new VBox(8);
    private final Map<String, Node> anchors = new HashMap<>();
    private Consumer<String> linkOpener = target -> {
    };

    public UserGuideView() {
        content.getStyleClass().add("guide-content");
        content.setPadding(new Insets(4, 12, 12, 4));
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        getStyleClass().add("guide-view");
    }

    /** Where in-guide links go; MainWindow scrolls, external targets open. */
    public void setLinkOpener(Consumer<String> linkOpener) {
        this.linkOpener = linkOpener == null ? target -> {
        } : linkOpener;
    }

    /** Renders the supplied blocks, replacing any previous content. */
    public void show(List<UserGuideDocument.Block> blocks) {
        anchors.clear();
        List<Node> children = new ArrayList<>(blocks.size());
        for (UserGuideDocument.Block block : blocks) {
            if (block instanceof UserGuideDocument.Paragraph paragraph) {
                children.add(paragraphNode(paragraph.spans(), "guide-paragraph"));
            } else if (block instanceof UserGuideDocument.Heading heading) {
                children.add(headingNode(heading));
            } else if (block instanceof UserGuideDocument.BulletList list) {
                children.add(bulletListNode(list.items()));
            } else if (block instanceof UserGuideDocument.OrderedList ordered) {
                children.add(orderedListNode(ordered));
            } else if (block instanceof UserGuideDocument.Table table) {
                children.add(tableNode(table));
            } else if (block instanceof UserGuideDocument.Quote quote) {
                children.add(quoteNode(quote));
            } else if (block instanceof UserGuideDocument.Rule) {
                Separator rule = new Separator();
                rule.getStyleClass().add("guide-rule");
                rule.setMaxWidth(Double.MAX_VALUE);
                children.add(rule);
            }
        }
        content.getChildren().setAll(children);
    }

    private Node headingNode(UserGuideDocument.Heading heading) {
        TextFlow flow = inlineFlow(heading.spans());
        flow.getStyleClass().add("guide-heading-" + Math.min(Math.max(heading.level(), 1), 4));
        String anchor = UserGuideDocument.anchorOf(heading);
        if (!anchor.isEmpty()) {
            anchors.putIfAbsent(anchor, flow);
        }
        return flow;
    }

    private Node paragraphNode(List<UserGuideDocument.Span> spans, String styleClass) {
        TextFlow flow = inlineFlow(spans);
        flow.getStyleClass().add(styleClass);
        return flow;
    }

    private Node bulletListNode(List<UserGuideDocument.ListItem> items) {
        VBox list = new VBox(4);
        list.getStyleClass().add("guide-list");
        for (UserGuideDocument.ListItem item : items) {
            list.getChildren().add(listItemNode("\u2022", item));
        }
        return list;
    }

    private Node orderedListNode(UserGuideDocument.OrderedList ordered) {
        VBox list = new VBox(4);
        list.getStyleClass().add("guide-list");
        int number = ordered.start();
        for (UserGuideDocument.ListItem item : ordered.items()) {
            list.getChildren().add(listItemNode(number + ".", item));
            number++;
        }
        return list;
    }

    private Node listItemNode(String marker, UserGuideDocument.ListItem item) {
        Label bullet = new Label(marker);
        bullet.getStyleClass().add("guide-bullet");
        TextFlow text = inlineFlow(item.spans());
        text.getStyleClass().add("guide-item-text");
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(8, bullet, text);
        row.getStyleClass().add("guide-item");
        javafx.scene.layout.HBox.setHgrow(text, Priority.ALWAYS);
        if (item.children().isEmpty()) {
            return row;
        }
        VBox stacked = new VBox(4, row);
        stacked.getStyleClass().add("guide-nested");
        VBox nested = new VBox(4);
        nested.getStyleClass().add("guide-nested-list");
        for (UserGuideDocument.ListItem child : item.children()) {
            nested.getChildren().add(listItemNode("\u2022", child));
        }
        stacked.getChildren().add(nested);
        return stacked;
    }

    private Node tableNode(UserGuideDocument.Table table) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("guide-table");
        // A TextFlow with no width wraps every character onto its own line,
        // which is the vertical-letter stacking the first version showed.
        // The first column gets a fixed share so the label reads as a word;
        // the rest splits evenly. Two-column guide tables are the common case.
        int columns = Math.max(table.header().size(),
                table.rows().stream().mapToInt(List::size).max().orElse(0));
        for (int column = 0; column < columns; column++) {
            ColumnConstraints constraint = new ColumnConstraints();
            if (column == 0) {
                constraint.setPercentWidth(28);
                constraint.setHgrow(Priority.NEVER);
            } else {
                constraint.setPercentWidth(72.0 / Math.max(1, columns - 1));
                constraint.setHgrow(Priority.ALWAYS);
            }
            constraint.setFillWidth(true);
            constraint.setMinWidth(60);
            grid.getColumnConstraints().add(constraint);
        }
        for (int column = 0; column < columns; column++) {
            List<UserGuideDocument.Span> cell = column < table.header().size()
                    ? table.header().get(column)
                    : List.of();
            TextFlow header = inlineFlow(cell);
            header.getStyleClass().add("guide-table-header");
            grid.add(header, column, 0);
        }
        int row = 1;
        for (List<List<UserGuideDocument.Span>> bodyRow : table.rows()) {
            for (int column = 0; column < columns; column++) {
                List<UserGuideDocument.Span> cell = column < bodyRow.size()
                        ? bodyRow.get(column)
                        : List.of();
                TextFlow value = inlineFlow(cell);
                value.getStyleClass().add("guide-table-cell");
                grid.add(value, column, row);
            }
            row++;
        }
        return grid;
    }

    private Node quoteNode(UserGuideDocument.Quote quote) {
        TextFlow flow = inlineFlow(quote.spans());
        flow.getStyleClass().add("guide-quote-text");
        javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(flow);
        box.getStyleClass().add("guide-quote");
        javafx.scene.layout.HBox.setHgrow(flow, Priority.ALWAYS);
        return box;
    }

    private TextFlow inlineFlow(List<UserGuideDocument.Span> spans) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("guide-flow");
        for (Node child : inlineNodes(spans, false, false)) {
            flow.getChildren().add(child);
        }
        return flow;
    }

    private List<Node> inlineNodes(List<UserGuideDocument.Span> spans, boolean bold, boolean italic) {
        List<Node> nodes = new ArrayList<>();
        for (UserGuideDocument.Span span : spans) {
            if (span instanceof UserGuideDocument.Text typed) {
                nodes.add(styledText(typed.text(), bold, italic, null));
            } else if (span instanceof UserGuideDocument.Code typed) {
                nodes.add(styledText(typed.text(), bold, italic, "guide-code"));
            } else if (span instanceof UserGuideDocument.Strong typed) {
                nodes.addAll(inlineNodes(typed.inner(), true, italic));
            } else if (span instanceof UserGuideDocument.Emphasis typed) {
                nodes.addAll(inlineNodes(typed.inner(), bold, true));
            } else if (span instanceof UserGuideDocument.Link typed) {
                nodes.add(linkNode(typed));
            }
        }
        return nodes;
    }

    private Text styledText(String text, boolean bold, boolean italic, String extraClass) {
        Text node = new Text(text);
        node.getStyleClass().add("guide-text");
        if (bold) {
            node.getStyleClass().add("guide-bold");
        }
        if (italic) {
            node.getStyleClass().add("guide-italic");
        }
        if (extraClass != null) {
            node.getStyleClass().add(extraClass);
        }
        return node;
    }

    private Node linkNode(UserGuideDocument.Link link) {
        String label = UserGuideDocument.plainText(link.inner());
        Hyperlink hyperlink = new Hyperlink(label);
        hyperlink.getStyleClass().add("guide-link");
        hyperlink.setOnAction(event -> linkOpener.accept(link.target()));
        return hyperlink;
    }

    /** Scrolls to the heading for an in-guide anchor, if present. */
    public void scrollToAnchor(String anchor) {
        String key = anchor.startsWith("#") ? anchor.substring(1) : anchor;
        Node target = anchors.get(key);
        if (target == null) {
            return;
        }
        applyCss();
        layout();
        double y = target.getBoundsInParent().getMinY() + content.getBoundsInParent().getMinY();
        double height = getViewportBounds().getHeight();
        double value = y / Math.max(1, content.getHeight() - height);
        setVvalue(Math.min(Math.max(value, 0), 1));
    }
}