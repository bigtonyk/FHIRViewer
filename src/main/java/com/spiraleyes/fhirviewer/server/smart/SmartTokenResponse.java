package com.spiraleyes.fhirviewer.server.smart;

import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What a token endpoint answered to an authorization-code exchange.
 *
 * <p>Extends the shape {@code server.TokenFetchResult} models for the
 * client-credentials grant with the fields SMART actually returns: granted
 * {@code scope}, {@code patient} and {@code encounter} context, {@code
 * id_token}, and the refresh token when the server issues one. Parsing is a
 * static method returning an {@link Optional}, following
 * {@code TokenFetchResult}: a {@code 200} whose body is not a grant — an
 * HTML page, an {@code {"error": ...}} document — is "no token", while an
 * HTTP refusal never reaches this class.</p>
 *
 * <p>Tokens live only in this instance, which lives only in the session.
 * {@link #toString()} and {@link #describe()} never print one.</p>
 *
 * @param accessToken the bearer token; never blank
 * @param tokenType the type, normally {@code Bearer}; may be {@code null}
 * @param expiresInSeconds the lifetime, or {@code null} when unstated
 * @param grantedScope the scopes granted, or {@code null} when unstated
 * @param patient the patient context, or {@code null} when absent
 * @param encounter the encounter context, or {@code null} when absent
 * @param idToken the OIDC identity token, or {@code null} when absent
 * @param refreshToken the refresh token, or {@code null} when not issued
 */
public record SmartTokenResponse(String accessToken, String tokenType, Long expiresInSeconds,
        String grantedScope, String patient, String encounter, String idToken,
        String refreshToken) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** True when the endpoint told us when this stops working. */
    public boolean hasExpiry() {
        return expiresInSeconds != null;
    }

    /** True when the server issued a refresh token with this grant. */
    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    /** When this grant stops working, or empty when the endpoint did not say. */
    public Optional<Instant> expiresAt() {
        if (expiresInSeconds == null) {
            return Optional.empty();
        }
        // No clamping: a negative lifetime is already expired, and hiding
        // that would make isExpired lie about a dead token.
        return Optional.of(Instant.now().plusSeconds(expiresInSeconds));
    }

    /** A short description for a dialog; never includes any token. */
    public String describe() {
        StringBuilder text = new StringBuilder("Access token received");
        if (tokenType != null && !tokenType.isBlank()) {
            text.append(" (").append(tokenType.strip()).append(')');
        }
        if (hasExpiry()) {
            text.append(", expires in ").append(expiresInSeconds).append(" seconds");
        }
        if (grantedScope != null && !grantedScope.isBlank()) {
            text.append(", granted ").append(grantedScope.strip().split("\\s+").length)
                    .append(" scope(s)");
        } else {
            text.append(", granted scopes not stated");
        }
        if (patient != null && !patient.isBlank()) {
            text.append(", patient context present");
        } else {
            text.append(", no patient context");
        }
        text.append(hasRefreshToken() ? ", refresh token issued" : ", no refresh token");
        return text.toString();
    }

    /**
     * Reads a grant document.
     *
     * @param body the response body, or {@code null}
     * @return the grant, or empty when the body is not one
     */
    public static Optional<SmartTokenResponse> from(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            JsonNode token = root.get("access_token");
            if (token == null || !token.isTextual() || token.asText().isBlank()) {
                return Optional.empty();
            }
            Long lifetime = null;
            JsonNode expires = root.get("expires_in");
            if (expires != null && expires.canConvertToLong()) {
                lifetime = expires.asLong();
            }
            return Optional.of(new SmartTokenResponse(token.asText().strip(),
                    textOrNull(root, "token_type"), lifetime, textOrNull(root, "scope"),
                    textOrNull(root, "patient"), textOrNull(root, "encounter"),
                    textOrNull(root, "id_token"), textOrNull(root, "refresh_token")));
        } catch (Exception notJson) {
            return Optional.empty();
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().strip();
    }

    /** Never prints any token. */
    @Override
    public String toString() {
        return "SmartTokenResponse[" + (tokenType == null ? "token" : tokenType.strip())
                + ", " + (hasExpiry() ? "expires in " + expiresInSeconds + "s" : "no expiry")
                + ", " + (hasRefreshToken() ? "refreshable" : "no refresh token") + "]";
    }
}
