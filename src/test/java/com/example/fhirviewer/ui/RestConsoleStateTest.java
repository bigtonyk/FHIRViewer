package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.ServerAuthKind;
import com.example.fhirviewer.server.rest.RestMethod;

/**
 * What the console remembers, and — more importantly — what it must not.
 *
 * <p>These tests read and write the same preferences node the application uses and clear it
 * afterwards. That is deliberate: a test against a mock would not catch the one thing that
 * matters here, which is a secret reaching the disk through a path nobody thought about.
 *
 * <p>They do use a node of their own rather than the real one, so running the suite cannot
 * disturb a developer's remembered console settings.
 */
class RestConsoleStateTest {

    private static RestConsoleState roundTrip(RestConsoleState state) {
        state.save();
        return RestConsoleState.load(List.of());
    }

    private static RestConsoleState populated() {
        return new RestConsoleState(
                "https://example.com/fhir",
                RestMethod.POST,
                "https://example.com/fhir",
                "Patient",
                List.of(new RestParameterList.Parameter("name", "Smith"),
                        new RestParameterList.Parameter("_include", "Patient:organization"),
                        new RestParameterList.Parameter("_include", "Patient:general-practitioner")),
                List.of(new RestParameterList.Parameter("If-Match", "W/\"3\"")),
                "application/fhir+json",
                ServerAuthKind.BASIC,
                "alice",
                1500, 900, 0.6);
    }

    @Test
    @DisplayName("The address, the verb and the path all come back")
    void addressIsRemembered() {
        RestConsoleState restored = roundTrip(populated());

        assertEquals("https://example.com/fhir", restored.serverBaseUrl());
        assertEquals("https://example.com/fhir", restored.baseUrl());
        assertEquals("Patient", restored.path());
        assertEquals(RestMethod.POST, restored.method());
    }

    @Test
    @DisplayName("Parameters come back in order, with a repeated name still repeated")
    void parametersKeepOrderAndRepetition() {
        RestConsoleState restored = roundTrip(populated());

        assertEquals(3, restored.parameters().size());
        assertEquals("name", restored.parameters().get(0).name());
        assertEquals("Smith", restored.parameters().get(0).value());
        assertEquals(List.of("Patient:organization", "Patient:general-practitioner"),
                restored.parameters().stream()
                        .filter(p -> p.name().equals("_include"))
                        .map(RestParameterList.Parameter::value)
                        .toList(),
                "an _include repeated twice must come back twice, in order");
    }

    @Test
    @DisplayName("Headers come back too")
    void headersAreRemembered() {
        RestConsoleState restored = roundTrip(populated());

        assertEquals(1, restored.headers().size());
        assertEquals("If-Match", restored.headers().get(0).name());
        assertEquals("W/\"3\"", restored.headers().get(0).value());
    }

    @Test
    @DisplayName("The chosen authentication kind, user name and window geometry come back")
    void authAndGeometryAreRemembered() {
        RestConsoleState restored = roundTrip(populated());

        assertEquals(ServerAuthKind.BASIC, restored.authKind());
        assertEquals("alice", restored.userName());
        assertEquals(1500, restored.windowWidth());
        assertEquals(900, restored.windowHeight());
        assertEquals(0.6, restored.dividerPosition());
    }

@Test
    @DisplayName("There is nowhere in the state to put a password, token or body")
    void thereIsNoPlaceForASecret() {
        // The test that matters most, and a structural one: the record has no component that
        // can hold a credential, so a future edit to the capture code cannot start persisting
        // one without this failing to compile.
        List<String> components = java.util.Arrays.stream(RestConsoleState.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertFalse(components.contains("password"), components.toString());
        assertFalse(components.contains("token"), components.toString());
        assertFalse(components.contains("secret"), components.toString());
        assertFalse(components.contains("body"),
                "a body is patient data as often as not; see the class Javadoc: " + components);
    }

    @Test
    @DisplayName("Nothing credential-shaped reaches the preferences store")
    void noSecretReachesTheStore() throws Exception {
        populated().save();

        java.util.prefs.Preferences node = RestConsoleStateTest.node();
        for (String key : node.keys()) {
            String value = node.get(key, "");
            assertFalse(value.contains("s3cret"), key + " looks like it holds a password");
            assertFalse(value.toLowerCase(Locale.ROOT).contains("bearer "),
                    key + " looks like it holds a token");
        }
    }

    @Test
    @DisplayName("An empty state reads back as usable defaults")
    void emptyStateIsUsable() {
        RestConsoleState restored = roundTrip(RestConsoleState.defaults());

        assertEquals(RestMethod.GET, restored.method());
        assertEquals(ServerAuthKind.ANONYMOUS, restored.authKind());
        assertTrue(restored.parameters().isEmpty());
        assertTrue(restored.windowWidth() > 0,
                "a remembered size of zero would open an unusably small window");
    }

    @Test
    @DisplayName("Unreadable stored text falls back rather than failing")
    void corruptStoredTextIsTolerated() throws Exception {
        java.util.prefs.Preferences node = node();
        node.put("method", "TELEPORT");
        node.put("authKind", "carrier-pigeon");
        node.put("parameters", "{not json at all");
        node.flush();

        RestConsoleState restored = RestConsoleState.load(List.of());

        assertEquals(RestMethod.GET, restored.method());
        assertEquals(ServerAuthKind.ANONYMOUS, restored.authKind());
        assertTrue(restored.parameters().isEmpty());
    }

    @Test
    @DisplayName("A remembered server is matched back to a configured one")
    void serverMatching() {
        assertTrue(populated().matchesAServer(List.of("https://example.com/fhir")));
        assertFalse(populated().matchesAServer(List.of("https://other.example/fhir")),
                "a deleted server should come back as a custom URL, not a wrong match");
        assertFalse(populated().matchesAServer(null));
    }

    @Test
    @DisplayName("toString describes the request without naming anything secret")
    void toStringIsSafe() {
        String described = populated().toString();

        assertTrue(described.contains("Patient"), described);
        assertFalse(described.contains("alice"), described);
    }

    /**
     * The preferences node these tests use.
     *
     * <p>Delegated to the class under test so the tests and the application cannot drift
     * onto different nodes, which would let this suite pass while the real key was broken.
     */
    private static java.util.prefs.Preferences node() throws java.util.prefs.BackingStoreException {
        return RestConsoleState.nodeForTest();
    }
}