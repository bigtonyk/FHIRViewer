package com.spiraleyes.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where downloaded packages live when the user has not chosen a directory.
 *
 * <p>The default used to be a string built once at class load, which tied it to whatever
 * {@code user.home} happened to be then. It is now resolved on every call, so the two tests
 * here pin both halves: the path is built from the current home, and moving home moves it.</p>
 *
 * <p>The preference node is the application's real one, snapshotted and restored around each
 * test, so a run can neither read nor leave behind a chosen directory.</p>
 */
class PackageStorageTest {

    private static final String A_PACKAGE = "hl7.fhir.us.core-9.0.0.tgz";

    @TempDir
    Path dir;

    private String originalHome;
    private Map<String, Map<String, String>> savedPreferences;

    @BeforeEach
    void isolateTheRun() throws Exception {
        originalHome = System.getProperty("user.home");
        savedPreferences = snapshot();
    }

    @AfterEach
    void restoreTheRun() throws Exception {
        restore(savedPreferences);
        if (originalHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    @DisplayName("Without a chosen directory, packages live under the current home")
    void theDefaultDirectoryIsUnderTheCurrentHome() throws Exception {
        Path home = dir.resolve("home");

        System.setProperty("user.home", home.toString());

        assertEquals(home.resolve(".fhirviewer").resolve("packages"),
                PackageStorage.getStorageDirectory());
    }

    @Test
    @DisplayName("The default follows a home that changes after the class is loaded")
    void theDefaultIsResolvedPerCallNotAtClassLoad() throws Exception {
        Path first = dir.resolve("first-home");
        Path second = dir.resolve("second-home");
        System.setProperty("user.home", first.toString());
        assertEquals(first.resolve(".fhirviewer").resolve("packages"),
                PackageStorage.getStorageDirectory());

        System.setProperty("user.home", second.toString());

        assertEquals(second.resolve(".fhirviewer").resolve("packages"),
                PackageStorage.getStorageDirectory());
    }

    @Test
    @DisplayName("A directory the user chose wins over the default")
    void aChosenDirectoryWins() throws Exception {
        Path chosen = dir.resolve("chosen");
        System.setProperty("user.home", dir.resolve("home").toString());

        PackageStorage.setStorageDirectory(chosen);

        assertEquals(chosen.toAbsolutePath(), PackageStorage.getStorageDirectory());
    }

    @Test
    @DisplayName("An archive in the directory is listed by the id and version in its name")
    void anArchiveIsListedByIdAndVersion() throws IOException {
        Path archive = Files.writeString(dir.resolve(A_PACKAGE), "");

        List<PackageStorage.StoredPackage> stored = PackageStorage.listStored(dir);

        assertEquals(1, stored.size());
        assertEquals("hl7.fhir.us.core", stored.get(0).packageId());
        assertEquals("9.0.0", stored.get(0).version());
        assertEquals("hl7.fhir.us.core#9.0.0", stored.get(0).key());
        assertEquals("hl7.fhir.us.core 9.0.0", stored.get(0).label());
        assertEquals(archive, stored.get(0).file());
    }

    private static Preferences packageNode() throws Exception {
        return Preferences.userRoot().node("/" + PackageStorage.class.getPackageName()
                .replace('.', '/'));
    }

    private static Preferences legacyNode() throws Exception {
        return Preferences.userRoot().node("/com/example/fhirviewer/ui");
    }

    private static Map<String, Map<String, String>> snapshot() throws Exception {
        Map<String, Map<String, String>> saved = new LinkedHashMap<>();
        for (Preferences node : new Preferences[] {packageNode(), legacyNode()}) {
            Map<String, String> keys = new LinkedHashMap<>();
            for (String key : node.keys()) {
                keys.put(key, node.get(key, ""));
            }
            node.clear();
            node.flush();
            saved.put(node.absolutePath(), keys);
        }
        return saved;
    }

    private static void restore(Map<String, Map<String, String>> saved) throws Exception {
        for (Map.Entry<String, Map<String, String>> entry : saved.entrySet()) {
            Preferences node = Preferences.userRoot().node(entry.getKey());
            node.clear();
            entry.getValue().forEach((key, value) -> node.put(key, value));
            node.flush();
        }
    }
}
