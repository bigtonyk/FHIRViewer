package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The aggregate result of one suite run: counts kept apart from console text.
 *
 * <p>The console shows {@link #passedCount()} and {@link #failedCount()} in a
 * dedicated results area, while the output pane keeps only what the cases
 * printed. {@code message} explains a run that produced no cases at all
 * (closed coordinator, parse refusal) and is empty otherwise.</p>
 *
 * @param name      the suite name
 * @param cases     per-case results, in execution order
 * @param elapsed   wall time spent running cases
 * @param cancelled whether Stop ended the run before every case ran
 * @param message   why no cases ran, or empty
 */
public record SuiteRunResult(
        String name,
        List<SuiteCaseResult> cases,
        Duration elapsed,
        boolean cancelled,
        String message) {

    public SuiteRunResult {
        Objects.requireNonNull(name, "name");
        cases = cases == null ? List.of() : List.copyOf(cases);
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
        message = message == null ? "" : message;
    }

    /** A run that never executed a case, with the reason in {@link #message()}. */
    public static SuiteRunResult refused(String name, String message) {
        return new SuiteRunResult(name, List.of(), Duration.ZERO, false, message);
    }

    public int total() {
        return cases.size();
    }

    public int passedCount() {
        return (int) cases.stream().filter(SuiteCaseResult::passed).count();
    }

    public int failedCount() {
        return total() - passedCount();
    }
}
