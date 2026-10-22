package com.spiraleyes.fhirviewer.service.scripting;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What one Run produced: ordered, timestamped entries plus the execution status.
 *
 * <p>The entries are display text and nothing else — console output, evaluated
 * values, compiler diagnostics, runtime exceptions and session-level failures.
 * The result never carries the script source, session state, or anything a
 * caller could mistake for a structured error object: the scripting console
 * shows text, and this is that text, already ordered and already timed.</p>
 *
 * <p>Five statuses, each with a different meaning a user can act on:</p>
 * <ul>
 *   <li>{@link Status#COMPLETED} — every snippet ran, nothing complained.</li>
 *   <li>{@link Status#COMPLETED_WITH_ERRORS} — the buffer ran to the end, but at
 *       least one snippet was rejected or threw; the entries say which.</li>
 *   <li>{@link Status#INCOMPLETE} — the buffer ends inside something JShell could
 *       not parse, so <em>nothing was executed</em>; refusing a broken script
 *       whole is the honest alternative to running its first half.</li>
 *   <li>{@link Status#STOPPED} — the user pressed Stop while it ran.</li>
 *   <li>{@link Status#FAILED}</li> — the session could not run it at all (closed,
 *       empty, engine failure); one {@link Kind#ERROR} entry says why.</li>
 * </ul>
 *
 * @param status       how the run ended
 * @param entries      ordered console/diagnostic lines, already redacted by construction
 * @param snippetCount how many complete snippets were actually handed to JShell
 * @param elapsed      wall time spent in the run, {@link Duration#ZERO} when it never ran
 */
public record ScriptRunResult(
        Status status,
        List<Entry> entries,
        int snippetCount,
        Duration elapsed
) {

    /** How the run ended; see the class javadoc for what each one means. */
    public enum Status {
        COMPLETED, COMPLETED_WITH_ERRORS, INCOMPLETE, STOPPED, FAILED
    }

    /** What one line is. */
    public enum Kind {
        /** Something the script printed to {@code System.out} or {@code System.err}. */
        OUTPUT,
        /** The value of an expression snippet, as JShell rendered it. */
        VALUE,
        /** A compile-time message for a snippet JShell rejected. */
        DIAGNOSTIC,
        /** An exception thrown while a snippet ran. */
        EXCEPTION,
        /** A session-level failure: closed session, empty buffer, engine trouble. */
        ERROR
    }

    /**
     * One timed line of display text.
     *
     * @param at   when the line was produced
     * @param kind what sort of line it is
     * @param text the display text; {@code null} becomes the empty string
     */
    public record Entry(Instant at, Kind kind, String text) {

        public Entry {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(kind, "kind");
            text = text == null ? "" : text;
        }
    }

    public ScriptRunResult {
        Objects.requireNonNull(status, "status");
        entries = entries == null ? List.of() : List.copyOf(entries);
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
        if (snippetCount < 0) {
            snippetCount = 0;
        }
    }

    /** A failed run with one {@link Kind#ERROR} entry and no elapsed time. */
    public static ScriptRunResult failed(String reason) {
        return new ScriptRunResult(Status.FAILED,
                List.of(new Entry(Instant.now(), Kind.ERROR, reason)), 0, Duration.ZERO);
    }

    /** True when something needs the user's attention rather than just a value. */
    public boolean hasErrors() {
        return status == Status.COMPLETED_WITH_ERRORS
                || status == Status.INCOMPLETE
                || status == Status.FAILED;
    }

    /** The first entry of the given kind, or {@code null} when there is none. */
    public Entry first(Kind kind) {
        for (Entry entry : entries) {
            if (entry.kind() == kind) {
                return entry;
            }
        }
        return null;
    }
}
