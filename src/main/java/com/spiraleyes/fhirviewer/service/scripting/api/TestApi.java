package com.spiraleyes.fhirviewer.service.scripting.api;

/**
 * Assertion API exposed to scripts as {@code test}.
 *
 * <p>Every assertion throws {@link AssertionError} on failure, so a script can
 * either let JShell report it (the run is then a failed test) or catch it
 * itself. The runner distinguishes an {@code AssertionError} (a failed
 * assertion) from any other throwable (an error).</p>
 */
public final class TestApi {

    /** A block of code an assertion can wrap. */
    @FunctionalInterface
    public interface Executable {
        void execute() throws Throwable;
    }

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

    public void assertNotNull(Object actual) {
        assertNotNull(actual, "expected non-null value");
    }

    public void assertNotNull(Object actual, String message) {
        if (actual == null) {
            throw new AssertionError(message == null ? "expected non-null value" : message);
        }
    }

    public void assertNull(Object actual) {
        assertNull(actual, "expected null value");
    }

    public void assertNull(Object actual, String message) {
        if (actual != null) {
            throw new AssertionError(message == null ? "expected null value" : message);
        }
    }

    public void assertThrows(Class<? extends Throwable> expectedType, Executable executable) {
        assertThrows(expectedType, executable, "expected " + (expectedType == null ? "exception" : expectedType.getSimpleName()) + " to be thrown");
    }

    public void assertThrows(Class<? extends Throwable> expectedType, Executable executable, String message) {
        if (expectedType == null) {
            throw new IllegalArgumentException("expectedType must not be null");
        }
        if (executable == null) {
            throw new IllegalArgumentException("executable must not be null");
        }
        try {
            executable.execute();
        } catch (Throwable thrown) {
            if (expectedType.isInstance(thrown)) {
                return;
            }
            throw new AssertionError((message == null ? "wrong exception type" : message)
                    + ": got " + thrown.getClass().getSimpleName());
        }
        throw new AssertionError(message == null ? "expected exception was not thrown" : message);
    }

    public void assertDoesNotThrow(Executable executable) {
        assertDoesNotThrow(executable, "expected no exception");
    }

    public void assertDoesNotThrow(Executable executable, String message) {
        if (executable == null) {
            throw new IllegalArgumentException("executable must not be null");
        }
        try {
            executable.execute();
        } catch (Throwable thrown) {
            throw new AssertionError((message == null ? "unexpected exception" : message)
                    + ": " + thrown.getClass().getSimpleName()
                    + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage()));
        }
    }

    public void fail() {
        fail("test failed");
    }

    public void fail(String message) {
        throw new AssertionError(message == null ? "test failed" : message);
    }
}
