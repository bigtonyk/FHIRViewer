package com.example.fhirviewer.model;

import java.util.Locale;
import java.util.Optional;

/**
 * The serialization format of a FHIR resource.
 *
 * <p>Detection is intentionally lenient: content sniffing is attempted first
 * (FHIR JSON starts with <code>{</code>, FHIR XML starts with <code>&lt;</code>),
 * then the file name extension is used as a fallback.</p>
 */
public enum ResourceFormat {

    JSON("JSON", "json"),
    XML("XML", "xml");

    private final String displayName;
    private final String extension;

    ResourceFormat(String displayName, String extension) {
        this.displayName = displayName;
        this.extension = extension;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getExtension() {
        return extension;
    }

    /**
     * Detects the format of the supplied content, using the content itself when it
     * is unambiguous and the file name as a fallback.
     *
     * @param content    the raw resource text (may be {@code null})
     * @param fileName   the originating file name (may be {@code null})
     * @return the detected format, or empty when it cannot be determined
     */
    public static Optional<ResourceFormat> detect(String content, String fileName) {
        Optional<ResourceFormat> byContent = fromContent(content);
        if (byContent.isPresent()) {
            return byContent;
        }
        return fromFileName(fileName);
    }

    /**
     * Detects the format by inspecting the first meaningful character of the content.
     */
    public static Optional<ResourceFormat> fromContent(String content) {
        if (content == null) {
            return Optional.empty();
        }
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                continue;
            }
            if (c == '{' || c == '[') {
                return Optional.of(JSON);
            }
            if (c == '<') {
                return Optional.of(XML);
            }
            // Any other leading character means this is neither JSON nor XML.
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Detects the format from a file name extension.
     */
    public static Optional<ResourceFormat> fromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return Optional.empty();
        }
        String suffix = lower.substring(dot + 1);
        for (ResourceFormat format : values()) {
            if (format.extension.equals(suffix)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** The format that is not this one. */
    public ResourceFormat other() {
        return this == JSON ? XML : JSON;
    }
}