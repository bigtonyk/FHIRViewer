package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Aggregate result of running a test suite.
 */
public record TestSuiteResult(
        String suiteName,
        List<TestCaseResult> results,
        Duration duration,
        Instant startedAt,
        boolean cancelled) {

    public TestSuiteResult {
        if (suiteName == null) suiteName = "";
        results = results == null ? List.of() : List.copyOf(results);
        if (duration == null) duration = Duration.ZERO;
        if (startedAt == null) startedAt = Instant.now();
    }

    public int total() { return results.size(); }
    public long passed() { return results.stream().filter(TestCaseResult::passed).count(); }
    public long failed() { return results.stream().filter(TestCaseResult::failed).count(); }
    public long errored() { return results.stream().filter(TestCaseResult::errored).count(); }
    public long skipped() { return results.stream().filter(TestCaseResult::skipped).count(); }
    public long cancelledCount() { return results.stream().filter(TestCaseResult::cancelled).count(); }

    public boolean allPassed() {
        return !results.isEmpty() && results.stream().allMatch(TestCaseResult::passed);
    }
}
