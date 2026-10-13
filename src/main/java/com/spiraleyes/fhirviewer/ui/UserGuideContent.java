package com.spiraleyes.fhirviewer.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the bundled user guide text.
 *
 * <p>Kept separate from the dialog so the loading can be tested without
 * starting the JavaFX toolkit: dialogs cannot be exercised by a headless
 * build, and the resource lookup is the part that must not be wrong.</p>
 */
public final class UserGuideContent {

    /** The guide on the class path, copied from {@code docs/user-guide}. */
    public static final String RESOURCE = "/help/User-Guide.md";

    private UserGuideContent() {
        // static entry point
    }

    /** The guide text, or an explanation when the resource is missing. */
    public static String load() {
        try (InputStream in = UserGuideContent.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return missing();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException problem) {
            return missing();
        }
    }

    private static String missing() {
        return "The user guide could not be found in this build.\n\n"
                + "Read docs/user-guide/FHIR-Server-Functions.md in the project sources.";
    }
}
