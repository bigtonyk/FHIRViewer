package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Extra authorization parameters and Force re-login: what a launch may add to its
 * authorization request, what it may never replace, and that both survive the store.
 */
class AuthorizationParametersTest {

    private static final String REDIRECT = "http://127.0.0.1:54321/callback";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Lines parse in order; blanks and comments are skipped; values keep inner '='")
    void parsesLines() {
        List<Map.Entry<String, String>> parsed = AuthorizationParameters.parse("""
                # Smile CDR provider selection
                launch = keycloak-standalone

                kc_idp_hint=corporate
                acr_values=urn:a urn:b
                token=a=b
                """);

        assertEquals(List.of(Map.entry("launch", "keycloak-standalone"),
                Map.entry("kc_idp_hint", "corporate"), Map.entry("acr_values", "urn:a urn:b"),
                Map.entry("token", "a=b")), parsed);
        assertTrue(AuthorizationParameters.problems("""
                # Smile CDR provider selection
                launch = keycloak-standalone
                kc_idp_hint=corporate
                """, SmartLaunchMode.STANDALONE, false).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "redirect_uri=http://evil.example.com/cb", "STATE=x", "client_id=other",
        "scope=system/*.*", "nonce=n", "code_challenge=c", "code_challenge_method=plain",
        "aud=https://other.example.com", "response_type=token", "response_mode=form_post",
        "request=eyJ", "request_uri=urn:x"
    })
    @DisplayName("A parameter the launch sets itself cannot be overridden")
    void reservedNamesAreRefused(String line) {
        List<String> problems = AuthorizationParameters.problems(line,
                SmartLaunchMode.STANDALONE, false);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("cannot be overridden"), problems.toString());
    }

    @Test
    @DisplayName("launch is free in a standalone launch but belongs to its own field in an EHR launch")
    void launchDependsOnMode() {
        assertTrue(AuthorizationParameters.problems("launch=x", SmartLaunchMode.STANDALONE,
                false).isEmpty());
        assertFalse(AuthorizationParameters.problems("launch=x", SmartLaunchMode.EHR_LAUNCH,
                false).isEmpty());
    }

    @Test
    @DisplayName("prompt is free unless Force re-login already sends it")
    void promptDependsOnForceLogin() {
        assertTrue(AuthorizationParameters.problems("prompt=consent",
                SmartLaunchMode.STANDALONE, false).isEmpty());
        assertFalse(AuthorizationParameters.problems("prompt=consent",
                SmartLaunchMode.STANDALONE, true).isEmpty());
    }

    @Test
    @DisplayName("Malformed lines, bad names, empty values and duplicates are each reported")
    void malformedLinesAreReported() {
        List<String> problems = AuthorizationParameters.problems("""
                no-equals-sign
                =value
                bad name=x
                empty=
                idp=a
                IDP=b
                """, SmartLaunchMode.STANDALONE, false);

        assertEquals(5, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("more than once")),
                problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("no value")), problems.toString());
    }

    @Test
    @DisplayName("Diagnostics see the names only, never a value")
    void namesOnly() {
        String names = AuthorizationParameters.namesOf("login_hint=someone@example.com\nidp=x");
        assertEquals("login_hint, idp", names);
        assertEquals("none", AuthorizationParameters.namesOf(null));
    }

    @Test
    @DisplayName("A standalone launch sends launch, prompt=login and its extras after the core set")
    void flowSendsExtrasAndPrompt() throws Exception {
        SmartLaunchConfig config = SmartLaunchConfig.builder()
                .id("id-fed").name("Federated").issuer("https://example.org/fhir")
                .mode(SmartLaunchMode.STANDALONE).clientId("public-client")
                .scopes("openid patient/*.read offline_access")
                .authorizationParameters("launch=keycloak-standalone\nkc_idp_hint=corporate")
                .forceLogin(true)
                .build();
        assertTrue(config.isComplete(), config.problems().toString());

        URI uri = SmartAuthorizationFlow.requestFor(config, discovered(),
                SmartAuthState.of("state-abc", "nonce-xyz"),
                Pkce.ofVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), REDIRECT)
                .toUri();

        Map<String, String> params = LoopbackCallbackServer.queryParams(uri.getRawQuery());
        assertEquals("keycloak-standalone", params.get("launch"));
        assertEquals("corporate", params.get("kc_idp_hint"));
        assertEquals("login", params.get("prompt"));
        assertEquals("https://example.org/fhir", params.get("aud"));
        assertEquals(REDIRECT, params.get("redirect_uri"));
    }

    @Test
    @DisplayName("Without either setting the request carries no prompt and no extras")
    void flowSendsNothingExtraByDefault() throws Exception {
        SmartLaunchConfig config = SmartLaunchConfig.builder()
                .id("id-plain").name("Plain").issuer("https://example.org/fhir")
                .clientId("public-client").scopes("patient/*.read").build();

        URI uri = SmartAuthorizationFlow.requestFor(config, discovered(),
                SmartAuthState.of("s", "n"), Pkce.generate(), REDIRECT).toUri();

        Map<String, String> params = LoopbackCallbackServer.queryParams(uri.getRawQuery());
        assertFalse(params.containsKey("prompt"), params.toString());
        assertFalse(params.containsKey("launch"), params.toString());
    }

    @Test
    @DisplayName("The request builder refuses a reserved or repeated extra whatever the caller checked")
    void builderIsTheBackstop() {
        assertThrows(IllegalArgumentException.class, () -> request()
                .extraParameters(List.of(Map.entry("redirect_uri", "http://evil.example.com")))
                .build());
        assertThrows(IllegalArgumentException.class, () -> request()
                .prompt("login")
                .extraParameters(List.of(Map.entry("prompt", "none")))
                .build());
        assertThrows(IllegalArgumentException.class, () -> request()
                .launchParameter("ehr-context")
                .extraParameters(List.of(Map.entry("launch", "other")))
                .build());
        assertThrows(IllegalArgumentException.class, () -> request()
                .extraParameters(List.of(Map.entry("idp", "a"), Map.entry("IDP", "b")))
                .build());

        URI uri = request().extraParameters(List.of(Map.entry("acr_values", "urn:a urn:b")))
                .build().toUri();
        assertEquals(1, count(uri.getRawQuery(), "redirect_uri="));
        assertTrue(uri.getRawQuery().contains("acr_values=urn%3Aa%20urn%3Ab"),
                uri.getRawQuery());
    }

    @Test
    @DisplayName("A configuration with a reserved extra reports it and cannot be authorized")
    void configReportsReservedExtra() {
        SmartLaunchConfig config = SmartLaunchConfig.builder()
                .id("id-bad").name("Bad").issuer("https://example.org/fhir")
                .clientId("public-client").authorizationParameters("state=mine").build();

        assertFalse(config.isComplete());
        assertThrows(SmartAuthorizationException.class,
                () -> new SmartAuthorizationFlow(url -> new BrowserLauncher.LaunchResult.Opened(),
                        new SmartTokenExchangeService(), new SmartJwksFetcher())
                        .authorize(config, discovered()));
    }

    @Test
    @DisplayName("Both settings survive the store, and a launch without them writes neither key")
    void storeRoundTrips() throws IOException {
        SmartLaunchStore store = new SmartLaunchStore(tempDir.resolve("smart.properties"));
        SmartLaunchConfig with = SmartLaunchConfig.builder()
                .id("id-a").name("With").issuer("https://example.org/fhir")
                .clientId("c").authorizationParameters("  kc_idp_hint=corporate \n\n idp=x ")
                .forceLogin(true).build();
        SmartLaunchConfig without = SmartLaunchConfig.builder()
                .id("id-b").name("Without").issuer("https://example.org/fhir")
                .clientId("c").build();

        store.write(List.of(with, without), null);
        List<SmartLaunchConfig> read = store.read();

        assertEquals("kc_idp_hint=corporate\nidp=x", read.get(0).authorizationParameters());
        assertTrue(read.get(0).forceLogin());
        assertNull(read.get(1).authorizationParameters());
        assertFalse(read.get(1).forceLogin());
        assertFalse(SmartLaunchStore.render(List.of(without), null).stringPropertyNames()
                .stream().anyMatch(k -> k.endsWith("forceLogin")
                        || k.endsWith("authorizationParameters")));
        SmartLaunchConfig copy = with.withName("Copy", "id-c");
        assertEquals(with.authorizationParameters(), copy.authorizationParameters());
        assertTrue(copy.forceLogin());
    }

    private static SmartAuthorizationRequest.Builder request() {
        return SmartAuthorizationRequest.to("https://auth.example.com/authorize")
                .clientId("public-client").redirectUri(REDIRECT);
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }

    private static SmartConfiguration discovered() {
        return SmartConfiguration.builder()
                .issuer("https://example.org/fhir")
                .authorizationEndpoint("https://example.org/fhir/authorize")
                .tokenEndpoint("https://example.org/fhir/token")
                .codeChallengeMethodsSupported(List.of("S256"))
                .build();
    }
}
