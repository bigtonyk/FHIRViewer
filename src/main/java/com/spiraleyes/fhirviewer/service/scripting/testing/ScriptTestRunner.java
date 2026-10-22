package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.time.Duration;
import java.time.Instant;

import com.spiraleyes.fhirviewer.service.scripting.api.ScriptBindings;
import com.spiraleyes.fhirviewer.service.scripting.ScriptRunResult;
import com.spiraleyes.fhirviewer.service.scripting.ScriptingSession;

public final class ScriptTestRunner {

    public TestRunResult run(String source, ScriptBindings bindings) {
        Instant started = Instant.now();
        ScriptingSession session = new ScriptingSession();
        try {
            ScriptRunResult run = session.run(source, bindings);
            Duration elapsed = Duration.between(started, Instant.now());
            TestOutcome outcome;
            String message = "";
            if (run.status() == ScriptRunResult.Status.STOPPED) {
                outcome = TestOutcome.CANCELLED;
                message = "Stopped";
            } else if (!run.hasErrors()) {
                outcome = TestOutcome.PASSED;
            } else {
                // Distinguish assertion failure vs other error by inspecting entries text
                boolean assertion = run.entries().stream()
                        .anyMatch(e -> e.text().contains("AssertionError") || e.text().contains("expected"));
                outcome = assertion ? TestOutcome.FAILED : TestOutcome.ERROR;
                ScriptRunResult.Entry first = run.entries().isEmpty() ? null : run.entries().get(0);
                message = first == null ? "" : first.text();
            }
            return new TestRunResult(outcome, elapsed, message, run, started);
        } finally {
            session.close();
        }
    }
}
