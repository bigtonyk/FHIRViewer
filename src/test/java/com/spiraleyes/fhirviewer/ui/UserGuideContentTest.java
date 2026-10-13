package com.spiraleyes.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the bundled user guide loads from the class path.
 */
class UserGuideContentTest {

    @Test
    @DisplayName("The bundled guide loads and is the server-functions guide")
    void loadsServerGuide() {
        String text = UserGuideContent.load();
        assertFalse(text.isBlank(), "The user guide text must not be blank");
        assertTrue(text.contains("FHIR Server Functions"),
                "Expected the server-functions guide, got:\n" + text.substring(0, Math.min(200, text.length())));
    }

    @Test
    @DisplayName("The bundled guide covers adding servers")
    void coversAddingServers() {
        String text = UserGuideContent.load();
        assertTrue(text.contains("Adding and editing servers"),
                "The guide should describe adding servers");
    }
}
