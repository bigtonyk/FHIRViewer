package com.example.fhirviewer.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Finds candidate server plugin jars in a folder, without loading any of them.
 *
 * <p>This is deliberately a <i>reader</i>, not an executor. Scanning a directory means
 * touching files the application does not trust yet, so this class only opens each jar,
 * looks for a {@code META-INF/services} declaration of {@link FhirServerPlugin}, and
 * reports the class names it finds. Nothing is instantiated here; see
 * {@link PluginLoader} for that, and note that a plugin only actually runs once the
 * user adds its jar to the class path and enables it in the configuration.</p>
 *
 * <p>Because a jar is opened read-only and closed immediately, a scan cannot execute
 * third-party code. A jar that is corrupt, or that declares the service in a form this
 * build cannot read, is reported with the reason instead of being silently skipped —
 * a user who picked a folder wants to know which files were ignored and why.</p>
 */
public final class PluginJarScanner {

    private PluginJarScanner() {
    }

    /** The outcome of looking at one jar. */
    public record ScannedJar(Path file, List<String> declaredClasses, String note) {

        /** A short label for a list row: the file name plus a summary. */
        public String label() {
            String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
            if (!declaredClasses.isEmpty()) {
                return name + "  (" + declaredClasses.size() + " plugin class(es))";
            }
            return name + (note == null || note.isBlank() ? "" : "  - " + note);
        }
    }

    /**
     * Lists every {@code .jar} in {@code folder}, reporting the plugin classes each one
     * declares.
     *
     * <p>Never throws for a bad jar; a folder that does not exist yields an empty
     * result. Files that are not jars are ignored entirely.</p>
     */
    public static List<ScannedJar> scan(Path folder) {
        List<ScannedJar> results = new ArrayList<>();
        if (folder == null || !Files.isDirectory(folder)) {
            return results;
        }
        try (var entries = Files.list(folder)) {
            for (Path file : entries.filter(PluginJarScanner::looksLikeJar)
                    .sorted()
                    .toList()) {
                results.add(inspect(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the plugin folder " + folder, e);
        }
        return results;
    }

    /**
     * Opens one jar and reads its service declaration.
     *
     * @return the declared plugin classes; empty when the jar declares none, in which
     *         case {@code note} says why it looked like a candidate but was not one
     */
    public static ScannedJar inspect(Path jar) {
        String serviceEntry = "META-INF/services/" + FhirServerPlugin.class.getName();
        try (JarFile archive = new JarFile(jar.toFile())) {
            JarEntry entry = archive.getJarEntry(serviceEntry);
            if (entry == null) {
                return new ScannedJar(jar, List.of(), "not a server plugin");
            }
            List<String> names = new ArrayList<>();
            try (InputStream in = archive.getInputStream(entry)) {
                for (String line : new String(in.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                    String trimmed = line.trim();
                    // '#' starts a comment, per the ServiceLoader specification.
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        names.add(trimmed);
                    }
                }
            }
            if (names.isEmpty()) {
                return new ScannedJar(jar, List.of(), "service file is empty");
            }
            return new ScannedJar(jar, List.copyOf(names), null);
        } catch (IOException e) {
            return new ScannedJar(jar, List.of(), "could not be read (" + e.getMessage() + ")");
        }
    }

    /**
     * True when the file name looks like a jar. Extension matching is case-insensitive
     * because Windows filesystems are too, and a plugin folder is user-chosen.
     */
    private static boolean looksLikeJar(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar");
    }
}
