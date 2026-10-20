package com.spiraleyes.fhirviewer.service.scripting.api;

/**
 * Minimal assertion API exposed to scripts as {@code test}.
 *
 * <p>Every assertion throws {@link AssertionError} on failure, so a script can
 * either let JShell report it (the run is then a failed test) or catch it
 * itself. The runner distinguishes an {@code AssertionError} (a failed
 * assertion) from any other throwable (an error).</p>
 */
public final class TestApi {

    public void assertTrue(boolean condition) {
        assertTrue(condition, "expected true");
    }

    public void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message == null ? "expected true" : message);
        }
    }

    public void assertFalse(boolean condition) {
        assertFalse(condition, "expected false");
    }

    public void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message == null ? "expected false" : message);
        }
    }

    public void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, "expected " + expected + " but was " + actual);
    }

    public void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message);
        }
    }

    public void fail() {
        fail("test failed");
    }

    public void fail(String message) {
        throw new AssertionError(message == null ? "test failed" : message);
    }
}
