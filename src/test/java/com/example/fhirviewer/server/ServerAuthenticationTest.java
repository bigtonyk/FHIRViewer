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
 * Tests the authentication seam itself: what each mechanism contributes as headers, and
 * the rule that keeps those values out of anything printable.
 *
 * <p>The point of Phase 3 is that adding a mechanism required no change anywhere that
 * sends a request, so the tests below deliberately define a mechanism that exists
 * nowhere else in the codebase and check that it is honoured like any other. If a
 * future change reintroduces an {@code instanceof} over the mechanism types, these fail.
 */
public class ServerAuthenticationTest {

    @Test
    @DisplayName("Anonymous access contributes no header at all")
    void anonymousContributesNothing() {
        RequestHeaders headers = AnonymousServerAuthentication.INSTANCE.requestHeaders();

        assertTrue(headers.isEmpty());
        assertEquals(0, headers.size());
        assertTrue(AnonymousServerAuthentication.INSTANCE.isAnonymous());
    }

    @Test
    @DisplayName("Basic contributes one base64 Authorization header")
    void basicContributesAuthorization() {
        // "alice:s3cret" base64-encoded.
        String header = new BasicServerAuthentication("alice", "s3cret")
                .requestHeaders().first("Authorization");

        assertEquals("Basic YWxpY2U6czNjcmV0", header);
    }

    @Test
    @DisplayName("Bearer contributes a bearer Authorization header")
    void bearerContributesAuthorization() {
        ServerAuthentication bearer = new BearerServerAuthentication("abc.def.ghi");

        assertEquals("Bearer abc.def.ghi", bearer.requestHeaders().first("Authorization"));
        assertEquals("bearer", bearer.type());
        assertFalse(bearer.isAnonymous());
    }

    @Test
    @DisplayName("A mechanism the project has never heard of is applied like any other")
    void anUnknownMechanismStillContributesItsHeader() {
        // This is the whole reason requestHeaders() exists. Before it, adding a vendor
        // API key meant another instanceof branch in newClient() and a refusal here.
        ServerAuthentication vendorKey = new ServerAuthentication() {
            @Override
            public String type() {
                return "vendor-key";
            }

            @Override
            public String displayName() {
                return "Vendor API key";
            }

            @Override
            public boolean isAnonymous() {
                return false;
            }

            @Override
            public RequestHeaders requestHeaders() {
                return RequestHeaders.of("X-Vendor-Key", "key-12345");
            }
        };

        assertEquals("key-12345", vendorKey.requestHeaders().first("X-Vendor-Key"));
        assertFalse(RequestHeaders.isSecret("X-Vendor-Key"),
                "the redaction list is a documented, fixed set: a header this build does "
                        + "not know about is not automatically treated as a secret");
    }

    @Test
    @DisplayName("A mechanism that supplies no header is distinguishable from one that does")
    void aHeaderlessMechanismIsDetectable() {
        // This is what StandardFhirRestPlugin now checks instead of naming two types:
        // a mechanism that cannot express itself is refused rather than silently
        // sending the request unauthenticated.
        ServerAuthentication incomplete = new ServerAuthentication() {
            @Override
            public String type() {
                return "smart";
            }

            @Override
            public String displayName() {
                return "SMART (not implemented)";
            }

            @Override
            public boolean isAnonymous() {
                return false;
            }
        };

        assertFalse(incomplete.isAnonymous());
        assertTrue(incomplete.requestHeaders().isEmpty());
    }

    @Test
    @DisplayName("Header lookup ignores case, as HTTP does")
    void lookupIgnoresCase() {
        RequestHeaders headers = new BearerServerAuthentication("t").requestHeaders();

        assertEquals("Bearer t", headers.first("authorization"));
        assertEquals("Bearer t", headers.first("AUTHORIZATION"));
        assertTrue(headers.contains("AuThOrIzAtIoN"));
        assertNull(headers.first("X-Absent"));
    }

    @Test
    @DisplayName("Credential values are redacted, and other values are not")
    void credentialsAreRedacted() {
        RequestHeaders headers = RequestHeaders.builder()
                .set("Authorization", "Bearer super-secret-token")
                .set("X-Tenant", "north")
                .build();

        String printed = headers.toString();

        assertFalse(printed.contains("super-secret-token"), "a token must never be printed");
        assertTrue(printed.contains("<redacted>"), "the reader still sees a value was set");
        assertTrue(printed.contains("Authorization"), "the name itself is useful");
        assertTrue(printed.contains("north"), "an ordinary header stays readable");
    }

    @Test
    @DisplayName("The transport and the authentication agree on which headers are secret")
    void redactionIsSharedWithTheTransport() {
        // RestHeaders.isSecret delegates here, so a header cannot be redacted on one
        // side of a request and printed on the other.
        assertTrue(RequestHeaders.isSecret("Authorization"));
        assertTrue(RequestHeaders.isSecret("PROXY-AUTHORIZATION"));
        assertTrue(RequestHeaders.isSecret("Set-Cookie"));
        assertTrue(RequestHeaders.isSecret("x-api-key"));
        assertFalse(RequestHeaders.isSecret("X-Tenant"));
        assertFalse(RequestHeaders.isSecret("Accept"));
        assertFalse(RequestHeaders.isSecret(null));
    }

    @Test
    @DisplayName("Nothing an authentication prints reveals the secret it holds")
    void noAuthenticationLeaksItsSecret() {
        BasicServerAuthentication basic = new BasicServerAuthentication("alice", "s3cret");
        BearerServerAuthentication bearer = new BearerServerAuthentication("top.secret.token");

        assertFalse(basic.toString().contains("s3cret"), "the password must not be printed");
        assertFalse(bearer.toString().contains("top.secret.token"),
                "the token must not be printed");
        assertTrue(basic.toString().contains("alice"), "the user name is not a secret");
        assertNotEquals("top.secret.token", bearer.toString());
    }

    @Test
    @DisplayName("Saved settings produce basic credentials, or anonymous when there are none")
    void basicIsBuiltFromSavedSettings() {
        ServerAuthentication withCredentials = BasicServerAuthentication.from(
                new PluginSettings("p", "https://x/fhir", "alice", "s3cret"));
        ServerAuthentication without =
                BasicServerAuthentication.from(new PluginSettings("p", "https://x/fhir", null, null));

        assertEquals("basic", withCredentials.type());
        assertFalse(withCredentials.isAnonymous());
        assertTrue(without.isAnonymous());
    }

    @Test
    @DisplayName("A blank token or user name is refused when the mechanism is built")
    void mechanismsValidateTheirInput() {
        assertThrows(IllegalArgumentException.class, () -> new BearerServerAuthentication("  "));
        assertThrows(IllegalArgumentException.class, () -> new BearerServerAuthentication(null));
        assertThrows(IllegalArgumentException.class, () -> new BasicServerAuthentication("", "p"));
        assertThrows(NullPointerException.class, () -> new BasicServerAuthentication("alice", null));
    }
}

