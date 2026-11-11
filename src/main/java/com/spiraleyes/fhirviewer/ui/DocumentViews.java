package com.spiraleyes.fhirviewer.ui;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * The views a document is rendered into.
 *
 * <p>{@code MainWindow} builds and owns these objects in {@code buildLayout}, so they cannot
 * move with the document state; the session asks for them through this interface instead of
 * taking twelve constructor parameters. Nothing here is a second copy of anything: every
 * method returns the window's existing field.</p>
 */
interface DocumentViews {

    ResourceTreeView tree();

    DetailsView details();

    JsonView json();

    XmlView xml();

    PrettyView pretty();

    StatusView status();

    BundleView bundle();

    FhirPathView fhirPath();

    TabPane documentTabs();

    TabPane structureTabs();

    Tab treeTab();

    Tab prettyTab();

    /** Whether the "show unpopulated elements" toggle is on. */
    boolean showUnpopulated();
}
