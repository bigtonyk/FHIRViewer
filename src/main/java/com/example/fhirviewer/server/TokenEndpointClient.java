package com.example.fhirviewer.server;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import com.example.fhirviewer.server.rest.JdkHttpRestClient;
import com.example.fhirviewer.server.rest.RestFailures;
import com.example.fhirviewer.server.rest.RestMethod;
import com.example.fhirviewer.server.rest.RestRequest;
import com.example.fhirviewer.server.rest.RestResponse;

/**
 * Runs an OAuth 2.0 {@code client_credentials} grant against a token endpoint.
 *
 * <p>This is the "get a bearer token" half of the console. It exists because
 * {@link BearerServerAuthentication} is only the point at which a token is <em>used</em>:
 * something has to obtain one, and the two halves are deliberately separate so a
 * gateway-issued token pasted by a user takes exactly the same road as one obtained here.
 *
 * <p><b>Only {@code client_credentials}.</b> Not the authorization-code flow, not a browser
 * hand-off, not SMART on FHIR. Those need a client registration and a durable token store
 * with an expiry schedule, which is a different piece of work with its own persistence
 * questions. {@code client_credentials} is the grant that fits a debugging window: no
 * login, no browser, and nothing that has to survive between runs. "A way to get a bearer
 * token" and "SMART on FHIR" are two features, and this is the first.
 *
 * <p><b>The transport is the project's, not a new one.</b> The grant is an ordinary
 * {@code POST} of a form-encoded body, which {@link RestRequest} already expresses and
 * {@link JdkHttpRestClient} already sends. The endpoint is usually on a different origin
 * from the FHIR server — the normal shape of OAuth — so the URL is split into an origin the
 * client is built with and a path it is given, rather than assuming the two are related.
 *
 * <p><b>Nothing is persisted.</b> The token and the client secret both live only as long as
 * the dialog holding them. See {@code docs/plans/11_REST_CONSOLE_UI.md}, decision DD3.
 */
public final class TokenEndpointClient {

    /** How long one grant may take, independent of the FHIR server's own timeout. */
    public static final int TIMEOUT_MILLIS = 20_000;

/**
     * Obtains a token.
     *
     * @param tokenEndpoint the full token URL, for example {@code https://auth.example.com/token}
     * @param clientId      the registered client id
     * @param clientSecret  the client secret; the caller keeps ownership and should wipe it
     * @param scope         an optional scope, or {@code null} for the endpoint's default
     * @return the grant the endpoint issued
     * @throws ServerOperationException when the endpoint could not be reached, refused the
     *         credentials, or answered with something that is not a token. The message
     *         carries the server's own words where it gave any.
     * @throws IllegalArgumentException when the endpoint URL is unusable
     */
    public TokenFetchResult fetch(String tokenEndpoint, String clientId, char[] clientSecret,
            String scope) throws ServerOperationException {
        if (tokenEndpoint == null || tokenEndpoint.isBlank()) {
            throw new IllegalArgumentException("Enter the token endpoint URL.");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Enter the client id.");
        }
        URI endpoint = parse(tokenEndpoint);
        String origin = endpoint.getScheme() + "://" + endpoint.getAuthority();
        String path = endpoint.getRawPath() == null || endpoint.getRawPath().isEmpty()
                ? "/"
                : endpoint.getRawPath()
                        + (endpoint.getRawQuery() == null ? "" : "?" + endpoint.getRawQuery());

        // The form body is a String and cannot be wiped; that copy is unavoidable once the
        // body exists, which is why the caller wipes the secret as soon as this returns.
        String form = formBody(clientId, clientSecret, scope);
        RestRequest request = RestRequest.builder(RestMethod.POST, path)
                .body(form)
                .contentType("application/x-www-form-urlencoded")
                .accept("application/json")
                .build();

        try (JdkHttpRestClient client = new JdkHttpRestClient(origin, TIMEOUT_MILLIS)) {
            RestResponse response = client.execute(request);
            if (!response.isSuccess()) {
                // A 401 or a 400 here is almost always a wrong secret or a wrong grant, and
                // the endpoint's own error body is the only thing that says which.
                throw RestFailures.httpFailure("Obtain a token from " + endpoint.getHost(), response);
            }
            return TokenFetchResult.from(response.bodyOrEmpty())
                    .orElseThrow(() -> new ServerOperationException(
                            ServerOperationException.Kind.BAD_REQUEST,
                            "The token endpoint answered HTTP " + response.statusCode()
                                    + " without an access_token. It may not be a token endpoint, "
                                    + "or it may want a different grant."));
        }
    }

    /** The warning for a token endpoint, or {@code null} when it is safe. */
    public static String transportWarning(String tokenEndpoint) {
        return TransportSecurity.warningFor(tokenEndpoint);
    }

    private static URI parse(String tokenEndpoint) {
        URI endpoint;
        try {
            endpoint = new URI(tokenEndpoint.trim());
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException(
                    "That is not a usable token endpoint URL: " + malformed.getReason());
        }
        if (endpoint.getScheme() == null || endpoint.getAuthority() == null) {
            throw new IllegalArgumentException(
                    "The token endpoint must be a full http:// or https:// URL.");
        }
        return endpoint;
    }

    /**
     * The form-encoded grant body, with {@code grant_type} first so a captured request
     * reads the way one is normally written.
     */
    private static String formBody(String clientId, char[] clientSecret, String scope) {
        StringBuilder form = new StringBuilder();
        form.append("grant_type=client_credentials");
        form.append("&client_id=").append(encode(clientId));
        if (clientSecret != null && clientSecret.length > 0) {
            form.append("&client_secret=").append(encode(new String(clientSecret)));
        }
        if (scope != null && !scope.isBlank()) {
            form.append("&scope=").append(encode(scope.strip()));
        }
        return form.toString();
    }

    /** Form encoding: a space is {@code +}, everything else outside the unreserved set is escaped. */
    private static String encode(String value) {
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

    /** Never returns anything containing a client secret. */
    @Override
    public String toString() {
        return "TokenEndpointClient";
    }
}