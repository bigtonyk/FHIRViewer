package com.example.fhirviewer.ui;

import java.io.IOException;

import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Read-only view that shows the resource rendered as FHIR JSON.
 *
 * <p>The application renders text itself with HAPI FHIR rather than using a web view,
 * so the displayed JSON is exactly what a FHIR parser produces. Wrapped in a card
 * with copy and reformat buttons; styling comes from <code>/css/app.css</code>.</p>
 */
public class JsonView extends VBox {

    private final TextArea area = new TextArea();
    private final Button copyButton = new Button("Copy");
    private final Button formatButton = new Button("Format");

    public JsonView() {
        getStyleClass().addAll("card", "code-view");
        setSpacing(0);

        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().addAll("mono-text", "code-area");
        VBox.setVgrow(area, Priority.ALWAYS);

        copyButton.getStyleClass().addAll("button-icon", "flat");
        copyButton.setOnAction(event -> copyToClipboard());

        formatButton.getStyleClass().addAll("button-icon", "flat");
        formatButton.setOnAction(event -> format());

        HBox toolbar = new HBox(copyButton, formatButton);
        toolbar.getStyleClass().add("code-toolbar");

        getChildren().addAll(toolbar, area);
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
