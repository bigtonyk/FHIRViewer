package com.example.fhirviewer.ui;

import java.net.URL;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;

import javafx.scene.Scene;

/**
 * Applies the application theme to a {@link Scene}.
 *
 * <p>The base look is provided by an AtlantaFX theme; application specific
 * styling lives in <code>/css/app.css</code> plus a small token sheet
 * (<code>/css/light.css</code> for light themes, <code>/css/dark.css</code>
 * for dark themes) whose values derive from the palette of the applied
 * theme, so every theme keeps its own colors. The theme can be switched at
 * runtime; doing so replaces the stylesheets on the scene, which restyles
 * the whole application.</p>
 */
public final class ThemeManager {

    /** Supported application themes. */
    public enum Theme {
        /** Professional light theme (the default). */
        PRIMER_LIGHT("Primer Light", false,
                new PrimerLight().getUserAgentStylesheet()),
        /** Professional dark theme. */
        PRIMER_DARK("Primer Dark", true,
                new PrimerDark().getUserAgentStylesheet()),
        /** Cool, blue based light theme. */
        NORD_LIGHT("Nord Light", false,
                new NordLight().getUserAgentStylesheet()),
        /** Cool, blue based dark theme. */
        NORD_DARK("Nord Dark", true,
                new NordDark().getUserAgentStylesheet()),
        /** macOS inspired light theme. */
        CUPERTINO_LIGHT("Cupertino Light", false,
                new CupertinoLight().getUserAgentStylesheet()),
        /** macOS inspired dark theme. */
        CUPERTINO_DARK("Cupertino Dark", true,
                new CupertinoDark().getUserAgentStylesheet()),
        /** Vibrant dark theme with a purple accent. */
        DRACULA("Dracula", true,
                new Dracula().getUserAgentStylesheet());

        private final String displayName;
        private final boolean dark;
        private final String userAgentStylesheet;

        Theme(String displayName, boolean dark, String userAgentStylesheet) {
            this.displayName = displayName;
            this.dark = dark;
            this.userAgentStylesheet = userAgentStylesheet;
        }

        /** The human readable name shown in menus, for example <code>Nord Light</code>. */
        public String getDisplayName() {
            return displayName;
        }

        /** True when this is a dark theme. */
        public boolean isDark() {
            return dark;
        }

        /** The AtlantaFX user agent stylesheet URL. */
        String getUserAgentStylesheet() {
            return userAgentStylesheet;
        }
    }

    private static final String APP_STYLESHEET = "/css/app.css";
    private static final String LIGHT_STYLESHEET = "/css/light.css";
    private static final String DARK_STYLESHEET = "/css/dark.css";

    private Theme current = Theme.PRIMER_LIGHT;

    /**
     * Applies the given theme to the scene: the AtlantaFX base theme plus the
     * application stylesheets. Applying a theme again at runtime restyles
     * every control in the scene.
     */
    public void apply(Scene scene, Theme theme) {
        current = theme == null ? Theme.PRIMER_LIGHT : theme;
        javafx.application.Application.setUserAgentStylesheet(current.getUserAgentStylesheet());
        scene.getStylesheets().clear();
        scene.getStylesheets().add(stylesheet(APP_STYLESHEET));
        scene.getStylesheets().add(stylesheet(current.isDark() ? DARK_STYLESHEET : LIGHT_STYLESHEET));
    }

    /** The theme currently applied. */
    public Theme current() {
        return current;
    }

    /** True while a dark theme is active. */
    public boolean isDark() {
        return current.isDark();
    }

    private String stylesheet(String resource) {
        URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Stylesheet not found on the class path: " + resource);
        }
        return url.toExternalForm();
    }
}
