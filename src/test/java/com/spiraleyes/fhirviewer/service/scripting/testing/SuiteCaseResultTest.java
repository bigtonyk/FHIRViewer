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
    }

    @Test
    @DisplayName("A stopped run fails rather than silently passing")
    void stoppedRunFails() {
        SuiteCaseResult result = SuiteCaseResult.from("interrupted",
                result(ScriptRunResult.Status.STOPPED));

        assertFalse(result.passed());
    }

    private static ScriptRunResult result(ScriptRunResult.Status status) {
        return new ScriptRunResult(status, List.of(), 1, Duration.ofMillis(1));
    }
}
