package com.example.fhirviewer.ui;

import javafx.scene.control.TextArea;
import javafx.scene.text.Font;

/**
 * Read-only view that shows the resource rendered as FHIR JSON.
 *
 * <p>The application renders text itself with HAPI FHIR rather than using a web view,
 * so the displayed JSON is exactly what a FHIR parser produces.</p>
 */
public class JsonView extends TextArea {

    public JsonView() {
        super();
        setEditable(false);
        setWrapText(false);
        setFont(Font.font("monospace", 12));
        getStyleClass().add("fhir-json-view");
    }

    /** Displays the supplied JSON text. */
    public void show(String json) {
        setText(json == null ? "" : json);
        positionCaret(0);
    }

    /** Clears the view. */
    public void showNothing() {
        setText("");
    }
}