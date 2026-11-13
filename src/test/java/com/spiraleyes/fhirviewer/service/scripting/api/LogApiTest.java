package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogApiTest {

    private final LogApi log = new LogApi();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final PrintStream original = System.out;

    @BeforeEach
    void captureOutput() {
        System.setOut(new PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(original);
    }

    @Test
    void infoWarnErrorWriteToTheConsole() {
        log.info("everything is fine");
        log.warn("watch this");
        log.error("that failed");

        String text = out.toString();
        assertTrue(text.contains("[INFO] everything is fine"), "info missing: " + text);
        assertTrue(text.contains("[WARN] watch this"), "warn missing: " + text);
        assertTrue(text.contains("[ERROR] that failed"), "error missing: " + text);
    }
}
