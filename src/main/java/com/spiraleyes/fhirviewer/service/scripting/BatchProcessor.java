package com.spiraleyes.fhirviewer.service.scripting;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * Processes a list of resources sequentially, one item at a time, through a
 * caller-supplied action.
 *
 * <p>Processing is always sequential and never concurrent, matching the Phase 5
 * guidance that concurrency stays bounded and off by default. Each item runs in
 * its own try/catch so one failure does not silently abort the whole batch; the
 * {@link BatchErrorPolicy} decides whether processing stops or continues.</p>
 *
 * <p>Cancellation is best-effort: {@link #cancel()} sets a flag checked between
 * items. An in-flight item's action runs to completion because arbitrary code
 * may not be safely interrupted. When cancellation is requested, remaining items
 * are recorded as skipped.</p>
 *
 * <p>This class performs no writes itself: the action is free to call
 * {@code fhir.create}, {@code fhir.update}, {@code fhir.delete}, or
 * {@code fhir.patch}, and those calls pass through the central
 * {@link com.spiraleyes.fhirviewer.service.scripting.api.WritePolicy} exactly as
 * any other script call would. Destructive operations therefore remain gated
 * behind the write policy and confirmation handler.</p>
 *
 * <p>No FHIR resources or full patient data are logged by this class; outcomes
 * carry only counts, messages and timings.</p>
 */
public final class BatchProcessor {

    /** One item's transformation. Receives the input resource and may return a
     *  transformed (possibly mutated) resource. Throwing an exception marks the
     *  item as failed. */
    @FunctionalInterface
    public interface BatchAction {
        IBaseResource apply(IBaseResource resource) throws Exception;
    }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Request best-effort cancellation; the current item finishes, later ones are skipped. */
    public void cancel() {
        cancelled.set(true);
    }

    /** True when cancellation has been requested. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Clears the cancellation flag so the processor can be reused. */
    public void resetCancellation() {
        cancelled.set(false);
    }

    /**
     * Processes every item in order.
     *
     * @param resources the items to process; never {@code null}
     * @param action    the per-item transformation; never {@code null}
     * @param policy    whether to stop on the first failure or continue; never {@code null}
     * @return a {@link BatchResult} with per-item outcomes and summary counts
     */
    public BatchResult process(List<IBaseResource> resources, BatchAction action,
            BatchErrorPolicy policy) {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(policy, "policy");
        resetCancellation();

        Instant started = Instant.now();
        List<BatchItemResult> items = new ArrayList<>(resources.size());
        int processed = 0, failed = 0, skipped = 0;

        for (int i = 0; i < resources.size(); i++) {
            if (cancelled.get()) {
                items.add(skippedItem(i, resources.get(i), "Cancelled"));
                skipped++;
                continue;
            }

            IBaseResource input = resources.get(i);
            Instant itemStarted = Instant.now();
            try {
                IBaseResource output = action.apply(input);
                Duration elapsed = Duration.between(itemStarted, Instant.now());
                items.add(new BatchItemResult(i, input, BatchItemOutcome.PROCESSED, "", output,
                        itemStarted, elapsed));
                processed++;
            } catch (Throwable thrown) {
                Duration elapsed = Duration.between(itemStarted, Instant.now());
                String message = thrown.getMessage();
                if (message == null || message.isBlank()) {
                    message = thrown.getClass().getSimpleName();
                }
                items.add(new BatchItemResult(i, input, BatchItemOutcome.FAILED, message, null,
                        itemStarted, elapsed));
                failed++;
                if (policy == BatchErrorPolicy.STOP_ON_FIRST_ERROR) {
                    cancelled.set(true);
                }
            }
        }

        Duration elapsed = Duration.between(started, Instant.now());
        return new BatchResult(resources.size(), processed, failed, skipped, elapsed,
                started, items, cancelled.get());
    }

    /** Processes every item in order with {@link BatchErrorPolicy#CONTINUE}. */
    public BatchResult process(List<IBaseResource> resources, BatchAction action) {
        return process(resources, action, BatchErrorPolicy.CONTINUE);
    }

    private static BatchItemResult skippedItem(int index, IBaseResource input, String message) {
        Instant now = Instant.now();
        return new BatchItemResult(index, input, BatchItemOutcome.SKIPPED, message, null, now,
                Duration.ZERO);
    }

    /** A progress callback that receives each item result as it completes. */
    @FunctionalInterface
    public interface ProgressListener {
        void onItem(BatchItemResult result);
    }

    /**
     * Processes every item in order, notifying a progress listener after each
     * item completes. Useful for UI progress bars.
     */
    public BatchResult process(List<IBaseResource> resources, BatchAction action,
            BatchErrorPolicy policy, ProgressListener listener) {
        Objects.requireNonNull(listener, "listener");
        BatchResult result = process(resources, action, policy);
        for (BatchItemResult item : result.items()) {
            listener.onItem(item);
        }
        return result;
    }

    /** Convenience: processes with {@link BatchErrorPolicy#CONTINUE} and a listener. */
    public BatchResult process(List<IBaseResource> resources, BatchAction action,
            ProgressListener listener) {
        return process(resources, action, BatchErrorPolicy.CONTINUE, listener);
    }
}