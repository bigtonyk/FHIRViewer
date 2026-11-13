package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SMART launch model and its store: what an authorization request needs, what the
 * workbench shows a user, and what is - and is not - written to disk.
 *
 * <p>These need no JavaFX toolkit, so they run in a headless build like every other
 * server-package test.</p>
 */
class SmartLaunchConfigTest {

    @TempDir
    Path tempDir;

    private static SmartLaunchConfig complete() {
        return SmartLaunchConfig.builder()
                .id("id-1")
                .name("Clinic")
                .issuer("https://example.org/fhir")
                .mode(SmartLaunchMode.STANDALONE)
                .clientId("public-client")
                .scopes("openid fhirUser launch/patient")
                .build();
    }

    @Test
    @DisplayName("A fully described launch has no problems")
    void completeLaunchIsComplete() {
        SmartLaunchConfig config = complete();

        assertTrue(config.isComplete(), "a launch with iss, client id and no mode mismatch "
                + "is what the workbench calls complete");
        assertTrue(config.problems().isEmpty());
    }

    @Test
    @DisplayName("A launch with no iss or client id says exactly what is missing")
    void missingEssentialsAreNamed() {
        SmartLaunchConfig config = SmartLaunchConfig.builder()
                .id("id-2")
                .name("Incomplete")
                .mode(SmartLaunchMode.STANDALONE)
                .build();

        assertFalse(config.isComplete());
        List<String> problems = config.problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("iss")),
                "the user must be told the server base URL is missing: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("client id")),
                "the user must be told the client id is missing: " + problems);
    }

    @Test
    @DisplayName("The wrong half of the form is reported, not silently accepted")
    void modeMismatchesAreReported() {
        SmartLaunchConfig ehrWithoutLaunch = SmartLaunchConfig.builder()
                .id("id-3")
                .name("EHR without context")
                .issuer("https://example.org/fhir")
                .mode(SmartLaunchMode.EHR_LAUNCH)
                .clientId("public-client")
                .build();
        SmartLaunchConfig standaloneWithLaunch = SmartLaunchConfig.builder()
                .id("id-4")
                .name("Standalone with context")
                .issuer("https://example.org/fhir")
                .mode(SmartLaunchMode.STANDALONE)
                .clientId("public-client")
                .launchParameter("launch-123")
                .build();

        assertTrue(ehrWithoutLaunch.problems().stream()
                        .anyMatch(p -> p.contains("launch parameter")),
                "an EHR launch without the EHR's parameter cannot be sent: "
                        + ehrWithoutLaunch.problems());
        assertTrue(standaloneWithLaunch.problems().stream()
                        .anyMatch(p -> p.contains("launch parameter")),
                "a standalone launch must not send a launch parameter: "
                        + standaloneWithLaunch.problems());
    }

    @Test
    @DisplayName("The builder refuses a launch with no id or no name")
    void builderRefusesUnusableEntries() {
        assertThrows(IllegalArgumentException.class,
                () -> SmartLaunchConfig.builder().name("No id").build(),
                "both id and name are persistence keys; a half-stored launch is worse "
                        + "than none");
        assertThrows(IllegalArgumentException.class,
                () -> SmartLaunchConfig.builder().id("some-id").build(),
                "a nameless launch could not be chosen from the selector again");
    }

    @Test
    @DisplayName("There is no client secret anywhere on the model")
    void noSecretFieldExists() {
        // Enforced by reflection because that is the only check that survives someone
        // adding the field later: a comment saying "don't" does not.
        for (var method : SmartLaunchConfig.class.getMethods()) {
            String name = method.getName().toLowerCase();
            assertFalse(name.contains("secret"),
                    "a public client has no secret; found " + method.getName());
        }
    }
    @Test
    @DisplayName("The store round-trips every field and the selected name")
    void storeRoundTrips() throws IOException {
        SmartLaunchStore store = new SmartLaunchStore(tempDir.resolve("smart-launches.properties"));
        SmartLaunchConfig ehr = SmartLaunchConfig.builder()
                .id("id-e")
                .name("Ward")
                .issuer("https://ward.example.org/fhir")
                .mode(SmartLaunchMode.EHR_LAUNCH)
                .clientId("client-e")
                .redirectUri("http://localhost:8080/callback")
                .scopes("openid patient/*.read")
                .launchParameter("launch-xyz")
                .build();

        store.write(List.of(complete(), ehr), "Ward");

        List<SmartLaunchConfig> read = store.read();
        assertEquals(2, read.size());
        assertEquals(complete(), read.get(0));
        assertEquals(ehr, read.get(1));
        assertEquals("Ward", store.readSelectedName());
        assertEquals(SmartLaunchMode.EHR_LAUNCH, read.get(1).mode());
        assertEquals("launch-xyz", read.get(1).launchParameter());
        assertEquals("http://localhost:8080/callback", read.get(1).redirectUri());
    }

    @Test
    @DisplayName("Nothing outside the documented field set reaches the file")
    void storeWritesNoUnexpectedKeys() throws IOException {
        // A key allowlist rather than a substring scan: the store's own file header says
        // "no client secret ... access tokens live in memory", so scanning for those words
        // would trip over the comment that promises they are absent. Every key that may
        // appear is listed here; anything else - a secret, a token, a passphrase - fails.
        SmartLaunchStore store = new SmartLaunchStore(tempDir.resolve("no-secrets.properties"));
        store.write(List.of(complete()), "Clinic");

        java.util.Set<String> allowed = java.util.Set.of(
                "id", "name", "issuer", "mode", "clientId", "redirectUri", "scopes",
                "launch", "audience", "selected");
        java.util.Properties properties = new java.util.Properties();
        try (java.io.Reader reader = Files.newBufferedReader(store.file(),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        assertFalse(properties.isEmpty());
        for (String key : properties.stringPropertyNames()) {
            // "launch.1.name" -> "name"; "selected" stays as it is.
            String suffix = key.startsWith("launch.")
                    ? key.substring(key.indexOf('.', 7) + 1)
                    : key;
            assertTrue(allowed.contains(suffix),
                    "unexpected key in the SMART launch store: " + key);
        }
    }

    @Test
    @DisplayName("One unreadable block does not cost the user the rest of the list")
    void storeSkipsUnreadableBlocks() throws IOException {
        SmartLaunchStore store = new SmartLaunchStore(tempDir.resolve("partial.properties"));
        Files.writeString(store.file(), """
                launch.1.id=good-id
                launch.1.name=Good
                launch.1.issuer=https://good.example.org/fhir
                launch.1.mode=STANDALONE
                launch.1.clientId=client-1
                launch.2.id=bad-id
                launch.2.issuer=https://bad.example.org/fhir
                """, StandardCharsets.UTF_8);

        List<SmartLaunchConfig> read = store.read();

        assertEquals(1, read.size(), "the block with no name is skipped, not fatal");
        assertEquals("Good", read.get(0).name());
        assertTrue(store.exists());
    }

    @Test
    @DisplayName("Reading a store that was never written yields an empty list, not a failure")
    void storeReadsMissingFileAsEmpty() throws IOException {
        SmartLaunchStore store = new SmartLaunchStore(tempDir.resolve("absent.properties"));

        assertFalse(store.exists());
        assertTrue(store.read().isEmpty());
        assertNull(store.readSelectedName());
    }
}
