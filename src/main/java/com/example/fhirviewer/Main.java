package com.example.fhirviewer;

import java.util.List;

import com.example.fhirviewer.ui.MainWindow;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Application entry point.
 *
 * <p>Creates the JavaFX window and hands over to {@link MainWindow}. All FHIR work
 * happens behind <code>com.example.fhirviewer.service.FhirService</code>.</p>
 *
 * <p>An optional first command line argument names a FHIR file to open at startup,
 * which also makes the application usable from a file association.</p>
 */
public class Main extends Application {

    private static final double INITIAL_WIDTH = 1180;
    private static final double INITIAL_HEIGHT = 760;
    private static final double MINIMUM_WIDTH = 860;
    private static final double MINIMUM_HEIGHT = 520;

    @Override
    public void start(Stage stage) {
        MainWindow mainWindow = new MainWindow(stage);

        Scene scene = new Scene(mainWindow.getRoot(), INITIAL_WIDTH, INITIAL_HEIGHT);
        stage.setTitle("FHIR Resource Viewer");
        stage.setScene(scene);
        stage.setMinWidth(MINIMUM_WIDTH);
        stage.setMinHeight(MINIMUM_HEIGHT);
        stage.show();

        openRequestedResource(mainWindow);
    }

    /** Opens the file named on the command line, if one was supplied. */
    private void openRequestedResource(MainWindow mainWindow) {
        List<String> arguments = getParameters().getRaw();
        if (!arguments.isEmpty() && !arguments.get(0).isBlank()) {
            mainWindow.openOnStartup(arguments.get(0));
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}