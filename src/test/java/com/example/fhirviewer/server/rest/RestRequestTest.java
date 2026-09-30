package com.example.fhirviewer.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the request model: what it validates, what it defaults, and what it refuses to
 * print. No HTTP is involved — a request is data, and that is the point of these tests.
 */
public class RestRequestTest {

    @Test
    @DisplayName("A verb factory produces a request with no body and FHIR JSON negotiated")
    void verbFactories() {
        RestRequest request = RestRequest.get("/Patient/1");

        assertEquals(RestMethod.GET, request.method());
        assertEquals("/Patient/1", request.path());
        assertFalse(request.hasBody());
        assertNull(request.body());
        assertEquals(RestRequest.DEFAULT_ACCEPT, request.accept());
        assertNull(request.contentType(), "a body-less request needs no content type");
    }

    @Test
    @DisplayName("Every verb can be requested through the builder")
    void builderCoversEveryVerb() {
        assertEquals(RestMethod.GET, RestRequest.builder(RestMethod.GET, "metadata").build().method());
        assertEquals(RestMethod.POST, RestRequest.builder(RestMethod.POST, "Patient").build().method());
        assertEquals(RestMethod.PUT, RestRequest.builder(RestMethod.PUT, "Patient/1").build().method());
        assertEquals(RestMethod.PATCH, RestRequest.builder(RestMethod.PATCH, "Patient/1").build().method());
        assertEquals(RestMethod.DELETE, RestRequest.builder(RestMethod.DELETE, "Patient/1").build().method());
    }

    @Test
    @DisplayName("A body brings a default content type, and an explicit one is kept")
    void contentTypeDefaultsOnlyWhenThereIsABody() {
        RestRequest inferred = RestRequest.builder(RestMethod.POST, "Patient")
                .body("{\"resourceType\":\"Patient\"}")
                .build();
        assertEquals(RestRequest.DEFAULT_CONTENT_TYPE, inferred.contentType());

        RestRequest explicitContent = RestRequest.builder(RestMethod.PATCH, "Patient/1")
                .body("[]")
                .contentType("application/json-patch+json")
                .build();
        assertEquals("application/json-patch+json", explicitContent.contentType());
    }

    @Test
    @DisplayName("A body on a verb that cannot carry one is refused, not silently dropped")
    void bodyOnReadIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> RestRequest.builder(RestMethod.GET, "Patient")
                .body("{}")
                .build());
        assertThrows(IllegalArgumentException.class, () -> RestRequest.builder(RestMethod.DELETE, "Patient/1")
                .body("{}")
                .build());
    }

    @Test
    @DisplayName("A missing verb or path is a programming error, caught at build time")
    void requiredFieldsAreChecked() {
        assertThrows(NullPointerException.class, () -> RestRequest.builder(null, "Patient"));
        assertThrows(IllegalArgumentException.class, () -> RestRequest.builder(RestMethod.GET, "  "));
        assertThrows(IllegalArgumentException.class, () -> RestRequest.builder(RestMethod.GET, null));
    }

    @Test
    @DisplayName("A repeated query parameter keeps every value, in the order given")
    void queryParametersAreMultiValued() {
        RestRequest request = RestRequest.builder(RestMethod.GET, "Patient")
                .parameter("name", "Smith")
                .parameter("name", "Jones")
                .parameter("_count", "20")
                .build();

        assertEquals(List.of("Smith", "Jones"), request.queryParameters().get("name"));
        assertEquals("Smith", request.parameter("name"), "the first value is the convenience read");
        assertEquals("20", request.parameter("_count"));
        assertNull(request.parameter("family"), "an unset parameter reads as null, not empty");
    }

    @Test
    @DisplayName("A whole parameter map can be supplied at once")
    void parameterMapIsAccepted() {
        RestRequest request = RestRequest.builder(RestMethod.GET, "Observation")
                .parameters(Map.of("patient", List.of("123", "456")))
                .build();

        assertEquals(List.of("123", "456"), request.queryParameters().get("patient"));
    }

    @Test
    @DisplayName("Blank names and null values are dropped rather than sent as empty")
    void emptyParametersAreIgnored() {
        RestRequest request = RestRequest.builder(RestMethod.GET, "Patient")
                .parameter("  ", "Smith")
                .parameter("name", null)
                .build();

        assertTrue(request.queryParameters().isEmpty());
    }

    @Test
    @DisplayName("Arbitrary headers can be added and are readable afterwards")
    void headersAreCarried() {
        RestRequest request = RestRequest.builder(RestMethod.POST, "Patient")
                .header("X-Tenant", "north")
                .build();

        assertEquals("north", request.headers().first("x-tenant"), "lookup ignores case");
    }

    @Test
    @DisplayName("A request never prints its body, its parameter values or its header values")
    void toStringLeaksNothing() {
        RestRequest request = RestRequest.builder(RestMethod.POST, "Patient")
                .body("{\"name\":[{\"family\":\"Sensitive\"}]}")
                .parameter("identifier", "MRN-12345")
                .header("Authorization", "Basic c2VjcmV0")
                .build();

        String printed = request.toString();

        assertFalse(printed.contains("Sensitive"), "the body must not be printed");
        assertFalse(printed.contains("MRN-12345"), "a search value must not be printed");
        assertFalse(printed.contains("c2VjcmV0"), "a credential must not be printed");
        assertTrue(printed.contains("POST"), "the verb still identifies the request");
        assertTrue(printed.contains("Patient"), "the path still identifies the request");
    }

    @Test
    @DisplayName("Query parameters keep the order they were added, not a hash order")
    void queryParameterOrderIsPreserved() {
        // Regression: build() froze the map with Map.copyOf, which does not preserve
        // iteration order, so the names came out in an arbitrary order. It was invisible
        // while every test read parameters back by name; it only showed up as a shuffled
        // query string once a plugin-declared operation began sending repeatable
        // parameters. The same trap was already fixed once, for FhirOperationRequest.
        RestRequest request = RestRequest.builder(RestMethod.GET, "Patient")
                .parameter("name", "Smith")
                .parameter("family", "Jones")
                .parameter("birthdate", "1970-01-01")
                .parameter("_count", "20")
                .build();

        assertEquals(List.of("name", "family", "birthdate", "_count"),
                List.copyOf(request.queryParameters().keySet()),
                "the order the caller wrote is the order the server is asked in");
    }

    @Test
    @DisplayName("The request is immutable once built")
    void builtRequestsAreFrozen() {
        RestRequest.Builder builder = RestRequest.builder(RestMethod.GET, "Patient")
                .parameter("name", "Smith");
        RestRequest first = builder.build();

        builder.parameter("name", "Jones");
        RestRequest second = builder.build();

        assertEquals(List.of("Smith"), first.queryParameters().get("name"),
                "the first request must not see a later change");
        assertEquals(List.of("Smith", "Jones"), second.queryParameters().get("name"));
        assertNotNull(first.headers());
    }
}
