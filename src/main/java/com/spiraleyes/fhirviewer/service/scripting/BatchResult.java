package com.spiraleyes.fhirviewer.service.scripting;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The aggregate result of a batch run over one or more items.
 *
 * @param total     number of items presented to the processor
 * @param processed number of items processed successfully
 * @param failed    number of items whose action failed
 * @param skipped   number of items skipped (cancellation or stop-on-error)
 * @param duration  total wall time spent processing
 * @param startedAt when the batch run began
 * @param items     per-item results, in processing order
 * @param cancelled whether cancellation was requested during the run
 */
public record BatchResult(
        int total,
        int processed,
        int failed,
        int skipped,
        Duration duration,
        Instant startedAt,
        List<BatchItemResult> items,
        boolean cancelled) {

    public BatchResult {
        if (duration == null) duration = Duration.ZERO;
        if (startedAt == null) startedAt = Instant.now();
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** True when every presented item was processed successfully. */
    public boolean allProcessed() {
        return total == 0 || (failed == 0 && skipped == 0 && !cancelled);
    }

    /** True when at least one item failed. */
    public boolean hasFailures() {
        return failed > 0;
    }

    /** Number of items that were neither processed nor failed (skipped or cancelled). */
    public int skippedOrCancelled() {
        return total - processed - failed;
    }
}
