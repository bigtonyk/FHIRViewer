package com.spiraleyes.fhirviewer.service.scripting;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * The outcome of processing a single item in a batch run.
 *
 * @param index     zero-based position of the item within the batch
 * @param input     the resource that was presented to the action
 * @param outcome   whether the item was processed, failed, or skipped
 * @param message   a human-readable detail (empty on success, the exception text on failure)
 * @param output    the resource returned by the action, or {@code null} when it failed or was skipped
 * @param startedAt when processing of this item began
 * @param duration  how long the item's action took, {@link Duration#ZERO} when it never ran
 */
public record BatchItemResult(
        int index,
        IBaseResource input,
        BatchItemOutcome outcome,
        String message,
        IBaseResource output,
        Instant startedAt,
        Duration duration) {

    public BatchItemResult {
        if (message == null) message = "";
        if (startedAt == null) startedAt = Instant.now();
        if (duration == null) duration = Duration.ZERO;
    }

    public boolean processed() { return outcome == BatchItemOutcome.PROCESSED; }
    public boolean failed() { return outcome == BatchItemOutcome.FAILED; }
    public boolean skipped() { return outcome == BatchItemOutcome.SKIPPED; }
}
