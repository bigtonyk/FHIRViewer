package com.example.fhirviewer;

import javafx.application.Application;

/**
 * Command line launcher that deliberately does not extend {@link Application}.
 *
 * <p>When JavaFX sits on the class path rather than the module path (plain
 * <code>java -cp</code> runs and jpackage launchers), the JVM launcher refuses to start
 * any main class that extends {@code Application}, reporting
 * <em>"JavaFX runtime components are missing"</em>. Starting from an ordinary class
 * sidesteps that check, so the application can be started in three equivalent ways:</p>
 *
 * <pre>
 * mvn javafx:run                                            (module path, development)
 * java -cp "target/classes;target/lib/*" com.example.fhirviewer.Launcher  (class path)
 * jpackage --main-class com.example.fhirviewer.Launcher                 (packaging)
 * </pre>
 *
 * <p>Use {@code Main} when launching JavaFX from the module path.</p>
 */
public final class Launcher {

    private Launcher() {
        // static entry point
    }

    public static void main(String[] args) {
        Application.launch(Main.class, args);
    }
}