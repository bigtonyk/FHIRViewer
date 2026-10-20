package com.spiraleyes.fhirviewer.service.scripting.api;

/**
 * Script output to the scripting console: info, warning and error.
 *
 * <p>Messages are printed to the script's captured output, so they appear in the
 * console next to the script's other output. Scripts decide what to log; nothing
 * is logged automatically and no resource content is logged unless a script
 * explicitly asks for it.
 */
public class LogApi {

    /** Informational message. */
    public void info(String message) {
        System.out.println("[INFO] " + text(message));
    }

    /** Warning message. */
    public void warn(String message) {
        System.out.println("[WARN] " + text(message));
    }

    /** Error message. */
    public void error(String message) {
        System.out.println("[ERROR] " + text(message));
    }

    private static String text(String message) {
        return message == null ? "" : message;
    }
}
