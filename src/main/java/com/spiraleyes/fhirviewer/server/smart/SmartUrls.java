package com.spiraleyes.fhirviewer.server.smart;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * The URL rules SMART discovery depends on: how a FHIR base URL becomes a discovery URL,
 * and what counts as a host this application may talk to without TLS.
 *
 * <p>Deliberately pure static functions with no I/O, so every rule the discovery service
 * relies on can be tested without a socket. {@code server.rest.RestUrls} joins a base with
 * a relative path; this class does the two SMART-specific jobs that are not "join": making
 * what the user typed safe to request, and building the one well-known path.</p>
 *
 * <p><b>Base paths survive normalization.</b> A FHIR server is very often mounted below a
 * path — {@code https://example.com/fhir}, {@code https://ehr.example.org/dc/fhir/R4} — and
 * that path is where both the FHIR API and the discovery document live. So normalization
 * trims whitespace and trailing slashes and nothing else: it never strips a path segment,
 * never collapses {@code ..}, and never rewrites the host. A rule that "simplified" a URL to
 * its origin would silently point discovery at the wrong document on every server that is
 * not at the root.</p>
 *
 * <p><b>Insecure HTTP is a loopback exception, not a setting.</b> A scheme-less URL is given
 * {@code https://} (or {@code http://} for a loopback host), but an explicit scheme is never
 * changed — so nothing the user wrote is ever downgraded. Plain {@code http} is only ever
 * allowed for loopback hosts, which is what lets a developer run a server on
 * {@code 127.0.0.1} without weakening anything reachable over a network.</p>
 */
public final class SmartUrls {

    /** The one well-known path, relative to a FHIR base URL (SMART App Launch §Discovery). */
    public static final String DISCOVERY_PATH = "/.well-known/smart-configuration";

    private SmartUrls() {
    }

    /**
     * Turns what the user typed into a URL that is safe to request.
     *
     * @param value the FHIR server base URL, with or without a scheme
     * @return the normalized URL: trimmed, one scheme, no trailing slash, path intact
     * @throws IllegalArgumentException when there is nothing usable to normalize — a blank
     *         value, a fragment, or a URL with no host — since each would produce a request
     *         that cannot succeed and a message the user can act on is better than a
     *         malformed-URL exception from the HTTP client later
     */
    public static String normalizeFhirBase(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A FHIR server base URL is required.");
        }
        String trimmed = value.trim();
        if (trimmed.contains("#")) {
            // A fragment is never sent to a server; keeping it would silently drop it and
            // make two different inputs look like the same base URL.
            throw new IllegalArgumentException(
                    "The base URL must not contain a fragment (the part after \"#\").");
        }
        String withScheme = ensureScheme(trimmed);
        URI uri = parse(withScheme);
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "The base URL must name a host, for example https://example.com/fhir.");
        }
        // Trailing slashes only. Path segments — the whole point of a mounted FHIR server —
        // are left exactly as typed.
        String normalized = uri.toString();
        int end = normalized.length();
        while (end > 0 && normalized.charAt(end - 1) == '/') {
            end--;
        }
        return normalized.substring(0, end);
    }

    /**
     * The discovery document's URL: {@code {base}/.well-known/smart-configuration}.
     *
     * <p>An absolute URL is returned unchanged, so a caller that already built the full URL
     * is not forced to unwrap it — the same courtesy {@code RestUrls.join} extends.</p>
     *
     * @param baseUrl a normalized FHIR base URL, or any absolute URL
     * @return the URL to request the discovery document from
     */
    public static String discoveryUrl(String baseUrl) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("A FHIR server base URL is required.");
        }
        if (baseUrl.endsWith(DISCOVERY_PATH)) {
            return baseUrl;
        }
        String normalized = normalizeFhirBase(baseUrl);
        return normalized + DISCOVERY_PATH;
    }
    /**
     * True when the host is this machine: {@code localhost}, a {@code 127.0.0.0/8} address,
     * or the IPv6 loopback.
     *
     * <p>The single condition under which plain {@code http} is ever allowed, so a developer
     * can run a SMART server on their own machine without the application accepting
     * unauthenticated transport for anything a network can reach.</p>
     */
    public static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        // An IPv6 literal arrives bracketed from URI#getHost.
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.equals("localhost")
                || value.equals("::1")
                || value.equals("0:0:0:0:0:0:0:1")
                || value.startsWith("127.");
    }

    /**
     * Parses an absolute http(s) URL, or returns {@code null} when it is not one.
     *
     * <p>Used by the endpoint checks, which report a problem rather than throwing — a
     * malformed endpoint in a server's own discovery document is the server's bug, and the
     * user needs to see which field was wrong rather than have discovery abort.</p>
     */
    static URI parseOrNull(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri = parse(url.trim());
        String scheme = uri.getScheme() == null
                ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        return uri.getHost() == null || uri.getHost().isBlank() ? null : uri;
    }

    /** Adds a scheme when the user omitted one: https everywhere, http on loopback only. */
    private static String ensureScheme(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return value;
        }
        if (lower.startsWith("//")) {
            // A protocol-relative URL is still missing its scheme; choosing one deliberately
            // is better than letting the HTTP client guess.
            value = value.substring(2);
        }
        // The first path segment decides the scheme for a scheme-less URL: a bare host gets
        // https, and only a host that is unambiguously this machine gets http.
        String hostPart = value.split("/", 2)[0];
        String host = hostPart.contains(":") && !hostPart.startsWith("[")
                ? hostPart.substring(0, hostPart.indexOf(':'))
                : hostPart;
        return (isLoopback(host) ? "http://" : "https://") + value;
    }

    /** Parses a URL that already has a scheme, reporting a problem the user can fix. */
    private static URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "The base URL is not a valid URL: " + e.getMessage());
        }
    }
}
