package com.example.fhirviewer.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small helpers for reading and writing resource files.
 */
public final class FileSupport {

    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private FileSupport() {
        // static helpers
    }

    /**
     * Reads a file as UTF-8 text, stripping a leading byte order mark if present
     * (editors on Windows sometimes add one, and FHIR parsers reject it).
     */
    public static String readText(Path path) throws IOException {
        return stripByteOrderMark(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Reads a classpath resource such as <code>/samples/patient-example.json</code>. */
    public static String readClasspathText(String resourcePath) throws IOException {
        try (InputStream stream = FileSupport.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Resource not found on the classpath: " + resourcePath);
            }
            return stripByteOrderMark(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Writes text as UTF-8, creating parent directories when needed. */
    public static void writeText(Path path, String text) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    /** The file name of a path, or the full path when it has no name part. */
    public static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /**
     * Strips a leading byte order mark if present. Editors on Windows sometimes add
     * one, and FHIR parsers reject it.
     */
    public static String stripByteOrderMark(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            return text.substring(1);
        }
        return text;
    }
}