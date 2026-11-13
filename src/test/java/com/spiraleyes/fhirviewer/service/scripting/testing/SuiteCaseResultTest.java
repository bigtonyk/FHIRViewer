package com.spiraleyes.fhirviewer.service.scripting.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.service.scripting.ScriptRunResult;

class SuiteCaseResultTest {

    @Test
    @DisplayName("A clean run passes")
    void cleanRunPasses() {
        SuiteCaseResult result = SuiteCaseResult.from("clean",
                result(ScriptRunResult.Status.COMPLETED));

        assertTrue(result.passed());
        assertEquals("", result.detail());
    }

    @Test
    @DisplayName("An assertion failure fails the case and keeps its message")
    void assertionFailureFailsTheCase() {
        ScriptRunResult failed = new ScriptRunResult(ScriptRunResult.Status.COMPLETED_WITH_ERRORS,
                List.of(new ScriptRunResult.Entry(Instant.now(), ScriptRunResult.Kind.EXCEPTION,
                        "java.lang.AssertionError: expected 2 but was 1")),
                1, Duration.ofMillis(5));

        SuiteCaseResult result = SuiteCaseResult.from("asserts", failed);

        assertFalse(result.passed());
        assertEquals("java.lang.AssertionError: expected 2 but was 1", result.detail());
        assertEquals(1, result.entries().size(),
                "the case must keep its entries for the details view");
        assertEquals("java.lang.AssertionError: expected 2 but was 1",
                result.entries().get(0).text());
    }

    @Test
    @DisplayName("A stopped run is reported as stopped, not failed")
    void stoppedRunIsStopped() {
        SuiteCaseResult result = SuiteCaseResult.from("interrupted",
                result(ScriptRunResult.Status.STOPPED));

        assertEquals(SuiteCaseResult.Status.STOPPED, result.status());
        assertFalse(result.passed());
        assertTrue(result.stopped());
        assertEquals("", result.detail());
    }

    @Test
    @DisplayName("Suite counts keep stopped cases out of the failures")
    void stoppedCasesAreCountedSeparately() {
        SuiteCaseResult passed = new SuiteCaseResult("passes",
                SuiteCaseResult.Status.PASSED, "", List.of());
        SuiteCaseResult stopped = new SuiteCaseResult("interrupted",
                SuiteCaseResult.Status.STOPPED, "", List.of());

        SuiteRunResult result = new SuiteRunResult("suite.jsh", List.of(passed, stopped), 2,
                Duration.ofMillis(10), true, "");

        assertEquals(2, result.total());
        assertEquals(1, result.passedCount());
        assertEquals(0, result.failedCount(),
                "a case the user stopped is not a failing test");
        assertEquals(1, result.stoppedCount());
    }

    private static ScriptRunResult result(ScriptRunResult.Status status) {
        return new ScriptRunResult(status, List.of(), 1, Duration.ofMillis(1));
    }
}
