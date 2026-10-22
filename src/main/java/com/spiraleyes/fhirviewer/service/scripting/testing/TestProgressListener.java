package com.spiraleyes.fhirviewer.service.scripting.testing;

/**
 * Progress callback for suite execution.
 */
public interface TestProgressListener {
    default void suiteStarted(TestSuite suite) {}
    default void testStarted(String name, int index, int total) {}
    default void testCompleted(TestCaseResult result, int index, int total) {}
    default void suiteCompleted(TestSuiteResult result) {}
}
