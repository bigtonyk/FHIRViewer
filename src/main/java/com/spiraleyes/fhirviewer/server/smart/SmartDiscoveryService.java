package com.spiraleyes.fhirviewer.server.smart;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and validates a server's {@code /.well-known/smart-configuration} document.
 *
 * <p>This is the only network call Phase 02 makes, and it is deliberately done with the
 * JDK's own {@link HttpClient} rather than through {@code server.rest.JdkHttpRestClient}.
 * The shared client follows same-scheme redirects and reads whole bodies, which is right
 * for reading a FHIR resource and wrong here: the discovery document's whole job is to say
 * where the next, credential-bearing request goes, so a redirect that moved this request to
 * another host would be a way to choose the application's authorization endpoints without
 * the user seeing it. This service therefore follows no redirects at all, refuses a
 * non-absolute URL, caps the body, and never disables TLS validation. It uses no
 * third-party HTTP library — only the JDK client the shared layer already wraps — so the
 * Phase 00 decision against new HTTP dependencies still holds.</p>
 *
 * <p><b>Threading.</b> Every method blocks on the network. Callers must not be the JavaFX
 * application thread; the workbench runs this through {@code ui.BackgroundTasks}.</p>
 *
 * <p><b>What is not done.</b> No token is sent, no redirect is followed, and an {@code iss}
 * the user typed is never assumed to equal the document's {@code issuer} — a mismatch is a
 * problem the caller is shown, not something this class resolves quietly.</p>
 */
public final class SmartDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(SmartDiscoveryService.class);

    /** How long the TCP connection may take. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** How long the whole exchange, headers and body, may take. */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * The largest discovery document this will read.
     *
     * <p>A conformant document is a few kilobytes. A server that answers the well-known
     * path with something enormous is either broken or hostile, and reading it all to find
     * out would make the client the thing that broke.</p>
     */
    public static final int MAX_BODY_BYTES = 512 * 1024;

    private final Duration connectTimeout;
    private final Duration requestTimeout;

    /** A service with the default deadlines. */
    public SmartDiscoveryService() {
        this(CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    /**
     * A service with explicit deadlines, so a test can ask for a very short one.
     *
     * @param connectTimeout how long the connection may take; never {@code null}
     * @param requestTimeout how long the exchange may take; never {@code null}
     */
    public SmartDiscoveryService(Duration connectTimeout, Duration requestTimeout) {
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    /**
     * Discovers what a FHIR server publishes about itself.
     *
     * @param fhirBaseUrl the FHIR server's base URL, as the user typed it
     * @return the typed document; never {@code null}, though it may carry
     *         {@link SmartConfiguration#problems()}
     * @throws SmartDiscoveryException when the URL is unusable, the server is not SMART,
     *         the document is malformed, or the network failed — each with a message a user
     *         can act on and a {@link SmartDiscoveryException.Kind} that says which it was
     */
    public SmartConfiguration discover(String fhirBaseUrl) throws SmartDiscoveryException {
        String discoveryUrl = discoveryUrlFor(fhirBaseUrl);
        log.info("Discovering SMART configuration from {}", originOf(discoveryUrl));

        HttpRequest request = HttpRequest.newBuilder(URI.create(discoveryUrl))
                // No redirects. See the class javadoc: this document decides where the next
                // request goes, so the host must be one the user chose.
                .GET()
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .build();

        HttpResponse<InputStream> response;
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // The body is read here, inside the try: closing the client closes whatever it
            // is still holding, and a lazily-read stream that outlives its client either
            // fails or blocks. Status and body are copied out as plain values so the rest
            // of the method can work without the exchange still being open.
            return interpret(response);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new SmartDiscoveryException(SmartDiscoveryException.Kind.NETWORK,
                    "The server did not answer the discovery request in time.", e);
        } catch (IOException e) {
            throw new SmartDiscoveryException(SmartDiscoveryException.Kind.NETWORK,
                    "The discovery request could not reach the server: " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmartDiscoveryException(SmartDiscoveryException.Kind.NETWORK,
                    "The discovery request was interrupted.", e);
        }
    }

    /**
     * Turns a completed exchange into a configuration or a diagnosis.
     *
     * <p>Kept separate so the client's lifetime — the thing that has to surround the body
     * read — stays obvious in {@link #discover(String)}.</p>
     */
    private SmartConfiguration interpret(HttpResponse<InputStream> response)
            throws SmartDiscoveryException {
        int status = response.statusCode();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String location = response.headers().firstValue("Location").orElse("");
            throw new SmartDiscoveryException(SmartDiscoveryException.Kind.NOT_SMART,
                    "The server redirected the discovery request (HTTP " + status
                            + ") instead of answering it. Discovery does not follow "
                            + "redirects, because the redirect could move it to a host you "
                            + "did not choose. If the server moved, use its new address: "
                            + originOf(location));
        }
        if (status == 404 || status == 410) {
            throw new SmartDiscoveryException(SmartDiscoveryException.Kind.NOT_SMART,
                    "This FHIR server does not publish a SMART configuration"
                            + " (HTTP " + status + " at the well-known address). It may not "
                            + "support SMART App Launch.");
        }
        if (status < 200 || status >= 300) {
            throw new SmartDiscoveryException(SmartDiscoveryException.Kind.NOT_SMART,
                    "The SMART configuration request was refused with HTTP " + status + ".");
        }

        String body = readCapped(response.body());
        SmartConfiguration configuration = SmartConfiguration.parse(body);
        log.info("Discovered {} capability(ies) from {}",
                configuration.capabilities().size(),
                originOf(response.uri().toString()));
        return configuration;
    }

    /**
     * The URL to request for a given FHIR base, with the user's errors reported as theirs.
     */
    static String discoveryUrlFor(String fhirBaseUrl) throws SmartDiscoveryException {
        try {
            return SmartUrls.discoveryUrl(fhirBaseUrl);
        } catch (IllegalArgumentException e) {
            throw new SmartDiscoveryException(SmartDiscoveryException.Kind.INVALID_URL,
                    e.getMessage(), e);
        }
    }
    /**
     * Reads at most {@link #MAX_BODY_BYTES} from the body.
     *
     * <p>The limit is enforced while reading, not after: a server streaming an endless body
     * must be cut off, not merely reported once it has all arrived.</p>
     */
    private static String readCapped(InputStream body) throws SmartDiscoveryException {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new SmartDiscoveryException(SmartDiscoveryException.Kind.NOT_SMART,
                        "The server's answer is larger than " + (MAX_BODY_BYTES / 1024)
                                + " KB, which a SMART configuration document never is. "
                                + "It is probably not one.");
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SmartDiscoveryException(SmartDiscoveryException.Kind.NETWORK,
                    "The server's answer could not be read: " + describe(e), e);
        }
    }

    /**
     * The scheme and host of a URL, for a message.
     *
     * <p>Never the full URL: a discovery URL can carry a query string, and a query string
     * can carry a patient identifier. The origin is what a user needs to recognise which
     * server answered. Public because the workbench names the origin in its status line
     * before the request is even built.</p>
     */
    public static String originOf(String url) {
        if (url == null || url.isBlank()) {
            return "(no address)";
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme() + "://";
            String authority = uri.getAuthority() != null ? uri.getAuthority() : uri.getHost();
            return scheme + (authority == null ? "(no host)" : authority);
        } catch (IllegalArgumentException e) {
            return "(an unparseable address)";
        }
    }

    /** A throwable's message, or its type, for a user-facing line. */
    private static String describe(Throwable problem) {
        String message = problem.getMessage();
        return message == null || message.isBlank()
                ? problem.getClass().getSimpleName()
                : message;
    }
}