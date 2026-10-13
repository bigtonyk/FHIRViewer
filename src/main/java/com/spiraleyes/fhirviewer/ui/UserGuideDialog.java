package com.spiraleyes.fhirviewer.ui;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The in-app user guide: the bundled server-functions guide as readable text.
 *
 * <p>A plain read-only text area rather than a web view, so no extra
 * dependency is needed and the guide works offline and in the packaged
 * image. The text comes from {@link UserGuideContent}, which is tested
 * without starting the toolkit.</p>
 */
public class UserGuideDialog extends Dialog<Void> {

    private final TextArea guideText = new TextArea();

    public UserGuideDialog(ThemeManager themeManager) {
        setTitle("User Guide");
        setHeaderText("FHIR Server Functions — User Guide");
        setResizable(true);

        guideText.setText(UserGuideContent.load());
        guideText.setEditable(false);
        guideText.setWrapText(true);
        guideText.setPrefRowCount(30);
        VBox.setVgrow(guideText, Priority.ALWAYS);

        VBox content = new VBox(guideText);
        content.setPrefWidth(720);
        content.setPrefHeight(560);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        getDialogPane().setPrefWidth(760);
        getDialogPane().setPrefHeight(600);
    }

    /** The read-only guide text, exposed for tests. */
    TextArea guideText() {
        return guideText;
    }
}
