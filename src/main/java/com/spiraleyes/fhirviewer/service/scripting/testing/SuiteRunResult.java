package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The aggregate result of one suite run: counts kept apart from console text.
 *
 * <p>The console shows {@link #passedCount()} and {@link #failedCount()} in a
 * dedicated results area, while the output pane keeps only what the cases
 * printed. {@code message} explains a run that did not execute every case
 * (closed coordinator, parse refusal, or a case session that could not be
 * created) and is empty otherwise.</p>
 *
 * @param name      the suite name
 * @param cases     per-case results, in execution order
 * @param planned   how many cases the suite holds, including ones that never ran
 * @param elapsed   wall time spent running cases
 * @param cancelled whether Stop or close ended the run before every case ran
 * @param message   why the run did not execute every case, or empty
 */
public record SuiteRunResult(
        String name,
        List<SuiteCaseResult> cases,
        int planned,
        Duration elapsed,
        boolean cancelled,
        String message) {

    public SuiteRunResult {
        Objects.requireNonNull(name, "name");
        cases = cases == null ? List.of() : List.copyOf(cases);
        // A result always executed at least the cases it carries, even if a caller passed
        // a smaller planned count.
        planned = Math.max(planned, cases.size());
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
        message = message == null ? "" : message;
    }

    /** A run that never executed a case, with the reason in {@link #message()}. */
    public static SuiteRunResult refused(String name, String message) {
        return new SuiteRunResult(name, List.of(), 0, Duration.ZERO, false, message);
    }

    /** Cases that produced a result; compare with {@link #planned()} after a partial run. */
    public int total() {
        return cases.size();
    }

    public int passedCount() {
        return (int) cases.stream().filter(SuiteCaseResult::passed).count();
    }

    /** Cases that failed on their own; a stopped case is counted separately. */
    public int failedCount() {
        return (int) cases.stream()
                .filter(testCase -> testCase.status() == SuiteCaseResult.Status.FAILED)
                .count();
    }

    public int stoppedCount() {
        return (int) cases.stream().filter(SuiteCaseResult::stopped).count();
    }
}
