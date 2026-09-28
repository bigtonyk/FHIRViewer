package com.example.fhirviewer.server.rest;

import java.util.Objects;

/**
 * Joins a configured base URL with a request path without losing either one.
 *
 * <p>Every request a plugin makes carries a path relative to its server's base URL, so
 * exactly one place has to decide how the two are combined. Doing it here keeps
 * {@code FhirServerConfiguration.baseUrl()} free to keep its trailing slash while a
 * caller passes {@code "/metadata"} or {@code "Patient/123"} interchangeably.
 */
public final class RestUrls {

    private RestUrls() {
    }

    /**
     * Combines a base URL and a relative path.
     *
     * <p>An absolute {@code http://} or {@code https://} path is returned unchanged, so a
     * vendor plugin that already knows a full URL is not forced to unwrap it. Anything
     * else is appended with exactly one slash between the two parts, and a query string
     * on the path is preserved.
     *
     * @param baseUrl the server base URL, for example {@code https://example.com/fhir}
     * @param path    a relative path, for example {@code /metadata} or {@code Patient/1}
     * @return the absolute URL to request
     * @throws IllegalArgumentException when either part is null or blank
     */
    public static String join(String baseUrl, String path) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(path, "path");
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("A base URL is required.");
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("A request path is required.");
        }
        if (isAbsolute(path)) {
            return path;
        }
        StringBuilder joined = new StringBuilder(stripTrailingSlashes(baseUrl.trim()));
        String relative = path.trim();
        if (!relative.startsWith("/")) {
            joined.append('/');
        }
        joined.append(relative);
        return joined.toString();
    }

    /**
     * True when the path already names a scheme, and can therefore be requested as it is.
     *
     * <p>Compared case-insensitively because schemes are, and checked with
     * {@code regionMatches} so a path that merely starts with the letters does not pass.
     */
    public static boolean isAbsolute(String path) {
        if (path == null) {
            return false;
        }
        String value = path.trim();
        return value.regionMatches(true, 0, "http://", 0, "http://".length())
                || value.regionMatches(true, 0, "https://", 0, "https://".length());
    }

    /** Removes every trailing slash, so joining cannot produce a doubled separator. */
    private static String stripTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
