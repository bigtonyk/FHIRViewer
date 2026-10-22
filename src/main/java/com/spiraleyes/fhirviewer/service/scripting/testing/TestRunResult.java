package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.time.Duration;
import java.time.Instant;

import com.spiraleyes.fhirviewer.service.scripting.ScriptRunResult;

public record TestRunResult(
        TestOutcome outcome,
        Duration duration,
        String message,
        ScriptRunResult runResult,
        Instant startedAt) {

    public TestRunResult {
        if (outcome == null) outcome = TestOutcome.ERROR;
        if (duration == null) duration = Duration.ZERO;
        if (message == null) message = "";
        if (startedAt == null) startedAt = Instant.now();
    }

    public boolean passed() { return outcome == TestOutcome.PASSED; }
}
