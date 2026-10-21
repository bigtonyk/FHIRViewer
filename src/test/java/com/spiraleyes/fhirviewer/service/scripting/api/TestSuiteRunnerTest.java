package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.service.FhirService;
import com.spiraleyes.fhirviewer.service.scripting.api.FhirApi;
import com.spiraleyes.fhirviewer.service.scripting.api.LogApi;
import com.spiraleyes.fhirviewer.service.scripting.api.ScriptBindings;
import com.spiraleyes.fhirviewer.service.scripting.api.ServerApi;
import com.spiraleyes.fhirviewer.service.scripting.api.TestApi;
import com.spiraleyes.fhirviewer.service.scripting.api.ValidationApi;
import com.spiraleyes.fhirviewer.service.scripting.api.ViewerApi;
import com.spiraleyes.fhirviewer.service.scripting.api.WritePolicy;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestCase;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestCaseResult;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestOutcome;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestProgressListener;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestSuite;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestSuiteResult;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestSuiteRunner;

class TestSuiteRunnerTest {

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
    void allPassingTestsResultInAllPassed() {
        TestSuite suite = new TestSuite("passing-suite", List.of(
                TestCase.of("pass-1", "test.assertTrue(true);", bindings()),
                TestCase.of("pass-2", "test.assertEquals(2, 1 + 1);", bindings())
        ));
        TestSuiteResult result = new TestSuiteRunner().run(suite);
        assertEquals(2, result.total());
        assertEquals(2, result.passed());
        assertEquals(0, result.failed());
        assertEquals(0, result.errored());
        assertEquals(0, result.skipped());
        assertTrue(result.allPassed());
        assertFalse(result.cancelled());
    }

    @Test
    void mixedOutcomesRecordedCorrectly() {
        TestSuite suite = new TestSuite("mixed-suite", List.of(
                TestCase.of("pass-1", "test.assertTrue(true);", bindings()),
                TestCase.of("fail-1", "test.assertTrue(false, \"boom\");", bindings()),
                TestCase.of("error-1", "throw new IllegalStateException(\"oops\");", bindings())
        ));
        TestSuiteResult result = new TestSuiteRunner().run(suite);
        assertEquals(3, result.total());
        assertEquals(1, result.passed());
        assertEquals(1, result.failed());
        assertEquals(1, result.errored());
        assertFalse(result.allPassed());
    }

    @Test
    void eachCaseUsesFreshSession() {
        TestSuite suite = new TestSuite("isolation-suite", List.of(
                TestCase.of("define", "int x = 42;", bindings()),
                TestCase.of("check-isolation", "x;", bindings())
        ));
        TestSuiteResult result = new TestSuiteRunner().run(suite);
        assertEquals(2, result.total());
        assertEquals(1, result.passed());
        assertEquals(1, result.errored());
    }

    @Test
    void destructiveTestWithoutConfirmationIsSkipped() {
        TestSuite suite = new TestSuite("destructive-suite", List.of(
                new TestCase("write-test",
                        "test.assertTrue(true);", bindings(), true, "test-server")
        ));
        TestSuiteRunner runner = new TestSuiteRunner();
        TestSuiteResult result = runner.run(suite);
        assertEquals(1, result.total());
        assertEquals(1, result.skipped());
        assertEquals(0, result.passed());
        assertTrue(result.results().get(0).message().contains("not confirmed"));
    }

    @Test
    void destructiveTestWithConfirmationRuns() {
        TestSuite suite = new TestSuite("confirmed-destructive-suite", List.of(
                new TestCase("write-test",
                        "test.assertTrue(true);", bindings(), true, "test-server")
        ));
        TestSuiteRunner runner = new TestSuiteRunner(tc -> true);
        TestSuiteResult result = runner.run(suite);
        assertEquals(1, result.total());
        assertEquals(1, result.passed());
        assertEquals(0, result.skipped());
    }

    @Test
    void preCancelledSuiteSkipsAllTests() {
        TestSuiteRunner runner = new TestSuiteRunner();
        TestSuite suite = new TestSuite("cancelled-suite", List.of(
                TestCase.of("one", "test.assertTrue(true);", bindings()),
                TestCase.of("two", "test.assertTrue(true);", bindings())
        ));
        TestProgressListener selfCancelling = new TestProgressListener() {
            @Override public void suiteStarted(TestSuite suite) {
                runner.cancel();
            }
        };
        TestSuiteResult result = runner.run(suite, selfCancelling);
        assertEquals(2, result.total());
        assertEquals(2, result.skipped());
        assertEquals(0, result.passed());
        assertTrue(result.cancelled());
    }

    @Test
    void nullBindingsResultInError() {
        TestSuite suite = new TestSuite("no-bindings", List.of(
                new TestCase("no-bindings-test", "test.assertTrue(true);", null, false, "")
        ));
        TestSuiteResult result = new TestSuiteRunner().run(suite);
        assertEquals(1, result.total());
        assertEquals(1, result.errored());
    }

    @Test
    void progressListenerReceivesCallbacks() {
        java.util.List<String> events = new java.util.ArrayList<>();
        TestProgressListener listener = new TestProgressListener() {
            @Override public void suiteStarted(TestSuite suite) {
                events.add("suiteStarted:" + suite.name());
            }
            @Override public void testStarted(String name, int index, int total) {
                events.add("testStarted:" + name);
            }
            @Override public void testCompleted(TestCaseResult result, int index, int total) {
                events.add("testCompleted:" + result.name() + ":" + result.outcome());
            }
            @Override public void suiteCompleted(TestSuiteResult result) {
                events.add("suiteCompleted:" + result.passed() + "/" + result.total());
            }
        };
        TestSuite suite = new TestSuite("progress-suite", List.of(
                TestCase.of("one", "test.assertTrue(true);", bindings()),
                TestCase.of("two", "test.assertTrue(true);", bindings())
        ));
        new TestSuiteRunner().run(suite, listener);
        assertEquals(6, events.size());
        assertTrue(events.contains("suiteStarted:progress-suite"));
        assertTrue(events.contains("testCompleted:one:PASSED"));
        assertTrue(events.contains("testCompleted:two:PASSED"));
        assertTrue(events.contains("suiteCompleted:2/2"));
    }
}