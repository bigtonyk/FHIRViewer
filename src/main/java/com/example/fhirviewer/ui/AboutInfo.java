package com.example.fhirviewer.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-data content of the About dialog: the application license notice, the
 * project links and the licenses of the bundled open-source libraries.
 *
 * <p>Deliberately free of JavaFX types so tests can assert on the dialog
 * content; {@link MainWindow} maps the lines to labels and hyperlinks.</p>
 */
public final class AboutInfo {

    /** The rendering style of a line. */
    public enum Kind {
        /** A bold section heading. */
        SECTION,
        /** A normal text line. */
        TEXT,
        /** A dimmed explanatory line. */
        MUTED,
        /** A clickable link; {@link Line#target()} carries the target to open. */
        LINK
    }

    /**
     * One rendered line of the dialog.
     *
     * @param kind   the rendering style of the line
     * @param text   the text to show
     * @param target the target to open for links (for example a URL); empty otherwise
     */
    public record Line(Kind kind, String text, String target) {

        public static Line section(String text) {
            return new Line(Kind.SECTION, text, "");
        }

        public static Line text(String text) {
            return new Line(Kind.TEXT, text, "");
        }

        public static Line muted(String text) {
            return new Line(Kind.MUTED, text, "");
        }

        public static Line link(String text, String target) {
            return new Line(Kind.LINK, text, target);
        }
    }

    /** The project home page. */
    public static final String PROJECT_URL = "https://github.com/bigtonyk/FHIRViewer";
    /** The contact address. */
    public static final String CONTACT_EMAIL = "bigtonyk@gmail.com";

    private static final String GPL_NOTICE =
            "FHIR Resource Viewer is free software: you can redistribute it and/or modify it "
            + "under the terms of the GNU General Public License as published by the Free "
            + "Software Foundation, version 3 of the License (SPDX: GPL-3.0-only).";
    private static final String GPL_NO_WARRANTY =
            "This program is distributed in the hope that it will be useful, but WITHOUT ANY "
            + "WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR "
            + "A PARTICULAR PURPOSE. See the GNU General Public License for more details.";

    private final List<Line> lines;

    public AboutInfo(String fhirVersion, String javaVersion, String javafxVersion) {
        lines = build(fhirVersion, javaVersion, javafxVersion);
    }

    /** Every line of the dialog, in display order. */
    public List<Line> lines() {
        return lines;
    }

    private static List<Line> build(String fhirVersion, String javaVersion, String javafxVersion) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.text(GPL_NOTICE));
        lines.add(Line.muted(GPL_NO_WARRANTY));

        lines.add(Line.section("Project"));
        lines.add(Line.link(PROJECT_URL, PROJECT_URL));
        lines.add(Line.link(CONTACT_EMAIL, "mailto:" + CONTACT_EMAIL));

        lines.add(Line.section("Runtime"));
        lines.add(Line.text("FHIR: " + fhirVersion + " (parsing and validation by HAPI FHIR)"));
        lines.add(Line.text("Java: " + javaVersion));
        lines.add(Line.text("JavaFX: " + javafxVersion));

        lines.add(Line.section("Open-source libraries"));
        lines.add(Line.text("Apache License 2.0 — HAPI FHIR, HL7 org.hl7.fhir core, Jackson, "
                + "Woodstox, Gson, Xpp3, Guava, Caffeine, Apache Commons (Codec, Compress, IO, "
                + "Lang, Logging, Text), Apache HttpClient, Apache Santuario XMLSec, Thymeleaf, "
                + "Attoparser, Unbescape, OGNL, Kotlin stdlib, OkHttp and Okio, Nimbus JOSE+JWT, "
                + "JavaEWAH, Jakarta RegExp, SQLite JDBC, XMLResolver, JCL-over-SLF4J, "
                + "OpenTelemetry, and the Google annotations libraries (error-prone, jsr305, j2objc)"));
        lines.add(Line.text("MIT — AtlantaFX (UI theme), SLF4J (API and Simple), "
                + "Checker Framework annotations, PlantUML (MIT edition)"));
        lines.add(Line.text("GPL v2 with Classpath Exception — JavaFX (OpenJFX) UI toolkit"));
        lines.add(Line.text("Eclipse Public License 2.0 — JUnit 5 and JUnit Platform (testing only). "
                + "The Jakarta Annotations API is dual-licensed under EPL 2.0 and "
                + "GPL v2 with Classpath Exception"));
        lines.add(Line.text("Mozilla Public License 2.0 — Saxon-HE (XPath/XSLT engine used by validation)"));
        lines.add(Line.text("ICU License (Unicode-3.0) — ICU4J"));
        lines.add(Line.text("BSD 2-Clause — StAX2 API and CommonMark. "
                + "BSD 3-Clause / Eclipse Distribution License 1.0 — Eclipse JGit and the UCUM library"));
        lines.add(Line.text("Javassist is tri-licensed under MPL 1.1, LGPL 2.1 and Apache License 2.0"));
        lines.add(Line.muted("The list covers the runtime libraries bundled with the application; "
                + "consult each project for the full license text."));
        return List.copyOf(lines);
    }
}