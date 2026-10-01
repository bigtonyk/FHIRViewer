package com.example.fhirviewer.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * TEMPORARY diagnostic. Writes what the search screen remembered and restored to a file, so
 * the reported symptom - "the search UI clears when I reopen it" - can be diagnosed from
 * evidence rather than from reading the code.
 *
 * <p>Appends rather than truncates, and never throws: a failure to write the trace must not
 * be the reason the screen stops working. Delete this file and its one caller once the cause
 * is found.</p>
 */
final class SearchMemoryTrace {

    private static final Path FILE = Path.of(System.getProperty("user.home"),
            "fhirviewer-search-trace.log");

    private SearchMemoryTrace() {
    }

    static void log(String message) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + "  " + message;
        try {
            Files.writeString(FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // A diagnostic that breaks the feature it is diagnosing is worse than none.
        }
    }
}