package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the About dialog content: the GPL v3 notice, the project links and the
 * license list of every bundled open-source library family.
 */
class AboutInfoTest {

    private static List<AboutInfo.Line> lines() {
        return new AboutInfo("R4", "25.0.1", "25.0.4").lines();
    }

    private static String joined() {
        StringBuilder sb = new StringBuilder();
        for (AboutInfo.Line line : lines()) {
            sb.append(line.text()).append('\n');
        }
        return sb.toString();
    }

    private static void assertMentioned(String text, String... needles) {
        for (String needle : needles) {
            assertTrue(text.contains(needle),
                    "Expected <" + needle + "> in the About content:\n" + text);
        }
    }

    @Test
    @DisplayName("The content states the GPL v3 license with the standard notice")
    void statesGplV3() {
        String text = joined();
        assertMentioned(text,
                "GNU General Public License",
                "version 3 of the License",
                "WITHOUT ANY WARRANTY");
        assertEquals(AboutInfo.Line.text("").kind(),
                lines().get(0).kind(),
                "The first line is a text line");
        assertTrue(lines().get(0).text().startsWith("FHIR Resource Viewer is free software"));
    }

    @Test
    @DisplayName("The content carries the GitHub URL and the contact email as links")
    void carriesProjectLinks() {
        List<AboutInfo.Line> all = lines();

        assertTrue(all.stream().anyMatch(line -> line.kind() == AboutInfo.Kind.LINK
                && AboutInfo.PROJECT_URL.equals(line.text())
                && AboutInfo.PROJECT_URL.equals(line.target())),
                "GitHub link line missing: " + all);

        assertTrue(all.stream().anyMatch(line -> line.kind() == AboutInfo.Kind.LINK
                && AboutInfo.CONTACT_EMAIL.equals(line.text())
                && ("mailto:" + AboutInfo.CONTACT_EMAIL).equals(line.target())),
                "Email link line missing: " + all);

        assertTrue(joined().contains("https://github.com/bigtonyk/FHIRViewer"));
        assertTrue(joined().contains("bigtonyk@gmail.com"));
    }

    @Test
    @DisplayName("The runtime versions are shown")
    void showsRuntimeVersions() {
        String text = joined();
        assertMentioned(text, "FHIR: R4", "Java: 25.0.1", "JavaFX: 25.0.4");
    }

    @Test
    @DisplayName("Every license family of the bundled libraries is listed")
    void listsEveryLicenseFamily() {
        String text = joined();
        assertMentioned(text,
                "Apache License 2.0",
                "MIT",
                "GPL v2 with Classpath Exception",
                "Eclipse Public License 2.0",
                "Mozilla Public License 2.0",
                "ICU License",
                "BSD 2-Clause",
                "BSD 3-Clause",
                "LGPL 2.1");
    }

    @Test
    @DisplayName("Every bundled library project is named")
    void namesEveryLibrary() {
        String text = joined();
        assertMentioned(text,
                // FHIR layer
                "HAPI FHIR", "org.hl7.fhir", "UCUM",
                // UI layer
                "JavaFX", "AtlantaFX",
                // JSON/XML/serialization
                "Jackson", "Woodstox", "StAX2", "Gson", "Xpp3",
                // Validation engine pieces
                "Saxon-HE", "ICU4J", "Thymeleaf", "Attoparser", "Unbescape", "XMLSec", "XMLResolver",
                // Apache utilities
                "Apache Commons", "Apache HttpClient",
                // Google utilities
                "Guava", "Caffeine",
                // Other
                "OkHttp", "Nimbus JOSE+JWT", "SLF4J", "Javassist", "CommonMark",
                "Eclipse JGit", "OGNL", "Kotlin stdlib", "SQLite JDBC", "OpenTelemetry",
                "Jakarta Annotations", "PlantUML", "JUnit 5");
    }

    @Test
    @DisplayName("Sections are in display order: license, project, runtime, libraries")
    void sectionsInOrder() {
        List<AboutInfo.Line> all = lines();
        assertEquals(AboutInfo.Kind.SECTION, all.stream()
                .filter(line -> line.kind() == AboutInfo.Kind.SECTION)
                .findFirst().orElseThrow().kind());
        List<String> sections = all.stream()
                .filter(line -> line.kind() == AboutInfo.Kind.SECTION)
                .map(AboutInfo.Line::text)
                .toList();
        assertEquals(List.of("Project", "Runtime", "Open-source libraries"), sections);
        assertTrue(all.indexOf(all.stream().filter(line -> line.text().contains("GNU General Public License"))
                .findFirst().orElseThrow())
                < all.indexOf(all.stream().filter(line -> line.kind() == AboutInfo.Kind.SECTION)
                        .findFirst().orElseThrow()),
                "The GPL notice comes before the first section");
    }
}