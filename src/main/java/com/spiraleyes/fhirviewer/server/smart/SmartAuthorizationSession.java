package com.spiraleyes.fhirviewer.server.smart;

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
 */
public final class SmartAuthorizationSession {

    private final String fhirBaseUrl;
    private final SmartTokenResponse tokens;
    private final String grantedScope;
    private final Instant expiresAt;
    private final String subject;
    private volatile boolean loggedOut;

    /**
     * @param fhirBaseUrl the FHIR base URL this session belongs to
     * @param tokens the grant; never {@code null}
     * @param subject the validated {@code sub} claim, or {@code null} when
     *        no identity scopes were requested
     */
    public SmartAuthorizationSession(String fhirBaseUrl, SmartTokenResponse tokens,
            String subject) {
        if (fhirBaseUrl == null || fhirBaseUrl.isBlank()) {
            throw new IllegalArgumentException("A FHIR base URL is required.");
        }
        this.fhirBaseUrl = fhirBaseUrl.strip();
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.grantedScope = tokens.grantedScope();
        this.expiresAt = tokens.expiresAt().orElse(null);
        this.subject = subject == null || subject.isBlank() ? null : subject.strip();
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

    /** The patient context, when the server supplied one. */
    public String patient() {
        return tokens.patient();
    }

    /** The encounter context, when the server supplied one. */
    public String encounter() {
        return tokens.encounter();
    }

    /** The validated subject, when identity scopes were requested. */
    public Optional<String> subject() {
        return Optional.ofNullable(subject);
    }

    /** The refresh token, when the server issued one. Never logged. */
    public Optional<String> refreshToken() {
        String value = tokens.refreshToken();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
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

    /** Clears local session state; the refresh token is dropped with it. */
    public void logout() {
        loggedOut = true;
    }

    /** A short description; never includes any token or subject. */
    public String describe() {
        return tokens.describe() + (loggedOut ? " (logged out)" : "");
    }

    /** Never prints a token, a subject, or patient context. */
    @Override
    public String toString() {
        return "SmartAuthorizationSession[server=" + fhirBaseUrl
                + (loggedOut ? ", logged out" : ", active") + "]";
    }
}
