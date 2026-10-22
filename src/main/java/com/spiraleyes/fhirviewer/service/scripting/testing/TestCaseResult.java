package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.time.Duration;
import java.time.Instant;

import com.spiraleyes.fhirviewer.service.scripting.ScriptRunResult;

/**
 * Result of a single test case within a suite.
 */
public record TestCaseResult(
        String name,
        TestOutcome outcome,
        Duration duration,
        String message,
        ScriptRunResult runResult,
        boolean destructive,
        String serverLabel,
        Instant startedAt) {

    public TestCaseResult {
        if (name == null) name = "";
        if (outcome == null) outcome = TestOutcome.ERROR;
        if (duration == null) duration = Duration.ZERO;
        if (message == null) message = "";
        if (startedAt == null) startedAt = Instant.now();
    }

    public boolean passed() { return outcome == TestOutcome.PASSED; }
    public boolean failed() { return outcome == TestOutcome.FAILED; }
    public boolean errored() { return outcome == TestOutcome.ERROR; }
    public boolean skipped() { return outcome == TestOutcome.SKIPPED; }
    public boolean cancelled() { return outcome == TestOutcome.CANCELLED; }
}
