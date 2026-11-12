package com.spiraleyes.fhirviewer.ui;

import javafx.scene.control.DialogPane;

/**
 * Applies the active theme to a dialog and gives it the width the small dialogs share.
 *
 * <p>One copy on purpose. These same two lines were written into five classes -
 * `DocumentSession`, `ServerResourceActions`, `ServerScreens`, `MainWindow` and
 * `WorkspaceActions` - so every change to dialog theming had to be made five times, and the
 * copies drifted: two of them had already grown a second, inline copy beside the method.</p>
 */
final class DialogTheme {

    /** The width alerts, prompts and the small server dialogs open at. */
    static final double SMALL_WIDTH = 620;

    private DialogTheme() {
    }

    /**
     * Gives the pane the window's stylesheets and the small-dialog width.
     *
     * <p>The stylesheets are set rather than appended: applying the theme twice must not
     * leave two copies of the same sheet on one pane.</p>
     */
    static void apply(DialogPane pane, ThemeManager themeManager) {
        pane.getStylesheets().setAll(themeManager.stylesheets());
        pane.setPrefWidth(SMALL_WIDTH);
    }
}
