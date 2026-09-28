package com.example.fhirviewer.server.rest;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.fhirviewer.server.RequestHeaders;
import com.example.fhirviewer.server.ServerAuthentication;
import com.example.fhirviewer.server.ServerOperationException;
import com.example.fhirviewer.server.ServerSession;

/**
 * The project's {@link RestClient}: the JDK HTTP client, wrapped so that callers never
 * see a {@link HttpClient}.
 *
 * <p>The JDK client is used rather than a new third-party one because it is already part
 * of the build — {@code service.PackageRegistryService} uses it for the package registry —
 * and because it exposes every verb, arbitrary headers, per-request timeouts and the
 * response status and headers, which is exactly what a vendor-agnostic REST layer needs.
 * It leaves TLS certificate validation on by default, and nothing here changes that.
 *
 * <p>The FHIR-specific client already in the project is untouched and still used for
 * standard FHIR operations. This one exists for the generic and vendor paths, where a
 * response is arbitrary JSON, XML or text rather than a typed resource.
 *
 * <p><b>Authentication.</b> The client applies whatever headers the session's
 * {@link ServerAuthentication} contributes to every request, so a plugin never has to
 * build an {@code Authorization} value itself and a new mechanism needs no change
 * here. {@link #forSession(ServerSession)} is the normal way to obtain a client: it
 * takes the URL, the timeout and the credentials from the server configuration the
 * application already holds, so there is one connection system rather than two.
 *
 * <p><b>Threading.</b> A client is created on the calling thread and is then safe to share.
 * Requests block, so the caller must not be the JavaFX application thread. The
 * {@code HttpClient}'s own asynchronous work is given a small daemon pool so the default
 * common pool is neither used nor kept alive by a background thread.
 */
public final class JdkHttpRestClient implements RestClient {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpRestClient.class);

    /** Used when the server configuration does not ask for a specific deadline. */
    public static final int DEFAULT_TIMEOUT_MILLIS = 20_000;

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final RestOutcomeParser outcomeParser;
    private final ExecutorService executor;
    private final RequestHeaders authentication;

    /**
     * A client for a server, using the default deadline.
     *
     * @param baseUrl the server's FHIR base URL
     */
    public JdkHttpRestClient(String baseUrl) {
        this(baseUrl, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * A client for a server, honouring the configured timeout.
     *
     * <p>{@code FhirServerConfiguration.timeoutMillis()} was carried but never read before
     * this class existed; a value of zero or less means "no explicit deadline", which is
     * the same rule {@code ServerDefinition} already documents.
     *
     * @param baseUrl       the server's FHIR base URL
     * @param timeoutMillis the deadline for one request, or {@code 0} for the default
     */
    public JdkHttpRestClient(String baseUrl, int timeoutMillis) {
        this(baseUrl, timeoutMillis, RequestHeaders.none());
    }

    /**
     * A client that sends the given authentication headers with every request.
     *
     * @param baseUrl         the server's FHIR base URL
     * @param timeoutMillis   the deadline for one request, or {@code 0} for the default
     * @param authentication  the headers the mechanism contributes; {@code null} means
     *                        anonymous, which is the same as {@link RequestHeaders#none()}
     */
    public JdkHttpRestClient(String baseUrl, int timeoutMillis, RequestHeaders authentication) {
        this(baseUrl, timeoutMillis, authentication, null, new RestOutcomeParser());
    }

    /**
     * A client for a live session: the active server's URL, its configured timeout, and
     * the authentication on the session.
     *
     * <p>This is how a plugin reaches the configured server without inventing a second
     * connection or configuration system. Everything comes from the {@link ServerSession}
     * the application already built — the base URL, {@code timeoutMillis()} and the
     * mechanism's headers — so a client cannot drift from the server definition the user
     * is looking at in the dialog. Nothing from the session's secrets is retained beyond
     * the header value itself, and the base URL is the only thing {@link #baseUrl()}
     * reports, so it can never carry a credential.
     *
     * @throws IllegalArgumentException when the session's server URL is not usable
     */
    public static JdkHttpRestClient forSession(ServerSession session) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(session.server(), "session.server()");
        return new JdkHttpRestClient(
                session.server().baseUrl(),
                session.server().timeoutMillis(),
                headersOf(session.authentication()));
    }

    /** The authentication's headers, tolerating a session built without one. */
    private static RequestHeaders headersOf(ServerAuthentication authentication) {
        return authentication == null ? RequestHeaders.none() : authentication.requestHeaders();
    }

    /**
     * A client with an explicit HTTP client and parser, for tests that need to observe the
     * requests or supply a stub.
     *
     * @param httpClient the client to send with, or {@code null} to build one
     */
    public JdkHttpRestClient(String baseUrl, int timeoutMillis, HttpClient httpClient,
            RestOutcomeParser outcomeParser) {
        this(baseUrl, timeoutMillis, RequestHeaders.none(), httpClient, outcomeParser);
    }

    /**
     * The full seam: authentication, an explicit HTTP client and a parser, for tests that
     * need to observe the requests or supply a stub.
     *
     * @param httpClient the client to send with, or {@code null} to build one
     */
    public JdkHttpRestClient(String baseUrl, int timeoutMillis, RequestHeaders authentication,
            HttpClient httpClient, RestOutcomeParser outcomeParser) {
        this.baseUrl = requireBaseUrl(baseUrl);
        // A value of zero or less means "no explicit deadline", which is the rule
        // ServerDefinition already documents. It is resolved here rather than at each use,
        // so timeout() always reports the deadline that will actually be applied.
        this.timeout = Duration.ofMillis(timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS);
        this.authentication = authentication == null ? RequestHeaders.none() : authentication;
        this.outcomeParser = Objects.requireNonNull(outcomeParser, "outcomeParser");
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fhir-rest-client");
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = httpClient == null ? newHttpClient() : httpClient;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    /** The deadline applied to each request. Never {@code null}. */
    public Duration timeout() {
        return timeout;
    }

    /**
     * The authentication headers this client sends with every request.
     *
     * <p>Exposed so a test, or a plugin building its own diagnostics, can assert what
     * will go on the wire. {@link RequestHeaders#toString()} redacts the values, so
     * printing this is safe.
     */
    public RequestHeaders authentication() {
        return authentication;
    }

    @Override
    public RestResponse get(String path, Map<String, List<String>> queryParameters)
            throws ServerOperationException {
        return execute(RestRequest.builder(RestMethod.GET, path)
                .parameters(queryParameters)
                .build());
    }

    @Override
    public RestResponse post(String path, String body, String contentType)
            throws ServerOperationException {
        return execute(RestRequest.builder(RestMethod.POST, path)
                .body(body).contentType(contentType).build());
    }

    @Override
    public RestResponse put(String path, String body, String contentType)
            throws ServerOperationException {
        return execute(RestRequest.builder(RestMethod.PUT, path)
                .body(body).contentType(contentType).build());
    }

    @Override
    public RestResponse patch(String path, String body, String contentType)
            throws ServerOperationException {
        return execute(RestRequest.builder(RestMethod.PATCH, path)
                .body(body).contentType(contentType).build());
    }

    @Override
    public RestResponse delete(String path) throws ServerOperationException {
        return execute(RestRequest.builder(RestMethod.DELETE, path).build());
    }

    @Override
    public RestResponse execute(RestRequest request) throws ServerOperationException {
        Objects.requireNonNull(request, "request");
        String url = RestUrls.join(baseUrl, request.path());
        String fullUrl = withQuery(url, request.queryParameters());
        HttpRequest httpRequest = toHttpRequest(request, fullUrl);
        long started = System.currentTimeMillis();
        try {
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return toRestResponse(request, response, started);
        } catch (InterruptedException interrupted) {
            // Restore the flag: swallowing it would leave the caller's thread believing it
            // was never cancelled, and the dialog that started it would wait forever.
            Thread.currentThread().interrupt();
            throw RestFailures.transportFailure(describe(request),
                    RestFailures.RestFailure.TIMEOUT, fullUrl, interrupted);
        } catch (Exception failure) {
            RestFailures.RestFailure kind = RestFailures.failureOf(failure);
            log.info("rest request failed {} kind={} elapsedMs={}", request, kind,
                    System.currentTimeMillis() - started);
            throw RestFailures.transportFailure(describe(request), kind, fullUrl, failure);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }


    /**
     * A client with ordinary defaults: redirects followed within the same scheme, and the
     * platform's default TLS settings.
     *
     * <p>No {@code SSLContext} is installed, so certificate and hostname validation stay
     * exactly as the platform provides them. The connect timeout matches the request
     * timeout, because there is nothing useful to wait for beyond it.
     */
    private HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .executor(executor)
                .build();
    }

    /**
     * Builds the JDK request, carrying the deadline, the headers and the body.
     *
     * <p>The client's authentication headers are applied first and a header the caller
     * set on the request is applied after, so a per-request value always wins. That
     * ordering matters: a caller overriding {@code Authorization} for one call — to try
     * a different identity, or to send none at all against a public endpoint — must not
     * be silently overruled by the session's credentials.
     */
    private HttpRequest toHttpRequest(RestRequest request, String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(toUri(url))
                .timeout(timeout);
        authentication.asMap().forEach((name, values) ->
                values.forEach(value -> builder.header(name, value)));
        request.headers().asMap().forEach((name, values) -> {
            // setHeader once to clear whatever the client applied under this name, then
            // add: a repeated request header such as several Link values must survive.
            for (int i = 0; i < values.size(); i++) {
                if (i == 0) {
                    builder.setHeader(name, values.get(0));
                } else {
                    builder.header(name, values.get(i));
                }
            }
        });
        HttpRequest.BodyPublisher publisher = request.hasBody()
                ? HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();
        return builder.method(request.method().name(), publisher).build();
    }

    /**
     * Turns the JDK response into a {@link RestResponse}, parsing the
     * {@code OperationOutcome} when there is one so the server's explanation survives.
     */
    private RestResponse toRestResponse(RestRequest request, HttpResponse<String> response,
            long started) {
        String body = response.body();
        List<RestOperationOutcome> issues = outcomeParser.parse(body);
        log.info("rest {} -> HTTP {} issues={} elapsedMs={}", request, response.statusCode(),
                issues.size(), System.currentTimeMillis() - started);
        return RestResponse.of(response.statusCode(), RestHeaders.of(response.headers().map()),
                body, issues);
    }

    /**
     * Appends the query parameters, percent-encoding both halves of each pair.
     *
     * <p>Encoding is not optional here: a search value is routinely a patient's name, and an
     * unencoded space or {@code &} would silently split one parameter into two.
     */
    private String withQuery(String url, Map<String, List<String>> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return url;
        }
        StringBuilder query = new StringBuilder();
        parameters.forEach((name, values) -> {
            if (values == null) {
                return;
            }
            values.forEach(value -> {
                if (query.length() > 0) {
                    query.append('&');
                }
                query.append(encode(name)).append('=').append(encode(value));
            });
        });
        if (query.isEmpty()) {
            return url;
        }
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + query;
    }

    /** Percent-encodes one query name or value for use in a query string. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static URI toUri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a usable request URL: " + url, e);
        }
    }

    /**
     * How a request is named in a failure message.
     *
     * <p>The verb and the path, with no query, no body and no headers: a failure message
     * reaches a dialog and a log, and both must stay free of patient data and credentials.
     */
    private static String describe(RestRequest request) {
        return request.method() + " " + request.path();
    }

    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("A base URL is required.");
        }
        String trimmed = baseUrl.trim();
        if (!RestUrls.isAbsolute(trimmed)) {
            throw new IllegalArgumentException("A base URL must be an http or https URL.");
        }
        return trimmed;
    }

}
