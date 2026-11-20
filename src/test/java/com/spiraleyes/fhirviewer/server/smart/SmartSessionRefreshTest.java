package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.server.ServerOperationException;
import com.spiraleyes.fhirviewer.server.StandardFhirRestPlugin;

/**
 * Renewing a SMART session with its refresh token: before a call when the token has
 * expired, once after a 401, never twice for the same stale token, and never when the
 * server has already refused.
 *
 * <p>One local stub plays both parts. Its FHIR side accepts only the access token its
 * token side issued last, so a read succeeds exactly when the session sent the renewed
 * token, and its token side rotates the refresh token on every renewal.</p>
 */
class SmartSessionRefreshTest {

    private static final String PATIENT_JSON =
            "{\"resourceType\":\"Patient\",\"id\":\"example-1\"}";
    private static final String CAPABILITY_JSON =
            "{\"resourceType\":\"CapabilityStatement\",\"status\":\"active\","
                    + "\"date\":\"2024-01-01\",\"kind\":\"instance\",\"fhirVersion\":\"4.0.1\","
                    + "\"format\":[\"json\"],\"rest\":[{\"mode\":\"server\"}]}";
    private static final String OUTCOME =
            "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                    + "\"code\":\"login\"}]}";

    private HttpServer server;
    private String baseUrl;
    private SmartFhirAccess access;
    private final AtomicReference<String> acceptedToken = new AtomicReference<>("tok-0");
    private final AtomicInteger issued = new AtomicInteger();
    private final List<String> tokenBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenStatus = new AtomicInteger(200);
    private final AtomicInteger tokenDelayMillis = new AtomicInteger();
    private volatile boolean rotateRefreshToken = true;
    private volatile boolean rejectEveryToken;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::fhir);
        server.createContext("/token", this::token);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        FhirServerPluginRegistry registry = new FhirServerPluginRegistry();
        registry.register(new StandardFhirRestPlugin(FhirContext.forR4()));
        access = new SmartFhirAccess(registry, new SmartDiagnostics(),
                new SmartTokenExchangeService(Duration.ofSeconds(5), Duration.ofSeconds(5)));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("An expired token is renewed before the read, and the read sends the new one")
    void expiredTokenIsRenewedFirst() throws Exception {
        SmartAuthorizationSession session = session(-5L, "ref-0");
        acceptedToken.set(null);

        assertTrue(access.patient(session).isPresent());

        assertEquals(1, tokenBodies.size());
        Map<String, String> form = LoopbackCallbackServer.queryParams(tokenBodies.get(0));
        assertEquals("refresh_token", form.get("grant_type"));
        assertEquals("ref-0", form.get("refresh_token"));
        assertEquals("public-client", form.get("client_id"));
        assertFalse(tokenBodies.get(0).contains("client_secret"), tokenBodies.get(0));
        assertEquals("tok-1", session.accessToken());
        assertEquals("ref-1", session.refreshToken().orElseThrow(),
                "a rotated refresh token replaces the spent one");
        assertFalse(session.isExpired());
        assertEquals("example-1", session.patient(), "launch context survives a renewal");
    }

    @Test
    @DisplayName("A 401 on a token that has not expired is answered with one renewal and a retry")
    void unauthorizedIsRetriedOnce() throws Exception {
        SmartAuthorizationSession session = session(3600L, "ref-0");
        acceptedToken.set("revoked-elsewhere");

        assertTrue(access.patient(session).isPresent());

        assertEquals(1, tokenBodies.size());
        assertEquals("tok-1", session.accessToken());
    }

    @Test
    @DisplayName("A second 401 after renewal is the server's answer and is not retried again")
    void secondUnauthorizedIsReported() {
        SmartAuthorizationSession session = session(3600L, "ref-0");
        rejectEveryToken = true;

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> access.patient(session));

        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, failure.kind());
        assertEquals(1, tokenBodies.size(), "one renewal, not a loop");
    }

    @Test
    @DisplayName("A refused renewal says authorize again and drops the refresh token")
    void refusedRenewalNeedsReauthorization() {
        SmartAuthorizationSession session = session(-5L, "ref-0");
        tokenStatus.set(400);

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> access.patient(session));

        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, failure.kind());
        assertTrue(failure.getMessage().contains("invalid_grant"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Authorize again"), failure.getMessage());
        assertFalse(session.canRefresh());
        assertTrue(session.needsReauthorization());

        assertThrows(ServerOperationException.class, () -> access.patient(session));
        assertEquals(1, tokenBodies.size(), "a refused refresh token is not sent again");
    }

    @Test
    @DisplayName("An expired session without a refresh token names the scopes that allow renewal")
    void noRefreshTokenNamesTheScopes() {
        SmartAuthorizationSession session = session(-5L, null);

        ServerOperationException failure = assertThrows(ServerOperationException.class,
                () -> access.patient(session));

        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, failure.kind());
        assertTrue(failure.getMessage().contains("offline_access"), failure.getMessage());
        assertTrue(tokenBodies.isEmpty());
        assertTrue(session.needsReauthorization());
    }

    @Test
    @DisplayName("A renewal that omits the refresh token and scope keeps the ones held")
    void omittedFieldsAreKept() throws Exception {
        rotateRefreshToken = false;
        SmartAuthorizationSession session = session(-5L, "ref-0");

        assertTrue(session.refresh(exchange(), session.accessToken()));

        assertEquals("ref-0", session.refreshToken().orElseThrow());
        assertEquals("patient/*.read offline_access", session.grantedScope());
        assertTrue(session.canRefresh());
    }

    @Test
    @DisplayName("Two callers renewing the same stale token make one request to the server")
    void concurrentRenewalsAreSerialized() throws Exception {
        tokenDelayMillis.set(300);
        SmartAuthorizationSession session = session(-5L, "ref-0");
        String stale = session.accessToken();
        SmartTokenExchangeService exchange = exchange();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(() -> {
                start.await();
                return session.refresh(exchange, stale);
            });
            Future<Boolean> second = pool.submit(() -> {
                start.await();
                return session.refresh(exchange, stale);
            });
            start.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS));
            assertTrue(second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, tokenBodies.size());
        assertEquals("tok-1", session.accessToken());
    }

    @Test
    @DisplayName("Logging out ends renewal, and a session without a token endpoint never renews")
    void logoutAndMissingEndpointCannotRenew() throws Exception {
        SmartAuthorizationSession session = session(-5L, "ref-0");
        session.logout();
        assertFalse(session.canRefresh());
        assertFalse(session.refresh(exchange(), session.accessToken()));

        SmartAuthorizationSession legacy = new SmartAuthorizationSession(baseUrl,
                tokens(-5L, "ref-0"), null);
        assertFalse(legacy.canRefresh());
        assertTrue(legacy.needsReauthorization());
        assertTrue(tokenBodies.isEmpty());
    }

    private SmartAuthorizationSession session(long expiresIn, String refreshToken) {
        return new SmartAuthorizationSession(baseUrl, tokens(expiresIn, refreshToken), null,
                baseUrl + "/token", "public-client");
    }

    private static SmartTokenResponse tokens(long expiresIn, String refreshToken) {
        return new SmartTokenResponse("tok-0", "Bearer", expiresIn,
                "patient/*.read offline_access", "example-1", null, null, refreshToken);
    }

    private static SmartTokenExchangeService exchange() {
        return new SmartTokenExchangeService(Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    private void fhir(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        boolean accepted = !rejectEveryToken && authorization != null
                && authorization.equals("Bearer " + acceptedToken.get());
        if (path.endsWith("/metadata")) {
            respond(exchange, 200, "application/fhir+json", CAPABILITY_JSON);
        } else if (!accepted) {
            respond(exchange, 401, "application/fhir+json", OUTCOME);
        } else if (path.endsWith("/Patient/example-1")) {
            respond(exchange, 200, "application/fhir+json", PATIENT_JSON);
        } else {
            respond(exchange, 404, "application/fhir+json", OUTCOME);
        }
    }

    private void token(HttpExchange exchange) throws IOException {
        try (java.io.InputStream in = exchange.getRequestBody()) {
            tokenBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        int delay = tokenDelayMillis.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (tokenStatus.get() != 200) {
            respond(exchange, tokenStatus.get(), "application/json",
                    "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token expired\"}");
            return;
        }
        int n = issued.incrementAndGet();
        acceptedToken.set("tok-" + n);
        String body = "{\"access_token\":\"tok-" + n + "\",\"token_type\":\"Bearer\","
                + "\"expires_in\":60"
                + (rotateRefreshToken ? ",\"refresh_token\":\"ref-" + n + "\"" : "") + "}";
        respond(exchange, 200, "application/json", body);
    }

    private static void respond(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
