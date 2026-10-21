package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.service.FhirService;
import com.spiraleyes.fhirviewer.service.scripting.testing.ScriptTestRunner;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestOutcome;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestRunResult;

class ScriptTestRunnerTest {

    private ScriptBindings bindings() {
        FhirService fhirService = new FhirService();
        TestFhirServerService service = new TestFhirServerService();
        FhirServerManager manager = new FhirServerManager();
        return new ScriptBindings(
                new FhirApi(service, manager, WritePolicy.READ_ONLY),
                new ViewerApi(fhirService),
                new ValidationApi(fhirService),
                new ServerApi(service, manager),
                new LogApi(),
                new TestApi());
    }

    @Test
    void passingAssertionIsPassed() {
        TestRunResult result = new ScriptTestRunner().run("test.assertTrue(true);", bindings());
        assertEquals(TestOutcome.PASSED, result.outcome());
        assertTrue(result.passed());
    }

    @Test
    void failingAssertionIsFailed() {
        TestRunResult result = new ScriptTestRunner().run("test.assertTrue(false, \"nope\");", bindings());
        assertEquals(TestOutcome.FAILED, result.outcome());
        assertTrue(result.message().contains("nope") || result.message().contains("AssertionError"),
                "message should carry the assertion detail: " + result.message());
    }

    @Test
    void runtimeExceptionIsError() {
        TestRunResult result = new ScriptTestRunner().run("throw new IllegalStateException(\"boom\");", bindings());
        assertEquals(TestOutcome.ERROR, result.outcome());
    }

    @Test
    void eachRunUsesAFreshSession() {
        ScriptTestRunner runner = new ScriptTestRunner();
        TestRunResult first = runner.run("int leaked = 42;", bindings());
        assertEquals(TestOutcome.PASSED, first.outcome());
        TestRunResult second = runner.run("leaked", bindings());
        assertTrue(second.outcome() != TestOutcome.PASSED,
                "second run inherited state from the first: " + second.outcome());
    }

    @Test
    void newAssertionsWorkThroughRunner() {
        ScriptTestRunner runner = new ScriptTestRunner();
        TestRunResult r1 = runner.run(
                "test.assertEquals(2, 1 + 1); test.assertNotNull(\"x\"); test.assertNull(null);",
                bindings());
        assertEquals(TestOutcome.PASSED, r1.outcome());

        TestRunResult r2 = runner.run(
                "test.assertThrows(IllegalArgumentException.class, () -> { throw new IllegalArgumentException(\"bad\"); });",
                bindings());
        assertEquals(TestOutcome.PASSED, r2.outcome());
    }

    @Test
    void assertThrowsDetectsMissingException() {
        ScriptTestRunner runner = new ScriptTestRunner();
        TestRunResult r = runner.run(
                "test.assertThrows(IllegalArgumentException.class, () -> { return 1; });",
                bindings());
        assertEquals(TestOutcome.FAILED, r.outcome());
    }
}
