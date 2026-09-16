package com.example.fhirviewer.ui;

import javafx.application.Platform;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.example.fhirviewer.model.ResourceNode;

/**
 * Read-only view that shows the resource rendered as FHIR XML.
 */
public class XmlView extends VBox implements ElementNavigationTarget {

    private final TextArea area = new TextArea();
    private final ScrollPane scrollPane = new ScrollPane();

    public XmlView() {
        getStyleClass().addAll("card", "mono-text", "fhir-xml-view");
        setFillWidth(true);  // Fill the available width in the tab
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);  // Allow the VBox to grow
        
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().addAll("mono-text", "code-area");
        
        // The editor fills the whole pane; its own scroll bars handle text that does
        // not fit (fitToWidth/fitToHeight keep the wrapper from shrinking the editor).
        scrollPane.setContent(area);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("code-scroll-pane");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        scrollPane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);  // Allow ScrollPane to grow
        
        getChildren().add(scrollPane);
    }

    /** Displays the supplied XML text. */
    public void show(String xml) {
        setText(xml == null ? "" : xml);
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
        
        int line = findLineContaining(text, name);
        if (line < 0) {
            return;
        }
        
        Platform.runLater(() -> {
            int offset = lineStartOffset(text, line);
            area.selectRange(offset, offset + Math.min(name.length(), text.length() - offset));
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
}