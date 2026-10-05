package com.example.fhirviewer.server;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What an OAuth 2.0 token endpoint answered, when it answered with one.
 *
 * <p>Parsing is a static method returning an {@link Optional} rather than a constructor
 * that throws, and the reason is worth stating. Two quite different things can come back
 * from a token endpoint: a refusal, which arrives as an HTTP status and is handled by
 * {@link RestFailures} before this class is consulted, and a {@code 200} whose body is not
 * a grant at all — an HTML login page from a misconfigured endpoint, a proxy's error
 * document, an {@code {"error": "invalid_client"}} body. Only the last of those is
 * semantically different from the other two, and treating all of them as "no token" with an
 * empty result is what keeps a wrong client secret from surfacing as a blank
 * {@code Authorization} header and a {@code 401} that looks like a permissions problem.
 *
 * <p>It uses the same Jackson {@link ObjectMapper} as {@code service.PackageRegistryService}
 * rather than pulling in a parser, and it never throws: anything that is not a usable grant
 * document is an empty result.
 *
 * @param accessToken    the bearer token; never {@code null} or blank
 * @param tokenType      the type, normally {@code Bearer}; may be {@code null}
 * @param expiresInSeconds the lifetime, or {@code null} when the endpoint did not say
 */
public record TokenFetchResult(String accessToken, String tokenType, Long expiresInSeconds) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** True when the endpoint told us when this stops working. */
    public boolean hasExpiry() {
        return expiresInSeconds != null;
    }

    /** A short description for a dialog; never includes the token itself. */
    public String describe() {
        StringBuilder text = new StringBuilder("Access token received");
        if (tokenType != null && !tokenType.isBlank()) {
            text.append(" (").append(tokenType.strip()).append(')');
        }
        if (hasExpiry()) {
            text.append(", expires in ").append(expiresInSeconds).append(" seconds");
        }
        return text.toString();
    }

    /**
     * Reads a grant document.
     *
     * @param body the response body, or {@code null}
     * @return the grant, or empty when the body is not one — which covers an error
     *         document, a login page and malformed JSON alike
     */
    public static Optional<TokenFetchResult> from(String body) {
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
                // Covers {"error":"invalid_client"} as well as a document missing the field.
                return Optional.empty();
            }
            JsonNode type = root.get("token_type");
            JsonNode expires = root.get("expires_in");
            Long lifetime = null;
            if (expires != null && expires.canConvertToLong()) {
                lifetime = expires.asLong();
            }
            return Optional.of(new TokenFetchResult(token.asText().strip(),
                    type != null && type.isTextual() ? type.asText() : null,
                    lifetime));
        } catch (Exception notJson) {
            // An HTML page from a misconfigured endpoint, or truncated output. Not a grant.
            return Optional.empty();
        }
    }

    /**
     * Never prints the token.
     *
     * <p>This record travels into a log line or an exception message sooner than one would
     * like, and an access token is a live credential for as long as it is valid.
     */
    @Override
    public String toString() {
        return "TokenFetchResult[" + (tokenType == null ? "token" : tokenType.strip())
                + ", " + (hasExpiry() ? "expires in " + expiresInSeconds + "s" : "no expiry")
                + "]";
    }
}