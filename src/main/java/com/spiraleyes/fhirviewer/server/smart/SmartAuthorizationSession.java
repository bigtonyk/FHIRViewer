package com.spiraleyes.fhirviewer.server.smart;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One authorized SMART session: what the server granted, in memory only.
 *
 * <p>Kept separate from {@link SmartLaunchConfig} on purpose: the config
 * says what a launch intends, the session says what one attempt received.
 * A config that changed shape on success would make the second run of a
 * working setup behave differently from the first. Tokens are never written
 * to disk, never logged, and {@link #logout()} clears them; Phase 04 reads
 * this session to build its bearer-authenticated client.</p>
 *
 * <p>The tokens are the one part that changes. A session that knows its token
 * endpoint and client id, and was issued a refresh token, can {@link #refresh renew}
 * itself in place: the Workbench, the shared holder and the Scripting Console all hold
 * this same object, so a renewal reaches every one of them without being handed round.
 * The server, the patient and encounter context and the subject never change — they
 * describe the launch, and a renewal is the same launch with a fresh token.</p>
 */
public final class SmartAuthorizationSession {

    private final String fhirBaseUrl;
    private final String patient;
    private final String encounter;
    private final String subject;
    private final String tokenEndpoint;
    private final String clientId;
    private volatile SmartTokenResponse tokens;
    private volatile String grantedScope;
    private volatile String refreshToken;
    private volatile Instant expiresAt;
    private volatile boolean loggedOut;

    /**
     * A session that cannot renew itself.
     *
     * @param fhirBaseUrl the FHIR base URL this session belongs to
     * @param tokens the grant; never {@code null}
     * @param subject the validated {@code sub} claim, or {@code null} when
     *        no identity scopes were requested
     */
    public SmartAuthorizationSession(String fhirBaseUrl, SmartTokenResponse tokens,
            String subject) {
        this(fhirBaseUrl, tokens, subject, null, null);
    }

    /**
     * A session that can renew itself when the grant carried a refresh token.
     *
     * @param fhirBaseUrl the FHIR base URL this session belongs to
     * @param tokens the grant; never {@code null}
     * @param subject the validated {@code sub} claim, or {@code null}
     * @param tokenEndpoint the token endpoint that issued the grant, or {@code null}
     * @param clientId the public client id the grant was issued to, or {@code null}
     */
    public SmartAuthorizationSession(String fhirBaseUrl, SmartTokenResponse tokens,
            String subject, String tokenEndpoint, String clientId) {
        if (fhirBaseUrl == null || fhirBaseUrl.isBlank()) {
            throw new IllegalArgumentException("A FHIR base URL is required.");
        }
        this.fhirBaseUrl = fhirBaseUrl.strip();
        Objects.requireNonNull(tokens, "tokens");
        this.patient = tokens.patient();
        this.encounter = tokens.encounter();
        this.subject = subject == null || subject.isBlank() ? null : subject.strip();
        this.tokenEndpoint = blankToNull(tokenEndpoint);
        this.clientId = blankToNull(clientId);
        this.refreshToken = blankToNull(tokens.refreshToken());
        this.grantedScope = tokens.grantedScope();
        this.expiresAt = tokens.expiresAt().orElse(null);
        this.tokens = tokens;
    }

    /** The FHIR base URL this session's token may be sent to — and no other. */
    public String fhirBaseUrl() {
        return fhirBaseUrl;
    }

    /** The bearer token, for the Authorization header. Never logged. */
    public String accessToken() {
        return tokens.accessToken();
    }

    /** What the server granted, which may differ from what was requested. */
    public String grantedScope() {
        return grantedScope;
    }

    /** The patient context, when the server supplied one with the first grant. */
    public String patient() {
        return patient;
    }

    /** The encounter context, when the server supplied one with the first grant. */
    public String encounter() {
        return encounter;
    }

    /** The validated subject, when identity scopes were requested. */
    public Optional<String> subject() {
        return Optional.ofNullable(subject);
    }

    /** The refresh token currently held, when the server issued one. Never logged. */
    public Optional<String> refreshToken() {
        return Optional.ofNullable(refreshToken);
    }

    /** When the access token stops working, or empty when unstated. */
    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    /** True once {@link #logout()} has cleared this session. */
    public boolean isLoggedOut() {
        return loggedOut;
    }

    /**
     * True when the access token's stated lifetime has passed. A session
     * with no stated expiry never reports itself expired — the server, not
     * the absence of a claim, decides that.
     */
    public boolean isExpired() {
        return !loggedOut && expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * True when the access token's stated lifetime ends within {@code margin}, or already
     * has. A session with no stated expiry is never about to expire.
     */
    public boolean expiresWithin(Duration margin) {
        Objects.requireNonNull(margin, "margin");
        Instant deadline = expiresAt;
        return !loggedOut && deadline != null && Instant.now().plus(margin).isAfter(deadline);
    }

    /**
     * True when this session holds what a renewal needs: a refresh token, the token
     * endpoint and the client id. A refused renewal drops the refresh token, so this turns
     * false rather than inviting the same refusal again.
     */
    public boolean canRefresh() {
        return !loggedOut && refreshToken != null && tokenEndpoint != null && clientId != null;
    }

    /**
     * True when nothing but a new authorization can make this session usable: it was
     * logged out, or its token has expired and it cannot renew itself.
     */
    public boolean needsReauthorization() {
        return loggedOut || (isExpired() && !canRefresh());
    }

    /**
     * Renews the access token with the refresh token, unless another caller already has.
     *
     * <p>Serialized on this session, because a server that rotates refresh tokens accepts
     * each one once: two callers renewing together would spend the same refresh token
     * twice and the second would fail. {@code staleToken} is the access token the caller
     * found wanting; when the session already holds a different one, someone else renewed
     * it meanwhile and this returns {@code true} without contacting the server.</p>
     *
     * <p>A new grant replaces the access token and its expiry. The refresh token and the
     * granted scope are replaced only when the response carries them, since a server may
     * omit either to mean "unchanged". A refusal drops the refresh token, so
     * {@link #canRefresh()} turns false and the caller is told to authorize again.</p>
     *
     * @param exchange the token client to renew through; never {@code null}
     * @param staleToken the access token the caller last used, or {@code null}
     * @return {@code true} when the session now holds a different access token
     * @throws SmartAuthorizationException when the server refuses or cannot be reached
     */
    public synchronized boolean refresh(SmartTokenExchangeService exchange, String staleToken)
            throws SmartAuthorizationException {
        Objects.requireNonNull(exchange, "exchange");
        if (staleToken != null && !staleToken.equals(tokens.accessToken())) {
            return true;
        }
        if (!canRefresh()) {
            return false;
        }
        SmartTokenResponse renewed;
        try {
            renewed = exchange.refresh(tokenEndpoint, clientId, refreshToken);
        } catch (SmartAuthorizationException refused) {
            if (refused.kind() == SmartAuthorizationException.Kind.TOKEN_EXCHANGE_FAILED) {
                // The server said no; asking again with the same token will not change
                // its answer. A network failure keeps the token for the next attempt.
                refreshToken = null;
            }
            throw refused;
        }
        if (loggedOut) {
            // Logged out while the renewal was in flight: keep nothing it returned.
            return false;
        }
        if (renewed.hasRefreshToken()) {
            refreshToken = renewed.refreshToken().strip();
        }
        if (renewed.grantedScope() != null && !renewed.grantedScope().isBlank()) {
            grantedScope = renewed.grantedScope();
        }
        expiresAt = renewed.expiresAt().orElse(null);
        tokens = renewed;
        return true;
    }

    /**
     * Clears local session state; the refresh token is dropped with it.
     *
     * <p>Not synchronized: a renewal holds the session's lock across a network call, and
     * logging out from the UI thread must not wait for it. A renewal that finishes after
     * this sees {@link #isLoggedOut()} and keeps nothing.</p>
     */
    public void logout() {
        loggedOut = true;
        refreshToken = null;
    }

    /** A short description; never includes any token or subject. */
    public String describe() {
        return tokens.describe() + (loggedOut ? " (logged out)" : "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Never prints a token, a subject, or patient context. */
    @Override
    public String toString() {
        return "SmartAuthorizationSession[server=" + fhirBaseUrl
                + (loggedOut ? ", logged out" : ", active") + "]";
    }
}
