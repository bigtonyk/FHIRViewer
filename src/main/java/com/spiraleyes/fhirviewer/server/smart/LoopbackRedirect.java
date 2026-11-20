package com.spiraleyes.fhirviewer.server.smart;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * A redirect URI the user registered with the authorization server, read as the loopback
 * address the callback listener must bind to.
 *
 * <p>An authorization server compares {@code redirect_uri} with what the client definition
 * lists, and many (Smile CDR among them) compare it exactly, port included. An ephemeral
 * port can never be registered in advance, so a launch that names a redirect URI makes the
 * listener use that port and path, and sends the URI byte for byte as entered.</p>
 *
 * <p>Only plain {@code http} to this machine is accepted: {@code 127.0.0.1} (or another
 * {@code 127.x} address) or {@code localhost}. The listener always binds an IPv4 loopback
 * address, never a network interface, so the authorization code still cannot leave the
 * machine. An IPv6 literal is refused rather than half supported, because the listener
 * would not be bound where the browser goes.</p>
 *
 * @param uri  the redirect URI exactly as entered, which is what the request sends
 * @param host the IPv4 loopback address to bind
 * @param port the fixed port to bind; always 1 to 65535
 * @param path the callback path to serve; never empty
 */
public record LoopbackRedirect(String uri, String host, int port, String path) {

    /**
     * Reads a configured redirect URI.
     *
     * @param value the redirect URI; blank means "no fixed address"
     * @return the parsed redirect, or empty when {@code value} is blank
     * @throws IllegalArgumentException with a message a user can act on when the value is
     *         not a loopback http URI with an explicit port
     */
    public static Optional<LoopbackRedirect> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String text = value.strip();
        URI parsed;
        try {
            parsed = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("The redirect URI is not a valid URL.");
        }
        if (!"http".equalsIgnoreCase(parsed.getScheme())) {
            throw new IllegalArgumentException(
                    "The redirect URI must start with http:// — the callback is received by "
                            + "this application on this machine.");
        }
        String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
        String bindHost;
        if (host.equals("localhost")) {
            bindHost = "127.0.0.1";
        } else if (host.startsWith("127.") && isIpv4(host)) {
            bindHost = host;
        } else {
            throw new IllegalArgumentException(
                    "The redirect URI must point at this machine: http://127.0.0.1:<port>/... "
                            + "or http://localhost:<port>/...");
        }
        if (parsed.getPort() < 1 || parsed.getPort() > 65535) {
            throw new IllegalArgumentException(
                    "The redirect URI needs an explicit port, such as "
                            + "http://127.0.0.1:8765/callback, so it can match the one "
                            + "registered with the server.");
        }
        if (parsed.getRawQuery() != null || parsed.getRawFragment() != null
                || parsed.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "The redirect URI cannot carry a query, a fragment or user information.");
        }
        String path = parsed.getRawPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            throw new IllegalArgumentException(
                    "The redirect URI needs a path, such as /callback.");
        }
        return Optional.of(new LoopbackRedirect(text, bindHost, parsed.getPort(), path));
    }

    private static boolean isIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)
                    || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
