package com.spiraleyes.fhirviewer.service.scripting.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;

/**
 * An in-memory record of the writes a scripting session attempted.
 *
 * <p>Entries deliberately hold only routing and outcome data -- server, operation,
 * resource type and id, version, status and duration. Request bodies and full
 * resources are never stored in an entry, so the journal can be shown beside the
 * console without turning patient data into a second copy.</p>
 *
 * <p>Undo is best effort. For update, patch and delete, the API stores a separate
 * before-image beside the entry so the console can offer to put the previous
 * resource back. Those images are held only in memory for the life of the console;
 * they are never rendered in the journal and never persisted.</p>
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

    /**
     * What an undo would need: the operation that succeeded and, when there was one,
     * the resource as it looked before the write.
     *
     * @param operation    the succeeded operation, never {@code null}
     * @param resourceType the resource type, or {@code null} when the server did not say
     * @param resourceId   the resource id, or {@code null} for a create whose id was lost
     * @param versionId    the version the write produced, when known
     * @param before       the previous resource for update/patch/delete; {@code null} for create
     */
    public record Undo(String operation, String resourceType, String resourceId, String versionId,
            IBaseResource before) {

        /** True when this plan has the data an undo actually needs. */
        public boolean canUndo() {
            return "create".equals(operation) ? resourceId != null : before != null;
        }
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();
    private final Map<Entry, Undo> undoPlans = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<Entry> undone = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Set<Entry> resolved = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));

    /** Adds a listener for entries recorded from any thread. */
    public void addListener(Consumer<Entry> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Records one attempt with no undo information and notifies listeners. */
    public Entry record(FhirServerConfiguration server, String operation, String resourceType,
            String resourceId, String versionId, Status status, String detail, Duration elapsed) {
        return record(server, operation, resourceType, resourceId, versionId, status, detail,
                elapsed, null);
    }

    /**
     * Records one attempt with its undo plan and notifies listeners once.
     *
     * <p>The plan travels with the entry so a listener never sees a successful write before
     * its before-image is available. That ordering is what lets the console enable Undo as
     * soon as the write appears.</p>
     */
    public Entry record(FhirServerConfiguration server, String operation, String resourceType,
            String resourceId, String versionId, Status status, String detail, Duration elapsed,
            Undo undo) {
        Entry entry = new Entry(Instant.now(),
                server == null ? "unknown" : server.name(),
                server == null ? "" : server.baseUrl(),
                operation, resourceType, resourceId, versionId,
                status == null ? Status.FAILED : status,
                detail == null ? "" : detail,
                elapsed == null ? Duration.ZERO : elapsed);
        if (undo != null) {
            undoPlans.put(entry, undo);
        }
        entries.add(entry);
        notifyListeners(entry);
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
        undoPlans.clear();
        undone.clear();
        resolved.clear();
    }

    private void notifyListeners(Entry entry) {
        for (Consumer<Entry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (RuntimeException ignored) {
                // A display listener must never turn a completed write into a failure.
            }
        }
    }

    /** The undo plan for one succeeded entry, or empty when none was captured. */
    public Optional<Undo> undoFor(Entry entry) {
        return Optional.ofNullable(entry == null ? null : undoPlans.get(entry));
    }

    /** The newest succeeded entry that has not been undone and has a usable undo plan. */
    public Optional<Entry> lastUndoable() {
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            Undo plan = undoPlans.get(entry);
            if (entry.status() == Status.SUCCEEDED && plan != null && plan.canUndo()
                    && !undone.contains(entry)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** Marks an entry as undone, so the same write is not offered twice. */
    public void markUndone(Entry entry) {
        if (entry != null) {
            undone.add(entry);
        }
    }

    /** True when this entry's write has already been reverted. */
    public boolean isUndone(Entry entry) {
        return entry != null && undone.contains(entry);
    }

    /** The newest unknown-outcome entry the user has not reviewed yet. */
    public Optional<Entry> lastUnresolvedUnknown() {
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            if (entry.status() == Status.UNKNOWN && !resolved.contains(entry)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** Marks an unknown outcome as reviewed, without asserting what the server did. */
    public void markResolved(Entry entry) {
        if (entry != null) {
            resolved.add(entry);
        }
    }

    /** True when an unknown outcome has been reviewed. */
    public boolean isResolved(Entry entry) {
        return entry != null && resolved.contains(entry);
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