package com.spiraleyes.fhirviewer.service.scripting.testing;

/**
 * Confirmation gate for destructive test cases. Return {@code true} to allow
 * execution, {@code false} to skip the test.
 */
@FunctionalInterface
public interface ConfirmationHandler {
    boolean confirm(TestCase testCase);
}
