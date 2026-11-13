package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parsing a discovery document, field by field.
 *
 * <p>The happy path is the sample response from SMART App Launch v2.2.0 §Conformance, so
 * the parser is tested against the specification's own example rather than one this
 * repository invented.</p>
 */
class SmartConfigurationTest {

    /** The specification's own sample response, verbatim apart from whitespace. */
    static final String SPEC_SAMPLE = """
            {
              "issuer": "https://ehr.example.com",
              "jwks_uri": "https://ehr.example.com/.well-known/jwks.json",
              "authorization_endpoint": "https://ehr.example.com/auth/authorize",
              "token_endpoint": "https://ehr.example.com/auth/token",
              "token_endpoint_auth_methods_supported": [
                "client_secret_basic", "private_key_jwt"
              ],
              "grant_types_supported": ["authorization_code", "client_credentials"],
              "registration_endpoint": "https://ehr.example.com/auth/register",
              "scopes_supported": ["openid", "profile", "launch", "launch/patient",
                "patient/*.rs", "user/*.rs", "offline_access"],
              "response_types_supported": ["code"],
              "management_endpoint": "https://ehr.example.com/user/manage",
              "introspection_endpoint": "https://ehr.example.com/user/introspect",
              "revocation_endpoint": "https://ehr.example.com/user/revoke",
              "code_challenge_methods_supported": ["S256"],
              "capabilities": [
                "launch-ehr", "permission-patient", "permission-v2", "client-public",
                "client-confidential-symmetric", "context-ehr-patient", "sso-openid-connect"
              ],
              "associated_endpoints": [{
                "url": "https://state.example.com",
                "capabilities": ["smart-app-state"]
              }]
            }
            """;

    @Test
    @DisplayName("The specification's own sample parses field for field")
    void specSampleParses() throws Exception {
        SmartConfiguration config = SmartConfiguration.parse(SPEC_SAMPLE);

        assertEquals("https://ehr.example.com", config.issuer());
        assertEquals("https://ehr.example.com/auth/authorize", config.authorizationEndpoint());
        assertEquals("https://ehr.example.com/auth/token", config.tokenEndpoint());
        assertEquals("https://ehr.example.com/.well-known/jwks.json", config.jwksUri());
        assertEquals("https://ehr.example.com/auth/register", config.registrationEndpoint());
        assertTrue(config.capabilities().contains("launch-ehr"));
        assertTrue(config.scopesSupported().contains("launch/patient"));
        assertEquals(List.of("S256"), config.codeChallengeMethodsSupported());
        assertTrue(config.grantTypesSupported().contains("authorization_code"));
        assertTrue(config.tokenEndpointAuthMethodsSupported().contains("private_key_jwt"));
        assertEquals("https://ehr.example.com/user/manage", config.managementEndpoint());
        assertTrue(config.problems().isEmpty(),
                "the specification's own sample must be problem-free: " + config.problems());
        assertTrue(config.isUsable());
    }
    @Test
    @DisplayName("Fields this build does not know are kept, not dropped")
    void unknownFieldsArePreserved() throws Exception {
        SmartConfiguration config = SmartConfiguration.parse("""
                {
                  "issuer": "https://ehr.example.com",
                  "authorization_endpoint": "https://ehr.example.com/auth/authorize",
                  "token_endpoint": "https://ehr.example.com/auth/token",
                  "capabilities": ["launch-standalone"],
                  "code_challenge_methods_supported": ["S256"],
                  "vendor_extension": {"depth": 3}
                }
                """);

        assertTrue(config.unknownFields().containsKey("vendor_extension"),
                "a field the next specification version adds must still be visible to the "
                        + "diagnostics");
        assertEquals("{\"depth\":3}", config.unknownFields().get("vendor_extension"));
    }

    @Test
    @DisplayName("A missing required endpoint is named, and makes the document unusable")
    void missingEndpointsAreNamed() throws Exception {
        SmartConfiguration config = SmartConfiguration.parse("""
                {"issuer": "https://ehr.example.com", "capabilities": ["launch-standalone"],
                 "code_challenge_methods_supported": ["S256"]}
                """);

        assertFalse(config.isUsable());
        assertTrue(config.problems().stream().anyMatch(p -> p.contains("authorization_endpoint")),
                "the user must be told which field is missing: " + config.problems());
        assertTrue(config.problems().stream().anyMatch(p -> p.contains("token_endpoint")),
                "the user must be told which field is missing: " + config.problems());
    }

    /**
     * A plain-http endpoint on a real host is refused; loopback is allowed.
     *
     * <p>The one check that protects a credential in transit: an https document must not
     * yield an http endpoint, while a developer's server on this machine stays usable.</p>
     */
    @Test
    @DisplayName("A plain-http endpoint is refused anywhere but this machine")
    void insecureEndpointsAreRefused() throws Exception {
        SmartConfiguration remote = SmartConfiguration.parse("""
                {
                  "issuer": "https://ehr.example.com",
                  "authorization_endpoint": "http://ehr.example.com/auth/authorize",
                  "token_endpoint": "https://ehr.example.com/auth/token",
                  "capabilities": ["launch-standalone"],
                  "code_challenge_methods_supported": ["S256"]
                }
                """);
        assertFalse(remote.isUsable(), "an https document must not yield an http endpoint");
        assertTrue(remote.problems().stream().anyMatch(p -> p.contains("plain http")),
                "the reason must name the transport: " + remote.problems());

        SmartConfiguration local = SmartConfiguration.parse("""
                {
                  "issuer": "http://localhost:8080/fhir",
                  "authorization_endpoint": "http://localhost:8080/auth/authorize",
                  "token_endpoint": "http://localhost:8080/auth/token",
                  "capabilities": ["launch-standalone"],
                  "code_challenge_methods_supported": ["S256"]
                }
                """);
        assertTrue(local.isUsable(),
                "a developer's loopback server must remain usable over plain http");
        assertTrue(local.problems().isEmpty(), String.valueOf(local.problems()));
    }

    @Test
    @DisplayName("A server without S256 — or with plain PKCE — is told why a launch fails")
    void pkceProblemsAreReported() throws Exception {
        SmartConfiguration without = SmartConfiguration.parse("""
                {
                  "issuer": "https://ehr.example.com",
                  "authorization_endpoint": "https://ehr.example.com/auth/authorize",
                  "token_endpoint": "https://ehr.example.com/auth/token",
                  "capabilities": ["launch-standalone"],
                  "code_challenge_methods_supported": ["plain"]
                }
                """);
        assertTrue(without.problems().stream().anyMatch(p -> p.contains("S256")),
                "S256 is what this application sends: " + without.problems());
        assertTrue(without.problems().stream().anyMatch(p -> p.contains("plain")),
                "plain PKCE must be flagged even when the server is otherwise usable: "
                        + without.problems());
    }

    @Test
    @DisplayName("An answer that is not a JSON object is not a SMART server")
    void malformedAnswersAreDiagnosed() {
        SmartDiscoveryException notJson = assertThrows(SmartDiscoveryException.class,
                () -> SmartConfiguration.parse("<html><body>Not found</body></html>"));
        assertEquals(SmartDiscoveryException.Kind.NOT_SMART, notJson.kind());

        SmartDiscoveryException notObject = assertThrows(SmartDiscoveryException.class,
                () -> SmartConfiguration.parse("[1, 2, 3]"),
                "a JSON array is valid JSON and still not a discovery document");
        assertEquals(SmartDiscoveryException.Kind.NOT_SMART, notObject.kind());
    }
}