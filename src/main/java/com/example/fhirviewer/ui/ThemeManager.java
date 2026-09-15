package com.example.fhirviewer.ui;

import java.net.URL;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;

import javafx.scene.Scene;

/**
 * Applies the application theme to a {@link Scene}.
 *
 * <p>The base look is provided by the AtlantaFX Primer theme (light or dark);
 * application specific styling lives in <code>/css/app.css</code> plus a small
 * per theme sheet (<code>/css/light.css</code>, <code>/css/dark.css</code>).
 * The theme can be switched at runtime; doing so replaces the stylesheets on
 * the scene, which restyles the whole application.</p>
 */
public final class ThemeManager {

    /** Supported application themes. */
    public enum Theme {
        /** Professional light theme (the default). */
        LIGHT,
        /** Dark theme, added without any changes to the application styling. */
        DARK
    }

    private static final String APP_STYLESHEET = "/css/app.css";
    private static final String LIGHT_STYLESHEET = "/css/light.css";
    private static final String DARK_STYLESHEET = "/css/dark.css";

    private Theme current = Theme.LIGHT;

    /**
     * Applies the given theme to the scene: the AtlantaFX base theme plus the
     * application stylesheets. Applying a theme again at runtime restyles
     * every control in the scene.
     */
    public void apply(Scene scene, Theme theme) {
        current = theme == null ? Theme.LIGHT : theme;
        switch (current) {
            case DARK -> javafx.application.Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
            case LIGHT -> javafx.application.Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        }
        scene.getStylesheets().clear();
        scene.getStylesheets().add(stylesheet(APP_STYLESHEET));
        scene.getStylesheets().add(stylesheet(current == Theme.DARK ? DARK_STYLESHEET : LIGHT_STYLESHEET));
    }

    /** The theme currently applied. */
    public Theme current() {
        return current;
    }

    /** True while the dark theme is active. */
    public boolean isDark() {
        return current == Theme.DARK;
    }

    private String stylesheet(String resource) {
        URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Stylesheet not found on the class path: " + resource);
        }
        return url.toExternalForm();
    }
}
