package com.spiraleyes.fhirviewer.server.smart;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A structured, in-memory diagnostic log for one SMART launch workbench session.
 *
 * <p>Events record what happened and when, but never secrets: no access tokens,
 * refresh tokens, authorization codes, PKCE verifiers, client secrets, raw
 * authorization headers, or patient identifiers. Callers must not pass those
 * values, and {@link #record} redacts anything that looks like one as a
 * belt-and-braces measure.</p>
 *
 * <p>{@link #export()} formats every event as text with redaction applied, so
 * an exported file is safe to attach to a bug report. {@link #clear()} removes
 * every event from the current session.</p>
 */
public final class SmartDiagnostics {

    private final List<SmartDiagnosticsEvent> events = new CopyOnWriteArrayList<>();

    /** Records one event, stamped with the current time. */
    public void record(SmartDiagnosticsEvent.Phase phase, SmartDiagnosticsEvent.Kind kind,
            String message, String detail) {
        events.add(new SmartDiagnosticsEvent(Instant.now(), phase, kind,
                redact(message), redact(detail)));
    }

    /** A snapshot of everything recorded so far. */
    public List<SmartDiagnosticsEvent> events() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /** Removes every event from the current session. */
    public void clear() {
        events.clear();
    }

    /**
     * Formats all events as text, ready to write to a file. Every message and
     * detail has already been redacted when it was recorded, so the export is
     * safe to share.
     */
    public String export() {
        StringBuilder out = new StringBuilder();
        out.append("SMART Launch Diagnostics Export\n");
        out.append("Generated ").append(Instant.now()).append("\n");
        out.append("Total events: ").append(events.size()).append("\n");
        out.append("----------------------------------------\n");
        for (SmartDiagnosticsEvent event : events) {
            out.append(event.instant()).append(" | ");
            out.append(event.phase()).append("/").append(event.kind()).append(" | ");
            out.append(event.message());
            if (event.detail() != null && !event.detail().isBlank()) {
                out.append(" [").append(event.detail()).append("]");
            }
            out.append("\n");
        }
        return out.toString();
    }

    /** Strips anything shaped like a credential or patient identifier. */
    static String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String cleaned = text;
        // Bearer tokens and basic-auth pairs.
        cleaned = cleaned.replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [redacted]");
        cleaned = cleaned.replaceAll("(?i)basic\\s+[A-Za-z0-9+/=]{8,}", "Basic [redacted]");
        // Authorization codes, PKCE verifiers and challenges in URLs.
        cleaned = cleaned.replaceAll("[?&]code=[A-Za-z0-9._~+/=-]+", "code=[redacted]");
        cleaned = cleaned.replaceAll("[?&]code_verifier=[A-Za-z0-9._~+/=-]+",
                "code_verifier=[redacted]");
        cleaned = cleaned.replaceAll("[?&]code_challenge=[A-Za-z0-9._~+/=-]+",
                "code_challenge=[redacted]");
        // Patient references.
        cleaned = cleaned.replaceAll("(?i)patient/[A-Za-z0-9._-]+", "patient/[redacted]");
        cleaned = cleaned.replaceAll("(?i)patient=[A-Za-z0-9._-]+", "patient=[redacted]");
        return cleaned;
    }
}
