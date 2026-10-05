package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An ad-hoc server configuration, and especially the credential key it must never produce.
 *
 * <p>The key is the property with teeth. {@link ServerCredentials} files passwords by it, so
 * a key that collided with a saved server's would mean typing a URL into the console could
 * pick up — or be mistaken for — credentials belonging to a server the user configured.
 */
class AdhocServerTest {

    @Test
    @DisplayName("It is a usable configuration for an unsaved URL")
    void carriesTheTypedUrl() {
        AdhocServer server = AdhocServer.at("https://example.com/fhir");

        assertEquals("https://example.com/fhir", server.baseUrl());
        assertEquals("R4", server.fhirVersion());
        assertTrue(server.timeoutMillis() > 0);
        assertTrue(server.extraHeaders().isEmpty());
    }

    @Test
    @DisplayName("A blank or schemeless URL is refused, naming the problem")
    void unusableUrlsAreRefused() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AdhocServer.at("  ")).getMessage().contains("base URL"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AdhocServer.at("example.com/fhir")).getMessage().contains("http"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AdhocServer.at("ftp://example.com")).getMessage().contains("http"));
    }

    @Test
    @DisplayName("Surrounding whitespace is trimmed rather than sent")
    void urlIsTrimmed() {
        assertEquals("https://example.com/fhir",
                AdhocServer.at("  https://example.com/fhir  ").baseUrl());
    }

    @Test
    @DisplayName("The credential key can never collide with a saved server")
    void credentialKeyCannotCollide() {
        String base = "https://example.com/fhir";

        assertNotEquals(base, AdhocServer.at(base).credentialKey(),
                "a plain URL key would be exactly what ServerDefinition-style keying produces");
        assertTrue(AdhocServer.at(base).credentialKey().startsWith("adhoc:"));
    }

    @Test
    @DisplayName("It serves no administration API of its own")
    void administrationBaseUrlIsAbsent() {
        assertNull(AdhocServer.at("https://example.com/fhir").administrationBaseUrl());
    }

    @Test
    @DisplayName("The usability check can be asked without catching anything")
    void usabilityIsAskable() {
        assertTrue(AdhocServer.isUsableBaseUrl("https://example.com"));
        assertTrue(AdhocServer.isUsableBaseUrl("http://localhost:8080/fhir"));
        assertFalse(AdhocServer.isUsableBaseUrl("localhost:8080"));
        assertFalse(AdhocServer.isUsableBaseUrl(""));
        assertFalse(AdhocServer.isUsableBaseUrl(null));
    }

    @Test
    @DisplayName("toString is a name and a URL, and nothing else")
    void toStringIsSafe() {
        assertEquals("REST console (https://example.com/fhir)",
                AdhocServer.at("https://example.com/fhir").toString());
    }
}