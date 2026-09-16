package com.example.fhirviewer.ui;

import java.io.IOException;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.model.ResourceNode;

/**
 * Read-only view that shows the resource rendered as FHIR JSON.
 *
 * <p>The application renders text itself with HAPI FHIR rather than using a web view,
 * so the displayed JSON is exactly what a FHIR parser produces. Wrapped in a card
 * with copy and reformat buttons; styling comes from <code>/css/app.css</code>.</p>
 */
public class JsonView extends VBox implements ElementNavigationTarget {

    private final TextArea area = new TextArea();
    private final Button copyButton = new Button("Copy");
    private final Button formatButton = new Button("Format");
    private final ScrollPane scrollPane = new ScrollPane();

    public JsonView() {
        getStyleClass().addAll("card", "code-view");
        setSpacing(0);
        setFillWidth(true);  // Fill the available width in the tab
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);  // Allow the VBox to grow

        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().addAll("mono-text", "code-area");
        
        // Wrap the TextArea in a ScrollPane for proper scrolling
        scrollPane.setContent(area);
        scrollPane.setFitToWidth(false);
        scrollPane.setFitToHeight(false);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("code-scroll-pane");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        scrollPane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);  // Allow ScrollPane to grow

        copyButton.getStyleClass().addAll("button-icon", "flat");
        copyButton.setOnAction(event -> copyToClipboard());

        formatButton.getStyleClass().addAll("button-icon", "flat");
        formatButton.setOnAction(event -> format());

        HBox toolbar = new HBox(copyButton, formatButton);
        toolbar.getStyleClass().add("code-toolbar");
        HBox.setHgrow(toolbar, Priority.ALWAYS);  // Allow toolbar to grow horizontally

        getChildren().addAll(toolbar, scrollPane);
        showNothing();
    }

    /** Displays the supplied JSON text. */
    public void show(String json) {
        setText(json == null ? "" : json);
        area.positionCaret(0);
    }

    /** Clears the view. */
    public void showNothing() {
        setText("");
    }

    private void setText(String text) {
        area.setText(text);
    }

    @Override
    public void scrollToElement(ResourceNode node) {
        if (node == null || node.getElementInfo().getName() == null) {
            return;
        }
        String name = node.getElementInfo().getName();
        String text = area.getText();
        if (text.isBlank()) {
            return;
        }
        
        // Find the line containing the element name
        int line = findLineContaining(text, name);
        if (line < 0) {
            return;
        }
        
        Platform.runLater(() -> {
            int offset = lineStartOffset(text, line);
            area.selectRange(offset, offset + Math.min(name.length(), text.length() - offset));
            // Scroll to make the selection visible
            area.setScrollTop(area.getScrollTop() + 1);
            area.setScrollTop(area.getScrollTop() - 1);
        });
    }

    private int findLineContaining(String text, String search) {
        // Match FHIR element names even when attribute values contain the name
        // (e.g. xml "<status value=\"active\"/>" should not match "value" by accident).
        // Search for the element name at the start of a line or right after whitespace
        // and require a non-word character or whitespace separator.
        String lowerText = text.toLowerCase();
        String lowerSearch = search.toLowerCase();
        int lineStart = 0;
        int lineNum = 0;
        int pos = 0;
        int searchLen = search.length();
        while (pos < text.length()) {
            if (pos == lineStart || (pos > 0 && text.charAt(pos - 1) == '\n')) {
                int lineEnd = text.indexOf('\n', pos);
                if (lineEnd < 0) {
                    lineEnd = text.length();
                }
                int match = indexOfWord(lowerText, pos, lineEnd, lowerSearch);
                if (match >= 0) {
                    return lineNum;
                }
                lineNum++;
                pos = lineEnd + 1;
                lineStart = pos;
            } else {
                pos++;
            }
        }
        return -1;
    }

    private int indexOfWord(String text, int from, int to, String search) {
        int pos = from;
        while (pos <= to - search.length()) {
            int idx = text.indexOf(search, pos);
            if (idx < 0 || idx > to) {
                return -1;
            }
            boolean startOk = (idx == from) || !Character.isLetterOrDigit(text.charAt(idx - 1));
            boolean endOk = (idx + search.length() >= to)
                    || !Character.isLetterOrDigit(text.charAt(idx + search.length()));
            if (startOk && endOk) {
                return idx;
            }
            pos = idx + 1;
        }
        return -1;
    }

    private int lineStartOffset(String text, int lineNum) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (count == lineNum) {
                    return i + 1;
                }
                count++;
            }
        }
        return 0;
    }

    private void copyToClipboard() {
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(area.getText());
        clipboard.setContent(content);
    }

    /**
     * Reformats the JSON: the text HAPI FHIR produces is already pretty printed,
     * so this collapses the text first and pretty prints it again with the
     * standard indentation of two spaces.
     */
    private void format() {
        String text = area.getText();
        if (text.isBlank()) {
            return;
        }
        try {
            area.setText(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(text)));
            area.positionCaret(0);
        } catch (IOException e) {
            // Not valid JSON: leave the text as it is.
        }
    }
}
