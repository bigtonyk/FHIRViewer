package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import com.spiraleyes.fhirviewer.service.scripting.api.ScriptBindings;
import com.spiraleyes.fhirviewer.service.scripting.ScriptRunResult;
import com.spiraleyes.fhirviewer.service.scripting.ScriptingSession;

/**
 * Runs a script source as a test and captures structured results.
 * <p>
 * The runner distinguishes assertion failures (AssertionError) from other errors,
 * tracks pass/fail/skip counts, and supports stop-on-failure configuration.
 * Test results are reported separately from interactive script output.
 */
public final class ScriptTestRunner {

    private final boolean stopOnFailure;

    public ScriptTestRunner() {
        this(true);
    }

    public ScriptTestRunner(boolean stopOnFailure) {
        this.stopOnFailure = stopOnFailure;
    }

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
                // Per Phase 2: check for AssertionError or expected values patterns
                boolean assertion = run.entries().stream()
                        .anyMatch(e -> e.text().contains("AssertionError") || e.text().contains("expected"));
                outcome = assertion ? TestOutcome.FAILED : TestOutcome.ERROR;
                message = computeMessage(run.entries());
            }
            // Extract per-entry results as test cases for tracking
            List<TestCaseResult> caseResults = extractCaseResults(run);
            int passed = (int) caseResults.stream().filter(r -> r.outcome() == TestOutcome.PASSED).count();
            int failed = (int) caseResults.stream().filter(r -> r.outcome() == TestOutcome.FAILED).count();
            int errored = (int) caseResults.stream().filter(r -> r.outcome() == TestOutcome.ERROR).count();
            int skipped = (int) caseResults.stream().filter(r -> r.outcome() == TestOutcome.SKIPPED).count();
            return new TestRunResult(outcome, elapsed, message, run, started,
                    passed, failed, errored, skipped, caseResults);
        } finally {
            session.close();
        }
    }

    /** Extract per-test-case results from the script run entries. */
    private List<TestCaseResult> extractCaseResults(ScriptRunResult run) {
        return run.entries().stream()
                .map(e -> {
                    String text = e.text().trim();
                    TestOutcome outcome = classifyOutcome(text);
                    String name = extractCaseName(text);
                    return new TestCaseResult(name, outcome, Duration.ZERO,
                            diagnosticToMessage(outcome, text), run, false, "", Instant.now());
                })
                .collect(Collectors.toList());
    }

    /** Classify a test outcome from diagnostic text. */
    private TestOutcome classifyOutcome(String text) {
        if (text == null || text.isBlank()) return TestOutcome.ERROR;
        // Per Phase 2 criteria: AssertionError → FAILED, others → ERROR
        if (text.contains("AssertionError")) return TestOutcome.FAILED;
        String lower = text.toLowerCase();
        if (lower.contains("passed")) return TestOutcome.PASSED;
        if (lower.contains("failed")) return TestOutcome.FAILED;
        if (lower.contains("error")) return TestOutcome.ERROR;
        if (lower.contains("skipped")) return TestOutcome.SKIPPED;
        return TestOutcome.ERROR;
    }

    /** Extract a case name from diagnostic text. */
    private String extractCaseName(String text) {
        if (text == null || text.isBlank()) return "assertion";
        String[] parts = text.split(":", 2);
        if (parts.length > 0 && !parts[0].trim().isBlank()) {
            return parts[0].trim();
        }
        return text.length() > 30 ? text.substring(0, 30) : text;
    }

    /** Convert diagnosis to a human message. */
    private String diagnosticToMessage(TestOutcome outcome, String text) {
        if (text == null || text.isBlank()) return "no message";
        switch (outcome) {
            case PASSED:
                return "assertion passed";
            case FAILED:
                return "assertion failed";
            case ERROR:
                return "error: " + (text.contains("AssertionError") ? "assertion error" : text);
            case SKIPPED:
                return "test skipped";
            default:
                return text;
        }
    }

    /** Compute a message from the run entries. */
    private String computeMessage(java.util.List<ScriptRunResult.Entry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        // Return the first entry's text as the message
        return entries.get(0).text();
    }
}