package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.spiraleyes.fhirviewer.service.scripting.ScriptRunResult;

/**
 * Result of running a test script, with per-case breakdown.
 * <p>
 * This class tracks the overall outcome plus per-case results including
 * pass/fail/error/skip counts and individual case details.
 */
public class TestRunResult {

    private final TestOutcome outcome;
    private final Duration duration;
    private final String message;
    private final ScriptRunResult runResult;
    private final Instant startedAt;
    private final int passed;
    private final int failed;
    private final int errored;
    private final int skipped;
    private final List<TestCaseResult> caseResults;

    public TestRunResult(TestOutcome outcome, Duration duration, String message,
                         ScriptRunResult runResult, Instant startedAt,
                         int passed, int failed, int errored, int skipped,
                         List<TestCaseResult> caseResults) {
        this.outcome = outcome;
        this.duration = duration != null ? duration : Duration.ZERO;
        this.message = message != null ? message : "";
        this.runResult = runResult;
        this.startedAt = startedAt != null ? startedAt : Instant.now();
        this.passed = passed;
        this.failed = failed;
        this.errored = errored;
        this.skipped = skipped;
        this.caseResults = caseResults != null ? caseResults : List.of();
    }

    public TestOutcome outcome() { return outcome; }
    public Duration duration() { return duration; }
    public String message() { return message; }
    public ScriptRunResult runResult() { return runResult; }
    public Instant startedAt() { return startedAt; }

    /** Total number of test cases run. */
    public int total() { return caseResults.size(); }

    /** Number of cases that passed. */
    public int passedCount() { return passed; }

    /** Convenience: check if overall result passed. */
    public boolean passed() { return outcome == TestOutcome.PASSED; }
}