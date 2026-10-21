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

    /** The SMART on FHIR launch guide on the class path. */
    public static final String RESOURCE_SMART = "/help/SMART-on-FHIR-Guide.md";

    /** The Phase 6 Scripting API Documentation guide on the class path. */
    public static final String RESOURCE_PHASE6 = "/help/PHASE-6-SCRIPTING-API-DOCUMENTATION.md";

    private UserGuideContent() {
        // static entry point
    }

    /** The guide text, or an explanation when the resource is missing. */
    public static String load() {
        try (InputStream in = UserGuideContent.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return missing("the server-functions user guide");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException problem) {
            return missing("the server-functions user guide");
        }
    }

    /** The SMART on FHIR launch guide text, or a fallback message. */
    public static String loadSmartGuide() {
        try (InputStream in = UserGuideContent.class.getResourceAsStream(RESOURCE_SMART)) {
            if (in == null) {
                return missing("the SMART on FHIR launch guide");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException problem) {
            return missing("the SMART on FHIR launch guide");
        }
    }

    /** The Phase 6 Scripting API Documentation text, or a fallback message. */
    public static String loadPhase6Guide() {
        try (InputStream in = UserGuideContent.class.getResourceAsStream(RESOURCE_PHASE6)) {
            if (in == null) {
                return missing("the Phase 6 Scripting API Documentation");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException problem) {
            return missing("the Phase 6 Scripting API Documentation");
        }
    }

    private static String missing(String which) {
        return "The " + which + " could not be found in this build.\n\n"
                + "See docs/smartonfhir/USER-GUIDE-SMART.md in the project sources.";
    }
}
