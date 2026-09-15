package com.example.fhirviewer.ui;

import javafx.scene.control.TextArea;

/**
 * Read-only view that shows the resource rendered as FHIR XML.
 */
public class XmlView extends TextArea {

    public XmlView() {
        super();
        setEditable(false);
        setWrapText(false);
        getStyleClass().addAll("card", "mono-text", "fhir-xml-view");
    }

    /** Displays the supplied XML text. */
    public void show(String xml) {
        setText(xml == null ? "" : xml);
        positionCaret(0);
    }

    /** Clears the view. */
    public void showNothing() {
        setText("");
    }
}