package com.spiraleyes.fhirviewer.server.smart;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWK;

/**
 * Fetches a server's JSON Web Key Set for ID-token signature checks.
 *
 * <p>Uses the JDK {@link HttpClient} directly, like
 * {@link SmartDiscoveryService}: the JWKS decides where trust comes from,
 * so redirects are never followed, the body is capped, and TLS is never
 * weakened. A missing or unreachable JWKS is an empty result rather than
 * a failure — the validator then reports the token unverifiable, which is
 * the honest diagnosis when a server publishes none.</p>
 */
public final class SmartJwksFetcher {

    /** Largest key set read; a JWKS is a few kilobytes. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final Duration connectTimeout;
    private final Duration requestTimeout;

    public SmartJwksFetcher() {
        this(SmartDiscoveryService.CONNECT_TIMEOUT, SmartDiscoveryService.REQUEST_TIMEOUT);
    }

    public SmartJwksFetcher(Duration connectTimeout, Duration requestTimeout) {
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    /** @return the published keys, or empty when none could be read */
    List<JWK> fetch(String jwksUri) {
        if (jwksUri == null || jwksUri.isBlank()) {
            return List.of();
        }
        URI uri;
        try {
            uri = URI.create(jwksUri.strip());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        if ("http".equalsIgnoreCase(uri.getScheme())
                && !SmartUrls.isLoopback(uri.getHost())) {
            return List.of();
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            return parse(readCapped(response.body()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    private String readCapped(InputStream body) throws IOException {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new IOException("JWKS larger than " + MAX_BODY_BYTES + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static List<JWK> parse(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            JsonNode keys = root == null ? null : root.get("keys");
            if (keys == null || !keys.isArray()) {
                return List.of();
            }
            List<JWK> parsed = new ArrayList<>();
            for (JsonNode key : keys) {
                try {
                    parsed.add(JWK.parse(key.toString()));
                } catch (java.text.ParseException skipped) {
                    // One unparsable key does not cost the usable ones.
                }
            }
            return List.copyOf(parsed);
        } catch (Exception notJson) {
            return List.of();
        }
    }
}
