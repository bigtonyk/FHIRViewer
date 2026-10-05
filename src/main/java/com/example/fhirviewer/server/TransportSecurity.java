package com.example.fhirviewer.server;

import java.util.Locale;

/**
 * Whether a server's URL is safe to send a credential to.
 *
 * <p>Exists because {@code http://} is genuinely useful — a developer running a local
 * HAPI server has no certificate — and genuinely dangerous. A password sent over plain HTTP
 * travels in base64, which is encoding and not encryption: anyone on the path can read it.
 * A blanket refusal would break the local case that most testing depends on, and a blanket
 * silence would let a production password cross a network in the clear with nothing on
 * screen saying so.</p>
 *
 * <p>So the rule is <b>loopback is fine, everything else is not</b>. Loopback traffic never
 * leaves the machine, so {@code http://localhost:8080} needs no warning. Anything else
 * reachable over {@code http://} does, and the caller is expected to say so before saving
 * a password rather than after the first failed request.</p>
 */
public final class TransportSecurity {

    private TransportSecurity() {
    }

    /**
     * True when a credential sent to this URL could be read in transit.
     *
     * <p>False for {@code https://} anything, and for {@code http://} on the loopback
     * interface. True for {@code http://} anywhere else.</p>
     *
     * @param baseUrl the server's base URL; {@code null} or blank is treated as unsafe,
     *                because a request built from it is not something to guess at
     */
    public static boolean exposesCredentials(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true;
        }
        String url = baseUrl.trim().toLowerCase(Locale.ROOT);
        if (url.startsWith("https://")) {
            return false;
        }
        if (!url.startsWith("http://")) {
            // Not a URL scheme we recognise. Better to warn than to assume it is safe.
            return true;
        }
        return !isLoopback(url);
    }

    /**
     * True when the URL names the loopback interface, where traffic never leaves the host.
     *
     * <p>Matches {@code localhost}, the IPv6 loopback literal, and any IPv4 address in
     * {@code 127.0.0.0/8}. The port is irrelevant — the question is the host, not the service.</p>
     *
     * <p><b>A host that merely starts with {@code 127.} is not loopback.</b>
     * {@code 127.0.0.1.example.com} is an ordinary remote host, and treating it as loopback
     * would exempt exactly the case the warning exists for: a password sent across the
     * internet to a domain that looks like a local address at a glance.</p>
     */
    public static boolean isLoopback(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String host = hostOf(baseUrl.trim().toLowerCase(Locale.ROOT));
        return "localhost".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host)
                || isIpv4In127Block(host);
    }

    /**
     * True only for a dotted-quad in {@code 127.0.0.0/8}.
     *
     * <p>Every octet must be present, numeric and in range, so a hostname with letters or a
     * different number of parts cannot pass however closely it resembles an address.</p>
     */
    private static boolean isIpv4In127Block(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            int value = 0;
            for (int c = 0; c < octet.length(); c++) {
                char digit = octet.charAt(c);
                if (digit < '0' || digit > '9') {
                    return false;
                }
                value = value * 10 + (digit - '0');
            }
            if (value > 255) {
                return false;
            }
        }
        return "127".equals(octets[0]);
    }

    /**
     * A sentence to show the user, naming what is at risk and what to do instead.
     *
     * @return the warning, or {@code null} when the URL is safe
     */
    public static String warningFor(String baseUrl) {
        if (!exposesCredentials(baseUrl)) {
            return null;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return "That server has no address, so a password could not be protected.";
        }
        if (isLoopback(baseUrl)) {
            return null;
        }
        return "This server is not using https, so a password would travel unencrypted and"
                + " could be read by anything on the network. Use https, or connect to"
                + " localhost on your own machine.";
    }

    /**
     * The host portion of a URL, without the scheme, credentials, port or path.
     *
     * <p>The brackets of an IPv6 literal are kept, so the result for
     * {@code http://[::1]:8080/fhir} is {@code [::1]} and the caller compares against the
     * bracketed form. Stripping them would leave a bare {@code ::1} that no longer matches
     * how the URL is written, and the two spellings would have to be kept consistent by
     * hand at every call site.</p>
     */
    private static String hostOf(String url) {
        String rest = url;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            // A URL may carry userinfo. Never treat it as the host, and never log it.
            rest = rest.substring(at + 1);
        }
        if (rest.startsWith("[")) {
            // A bracketed IPv6 literal, e.g. [::1]:8080. The colons inside the brackets are
            // part of the host, so scanning for ':' as the port separator would stop at the
            // first one and yield "[" - which matches neither loopback form and silently
            // turns a local address into a remote one.
            int close = rest.indexOf(']');
            return close > 0 ? rest.substring(0, close + 1) : rest;
        }
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end);
    }

    /**
     * True when the URL carries embedded userinfo, which must never be logged.
     *
     * <p>Not used to reject such a URL — {@link ServerDefinition} refuses one today — but
     * kept here so a future check has one place to ask.
     */
    public static boolean hasEmbeddedCredentials(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String rest = baseUrl.trim();
        int scheme = rest.indexOf("://");
        rest = scheme >= 0 ? rest.substring(scheme + 3) : rest;
        int at = rest.indexOf('@');
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return at >= 0 && at < end;
    }

    /** Never returns anything containing the URL, so it is safe to log. */
    @Override
    public String toString() {
        return "TransportSecurity";
    }
}
