package com.spiraleyes.fhirviewer.service.scripting.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;

/**
 * An in-memory record of the writes a scripting session attempted.
 *
 * <p>Entries deliberately hold only routing and outcome data -- server, operation,
 * resource type and id, version, status and duration. Request bodies and full
 * resources are never stored, so the journal can be shown beside the console
 * without turning patient data into a second copy.</p>
 */
public final class WriteJournal {

    /** How a write attempt ended. */
    public enum Status {
        SUCCEEDED,
        FAILED,
        UNKNOWN,
        CANCELLED
    }

    /** One attempted write. {@code detail} is a status label, never a response body. */
    public record Entry(Instant at, String serverName, String serverBaseUrl, String operation,
            String resourceType, String resourceId, String versionId, Status status, String detail,
            Duration elapsed) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();

    /** Adds a listener for entries recorded from any thread. */
    public void addListener(Consumer<Entry> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Records one attempt and notifies listeners. */
    public Entry record(FhirServerConfiguration server, String operation, String resourceType,
            String resourceId, String versionId, Status status, String detail, Duration elapsed) {
        Entry entry = new Entry(Instant.now(),
                server == null ? "unknown" : server.name(),
                server == null ? "" : server.baseUrl(),
                operation, resourceType, resourceId, versionId,
                status == null ? Status.FAILED : status,
                detail == null ? "" : detail,
                elapsed == null ? Duration.ZERO : elapsed);
        entries.add(entry);
        for (Consumer<Entry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (RuntimeException ignored) {
                // A display listener must never turn a completed write into a failure.
            }
        }
        return entry;
    }

    /** Every entry, oldest first. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Entries from {@code start} onward, for a per-run summary. */
    public List<Entry> entriesFrom(int start) {
        int from = Math.max(0, Math.min(start, entries.size()));
        return List.copyOf(entries.subList(from, entries.size()));
    }

    /** Number of entries recorded so far. */
    public int size() {
        return entries.size();
    }

    /** Removes every entry; listeners are kept. */
    public void clear() {
        entries.clear();
    }

    /** A short, body-free summary suitable for the console status area. */
    public static String summary(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            return "Writes: none.";
        }
        int succeeded = 0;
        int failed = 0;
        int unknown = 0;
        int cancelled = 0;
        for (Entry entry : entries) {
            switch (entry.status()) {
                case SUCCEEDED -> succeeded++;
                case FAILED -> failed++;
                case UNKNOWN -> unknown++;
                case CANCELLED -> cancelled++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (succeeded > 0) {
            parts.add(succeeded + " succeeded");
        }
        if (failed > 0) {
            parts.add(failed + " failed");
        }
        if (unknown > 0) {
            parts.add(unknown + " outcome unknown");
        }
        if (cancelled > 0) {
            parts.add(cancelled + " cancelled");
        }
        return "Writes: " + String.join(", ", parts) + ".";
    }
}