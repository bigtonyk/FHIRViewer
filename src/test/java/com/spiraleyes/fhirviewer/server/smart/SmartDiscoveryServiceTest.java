package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The discovery service against a scripted local server. No internet: every answer the
 * service could meet is produced on {@code 127.0.0.1}, from a 404 through a redirect to a
 * body that never ends.
 */
class SmartDiscoveryServiceTest {

    private SmartStubServer server;
    private final SmartDiscoveryService service = new SmartDiscoveryService();

    @BeforeEach
    void startServer() throws Exception {
        server = new SmartStubServer();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    @DisplayName("A valid document is fetched, typed and usable")
    void validDocumentIsDiscovered() throws Exception {
        server.answerWith(200, "application/json", SmartConfigurationTest.SPEC_SAMPLE);

        SmartConfiguration config = service.discover(server.fhirBaseUrl());

        assertTrue(config.isUsable(), String.valueOf(config.problems()));
        assertEquals(1, server.requestCount(), "discovery is a single request");
        assertEquals("/fhir/.well-known/smart-configuration", server.lastPath(),
                "the well-known path anchors at the FHIR base, path and all");
    }

    @Test
    @DisplayName("A server with no document is not SMART — not a crash")
    void missingDocumentIsNotSmart() {
        server.answerWith(404, "text/plain", "Not found");

        SmartDiscoveryException failure = assertThrows(SmartDiscoveryException.class,
                () -> service.discover(server.fhirBaseUrl()));

        assertEquals(SmartDiscoveryException.Kind.NOT_SMART, failure.kind());
        assertTrue(failure.getMessage().contains("404"),
                "the status must reach the message: " + failure.getMessage());
    }

    @Test
    @DisplayName("A redirect is reported, never followed")
    void redirectIsNeverFollowed() {
        server.redirectTo("http://127.0.0.1:9/fhir/.well-known/smart-configuration");

        SmartDiscoveryException failure = assertThrows(SmartDiscoveryException.class,
                () -> service.discover(server.fhirBaseUrl()));

        assertEquals(SmartDiscoveryException.Kind.NOT_SMART, failure.kind());
        assertEquals(1, server.requestCount(),
                "a followed redirect would be a request this server never sees as a second one");
    }

    @Test
    @DisplayName("An HTML answer is not a SMART server")
    void htmlAnswerIsNotSmart() {
        server.answerWith(200, "text/html", "<html><body>Server error</body></html>");

        SmartDiscoveryException failure = assertThrows(SmartDiscoveryException.class,
                () -> service.discover(server.fhirBaseUrl()));

        assertEquals(SmartDiscoveryException.Kind.NOT_SMART, failure.kind());
    }

    @Test
    @DisplayName("A URL that cannot become a request fails before any socket opens")
    void badUrlFailsUpFront() {
        SmartDiscoveryException failure = assertThrows(SmartDiscoveryException.class,
                () -> service.discover("not a url at all?still not"));

        assertEquals(SmartDiscoveryException.Kind.INVALID_URL, failure.kind());
    }

    @Test
    @DisplayName("A body the size of a phone book is not a discovery document")
    void oversizedBodyIsRefused() {
        StringBuilder huge = new StringBuilder("{\"issuer\":\"x\",");
        while (huge.length() < SmartDiscoveryService.MAX_BODY_BYTES + 100) {
            huge.append("\"padding\":\"0123456789abcdef\",");
        }
        huge.append("\"x\":1}");
        server.answerWith(200, "application/json", huge.toString());

        SmartDiscoveryException failure = assertThrows(SmartDiscoveryException.class,
                () -> service.discover(server.fhirBaseUrl()));

        assertEquals(SmartDiscoveryException.Kind.NOT_SMART, failure.kind());
    }

    @Test
    @DisplayName("A server that never answers produces a timeout, not a hang")
    void slowServerTimesOut() {
        SmartDiscoveryService impatient = new SmartDiscoveryService(
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        server.answerAfter(10_000);

        SmartDiscoveryException failure = assertThrows(SmartDiscoveryException.class,
                () -> impatient.discover(server.fhirBaseUrl()));

        assertEquals(SmartDiscoveryException.Kind.NETWORK, failure.kind());
        // The message must say "time", not quote an exception type the user cannot fix.
        assertTrue(failure.getMessage().toLowerCase().contains("time"),
                failure.getMessage());
    }

    @Test
    @DisplayName("A port nobody listens on is a network failure")
    void refusedConnectionIsNetworkFailure() {
        // Short deadlines: on some machines a refused connection arrives slowly, and the
        // point of this test is the kind of failure, not the waiting.
        SmartDiscoveryService impatient = new SmartDiscoveryService(
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        SmartDiscoveryException failure = assertThrows(SmartDiscoveryException.class,
                () -> impatient.discover("http://127.0.0.1:9/fhir"),
                "port 9 is the discard port: nothing answers there");

        assertEquals(SmartDiscoveryException.Kind.NETWORK, failure.kind());
    }
}
