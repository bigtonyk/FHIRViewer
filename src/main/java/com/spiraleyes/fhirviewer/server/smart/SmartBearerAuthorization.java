package com.spiraleyes.fhirviewer.server.smart;

import com.spiraleyes.fhirviewer.server.RequestHeaders;
import com.spiraleyes.fhirviewer.server.ServerAuthentication;

/**
 * Supplies bearer authorization to requests that belong to one authorized session.
 *
 * <p>The whole point of this class is the rule Phase 04 exists for: a SMART token is
 * attached only to requests that go to the server that granted it, never to another
 * configured server and never to an administrative or plugin endpoint. The authorization
 * flows through {@link ServerAuthentication}, which is the single seam every HTTP path in
 * the project already uses, so the HAPI client in
 * {@link com.spiraleyes.fhirviewer.server.StandardFhirRestPlugin} applies it without
 * change and a future vendor plugin needs no change either.</p>
 *
 * <p>A session is immutable once built and is never stored in a global singleton: every
 * caller holds its own reference, and a different session means a different
 * authentication object.</p>
 */
public final class SmartBearerAuthorization implements ServerAuthentication {

    /** The header this mechanism contributes, shared with the other bearer implementations. */
    public static final String AUTHORIZATION = RequestHeaders.AUTHORIZATION;

    private static final String SCHEME = "Bearer ";

    private final String token;
    private final String fhirBaseUrl;

    /**
     * @param session the authorized session the bearer is carved out of; never {@code null}
     * @throws IllegalArgumentException when the session has no token to send
     */
    public SmartBearerAuthorization(SmartAuthorizationSession session) {
        if (session == null) {
            throw new IllegalArgumentException("A session is required for bearer authorization.");
        }
        String token = session.accessToken();
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "The session has no access token: the server granted no token for this launch.");
        }
        this.token = token.trim();
        this.fhirBaseUrl = session.fhirBaseUrl();
    }

    /**
     * The FHIR base URL this authorization is valid for, used to enforce that the
     * token is never sent to a server the session did not authorize.
     */
    public String fhirBaseUrl() {
        return fhirBaseUrl;
    }

    @Override
    public String type() {
        return "smart-bearer";
    }

    @Override
    public String displayName() {
        return "SMART authorization";
    }

    @Override
    public boolean isAnonymous() {
        return false;
    }

    /**
     * The header set this mechanism contributes, the single extension point both HTTP
     * stacks apply.
     *
     * <p>Only {@link #AUTHORIZATION} is contributed here: a bearer token travels alone.
     * The value is the bare token, and {@link RequestHeaders} redacts it in {@code
     * toString()} so a log line can never carry it.</p>
     */
    @Override
    public RequestHeaders requestHeaders() {
        return RequestHeaders.of(AUTHORIZATION, SCHEME + token);
    }

    /**
     * A description safe for a log line or a dialog: the mechanism and the server
     * the token may be sent to, and never a single character of the token itself.
     */
    @Override
    public String toString() {
        return "SmartBearerAuthorization[bearer to " + fhirBaseUrl + "]";
    }
}
