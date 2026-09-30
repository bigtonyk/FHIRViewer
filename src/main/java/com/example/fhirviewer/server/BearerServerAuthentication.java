package com.example.fhirviewer.server;

import java.util.Objects;

/**
 * Bearer authentication: an access token sent as {@code Authorization: Bearer ...}.
 *
 * <p>This is the half of OAuth 2.0 and SMART on FHIR that is genuinely needed now: the
 * point at which a token is <em>used</em>. Whatever obtained the token — a SMART
 * backend-services or authorization-code flow, a gateway that issues one, or a user who
 * pasted one in — presents it here and the rest of the application does not change.
 * That is deliberate: the authorization-code flow needs a browser, a client
 * registration and a token cache, none of which belong in a transport phase, and
 * building a half of one would be worse than not starting it.</p>
 *
 * <p>The token is held for the lifetime of a {@link ServerSession} and is never
 * persisted by this class; saving it is {@link PluginSettingsStore}'s job. It is
 * treated as a secret everywhere: {@link #toString()} never prints it, and
 * {@link RequestHeaders} redacts it in the header set this contributes.</p>
 *
 * <p>Because a token can expire, an operation may come back {@code 401}. That is
 * reported as {@link ServerOperationException.Kind#UNAUTHORIZED}, which the UI already
 * renders as "the server rejected the credentials" — re-authenticating is the
 * application's decision, not this class's.</p>
 */
public final class BearerServerAuthentication implements ServerAuthentication {

    private final String token;

    /**
     * @param token the access token; must not be blank
     */
    public BearerServerAuthentication(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("An access token is required for bearer authentication.");
        }
        this.token = token.trim();
    }

    @Override
    public String type() {
        return "bearer";
    }

    @Override
    public String displayName() {
        return "Bearer token";
    }

    @Override
    public boolean isAnonymous() {
        return false;
    }

    @Override
    public RequestHeaders requestHeaders() {
        return RequestHeaders.of(RequestHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /**
     * How much of the token may safely be shown to a user.
     *
     * <p>Enough to tell two tokens apart when diagnosing which one is in use, and not
     * enough to use one. A JWT's three dot-separated segments make this especially
     * safe, since the header and payload are not secret by design.
     */
    public String describeToken() {
        return token.substring(0, Math.min(6, token.length())) + "...";
    }

    @Override
    public String toString() {
        Objects.requireNonNull(token);
        return "Bearer token " + describeToken();
    }
}
