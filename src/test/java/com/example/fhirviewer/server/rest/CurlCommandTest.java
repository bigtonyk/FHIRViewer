package com.example.fhirviewer.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading and writing cURL commands.
 *
 * <p>The round trip is the property that matters and the reason the shell quoting lives in
 * one place: a command this class renders has to parse back to the request it came from, or
 * "copy as cURL", edit, "paste" quietly changes the request under the user.
 */
class CurlCommandTest {

    private static CurlCommand.CurlRequest parse(String command) {
        return CurlCommand.parse(command)
                .orElseThrow(() -> new AssertionError("nothing parsed from: " + command));
    }

    @Test
    @DisplayName("A plain search command becomes a GET with its URL")
    void plainSearch() {
        CurlCommand.CurlRequest request =
                parse("curl 'https://example.com/fhir/Patient?name=Smith'");

        assertEquals(RestMethod.GET, request.method());
        assertEquals("https://example.com/fhir/Patient?name=Smith", request.url());
        assertEquals(0, request.headers().size());
    }

    @Test
    @DisplayName("Unquoted and double-quoted URLs work the same")
    void quotingVariants() {
        assertEquals(parse("curl https://example.com/fhir/Patient").url(),
                parse("curl \"https://example.com/fhir/Patient\"").url());
        assertEquals(parse("curl https://example.com/fhir/Patient").url(),
                parse("curl 'https://example.com/fhir/Patient'").url());
    }

    @Test
    @DisplayName("Headers arrive in order, with the colon split off")
    void headersAreParsed() {
        CurlCommand.CurlRequest request = parse("curl 'https://example.com/fhir/Patient' "
                + "-H 'Accept: application/fhir+json' -H 'Prefer: return=representation'");

        assertEquals(2, request.headers().size());
        assertEquals("Accept", request.headers().get(0).name());
        assertEquals("application/fhir+json", request.headers().get(0).value());
        assertEquals("Prefer", request.headers().get(1).name());
    }

    @Test
    @DisplayName("The --header=Name:value spelling is one token, not two")
    void inlineHeaderValue() {
        CurlCommand.CurlRequest request =
                parse("curl 'https://example.com/fhir/Patient' --header='Accept: application/json'");

        assertEquals(1, request.headers().size());
        assertEquals("Accept", request.headers().get(0).name());
        assertEquals("application/json", request.headers().get(0).value());
    }

    @Test
    @DisplayName("A body with -d implies POST, and arrives unescaped")
    void bodyImpliesPost() {
        // The apostrophe is escaped as '\'' because a bare one would end the quoted
        // string. Parsing undoes that, exactly as a shell would, so what comes back is
        // the body the caller meant rather than its quoted spelling.
        CurlCommand.CurlRequest request = parse("curl 'https://example.com/fhir/Patient' "
                + "-d '{ \"resourceType\": \"Patient\", \"family\": \"O'\\''Brien\" }'");

        assertEquals(RestMethod.POST, request.method());
        assertEquals("{ \"resourceType\": \"Patient\", \"family\": \"O'Brien\" }", request.body());
    }

    @Test
    @DisplayName("An explicit verb wins over the body default")
    void explicitVerbWins() {
        assertEquals(RestMethod.PUT,
                parse("curl -X PUT 'https://example.com/fhir/Patient/1' -d '{}'").method());
    }

    @Test
    @DisplayName("Flags that only affect cURL's output are ignored, not refused")
    void outputFlagsAreIgnored() {
        CurlCommand.CurlRequest request = parse(
                "curl -sS -L -i --compressed 'https://example.com/fhir/metadata'");

        assertEquals(RestMethod.GET, request.method());
        assertEquals("https://example.com/fhir/metadata", request.url());
    }

    @Test
    @DisplayName("--insecure is refused, naming the flag and the reason")
    void insecureIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CurlCommand.parse("curl -k 'https://example.com/fhir/Patient'"));

        assertTrue(failure.getMessage().contains("--insecure"), failure.getMessage());
        assertTrue(failure.getMessage().contains("certificate"), failure.getMessage());
    }

@Test
    @DisplayName("-u arrives as an Authorization header for the console to show")
    void basicCredentialsBecomeAHeader() {
        CurlCommand.CurlRequest request =
                parse("curl -u alice:s3cret 'https://example.com/fhir/Patient'");

        assertEquals(1, request.headers().size());
        assertEquals("Authorization", request.headers().get(0).name());
    }

    @Test
    @DisplayName("Nothing is parsed from an empty line or one with no URL")
    void nothingToParse() {
        assertTrue(CurlCommand.parse("").isEmpty());
        assertTrue(CurlCommand.parse(null).isEmpty());
        assertTrue(CurlCommand.parse("curl -X GET").isEmpty());
    }

    @Test
    @DisplayName("A rendered command parses back to the same request")
    void roundTripPreservesTheRequest() {
        CurlCommand.CurlRequest original = new CurlCommand.CurlRequest(
                RestMethod.POST,
                "https://example.com/fhir/Patient?name=Smith O'Brien",
                java.util.List.of(
                        new CurlCommand.CurlHeader("Accept", "application/fhir+json"),
                        new CurlCommand.CurlHeader("If-Match", "W/\"3\"")),
                "{ \"family\": \"O'Brien\", \"note\": \"a \\\"quoted\\\" value\" }");

        String rendered = CurlCommand.render(original);
        CurlCommand.CurlRequest reparsed = parse(rendered);

        assertEquals(original.method(), reparsed.method());
        assertEquals(original.url(), reparsed.url());
        assertEquals(original.headers(), reparsed.headers());
        assertEquals(original.body(), reparsed.body());
    }

    @Test
    @DisplayName("A rendered command survives a body containing a single quote")
    void singleQuotesSurviveTheRoundTrip() {
        String body = "{ \"family\": \"O'Brien\" }";
        String rendered = CurlCommand.render(new CurlCommand.CurlRequest(
                RestMethod.POST, "https://example.com/fhir/Patient", java.util.List.of(), body));

        assertTrue(rendered.contains("'\\''"), "the quote has to be closed, escaped and reopened: " + rendered);
        assertEquals(body, parse(rendered).body());
    }

    @Test
    @DisplayName("Copying a request out leaves credentials out by default")
    void renderOmitsCredentialsByDefault() {
        CurlCommand.CurlRequest request = new CurlCommand.CurlRequest(
                RestMethod.GET, "https://example.com/fhir/Patient",
                java.util.List.of(
                        new CurlCommand.CurlHeader("Accept", "application/fhir+json"),
                        new CurlCommand.CurlHeader("Authorization", "Bearer super-secret")),
                null);

        String rendered = CurlCommand.render(request);

        assertFalse(rendered.contains("super-secret"),
                "the rendered text is what gets pasted into a ticket: " + rendered);
        assertTrue(rendered.contains("Accept"));
    }

    @Test
    @DisplayName("A caller that means to include them can")
    void renderCanIncludeCredentials() {
        CurlCommand.CurlRequest request = new CurlCommand.CurlRequest(
                RestMethod.GET, "https://example.com/fhir/Patient",
                java.util.List.of(new CurlCommand.CurlHeader("Authorization", "Bearer tok")), null);

        assertTrue(CurlCommand.render(request, true).contains("Bearer tok"));
    }

    @Test
    @DisplayName("The parsed form never prints a header value in toString")
    void toStringHidesHeaderValues() {
        String described = parse("curl -u alice:s3cret 'https://example.com/fhir/Patient'").toString();

        assertFalse(described.contains("s3cret"), described);
    }

    @Test
    @DisplayName("Shell tokenizing follows the rules that matter")
    void tokenizeHandlesTheUsualQuoting() {
        assertEquals(java.util.List.of("curl", "-H", "Accept: a b", "https://x"),
                CurlCommand.tokenize("curl -H \"Accept: a b\" https://x"));
        assertEquals(java.util.List.of("it's"), CurlCommand.tokenize("'it'\\''s'"));
        assertEquals(java.util.List.of("a b"), CurlCommand.tokenize("a\\ b"));
    }

    @Test
    @DisplayName("An empty value is quoted so it survives as a token")
    void emptyValueIsQuoted() {
        assertEquals("''", CurlCommand.quote(""));
    }

    @Test
    @DisplayName("A real FHIR command from a bug report parses")
    void aRealisticCommand() {
        Optional<CurlCommand.CurlRequest> request = CurlCommand.parse(
                "curl -X GET 'https://hapi.fhir.org/baseR4/Patient?name=Smith&_count=5' "
                        + "-H 'Accept: application/fhir+json' "
                        + "-H 'Authorization: Bearer eyJhbGciOi.J9.abc'");

        assertTrue(request.isPresent());
        assertEquals(RestMethod.GET, request.get().method());
        assertEquals(2, request.get().headers().size());
        assertTrue(request.get().url().contains("_count=5"));
    }
}