package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
/**
 * Tests for the plaintext-transport rule.
 *
 * <p>The property that matters is the one a user would call a security hole: a password
 * must never be sent to a remote host over plain HTTP without the application saying so.
 * The cases below are the ones where that rule is easy to get wrong — a loopback address
 * that looks remote, a URL with userinfo in it, and a scheme nobody anticipated.</p>
 */
class TransportSecurityTest {

    @Test
    @DisplayName("A password sent over https cannot be read in transit")
    void httpsIsSafe() {
        assertFalse(TransportSecurity.exposesCredentials("https://prod.example.com/fhir"));
        assertNull(TransportSecurity.warningFor("https://prod.example.com/fhir"));
    }

    @Test
    @DisplayName("A password sent over http to a remote host is exposed")
    void remoteHttpIsExposed() {
        assertTrue(TransportSecurity.exposesCredentials("http://prod.example.com/fhir"),
                "http to a remote host puts the password on the wire in the clear");
    }

    @Test
    @DisplayName("Loopback over http needs no warning, because it never leaves the machine")
    void loopbackHttpIsExempt() {
        // The case a blanket refusal would break, and the one most local testing depends on.
        for (String url : new String[] {
            "http://localhost:8080/fhir",
            "http://localhost/fhir",
            "http://127.0.0.1:8080/fhir",
            "http://127.0.0.1/fhir",
            "http://[::1]:8080/fhir",
        }) {
            assertFalse(TransportSecurity.exposesCredentials(url), url + " is loopback");
            assertNull(TransportSecurity.warningFor(url), url + " should not warn");
        }
    }

    @Test
    @DisplayName("A host that merely starts with 127 is not treated as loopback")
    void aLookalikeHostIsNotExempt() {
        // 127.0.0.1 is loopback; 127.0.0.1.example.com is a remote host that would send the
        // password across the internet. The old startsWith check was on the host, which is
        // right, but it is worth pinning that the host is what gets checked.
        assertTrue(TransportSecurity.exposesCredentials("http://127.0.0.1.example.com/fhir"),
                "a lookalike host is remote and must be warned about");
        assertFalse(TransportSecurity.exposesCredentials("http://127.0.0.2:8080/fhir"),
                "127.0.0.0/8 is loopback");
    }

    @Test
    @DisplayName("Only a real dotted-quad in 127.0.0.0/8 counts as loopback")
    void onlyRealAddressesInTheBlockCount() {
        // Each of these is close enough to a loopback address to pass a loose prefix check
        // and is not one: too few octets, too many, an octet out of range, or letters.
        for (String host : new String[] {
            "127.0.1", "127.0.0.1.5", "127.0.0.999", "127.0.0.1x", "127.0.0.x",
        }) {
            assertTrue(TransportSecurity.exposesCredentials("http://" + host + "/fhir"),
                    host + " is not a loopback address and must be warned about");
        }
        // The whole block really is loopback, not just 127.0.0.1.
        for (String host : new String[] { "127.0.0.1", "127.0.0.2", "127.1.2.3", "127.255.255.254" }) {
            assertFalse(TransportSecurity.exposesCredentials("http://" + host + ":8080/fhir"),
                    host + " is in 127.0.0.0/8 and is loopback");
        }
    }

    @Test
    @DisplayName("A bracketed IPv6 loopback address is recognised")
    void bracketedIpv6LoopbackIsRecognised() {
        // The host of [::1]:8080 is "[::1]" once the brackets are kept, but the colons inside
        // them are part of the address - scanning for the port separator first turned this
        // local address into a remote one.
        assertFalse(TransportSecurity.exposesCredentials("http://[::1]:8080/fhir"),
                "[::1] is loopback");
        assertFalse(TransportSecurity.exposesCredentials("http://[::1]/fhir"));
        // A different IPv6 address is remote, and must not be exempted.
        assertTrue(TransportSecurity.exposesCredentials("http://[2001:db8::1]:8080/fhir"),
                "a routable IPv6 address is not loopback");
    }

    @Test
    @DisplayName("A host that merely starts with localhost is not loopback")
    void aLocalhostLookalikeIsNotExempt() {
        assertTrue(TransportSecurity.exposesCredentials("http://localhost.example.com/fhir"),
                "localhost.example.com is a remote host");
    }

    @Test
    @DisplayName("A URL with userinfo is not mistaken for a loopback host")
    void userinfoDoesNotConfuseTheHost() {
        // http://localhost@evil.example.com/ resolves to evil.example.com. Reading the
        // userinfo as part of the host would call this loopback and stay silent.
        assertTrue(TransportSecurity.exposesCredentials("http://localhost@evil.example.com/fhir"),
                "the host is evil.example.com, which is not loopback");
        assertTrue(TransportSecurity.hasEmbeddedCredentials("http://localhost@evil.example.com/fhir"),
                "and the embedded credential should still be detectable");
    }

    @Test
    @DisplayName("A scheme nobody anticipated is treated as unsafe")
    void anUnknownSchemeIsUnsafe() {
        // Better to warn on something we cannot reason about than to assume it is fine.
        assertTrue(TransportSecurity.exposesCredentials("ftp://files.example.com/fhir"));
        assertTrue(TransportSecurity.exposesCredentials("not a url at all"));
    }

    @Test
    @DisplayName("A missing URL is treated as unsafe")
    void aMissingUrlIsUnsafe() {
        assertTrue(TransportSecurity.exposesCredentials(null));
        assertTrue(TransportSecurity.exposesCredentials("   "));
    }

    @Test
    @DisplayName("The scheme and host are matched without regard to case")
    void matchingIgnoresCase() {
        assertFalse(TransportSecurity.exposesCredentials("HTTPS://Prod.Example.com/fhir"));
        assertFalse(TransportSecurity.exposesCredentials("HTTP://LOCALHOST:8080/fhir"),
                "HTTP://LOCALHOST is still loopback");
    }

    @Test
    @DisplayName("The warning names the risk and does not quote the URL")
    void theWarningIsSafeToShow() {
        String warning = TransportSecurity.warningFor("http://prod.example.com/fhir");
        assertTrue(warning != null && warning.contains("https"), "it should say what to use");
        assertFalse(warning.contains("prod.example.com"),
                "the warning is shown in the UI, so it should not echo the URL back");
    }
}
