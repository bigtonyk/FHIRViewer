package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a {@link TestSuite} sequentially by default, isolating each test in a
 * fresh JShell session, reporting progress, supporting best-effort
 * cancellation, and gating destructive tests behind a {@link ConfirmationHandler}.
 */
public final class TestSuiteRunner {

    private final ScriptTestRunner runner = new ScriptTestRunner();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile ConfirmationHandler confirmationHandler;

    public TestSuiteRunner() {
        this(null);
    }

    public TestSuiteRunner(ConfirmationHandler confirmationHandler) {
        this.confirmationHandler = confirmationHandler;
    }

    public void setConfirmationHandler(ConfirmationHandler handler) {
        this.confirmationHandler = handler;
    }

    /** Request best-effort cancellation; the current test finishes, later ones are skipped. */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void resetCancellation() {
        cancelled.set(false);
    }

    public TestSuiteResult run(TestSuite suite) {
        return run(suite, null);
    }

    public TestSuiteResult run(TestSuite suite, TestProgressListener listener) {
        Objects.requireNonNull(suite, "suite");
        resetCancellation();
        Instant started = Instant.now();
        List<TestCaseResult> results = new ArrayList<>();
        int total = suite.size();
        if (listener != null) listener.suiteStarted(suite);
        for (int i = 0; i < suite.cases().size(); i++) {
            TestCase tc = suite.cases().get(i);
            if (cancelled.get()) {
                TestCaseResult skipped = new TestCaseResult(tc.name(), TestOutcome.SKIPPED,
                        Duration.ZERO, "Cancelled", null, tc.destructive(), tc.serverLabel(), Instant.now());
                results.add(skipped);
                if (listener != null) listener.testCompleted(skipped, i, total);
                continue;
            }
            if (listener != null) listener.testStarted(tc.name(), i, total);
            TestCaseResult r = runOne(tc);
            results.add(r);
            if (listener != null) listener.testCompleted(r, i, total);
            if (r.outcome() == TestOutcome.CANCELLED) {
                cancelled.set(true);
            }
        }
        Duration elapsed = Duration.between(started, Instant.now());
        TestSuiteResult suiteResult = new TestSuiteResult(suite.name(), results, elapsed, started, cancelled.get());
        if (listener != null) listener.suiteCompleted(suiteResult);
        return suiteResult;
    }

    private TestCaseResult runOne(TestCase tc) {
        if (tc.destructive()) {
            ConfirmationHandler handler = confirmationHandler;
            boolean allowed = handler != null && handler.confirm(tc);
            if (!allowed) {
                return new TestCaseResult(tc.name(), TestOutcome.SKIPPED, Duration.ZERO,
                        "Destructive test not confirmed", null, true, tc.serverLabel(), Instant.now());
            }
        }
        if (tc.bindings() == null) {
            return new TestCaseResult(tc.name(), TestOutcome.ERROR, Duration.ZERO,
                    "No bindings supplied for test", null, tc.destructive(), tc.serverLabel(), Instant.now());
        }
        Instant started = Instant.now();
        TestRunResult run = runner.run(tc.source(), tc.bindings());
        Duration elapsed = Duration.between(started, Instant.now());
        TestOutcome outcome = run.outcome();
        String message = run.message();
        // Propagate runner cancellation
        if (run.outcome() == TestOutcome.CANCELLED) {
            cancelled.set(true);
        }
        return new TestCaseResult(tc.name(), outcome, elapsed, message, run.runResult(), tc.destructive(), tc.serverLabel(), started);
    }
}
