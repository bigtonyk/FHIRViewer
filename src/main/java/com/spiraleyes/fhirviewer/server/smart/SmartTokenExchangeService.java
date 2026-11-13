package com.spiraleyes.fhirviewer.server.smart;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Exchanges an authorization code for tokens (authorization-code grant
 * with PKCE, public client — no client secret).
 *
 * <p>A deliberate sibling of {@code server.TokenEndpointClient}: that
 * client sends {@code grant_type=client_credentials} with a secret, while
 * this one sends {@code grant_type=authorization_code} with the PKCE
 * verifier and the exact redirect URI from the authorize step.</p>
 */
public final class SmartTokenExchangeService {

    /** How long the connection may take. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** How long the whole exchange may take. */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** Largest token response read; a grant document is a few kilobytes. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final Duration connectTimeout;
    private final Duration requestTimeout;

    /** A service with the default deadlines. */
    public SmartTokenExchangeService() {
        this(CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    /** A service with explicit deadlines, so a test can ask for short ones. */
    public SmartTokenExchangeService(Duration connectTimeout, Duration requestTimeout) {
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    /**
     * Exchanges the code this attempt's callback delivered.
     *
     * @param tokenEndpoint the token URL from discovery; never blank
     * @param clientId the public client id; never blank
     * @param code the authorization code; never blank
     * @param redirectUri the exact redirect URI from the authorize step
     * @param verifier the PKCE verifier for this attempt; never blank
     * @return the grant the endpoint issued
     * @throws SmartAuthorizationException when the endpoint refuses, answers
     *         unusably, or cannot be reached
     */
    public SmartTokenResponse exchange(String tokenEndpoint, String clientId, String code,
            String redirectUri, String verifier) throws SmartAuthorizationException {
        require(tokenEndpoint, "A token endpoint is required for the exchange.");
        require(clientId, "An OAuth client id is required for the exchange.");
        require(code, "An authorization code is required for the exchange.");
        require(redirectUri, "The redirect URI from the authorization request is required.");
        require(verifier, "The PKCE verifier from the authorization request is required.");
        URI endpoint = parseEndpoint(tokenEndpoint.strip());
        String form = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri.strip())
                + "&client_id=" + encode(clientId.strip())
                + "&code_verifier=" + encode(verifier);
        HttpResponse<InputStream> response = post(endpoint, form);
        int status = response.statusCode();
        String body = readCapped(response.body());
        if (status < 200 || status >= 300) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.TOKEN_EXCHANGE_FAILED,
                    "The token endpoint refused the exchange (HTTP " + status + ")"
                            + errorDetail(body) + ".");
        }
        return SmartTokenResponse.from(body).orElseThrow(() ->
                new SmartAuthorizationException(
                        SmartAuthorizationException.Kind.TOKEN_EXCHANGE_FAILED,
                        "The token endpoint answered without an access token"
                                + errorDetail(body) + "."));
    }

    private URI parseEndpoint(String tokenEndpoint) throws SmartAuthorizationException {
        URI endpoint;
        try {
            endpoint = URI.create(tokenEndpoint);
        } catch (IllegalArgumentException e) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.INVALID_REQUEST,
                    "The token endpoint is not a usable URL.", e);
        }
        if ("http".equalsIgnoreCase(endpoint.getScheme())
                && !SmartUrls.isLoopback(endpoint.getHost())) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.INVALID_REQUEST,
                    "The token endpoint must be https, or http on this machine only.");
        }
        return endpoint;
    }

    private HttpResponse<InputStream> post(URI endpoint, String form)
            throws SmartAuthorizationException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SmartAuthorizationException(SmartAuthorizationException.Kind.NETWORK,
                    "The token endpoint could not be reached: " + describe(e), e);
        }
    }

    private String readCapped(InputStream body) throws SmartAuthorizationException {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new SmartAuthorizationException(
                        SmartAuthorizationException.Kind.TOKEN_EXCHANGE_FAILED,
                        "The token endpoint answered with more data than a token response is.");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SmartAuthorizationException(SmartAuthorizationException.Kind.NETWORK,
                    "The token response could not be read: " + describe(e), e);
        }
    }

    /** The server's own error words, parsed — never a raw HTML page. */
    static String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (root != null && root.isObject()) {
                String error = textOf(root, "error");
                String description = textOf(root, "error_description");
                if (error != null) {
                    return ": " + error + (description == null ? "" : " — " + description);
                }
            }
        } catch (Exception notJson) {
            // Not a JSON error document; nothing quotable in it.
        }
        return "";
    }

    private static String textOf(com.fasterxml.jackson.databind.JsonNode root, String field) {
        com.fasterxml.jackson.databind.JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        String text = node.asText().strip();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    static String encode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                encoded.append((char) c);
            } else if (c == ' ') {
                encoded.append('+');
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return encoded.toString();
    }

    private static void require(String value, String message)
            throws SmartAuthorizationException {
        if (value == null || value.isBlank()) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.INVALID_REQUEST, message);
        }
    }

    private static String describe(Throwable problem) {
        String message = problem.getMessage();
        return message == null || message.isBlank()
                ? problem.getClass().getSimpleName() : message;
    }
}

