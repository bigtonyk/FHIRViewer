package com.example.fhirviewer.server;

/**
 * How a request to a FHIR server is authenticated.
 *
 * <p>Authentication is its own interface so new mechanisms (OAuth 2.0, SMART on FHIR,
 * API keys, vendor-specific flows) can be added without touching the plugin or server
 * configuration types. It is deliberately separate from the REST operation code: a
 * plugin asks for a {@link ServerSession} and the transport applies whatever headers
 * the mechanism contributes.</p>
 *
 * <p><b>Adding a mechanism.</b> Implement the three original methods and override
 * {@link #requestHeaders()}. Nothing that sends a request needs to change: the HAPI
 * client and the generic transport both apply the returned headers, and each has a
 * default that contributes nothing, so existing implementations keep working and
 * existing compiled plugins keep linking.</p>
 */
public interface ServerAuthentication {

    /** A short id such as <code>anonymous</code>, <code>basic</code> or <code>bearer</code>. */
    String type();

    /** A human readable label shown in the server dialog. */
    String displayName();

    /** True when this provider carries no secret at all. */
    boolean isAnonymous();

    /**
     * The header fields to send with every request this authentication is used for.
     *
     * <p><b>This is the extension point.</b> An implementation returns the headers its
     * mechanism needs — {@code Authorization: Basic ...}, {@code Authorization: Bearer
     * ...}, or a vendor API key — and both clients apply them without knowing which
     * mechanism produced them. That is what removed the need for an
     * {@code instanceof BasicServerAuthentication} branch in the client wiring, and it
     * is why a bearer or SMART provider does not require a change there either.</p>
     *
     * <p>Values are credentials: they travel in the {@code Authorization} header of
     * every request and must never be logged or printed. {@link RequestHeaders}
     * redacts them in {@code toString()}, so returning one is safe to describe in a
     * message; returning a bare {@code Map} would not be.</p>
     *
     * <p>Defaults to nothing, which is correct for anonymous access and keeps any
     * implementation compiled against the previous three-method interface working
     * unchanged.</p>
     *
     * @return the headers to apply; never {@code null}
     */
    default RequestHeaders requestHeaders() {
        return RequestHeaders.none();
    }
}

