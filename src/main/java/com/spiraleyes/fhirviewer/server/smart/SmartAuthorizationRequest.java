package com.spiraleyes.fhirviewer.server.smart;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the SMART standalone authorization URL (SMART App Launch v2.2.0
 * standalone launch, Authorization Code with PKCE S256).
 *
 * <p>Built with {@link URI} construction rather than query-string
 * concatenation: hand-joined parameters are how a scope containing a space,
 * or a redirect URI containing a query, becomes a broken request. Every
 * parameter is validated before use, and each failure names the field.</p>
 *
 * <p>Sends {@code response_type=code}, {@code client_id},
 * {@code redirect_uri}, {@code scope}, {@code state},
 * {@code code_challenge}/{@code code_challenge_method=S256}, {@code aud}
 * (the FHIR base URL, required for standalone launch), and {@code nonce}
 * when OIDC identity scopes are requested.</p>
 */
public final class SmartAuthorizationRequest {

    /** The response this application can handle; the only one it sends. */
    public static final String RESPONSE_TYPE_CODE = "code";

    /** Scopes that turn this into an OIDC request needing a nonce. */
    private static final List<String> OIDC_SCOPES = List.of("openid", "profile", "email",
            "address", "phone", "fhirUser");

    private final String authorizationUrl;
    private final String clientId;
    private final String redirectUri;
    private final String scopes;
    private final SmartAuthState authState;
    private final Pkce pkce;
    private final String audience;
    private final String launchParameter;

    private SmartAuthorizationRequest(Builder builder) {
        this.authorizationUrl = builder.authorizationUrl;
        this.clientId = builder.clientId;
        this.redirectUri = builder.redirectUri;
        this.scopes = builder.scopes;
        this.authState = builder.authState;
        this.pkce = builder.pkce;
        this.audience = builder.audience;
        this.launchParameter = builder.launchParameter;
    }

    /** @return the full authorization URL to open in the system browser */
    public URI toUri() {
        List<String[]> params = new ArrayList<>();
        params.add(new String[] {"response_type", RESPONSE_TYPE_CODE});
        params.add(new String[] {"client_id", clientId});
        params.add(new String[] {"redirect_uri", redirectUri});
        if (scopes != null && !scopes.isBlank()) {
            params.add(new String[] {"scope", scopes.strip()});
        }
        params.add(new String[] {"state", authState.state()});
        params.add(new String[] {"code_challenge", pkce.challenge()});
        params.add(new String[] {"code_challenge_method", pkce.method()});
        if (audience != null && !audience.isBlank()) {
            params.add(new String[] {"aud", audience.strip()});
        }
        if (needsNonce(scopes)) {
            params.add(new String[] {"nonce", authState.nonce()});
        }
        if (launchParameter != null && !launchParameter.isBlank()) {
            params.add(new String[] {"launch", launchParameter.strip()});
        }
        try {
            URI base = new URI(authorizationUrl);
            String query = encodeParams(params);
            String existing = base.getRawQuery();
            String merged = existing == null || existing.isBlank()
                    ? query : existing + "&" + query;
            // Single-string construction: the multi-argument constructor would
            // quote the already-encoded query a second time.
            StringBuilder rebuilt = new StringBuilder();
            rebuilt.append(base.getScheme()).append("://").append(base.getAuthority());
            if (base.getRawPath() != null) {
                rebuilt.append(base.getRawPath());
            }
            rebuilt.append('?').append(merged);
            if (base.getRawFragment() != null) {
                rebuilt.append('#').append(base.getRawFragment());
            }
            return new URI(rebuilt.toString());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "The authorization endpoint is not a usable URL: " + e.getMessage(), e);
        }
    }

    /** True when the requested scopes include an OIDC identity scope. */
    public static boolean needsNonce(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return false;
        }
        for (String scope : scopes.strip().split("\\s+")) {
            if (OIDC_SCOPES.contains(scope)) {
                return true;
            }
        }
        return false;
    }

    /** The state to expect back in the callback; kept by the caller. */
    public SmartAuthState authState() {
        return authState;
    }

    /** The PKCE pair whose verifier the token exchange will send. */
    public Pkce pkce() {
        return pkce;
    }

    static String encodeParams(List<String[]> params) {
        StringBuilder query = new StringBuilder();
        for (String[] param : params) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(encode(param[0])).append('=').append(encode(param[1]));
        }
        return query.toString();
    }

    /** Percent-encoding for query values (space becomes %20, not +). */
    static String encode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                encoded.append((char) c);
            } else if (c == ' ') {
                encoded.append("%20");
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return encoded.toString();
    }

    /** Starts a request against an authorization endpoint. */
    public static Builder to(String authorizationEndpoint) {
        return new Builder(authorizationEndpoint);
    }

    /** Names the endpoint only; the URL carries state and never appears here. */
    @Override
    public String toString() {
        return "SmartAuthorizationRequest[endpoint=" + authorizationUrl + "]";
    }

    /** One request, validated field by field as it is assembled. */
    public static final class Builder {

        private final String authorizationUrl;
        private String clientId;
        private String redirectUri;
        private String scopes;
        private SmartAuthState authState;
        private Pkce pkce;
        private String audience;
        private String launchParameter;

        private Builder(String authorizationEndpoint) {
            if (authorizationEndpoint == null || authorizationEndpoint.isBlank()) {
                throw new IllegalArgumentException("An authorization endpoint is required.");
            }
            URI parsed = SmartUrls.parseOrNull(authorizationEndpoint.strip());
            if (parsed == null) {
                throw new IllegalArgumentException(
                        "The authorization endpoint is not an absolute http(s) URL.");
            }
            if ("http".equalsIgnoreCase(parsed.getScheme())
                    && !SmartUrls.isLoopback(parsed.getHost())) {
                throw new IllegalArgumentException(
                        "The authorization endpoint must be https, or http on this machine only.");
            }
            this.authorizationUrl = authorizationEndpoint.strip();
        }

        /** @param value the registered public client id; never blank */
        public Builder clientId(String value) {
            this.clientId = value;
            return this;
        }

        /**
         * @param value the loopback redirect URI; must be http(s) on this
         *        machine — a non-loopback redirect would send the
         *        authorization code to someone else's server
         */
        public Builder redirectUri(String value) {
            this.redirectUri = value;
            return this;
        }

        /** @param value space-separated SMART scopes; may be blank */
        public Builder scopes(String value) {
            this.scopes = value;
            return this;
        }

        /** @param value the state/nonce pair; a fresh one when omitted */
        public Builder authState(SmartAuthState value) {
            this.authState = value;
            return this;
        }

        /** @param value the PKCE pair; a fresh one when omitted */
        public Builder pkce(Pkce value) {
            this.pkce = value;
            return this;
        }

        /** @param value the {@code aud} — the FHIR base URL for standalone */
        public Builder audience(String value) {
            this.audience = value;
            return this;
        }

        /** @param value the EHR-supplied launch token; standalone never sets this */
        public Builder launchParameter(String value) {
            this.launchParameter = value;
            return this;
        }

        /** @return the validated request */
        public SmartAuthorizationRequest build() {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("An OAuth client id is required.");
            }
            if (redirectUri == null || redirectUri.isBlank()) {
                throw new IllegalArgumentException("A redirect URI is required.");
            }
            URI redirect;
            try {
                redirect = new URI(redirectUri.strip());
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(
                        "The redirect URI is not a valid URI: " + e.getMessage(), e);
            }
            String scheme = redirect.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException(
                        "The redirect URI must be an http(s) loopback address.");
            }
            if (!SmartUrls.isLoopback(redirect.getHost())) {
                throw new IllegalArgumentException(
                        "The redirect URI must be a loopback address on this machine.");
            }
            if (authState == null) {
                authState = SmartAuthState.generate();
            }
            if (pkce == null) {
                pkce = Pkce.generate();
            }
            Objects.requireNonNull(authState, "authState");
            Objects.requireNonNull(pkce, "pkce");
            return new SmartAuthorizationRequest(this);
        }
    }
}
