package com.spiraleyes.fhirviewer.ui;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The in-app user guide: the bundled server-functions guide rendered
 * as formatted text.
 *
 * <p>Native controls rather than a web view, so no extra dependency is
 * needed and the guide works offline and in the packaged image. The
 * markdown is parsed by {@link UserGuideDocument} and rendered by
 * {@link UserGuideView}; both are covered without starting the
 * toolkit where possible.</p>
 */
public class UserGuideDialog extends Dialog<Void> {

    private final UserGuideView guideView = new UserGuideView();

    public UserGuideDialog(ThemeManager themeManager) {
        setTitle("User Guide");
        setHeaderText("FHIR Resource Viewer — User Guide");
        setResizable(true);

        guideView.show(UserGuideDocument.parse(UserGuideContent.load()));
        guideView.setLinkOpener(target -> guideView.scrollToAnchor(target));
        VBox.setVgrow(guideView, Priority.ALWAYS);

        VBox content = new VBox(guideView);
        content.setPrefWidth(720);
        content.setPrefHeight(560);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        getDialogPane().setPrefWidth(760);
        getDialogPane().setPrefHeight(600);
    }

    /** The rendered guide view, exposed for tests. */
    UserGuideView guideView() {
        return guideView;
    }
}
