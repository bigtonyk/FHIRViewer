package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reading a registered redirect URI as the loopback address to bind.
 */
class LoopbackRedirectTest {

    @Test
    @DisplayName("Blank means no fixed address")
    void blankIsEmpty() {
        assertTrue(LoopbackRedirect.parse(null).isEmpty());
        assertTrue(LoopbackRedirect.parse("   ").isEmpty());
    }

    @Test
    @DisplayName("A 127.0.0.1 URI binds that address, port and path, and is sent as entered")
    void ipv4Loopback() {
        LoopbackRedirect redirect = LoopbackRedirect.parse(" http://127.0.0.1:8765/smart/cb ")
                .orElseThrow();
        assertEquals("http://127.0.0.1:8765/smart/cb", redirect.uri());
        assertEquals("127.0.0.1", redirect.host());
        assertEquals(8765, redirect.port());
        assertEquals("/smart/cb", redirect.path());
    }

    @Test
    @DisplayName("localhost is sent as localhost but bound on 127.0.0.1")
    void localhostBindsIpv4() {
        LoopbackRedirect redirect = LoopbackRedirect.parse("http://localhost:9000/callback")
                .orElseThrow();
        assertEquals("http://localhost:9000/callback", redirect.uri());
        assertEquals("127.0.0.1", redirect.host());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://127.0.0.1:8765/callback",
        "http://example.com:8765/callback",
        "http://192.168.1.5:8765/callback",
        "http://127.0.0.1.example.com:8765/callback",
        "http://[::1]:8765/callback",
        "http://127.0.0.1/callback",
        "http://127.0.0.1:8765",
        "http://127.0.0.1:8765/",
        "http://127.0.0.1:8765/callback?x=1",
        "http://127.0.0.1:8765/callback#frag",
        "http://user@127.0.0.1:8765/callback",
        "not a url"
    })
    @DisplayName("Anything but an http loopback URI with a port and a path is refused")
    void refusesUnusable(String value) {
        assertThrows(IllegalArgumentException.class, () -> LoopbackRedirect.parse(value));
    }
}
