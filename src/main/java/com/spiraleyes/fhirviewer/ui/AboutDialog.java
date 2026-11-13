package com.spiraleyes.fhirviewer.ui;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.HostServices;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * The About dialog: the license notice, the project links, the runtime versions, the bundled
 * libraries and the logo.
 *
 * <p>Extracted from {@code MainWindow} as the first step of the M1 split (R4 §7). It shares
 * nothing with the window except the stage it is modal to, the stylesheets that keep it
 * following the theme, the host services that open a link, and the status line a link that
 * cannot be opened is reported to — which is why it was the cheapest region to move.</p>
 */
final class AboutDialog {

    private static final Logger logger = LoggerFactory.getLogger(AboutDialog.class);

    /** Shown in the dialog's title bar, matching the application's own. */
    private static final String APPLICATION_TITLE = "FHIR Resource Viewer";

    /** The decoded logo, kept because the dialog can be opened more than once. */
    private static Image applicationLogo;

    /** True once a load has been attempted, so a missing image is not retried on every open. */
    private static boolean applicationLogoFailed;

    private final Stage owner;
    private final HostServices hostServices;
    private final ThemeManager themeManager;
    /** The FHIR version the About text reports; the window owns the service that knows it. */
    private final String fhirVersion;
    /** Where a link that cannot be opened is reported. */
    private final Consumer<String> status;

    AboutDialog(Stage owner, HostServices hostServices, ThemeManager themeManager,
            String fhirVersion, Consumer<String> status) {
        this.owner = owner;
        this.hostServices = hostServices;
        this.themeManager = themeManager;
        this.fhirVersion = fhirVersion;
        this.status = status;
    }

    /** Shows the dialog, blocking until it is dismissed. */
    void show() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("About " + APPLICATION_TITLE);
        alert.setHeaderText(APPLICATION_TITLE);
        // The dialog lives in its own scene; give it the same stylesheets so
        // the design tokens (-surface, -text-strong, ...) resolve there too.
        alert.getDialogPane().getStylesheets().addAll(themeManager.stylesheets());
        alert.getDialogPane().setContent(content());
        alert.getDialogPane().setPrefWidth(680);
        alert.showAndWait();
    }

    /**
     * The About dialog content: the GPL v3 notice, the project links and the
     * licenses of the bundled open-source libraries, scrollable because the
     * license list is long.
     */
    private Node content() {
        AboutInfo about = new AboutInfo(
                fhirVersion,
                System.getProperty("java.version"),
                System.getProperty("javafx.version", "unknown"));

        VBox body = new VBox(4);
        body.getStyleClass().add("about-content");
        Node logo = aboutLogo();
        if (logo != null) {
            logo.getStyleClass().add("about-logo");
            body.getChildren().add(logo);
        }
        for (AboutInfo.Line line : about.lines()) {
            switch (line.kind()) {
                case SECTION -> {
                    Label label = new Label(line.text());
                    label.getStyleClass().add("about-section-title");
                    body.getChildren().add(label);
                }
                case TEXT -> {
                    Label label = new Label(line.text());
                    label.setWrapText(true);
                    label.getStyleClass().add("about-line");
                    body.getChildren().add(label);
                }
                case MUTED -> {
                    Label label = new Label(line.text());
                    label.setWrapText(true);
                    label.getStyleClass().addAll("about-line", "about-muted");
                    body.getChildren().add(label);
                }
                case LINK -> {
                    Hyperlink link = new Hyperlink(line.text());
                    link.setWrapText(true);
                    link.setOnAction(event -> openInBrowser(line.target()));
                    body.getChildren().add(link);
                }
            }
        }

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(420);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    /**
     * The About dialog logo, or {@code null} when the image is not on the class path.
     *
     * <p>Branding is a nicety, so a missing or corrupt image must never stop the
     * About dialog from opening: the stream is read defensively and any failure
     * simply yields no logo. The image is kept in memory because the dialog can be
     * opened more than once, and a 900 KB JPEG is re-decoded on every open otherwise.</p>
     */
    private Node aboutLogo() {
        Image image = applicationLogo();
        if (image == null) {
            return null;
        }
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        // Scale the large source down to a banner width rather than letting the
        // dialog grow to the image's 1408px natural width.
        double targetWidth = 380;
        view.setFitWidth(targetWidth);
        view.setFitHeight(image.getHeight() * targetWidth / image.getWidth());
        return view;
    }

    /** The cached application logo, loaded once from the class path. */
    private static Image applicationLogo() {
        if (applicationLogo == null && !applicationLogoFailed) {
            try (java.io.InputStream in =
                         AboutDialog.class.getResourceAsStream("/SpiralEyesLogo.jpg")) {
                if (in != null) {
                    applicationLogo = new Image(in);
                }
            } catch (java.io.IOException | RuntimeException problem) {
                logger.warn("Could not load the application logo", problem);
            }
            applicationLogoFailed = true;
        }
        return applicationLogo;
    }

    /** Opens a project or contact link, reporting failures in the status bar. */
    private void openInBrowser(String target) {
        try {
            if (hostServices != null) {
                hostServices.showDocument(target);
            }
        } catch (RuntimeException e) {
            status.accept("Could not open " + target);
        }
    }
}
