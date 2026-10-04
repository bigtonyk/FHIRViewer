package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.ServerAuthKind;
import com.example.fhirviewer.server.rest.RestMethod;
import com.example.fhirviewer.server.rest.RestRequest;

/**
 * The console form: what it refuses before sending, and what it builds.
 *
 * <p>The refusals are the substance. A message that names the empty field is the only
 * version of the message that is any use next to a form, and an unauthenticated request
 * sent because the token box was left blank produces a {@code 401} that reads as a
 * permissions problem the user does not have.
 */
class RestConsoleFormTest {

    private static RestConsoleForm ready() {
        return new RestConsoleForm().baseUrl("https://example.com/fhir").path("/Patient");
    }

    @Test
    @DisplayName("A complete form reports no problem")
    void aCompleteFormIsReady() {
        assertTrue(ready().problem().isEmpty());
    }

    @Test
    @DisplayName("A missing base URL names the base URL")
    void missingBaseUrl() {
        assertEquals("a base URL", new RestConsoleForm().path("/Patient").problem().orElseThrow());
    }

    @Test
    @DisplayName("A base URL with no scheme names the scheme")
    void baseUrlNeedsAScheme() {
        assertTrue(new RestConsoleForm().baseUrl("example.com/fhir").path("/Patient")
                .problem().orElseThrow().contains("http://"));
    }

    @Test
    @DisplayName("A missing path says what a path looks like")
    void missingPath() {
        assertTrue(new RestConsoleForm().baseUrl("https://example.com/fhir")
                .problem().orElseThrow().contains("Patient"));
    }

    @Test
    @DisplayName("A body on a GET is refused before the transport would ignore it")
    void bodyOnGetIsRefused() {
        String problem = ready().method(RestMethod.GET).body("{ \"a\": 1 }").problem().orElseThrow();

        assertTrue(problem.contains("GET"), problem);
        assertTrue(problem.contains("cannot carry"), problem);
    }

    @Test
    @DisplayName("A body is fine on a verb that can carry one")
    void bodyIsFineOnPost() {
        assertTrue(ready().method(RestMethod.POST).body("{ \"a\": 1 }").problem().isEmpty());
    }

    @Test
    @DisplayName("Missing credentials are refused, naming the field")
    void missingCredentialsAreRefused() {
        assertTrue(ready().authentication(ServerAuthKind.BEARER, "", "", "")
                .problem().orElseThrow().contains("access token"));
        assertTrue(ready().authentication(ServerAuthKind.BASIC, "", "pw", "")
                .problem().orElseThrow().contains("user name"));
    }

    @Test
    @DisplayName("Anonymous needs nothing")
    void anonymousNeedsNothing() {
        assertTrue(ready().authentication(ServerAuthKind.ANONYMOUS, "", "", "")
                .problem().isEmpty());
    }

    @Test
    @DisplayName("An incomplete form cannot produce a request")
    void incompleteFormsCannotBuild() {
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new RestConsoleForm().toRequest()).getMessage().contains("incomplete"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new RestConsoleForm().toSession()).getMessage().contains("incomplete"));
    }

@Test
    @DisplayName("The built request carries the verb, path and ordered parameters")
    void buildsTheExpectedRequest() {
        RestRequest request = ready()
                .parameters(RestParameterList.empty()
                        .add("_include", "Patient:organization")
                        .add("_include", "Patient:general-practitioner")
                        .add("name", "Smith"))
                .toRequest();

        assertEquals(RestMethod.GET, request.method());
        assertEquals("/Patient", request.path());
        assertEquals(List.of("Patient:organization", "Patient:general-practitioner"),
                request.queryParameters().get("_include"));
        assertEquals(List.of("Smith"), request.queryParameters().get("name"),
                "query values are multi-valued so that a repeat can survive");
    }

    @Test
    @DisplayName("A header the user typed is carried on the request")
    void headersAreCarried() {
        RestRequest request = ready()
                .headers(RestHeaderList.empty().add("If-Match", "W/\"3\""))
                .toRequest();

        assertEquals("W/\"3\"", request.headers().first("If-Match"));
    }

    @Test
    @DisplayName("An Authorization header cannot be typed at all")
    void authorizationCannotBeTyped() {
        assertThrows(IllegalArgumentException.class,
                () -> RestHeaderList.empty().add("Authorization", "Bearer leaked"));
        assertThrows(IllegalArgumentException.class,
                () -> RestHeaderList.empty().add("Cookie", "session=abc"));

        assertTrue(RestHeaderList.refusalMessage("Authorization").contains("Authentication"),
                "the refusal has to say what to use instead");
    }

    @Test
    @DisplayName("A pasted header block is read, skipping lines with no colon")
    void headersParseFromText() {
        RestHeaderList headers = RestHeaderList.parse("Accept: application/fhir+json\nnonsense\n");

        assertEquals(1, headers.size());
        assertEquals("application/fhir+json", headers.value("Accept"));
    }

    @Test
    @DisplayName("The session carries the typed URL and the chosen credentials")
    void sessionCarriesTheUrl() {
        var session = ready().authentication(ServerAuthKind.BEARER, "", "", "abc.def").toSession();

        assertEquals("https://example.com/fhir", session.server().baseUrl());
        assertEquals("Bearer abc.def",
                session.authentication().requestHeaders().first("Authorization"));
    }

    @Test
    @DisplayName("The URL preview is safe to ask for on every keystroke")
    void resolvedUrlIsSafeWhenHalfTyped() {
        assertEquals("", new RestConsoleForm().resolvedUrl());
        assertEquals("", new RestConsoleForm().baseUrl("example.com").path("/Patient")
                .resolvedUrl());
        assertEquals("https://example.com/fhir/Patient", ready().resolvedUrl());
        assertEquals("https://example.com/fhir/Patient?name=Smith",
                ready().parameters(RestParameterList.of("name", "Smith")).resolvedUrl());
    }

    @Test
    @DisplayName("Only a verb that can carry one reports that it accepts a body")
    void acceptsBodyFollowsTheVerb() {
        assertFalse(ready().method(RestMethod.GET).acceptsBody());
        assertFalse(ready().method(RestMethod.DELETE).acceptsBody());
        assertTrue(ready().method(RestMethod.POST).acceptsBody());
        assertTrue(ready().method(RestMethod.PUT).acceptsBody());
    }

    @Test
    @DisplayName("toString names the request but never the credential")
    void toStringHidesCredentials() {
        String described = ready()
                .authentication(ServerAuthKind.BEARER, "", "", "super-secret-token").toString();

        assertFalse(described.contains("super-secret-token"), described);
        assertTrue(described.contains("https://example.com/fhir/Patient"), described);
    }
}