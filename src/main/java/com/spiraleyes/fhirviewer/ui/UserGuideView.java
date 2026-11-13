package com.spiraleyes.fhirviewer.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Renders a parsed {@link UserGuideDocument} as JavaFX nodes: headings,
 * paragraphs, lists, tables, quotes, code blocks and rules with inline
 * bold, italic, code and links.
 *
 * <p>Native controls rather than a web view, so no extra dependency is
 * needed and the guide works offline and in the packaged image. All
 * styling comes from {@code /css/app.css} (classes {@code guide-*}),
 * which read the theme tokens, so light and dark themes restyle the
 * guide with the rest of the application.</p>
 */
public class UserGuideView extends ScrollPane {

    private final VBox content = new VBox(8);
    private final Map<String, Node> anchors = new LinkedHashMap<>();
    private final List<GuideHeading> headings = new ArrayList<>();
    private final Deque<Double> backPositions = new ArrayDeque<>();
    private final List<SearchBlock> searchBlocks = new ArrayList<>();
    private final List<Node> searchHits = new ArrayList<>();
    private String searchQuery = "";
    private int searchIndex = -1;
    private Node highlighted;
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

    /** A rendered section heading, used by the dialog's contents menu. */
    public record GuideHeading(String title, String anchor) {
    }

    /** Renders the supplied blocks, replacing any previous content. */
    public void show(List<UserGuideDocument.Block> blocks) {
        anchors.clear();
        headings.clear();
        backPositions.clear();
        searchBlocks.clear();
        searchHits.clear();
        searchQuery = "";
        searchIndex = -1;
        highlighted = null;
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
            } else if (block instanceof UserGuideDocument.CodeBlock code) {
                children.add(codeBlockNode(code));
            } else if (block instanceof UserGuideDocument.Rule) {
                Separator rule = new Separator();
                rule.getStyleClass().add("guide-rule");
                rule.setMaxWidth(Double.MAX_VALUE);
                children.add(rule);
            }
        }
        content.getChildren().setAll(children);
        for (int i = 0; i < children.size() && i < blocks.size(); i++) {
            searchBlocks.add(new SearchBlock(children.get(i), blockText(blocks.get(i))));
        }
    }

    /** The headings currently rendered, in document order. */
    public List<GuideHeading> headings() {
        return List.copyOf(headings);
    }

    private Node headingNode(UserGuideDocument.Heading heading) {
        TextFlow flow = inlineFlow(heading.spans());
        flow.getStyleClass().add("guide-heading-" + Math.min(Math.max(heading.level(), 1), 4));
        String anchor = UserGuideDocument.anchorOf(heading);
        if (!anchor.isEmpty() && anchors.putIfAbsent(anchor, flow) == null) {
            headings.add(new GuideHeading(UserGuideDocument.plainText(heading.spans()), anchor));
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

    private Node codeBlockNode(UserGuideDocument.CodeBlock code) {
        // A TextArea keeps the block's newlines and indentation, can be selected
        // with the mouse, and does not collapse the sample into one run-on
        // paragraph. Copy uses the source, not whatever the soft wrap displays.
        String display = code.text().replace("\t", "    ");
        TextArea area = new TextArea(display);
        area.getStyleClass().add("guide-code-block-area");
        area.setEditable(false);
        area.setWrapText(true);
        area.setFocusTraversable(true);
        int lines = Math.max(1, display.split("\n", -1).length);
        area.setPrefRowCount(Math.min(18, Math.max(3, lines)));
        area.setMaxHeight(360);

        Button copy = new Button("Copy");
        copy.getStyleClass().addAll("button-ghost", "guide-copy-button");
        copy.setTooltip(new Tooltip("Copy this sample to the clipboard"));
        copy.setOnAction(event -> {
            ClipboardContent clip = new ClipboardContent();
            // The source form is what a reader pastes; tabs are already shown as spaces.
            clip.putString(display);
            Clipboard.getSystemClipboard().setContent(clip);
            copy.setText("Copied");
        });
        HBox bar = new HBox(copy);
        bar.getStyleClass().add("guide-code-bar");
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox block = new VBox(4, bar, area);
        block.getStyleClass().add("guide-code-block");
        return block;
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
        backPositions.push(getVvalue());
        scrollTo(target);
    }

    /** True after an anchor jump; Back has somewhere to return to. */
    public boolean canGoBack() {
        return !backPositions.isEmpty();
    }

    /** Returns to the viewport position before the last anchor jump. */
    public boolean goBack() {
        if (backPositions.isEmpty()) {
            return false;
        }
        setVvalue(backPositions.pop());
        return true;
    }

    /**
     * Moves to the next or previous block containing {@code query}.
     *
     * @return the one-based matching-block number, or zero when there is no match
     */
    public int search(String query, boolean forward) {
        String normalized = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            clearSearch();
            return 0;
        }
        if (!normalized.equals(searchQuery)) {
            rebuildSearch(normalized);
        }
        if (searchHits.isEmpty()) {
            clearHighlight();
            searchIndex = -1;
            return 0;
        }
        if (forward) {
            searchIndex = (searchIndex + 1) % searchHits.size();
        } else {
            searchIndex = searchIndex <= 0 ? searchHits.size() - 1 : searchIndex - 1;
        }
        Node hit = searchHits.get(searchIndex);
        highlight(hit);
        scrollTo(hit);
        return searchIndex + 1;
    }

    /** The number of matching blocks Previous/Next can visit. */
    public int searchMatchCount() {
        return searchHits.size();
    }

    private void rebuildSearch(String normalized) {
        searchQuery = normalized;
        searchHits.clear();
        searchIndex = -1;
        for (SearchBlock block : searchBlocks) {
            String text = block.text().toLowerCase(Locale.ROOT);
            // One stop per block: Previous/Next move the viewport to a block, so
            // repeating an occurrence inside one paragraph must not add extra stops.
            if (text.contains(normalized)) {
                searchHits.add(block.node());
            }
        }
    }

    private void clearSearch() {
        searchQuery = "";
        searchHits.clear();
        searchIndex = -1;
        clearHighlight();
    }

    private void highlight(Node node) {
        clearHighlight();
        highlighted = node;
        node.getStyleClass().add("guide-search-hit");
    }

    private void clearHighlight() {
        if (highlighted != null) {
            highlighted.getStyleClass().remove("guide-search-hit");
            highlighted = null;
        }
    }

    private void scrollTo(Node target) {
        applyCss();
        layout();
        double y = target.getBoundsInParent().getMinY() + content.getBoundsInParent().getMinY();
        double height = getViewportBounds().getHeight();
        double value = y / Math.max(1, content.getHeight() - height);
        setVvalue(Math.min(Math.max(value, 0), 1));
    }

    private static String blockText(UserGuideDocument.Block block) {
        if (block instanceof UserGuideDocument.Heading heading) {
            return UserGuideDocument.plainText(heading.spans());
        }
        if (block instanceof UserGuideDocument.Paragraph paragraph) {
            return UserGuideDocument.plainText(paragraph.spans());
        }
        if (block instanceof UserGuideDocument.Quote quote) {
            return UserGuideDocument.plainText(quote.spans());
        }
        if (block instanceof UserGuideDocument.CodeBlock code) {
            return code.text();
        }
        if (block instanceof UserGuideDocument.BulletList list) {
            return listText(list.items());
        }
        if (block instanceof UserGuideDocument.OrderedList list) {
            return listText(list.items());
        }
        if (block instanceof UserGuideDocument.Table table) {
            StringBuilder text = new StringBuilder();
            for (List<UserGuideDocument.Span> cell : table.header()) {
                text.append(UserGuideDocument.plainText(cell)).append(' ');
            }
            for (List<List<UserGuideDocument.Span>> row : table.rows()) {
                for (List<UserGuideDocument.Span> cell : row) {
                    text.append(UserGuideDocument.plainText(cell)).append(' ');
                }
            }
            return text.toString();
        }
        return "";
    }

    private static String listText(List<UserGuideDocument.ListItem> items) {
        StringBuilder text = new StringBuilder();
        for (UserGuideDocument.ListItem item : items) {
            text.append(UserGuideDocument.plainText(item.spans())).append(' ');
            text.append(listText(item.children()));
        }
        return text.toString();
    }

    private record SearchBlock(Node node, String text) {
    }
}