package com.example.fhirviewer.service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.prefs.Preferences;

/**
 * Knows where downloaded FHIR NPM packages are stored on disk. The storage
 * directory is remembered across runs via {@link Preferences}; the default is
 * {@code ~/.fhirviewer/packages}.
 */
public final class PackageStorage {

    private static final String PREF_KEY = "storageDirectory";
    private static final String DEFAULT_DIR =
            System.getProperty("user.home") + "/.fhirviewer/packages";

    private PackageStorage() {
    }

    /** The directory packages are downloaded to, from preferences or the default. */
    public static Path getStorageDirectory() {
        Preferences current = Preferences.userNodeForPackage(PackageStorage.class);
        String stored = current.get(PREF_KEY, null);
        if (stored == null || stored.isBlank()) {
            // Earlier builds stored the preference under the dialog's package; migrate it.
            stored = Preferences.userRoot().node("/com/example/fhirviewer/ui")
                    .get(PREF_KEY, null);
        }
        if (stored == null || stored.isBlank()) {
            stored = DEFAULT_DIR;
        }
        return Paths.get(stored);
    }

    /** Remembers the storage directory across application runs. */
    public static void setStorageDirectory(Path directory) {
        Preferences.userNodeForPackage(PackageStorage.class)
                .put(PREF_KEY, directory.toAbsolutePath().toString());
    }
}
