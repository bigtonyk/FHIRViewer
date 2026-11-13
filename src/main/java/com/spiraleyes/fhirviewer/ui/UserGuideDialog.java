package com.spiraleyes.fhirviewer.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Window;

/**
 * The in-app user guide: a bundled markdown guide rendered as formatted text.
 *
 * <p>Native controls rather than a web view, so no extra dependency is
 * needed and the guide works offline and in the packaged image. The
 * markdown is parsed by {@link UserGuideDocument} and rendered by
 * {@link UserGuideView}; both are covered without starting the
 * toolkit where possible.</p>
 */
public class UserGuideDialog extends Dialog<Void> {

    private final UserGuideView guideView = new UserGuideView();
    private final TextField searchField = new TextField();
    private final Label searchStatus = new Label();
    private final Button backButton = new Button("Back");
    private final MenuButton contentsButton = new MenuButton("Contents");

    /**
     * The standard server-functions user guide.
     */
    public UserGuideDialog(ThemeManager themeManager) {
        this(themeManager, UserGuideContent.load());
    }

    /**
     * A guide loaded from a caller-supplied string, for example a SMART on FHIR guide
     * bundled alongside the server-functions guide.
     */
    public UserGuideDialog(ThemeManager themeManager, String guideText) {
        setTitle("User Guide");
        setHeaderText("FHIR Resource Viewer — User Guide");
        setResizable(true);
        initModality(Modality.NONE);

        guideView.show(UserGuideDocument.parse(guideText));
        guideView.setLinkOpener(this::followLink);
        VBox.setVgrow(guideView, Priority.ALWAYS);

        VBox content = new VBox(8, buildNavigation(), guideView);
        content.setPrefWidth(720);
        content.setPrefHeight(560);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        getDialogPane().setPrefWidth(760);
        getDialogPane().setPrefHeight(600);

        populateContents();
        setOnShown(event -> fitToScreen());
    }

    /** The rendered guide view, exposed for tests. */
    UserGuideView guideView() {
        return guideView;
    }

    TextField searchField() {
        return searchField;
    }

    Label searchStatus() {
        return searchStatus;
    }

    Button backButton() {
        return backButton;
    }

    MenuButton contentsButton() {
        return contentsButton;
    }

    /** Shows the guide and moves to an anchor once the toolkit has laid it out. */
    public void showAt(String anchor) {
        show();
        if (anchor != null && !anchor.isBlank()) {
            Platform.runLater(() -> {
                guideView.scrollToAnchor(anchor);
                refreshNavigation();
            });
        }
    }

    private VBox buildNavigation() {
        backButton.setOnAction(event -> {
            guideView.goBack();
            refreshNavigation();
        });
        backButton.setDisable(true);
        backButton.setMinWidth(Region.USE_PREF_SIZE);
        contentsButton.setMinWidth(Region.USE_PREF_SIZE);

        searchField.setPromptText("Search this guide");
        searchField.setPrefWidth(180);
        searchField.setMinWidth(100);
        searchField.setMaxWidth(360);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                search(event.isShiftDown() ? false : true);
            } else if (event.getCode() == KeyCode.ESCAPE) {
                searchField.clear();
                searchStatus.setText("");
            }
        });

        Button previous = new Button("Previous");
        previous.setOnAction(event -> search(false));
        previous.setMinWidth(Region.USE_PREF_SIZE);
        Button next = new Button("Next");
        next.setOnAction(event -> search(true));
        next.setMinWidth(Region.USE_PREF_SIZE);
        searchStatus.getStyleClass().add("app-subtitle");
        searchStatus.setMinWidth(80);
        searchStatus.setAlignment(Pos.CENTER_LEFT);

        HBox navigation = new HBox(8, backButton, contentsButton, searchStatus);
        navigation.setAlignment(Pos.CENTER_LEFT);
        HBox searchRow = new HBox(8, searchField, previous, next);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        VBox bar = new VBox(6, navigation, searchRow);
        bar.setPadding(new Insets(0, 0, 2, 0));
        return bar;
    }

    private void followLink(String target) {
        if (target == null || target.isBlank()) {
            return;
        }
        if (target.startsWith("#")) {
            guideView.scrollToAnchor(target);
            refreshNavigation();
            return;
        }
        if (!target.startsWith("https://") && !target.startsWith("http://")) {
            searchStatus.setText("Only web links can be opened.");
            return;
        }
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(target));
            } else {
                searchStatus.setText("This desktop cannot open a browser.");
            }
        } catch (IOException | RuntimeException problem) {
            searchStatus.setText("Could not open the link.");
        }
    }

    private void populateContents() {
        contentsButton.getItems().clear();
        for (UserGuideView.GuideHeading heading : guideView.headings()) {
            MenuItem item = new MenuItem(heading.title());
            item.setOnAction(event -> {
                guideView.scrollToAnchor(heading.anchor());
                refreshNavigation();
            });
            contentsButton.getItems().add(item);
        }
        contentsButton.setDisable(contentsButton.getItems().isEmpty());
    }

    void search(boolean forward) {
        int match = guideView.search(searchField.getText(), forward);
        int total = guideView.searchMatchCount();
        searchStatus.setText(match == 0 ? "No matches" : match + " of " + total);
        refreshNavigation();
    }

    private void refreshNavigation() {
        backButton.setDisable(!guideView.canGoBack());
    }

    /**
     * Keeps the requested guide size, but never larger than the display and never
     * positioned with its title bar or right edge outside it.
     */
    private void fitToScreen() {
        Window window = getDialogPane().getScene().getWindow();
        Rectangle2D bounds = Screen.getScreensForRectangle(
                window.getX(), window.getY(), window.getWidth(), window.getHeight())
                .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
        double margin = 16;
        double maxWidth = Math.max(500, bounds.getWidth() - margin * 2);
        double maxHeight = Math.max(400, bounds.getHeight() - margin * 2);
        double width = Math.min(window.getWidth(), maxWidth);
        double height = Math.min(window.getHeight(), maxHeight);
        window.setWidth(width);
        window.setHeight(height);
        window.setX(clamp(window.getX(), bounds.getMinX() + margin,
                bounds.getMaxX() - width - margin));
        window.setY(clamp(window.getY(), bounds.getMinY() + margin,
                bounds.getMaxY() - height - margin));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
