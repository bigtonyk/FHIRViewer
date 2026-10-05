package com.example.fhirviewer.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests header lookup, multi-value handling and, most importantly, redaction. */
public class RestHeadersTest {

    @Test
    @DisplayName("Header lookup ignores case, because HTTP header names are case-insensitive")
    void lookupIgnoresCase() {
        RestHeaders headers = RestHeaders.builder().set("Content-Type", "application/fhir+json").build();

        assertEquals("application/fhir+json", headers.first("content-type"));
        assertEquals("application/fhir+json", headers.first("CONTENT-TYPE"));
        assertTrue(headers.contains("Content-Type"));
        assertNull(headers.first("Accept"), "an absent header reads as null, not empty");
    }

    @Test
    @DisplayName("A repeated header name keeps every value")
    void headersAreMultiValued() {
        RestHeaders headers = RestHeaders.builder()
                .add("Link", "<https://a/fhir/Patient?page=2>; rel=\"next\"")
                .add("link", "<https://a/fhir/Patient?page=3>; rel=\"next\"")
                .build();

        assertEquals(2, headers.all("Link").size());
        assertEquals(1, headers.size(), "the two values live under one name");
    }

    @Test
    @DisplayName("Setting a name replaces every value already under it")
    void setReplaces() {
        RestHeaders headers = RestHeaders.builder()
                .add("Accept", "text/plain")
                .set("accept", "application/fhir+json")
                .build();

        assertEquals(1, headers.all("accept").size());
        assertEquals("application/fhir+json", headers.first("Accept"));
    }

    @Test
    @DisplayName("with and withReplacing differ only in whether existing values survive")
    void withVariants() {
        RestHeaders base = RestHeaders.builder().set("X-Tag", "one").build();

        assertEquals(List.of("one", "two"), base.with("x-tag", "two").all("X-Tag"));
        assertEquals(List.of("two"), base.withReplacing("X-Tag", "two").all("X-Tag"));
    }

    @Test
    @DisplayName("Credentials are redacted in the printed form, but ordinary headers are not")
    void credentialsAreRedacted() {
        RestHeaders headers = RestHeaders.builder()
                .set("Authorization", "Basic YWxpY2U6czNjcmV0")
                .set("X-Api-Key", "key-98765")
                .set("Cookie", "session=abc")
                .set("X-Tenant", "north")
                .build();

        String printed = headers.toString();

        assertFalse(printed.contains("YWxpY2U6czNjcmV0"), "a password must never be printed");
        assertFalse(printed.contains("key-98765"), "an API key must never be printed");
        assertFalse(printed.contains("session=abc"), "a cookie must never be printed");
        assertTrue(printed.contains("<redacted>"), "the reader still sees that a value was set");
        assertTrue(printed.contains("north"), "an ordinary header stays readable");
    }

    @Test
    @DisplayName("Redaction is by name, whatever case the caller used")
    void redactionIsCaseInsensitive() {
        assertTrue(RestHeaders.isSecret("AUTHORIZATION"));
        assertTrue(RestHeaders.isSecret("authorization"));
        assertFalse(RestHeaders.isSecret("X-Tenant"));
    }

    @Test
    @DisplayName("A map from the JDK client is wrapped without keeping the original")
    void wrapsAnExistingMap() {
        Map<String, List<String>> source = new java.util.LinkedHashMap<>();
        source.put("Content-Type", List.of("application/json"));
        source.put("X-Empty", List.of());

        RestHeaders headers = RestHeaders.of(source);
        source.put("X-Late", List.of("ignored"));

        assertEquals("application/json", headers.first("Content-Type"));
        assertFalse(headers.contains("X-Empty"), "a name with no values is not a header");
        assertNull(headers.first("X-Late"), "the wrapper copied rather than aliased");
    }

    @Test
    @DisplayName("The FHIR JSON shortcut sets both content type and accept")
    void fhirJsonShortcut() {
        RestHeaders headers = RestHeaders.builder().fhirJson().build();

        assertEquals("application/fhir+json", headers.first("Content-Type"));
        assertEquals("application/fhir+json", headers.first("Accept"));
    }

    @Test
    @DisplayName("An empty header set is usable and reports itself as empty")
    void emptyHeaders() {
        assertTrue(RestHeaders.empty().isEmpty());
        assertEquals(0, RestHeaders.empty().size());
        assertTrue(RestHeaders.empty().all("anything").isEmpty());
    }
}
