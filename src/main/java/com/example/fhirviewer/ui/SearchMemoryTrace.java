package com.example.fhirviewer.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TEMPORARY diagnostic. Writes what the search screen remembered and restored to a file, so
 * the reported symptom - "the search UI clears when I reopen it" - can be diagnosed from
 * evidence rather than from reading the code.
 *
 * <p>Appends rather than truncates, and never throws: a failure to write the trace must not
 * be the reason the screen stops working. Delete this file and its one caller once the cause
 * is found.</p>
 */
/**
 * TEMPORARY diagnostic. Writes what the search screens remembered and restored, so the
 * reported symptom - "the search UI clears when I reopen it" - can be diagnosed from
 * evidence rather than from reading the code.
 *
 * <p>The file lands in the working directory, which for {@code launch-app.cmd} is the project
 * root, so it sits next to the code. {@code -Dfhirviewer.trace=<path>} overrides that.</p>
 *
 * <p>Never throws: a failure to write must not be the reason the screen stops working. But
 * failures are now <em>reported</em> - the first version swallowed them, which made "no log
 * file" indistinguishable from "nothing was logged", and that ambiguity cost a round of
 * guessing.</p>
 */
final class SearchMemoryTrace {

    /** Where the trace is written, or {@code null} if nowhere was writable. */
    private static final Path FILE = chooseFile();

    /** Every path tried, reported in the header and in any failure message. */
    private static final String ATTEMPTED = describeCandidates();

    /** Written once per JVM, so separate runs can be told apart in an appended file. */
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private SearchMemoryTrace() {
    }

    /** Candidate locations, in preference order. */
    private static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        String override = System.getProperty("fhirviewer.trace");
        if (override != null && !override.isBlank()) {
            out.add(Path.of(override));
        }
        // The working directory: the project root when launched by launch-app.cmd.
        out.add(Path.of("").toAbsolutePath().resolve(FILE_NAME));
        out.add(Path.of(System.getProperty("user.dir", ".")).resolve(FILE_NAME));
        out.add(Path.of(System.getProperty("user.home", "."), FILE_NAME));
        return out;
    }

    private static final String FILE_NAME = "fhirviewer-search-trace.log";

    private static String describeCandidates() {
        StringBuilder text = new StringBuilder();
        for (Path candidate : candidates()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(candidate);
        }
        return text.toString();
    }

    /** The first candidate whose directory is writable, or {@code null} if none is. */
    private static Path chooseFile() {
        for (Path candidate : candidates()) {
            try {
                Path parent = candidate.toAbsolutePath().getParent();
                Files.createDirectories(parent);
                if (Files.isWritable(parent)) {
                    return candidate;
                }
            } catch (IOException | RuntimeException problem) {
                System.err.println("[search-trace] " + candidate + ": " + problem);
            }
        }
        return null;
    }

    static void log(String message) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + "  " + message;
        if (STARTED.compareAndSet(false, true)) {
            line = "=== run started; writing to " + FILE
                    + " (tried: " + ATTEMPTED + ") ===" + System.lineSeparator() + line;
        }
        if (FILE == null) {
            System.err.println("[search-trace] NO WRITABLE LOCATION; tried: " + ATTEMPTED);
            System.err.println("[search-trace] " + line);
            return;
        }
        try {
            Files.writeString(FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException problem) {
            System.err.println("[search-trace] could not write " + FILE + ": " + problem);
            System.err.println("[search-trace] " + line);
        }
    }
}