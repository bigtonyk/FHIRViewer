package com.example.fhirviewer.server;

import java.util.Locale;
import java.util.Map;

/**
 * A FHIR server that was typed into the REST console rather than saved in the manager.
 *
 * <p>It exists for one reason. {@code JdkHttpRestClient.forSession} needs a
 * {@link FhirServerConfiguration} to take a base URL and a timeout from, and until this
 * class the only implementations of that interface were {@link ServerDefinition} and the
 * test fixtures — every one of which has to be built and stored before a request can be
 * sent. A one-off call to a URL that is not a configured server therefore had nowhere to
 * live: the user could not try an endpoint without first adding it to their server list,
 * which is the opposite of what a console is for.
 *
 * <p><b>No plugin serves this.</b> {@link #pluginId()} is a marker, not a real plugin id,
 * and the console never looks it up — the whole point of the screen is to call endpoints no
 * plugin declares. See {@code docs/plans/11_REST_CONSOLE_UI.md}, which records this as a
 * deliberate peer of the plugin path rather than an exception to it.
 *
 * <p><b>The credential key cannot collide with a saved server.</b> It is prefixed with
 * {@code adhoc:}, which {@link ServerDefinition} never produces. That matters because
 * {@link ServerCredentials} files passwords by this key: an ad-hoc URL that returned a
 * plain base URL could find — or worse, be found by — the credentials of a server the user
 * configured with the same address, which is a surprising way to send a password.
 */
public final class AdhocServer implements FhirServerConfiguration {

    /**
     * The deadline for a console request.
     *
     * <p>Matches the transport's own default. Duplicated as a constant rather than imported
     * from {@code server.rest.JdkHttpRestClient} because that would make the {@code server}
     * package depend on {@code server.rest}, which already depends on it.
     */
    public static final int DEFAULT_TIMEOUT_MILLIS = 20_000;

    /** Marks a credential key as belonging to the console rather than to a saved server. */
    private static final String CREDENTIAL_PREFIX = "adhoc:";

    private static final String NAME = "REST console";

    private final String baseUrl;

    private AdhocServer(String baseUrl) {
        this.baseUrl = requireUrl(baseUrl);
    }

    /**
     * A console server at this address.
     *
     * @param baseUrl the base URL the user typed, for example {@code https://example.com/fhir}
     * @throws IllegalArgumentException when it is blank, or does not name an http or https
     *         address — checked here so the failure names the field rather than surfacing
     *         later as an opaque error from the transport
     */
    public static AdhocServer at(String baseUrl) {
        return new AdhocServer(baseUrl);
    }

    /**
     * True when the text looks like an address this class can use.
     *
     * <p>A separate predicate so a form can grey out its send button, without having to
     * catch an exception to ask the question.
     */
    public static boolean isUsableBaseUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String url = candidate.trim().toLowerCase(Locale.ROOT);
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private static String requireUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("Enter the base URL of the server to call.");
        }
        if (!isUsableBaseUrl(candidate)) {
            throw new IllegalArgumentException(
                    "The base URL must start with http:// or https://");
        }
        return candidate.trim();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    /** R4, matching every other configuration in the project today. */
    @Override
    public String fhirVersion() {
        return "R4";
    }

    /** A marker rather than a registered plugin; nothing ever looks this up. */
    @Override
    public String pluginId() {
        return "rest-console";
    }

    @Override
    public int timeoutMillis() {
        return DEFAULT_TIMEOUT_MILLIS;
    }

    @Override
    public Map<String, String> extraHeaders() {
        return Map.of();
    }

    @Override
    public String credentialKey() {
        return CREDENTIAL_PREFIX + baseUrl;
    }

    @Override
    public String toString() {
        return NAME + " (" + baseUrl + ")";
    }
}