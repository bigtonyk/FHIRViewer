package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for searching with the user's own query string.
 *
 * <p>The property that matters is that the string reaches the server <em>unchanged</em>. A
 * raw search exists precisely so that prefixes, modifiers, chains and {@code _sort} work,
 * and every convenience taken on the way — splitting on {@code &amp;}, re-encoding a value
 * the user had already escaped, wrapping it in a parameter — destroys exactly that. So
 * these tests assert on the request line the server actually received, not on what the
 * client was asked to send.</p>
 *
 * <p>Offline throughout, via a loopback {@code HttpServer}, as the rest of the server
 * tests are.</p>
 */
class RawSearchTest {

    private HttpServer server;
    private ServerSession session;
    private final AtomicReference<String> receivedQuery = new AtomicReference<>();

    private static final String SEARCH_BUNDLE =
            "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":0,\"link\":[]}";

    private static final String CAPABILITY_STATEMENT =
            "{\"resourceType\":\"CapabilityStatement\",\"status\":\"active\","
                    + "\"date\":\"2026-01-01\",\"kind\":\"instance\",\"fhirVersion\":\"4.0.1\","
                    + "\"format\":[\"json\"]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        ServerDefinition definition = ServerDefinition.named("Canned",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir").build();
        session = new ServerSession(definition, AnonymousServerAuthentication.INSTANCE);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        receivedQuery.set(exchange.getRequestURI().getRawQuery());
        // The HAPI path validates the server with GET /metadata before its first real
        // request, so the handler has to answer that with a CapabilityStatement rather
        // than the search Bundle. Both bodies are recorded; only the query is asserted on.
        String body = exchange.getRequestURI().getPath().endsWith("/metadata")
                ? CAPABILITY_STATEMENT
                : SEARCH_BUNDLE;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private SearchResultPage search(String rawQuery) throws ServerOperationException {
        return new StandardFhirRestPlugin().search(session,
                new SearchRequest("Patient", List.of(SearchCriterion.raw(rawQuery)), 20));
    }

    @Test
    @DisplayName("A raw search string reaches the server exactly as typed")
    void theRawStringArrivesIntact() throws Exception {
        // Prefixes, a modifier, a chain and _sort together. Any of them normalised away
        // and the search the user asked for is not the search they get.
        search("name:contains=Smith&subject.name=John&_sort=-birthdate");

        assertEquals("name:contains=Smith&subject.name=John&_sort=-birthdate",
                receivedQuery.get(),
                "the query string must arrive verbatim");
    }

    @Test
    @DisplayName("A raw search with an already-escaped value is not encoded twice")
    void anEscapedValueIsNotDoubleEncoded() throws Exception {
        search("name=Smith%20John");

        assertEquals("name=Smith%20John", receivedQuery.get(),
                "re-encoding would turn %20 into %2520 and the server would search for"
                        + " the literal text 'Smith%20John'");
    }

    @Test
    @DisplayName("A pasted URL loses its type and question mark but keeps the query")
    void aPastedUrlIsTrimmed() {
        // What a user pastes from a browser address bar.
        assertEquals("name=Smith&_count=5",
                SearchCriterion.raw("https://example.org/fhir/Patient?name=Smith&_count=5").value());
        assertEquals("name=Smith", SearchCriterion.raw("Patient?name=Smith").value());
        assertEquals("name=Smith", SearchCriterion.raw("?name=Smith").value());
        assertEquals("name=Smith", SearchCriterion.raw("  name=Smith  ").value());
    }

    @Test
    @DisplayName("An empty raw search is refused rather than searching for everything")
    void anEmptyRawSearchIsRefused() {
        // The dangerous one: an empty criteria list means "no filter", which is every
        // resource of the type. Better to say so than to return a patient's whole table.
        assertThrows(IllegalArgumentException.class, () -> SearchCriterion.raw("   "));
        assertThrows(IllegalArgumentException.class, () -> SearchCriterion.raw("?"));
        assertThrows(IllegalArgumentException.class, () -> SearchCriterion.raw(null));
    }

    @Test
    @DisplayName("A search cannot be a raw string and a parameter list at once")
    void rawAndParametersCannotBeMixed() {
        // Both kinds travel by different routes and have no single correct answer.
        // Dropping one silently would send a search the user did not ask for.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new SearchRequest("Patient", List.of(
                        SearchCriterion.raw("name=Smith"),
                        new SearchCriterion("birthdate", "1990-01-01")), 20));

        assertTrue(thrown.getMessage().contains("not both"),
                "the message should say why: " + thrown.getMessage());
    }

    @Test
    @DisplayName("Several name/value parameters are all sent")
    void severalParametersAreAllSent() throws Exception {
        // The other half of what was asked for: more than one parameter in one search.
        //
        // HAPI renders matchesExactly() as "name:exact=value" and percent-encodes the
        // colon, so the wire form is "family%3Aexact=Smith". Both details are correct and
        // are asserted on rather than worked around - the raw path above is what sends a
        // search without that normalisation, and its test proves the difference.
        new StandardFhirRestPlugin().search(session, new SearchRequest("Patient",
                List.of(new SearchCriterion("family", "Smith"),
                        new SearchCriterion("given", "John"),
                        new SearchCriterion("birthdate", "1990-01-01")), 20));

        String query = receivedQuery.get();
        assertTrue(query != null && query.contains("family%3Aexact=Smith"),
                "family was dropped or altered: " + query);
        assertTrue(query.contains("given%3Aexact=John"), "given was dropped: " + query);
        assertTrue(query.contains("birthdate%3Aexact=1990-01-01"),
                "birthdate was dropped: " + query);
    }
}
