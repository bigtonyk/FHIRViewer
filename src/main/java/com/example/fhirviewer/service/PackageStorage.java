package com.example.fhirviewer.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    /** One .tgz file in the storage directory. */
    public record StoredPackage(Path file, String packageId, String version) {

        /** For example {@code hl7.fhir.us.core 9.0.0}. */
        public String label() {
            return version.isEmpty() ? packageId : packageId + " " + version;
        }

        /** For example {@code hl7.fhir.us.core#9.0.0}. */
        public String key() {
            return packageId + "#" + version;
        }
    }

    private PackageStorage() {
    }

    /**
     * The packages already downloaded into the storage directory, in file-name
     * order. File names follow {@code <package id>-<version>.tgz}; anything that
     * does not parse is still listed with its metadata read from the archive.
     */
    public static List<StoredPackage> listStored() {
        return listStored(getStorageDirectory());
    }

    /** The packages in a specific directory. */
    public static List<StoredPackage> listStored(Path directory) {
        List<StoredPackage> stored = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return stored;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.tgz")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            return stored;
        }
        files.sort(Comparator.comparing(Path::getFileName));
        for (Path file : files) {
            StoredPackage parsed = parseFileName(file);
            stored.add(parsed != null ? parsed : readMetadata(file));
        }
        return stored;
    }

    /** Splits {@code <package id>-<version>.tgz} into id and version. */
    private static StoredPackage parseFileName(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".tgz")) {
            return null;
        }
        String stem = name.substring(0, name.length() - 4);
        int dash = stem.lastIndexOf('-');
        if (dash <= 0 || dash == stem.length() - 1) {
            return null;
        }
        String id = stem.substring(0, dash);
        String version = stem.substring(dash + 1);
        // A version starts with a digit; ids never contain '-' in practice.
        if (version.isEmpty() || !Character.isDigit(version.charAt(0))) {
            return null;
        }
        return new StoredPackage(file, id, version);
    }

    /** Falls back to the archive's own package.json for unusual file names. */
    private static StoredPackage readMetadata(Path file) {
        try {
            NpmPackageReader.PackageMetadata metadata = NpmPackageReader.readMetadata(file);
            return new StoredPackage(file,
                    metadata.name().isEmpty() ? file.getFileName().toString() : metadata.name(),
                    metadata.version());
        } catch (IOException | RuntimeException e) {
            return new StoredPackage(file, file.getFileName().toString(), "");
        }
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
