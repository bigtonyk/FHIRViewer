package com.spiraleyes.fhirviewer.service.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ScriptingSession} without starting the JavaFX toolkit.
 *
 * <p>Every test owns its session and closes it afterwards: JShell engines are
 * cheap but not free, and a leaked one keeps a classloader (and the
 * {@code local} execution machinery) around for the rest of the JVM.</p>
 */
class ScriptingSessionTest {

    private ScriptingSession session;

    @BeforeEach
    void openSession() {
        session = new ScriptingSession();
    }

    @AfterEach
    void closeSession() {
        session.close();
    }

    private static ScriptRunResult.Entry first(ScriptRunResult result, ScriptRunResult.Kind kind) {
        ScriptRunResult.Entry entry = result.first(kind);
        assertNotNull(entry, "expected an entry of kind " + kind + " in " + result.entries());
        return entry;
    }

    private static String loadExample() throws Exception {
        try (InputStream in = ScriptingSessionTest.class
                .getResourceAsStream("/scripts/scripting-example.jsh")) {
            assertNotNull(in, "the example resource must be on the test class path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("An expression evaluates and its value is reported")
    void expressionsEvaluateAndShowValues() {
        ScriptRunResult result = session.run("40 + 2");

        assertEquals(ScriptRunResult.Status.COMPLETED, result.status());
        assertEquals(1, result.snippetCount());
        assertEquals("42", first(result, ScriptRunResult.Kind.VALUE).text());
    }

    @Test
    @DisplayName("Variables persist between runs until Reset Session")
    void statePersistsBetweenRuns() {
        session.run("int remembered = 40;");
        ScriptRunResult result = session.run("remembered + 2");

        assertEquals(ScriptRunResult.Status.COMPLETED, result.status());
        assertEquals("42", first(result, ScriptRunResult.Kind.VALUE).text());
    }

    @Test
    @DisplayName("Reset starts a fresh session — earlier definitions are gone")
    void resetStartsAFreshSession() {
        session.run("int gone = 1;");
        session.reset();
        ScriptRunResult result = session.run("gone + 1");

        assertEquals(ScriptRunResult.Status.COMPLETED_WITH_ERRORS, result.status());
        assertTrue(first(result, ScriptRunResult.Kind.DIAGNOSTIC).text()
                .contains("cannot find symbol"),
                "the diagnostic should name the cause: " + result.entries());
    }

    @Test
    @DisplayName("A multi-snippet buffer runs in order and reports the snippet count")
    void multiSnippetBufferRunsInOrder() {
        ScriptRunResult result = session.run("int first = 2;\nint second = 3;\nfirst * second");

        assertEquals(ScriptRunResult.Status.COMPLETED, result.status());
        assertEquals(3, result.snippetCount());
        List<String> values = result.entries().stream()
                .filter(e -> e.kind() == ScriptRunResult.Kind.VALUE)
                .map(ScriptRunResult.Entry::text)
                .toList();
        assertEquals(List.of("2", "3", "6"), values,
                "each part reports its value in order: " + result.entries());
    }

    @Test
    @DisplayName("Methods defined in a script stay defined and callable")
    void methodsPersistBetweenRuns() {
        session.run("int square(int n) { return n * n; }");
        ScriptRunResult result = session.run("square(7)");

        assertEquals(ScriptRunResult.Status.COMPLETED, result.status());
        assertEquals("49", first(result, ScriptRunResult.Kind.VALUE).text());
    }


    @Test
    @DisplayName("Console output reaches the console pane and streams are restored afterwards")
    void consoleOutputIsCapturedAndStreamsRestored() {
        java.io.PrintStream before = System.out;
        ScriptRunResult result = session.run(
                "System.out.println(\"hello\");\nSystem.err.println(\"careful\");");

        assertTrue(result.entries().stream()
                .filter(e -> e.kind() == ScriptRunResult.Kind.OUTPUT)
                .anyMatch(e -> e.text().equals("hello")),
                "printed lines must appear as output entries: " + result.entries());
        assertTrue(result.entries().stream()
                .filter(e -> e.kind() == ScriptRunResult.Kind.OUTPUT)
                .anyMatch(e -> e.text().equals("careful")),
                "stderr lines reach the console too: " + result.entries());
        assertTrue(before == System.out,
                "System.out must be the same stream after the run that it was before");
    }

    @Test
    @DisplayName("A compile error is a diagnostic entry, not a crash")
    void compileDiagnosticsAreReported() {
        ScriptRunResult result = session.run("int broken = ;");

        assertEquals(ScriptRunResult.Status.COMPLETED_WITH_ERRORS, result.status());
        ScriptRunResult.Entry diagnostic = first(result, ScriptRunResult.Kind.DIAGNOSTIC);
        assertFalse(diagnostic.text().isBlank(), "the diagnostic must say something");
        assertTrue(diagnostic.text().toLowerCase().contains("illegal start of expression"),
                "the message should come from the compiler: " + diagnostic.text());
    }

    @Test
    @DisplayName("A runtime exception is reported with its class and message")
    void runtimeExceptionsAreReported() {
        ScriptRunResult result = session.run("\"abc\".substring(10)");

        assertEquals(ScriptRunResult.Status.COMPLETED_WITH_ERRORS, result.status());
        ScriptRunResult.Entry failure = first(result, ScriptRunResult.Kind.EXCEPTION);
        assertTrue(failure.text().contains("StringIndexOutOfBoundsException"),
                "the entry names the exception class: " + failure.text());
    }

    @Test
    @DisplayName("An incomplete script runs nothing and says where it got stuck")
    void incompleteScriptsRunNothing() {
        ScriptRunResult result = session.run("int visible = 7;\nif (true) {");

        assertEquals(ScriptRunResult.Status.INCOMPLETE, result.status());
        assertEquals(0, result.snippetCount());
        assertTrue(first(result, ScriptRunResult.Kind.DIAGNOSTIC).text().contains("incomplete"),
                "the refusal must say the script is incomplete: " + result.entries());

        // The complete part was refused along with the rest: nothing executed.
        ScriptRunResult after = session.run("visible + 0");
        assertEquals(ScriptRunResult.Status.COMPLETED_WITH_ERRORS, after.status(),
                "the variable from the refused buffer must not exist");
    }

    @Test
    @DisplayName("A comments-only buffer completes with nothing to execute")
    void commentsOnlyScriptDoesNothing() {
        ScriptRunResult result = session.run("// just a comment\n// another one");

        assertEquals(ScriptRunResult.Status.COMPLETED, result.status());
        assertEquals(0, result.snippetCount());
        assertTrue(result.entries().isEmpty(), "comments print nothing");
    }

    @Test
    @DisplayName("The default imports cover java.util")
    void defaultImportsCoverJavaUtil() {
        ScriptRunResult result = session.run("List.of(1, 2, 3).size()");

        assertEquals(ScriptRunResult.Status.COMPLETED, result.status());
        assertEquals("3", first(result, ScriptRunResult.Kind.VALUE).text());
    }

    @Test
    @DisplayName("Stop interrupts a sleeping script from another thread")
    void stopInterruptsASleepingScript() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ScriptRunResult> finished = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            started.countDown();
            finished.set(session.run("Thread.sleep(30000);"));
        });
        worker.start();
        assertTrue(started.await(10, TimeUnit.SECONDS), "the worker must start");

        long stopAt = System.nanoTime();
        session.stop();
        worker.join(10_000);
        long elapsed = System.nanoTime() - stopAt;

        assertFalse(worker.isAlive(), "stop must end the sleeping script, not wait it out");
        assertNotNull(finished.get(), "the interrupted run still reports a result");
        assertEquals(ScriptRunResult.Status.STOPPED, finished.get().status());
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(10),
                "stop should take milliseconds, not seconds: " + elapsed);
    }

    @Test
    @DisplayName("Running after close fails clearly instead of throwing")
    void runAfterCloseFailsClearly() {
        session.close();
        ScriptRunResult result = session.run("1 + 1");

        assertEquals(ScriptRunResult.Status.FAILED, result.status());
        assertTrue(first(result, ScriptRunResult.Kind.ERROR).text().contains("closed"),
                "the failure must say the session is closed: " + result.entries());
        session.close(); // closing twice must not throw
    }

    @Test
    @DisplayName("An empty buffer fails clearly instead of doing nothing silently")
    void emptyRunFailsClearly() {
        ScriptRunResult result = session.run("   \n  ");

        assertEquals(ScriptRunResult.Status.FAILED, result.status());
        assertTrue(first(result, ScriptRunResult.Kind.ERROR).text().contains("nothing to run"),
                "the failure must say there is nothing to run: " + result.entries());
    }

    @Test
    @DisplayName("The bundled example runs harmlessly end to end")
    void bundledExampleRunsHarmlessly() throws Exception {
        String example = loadExample();
        assertFalse(example.isBlank(), "the example resource must not be empty");

        ScriptRunResult result = session.run(example);

        assertEquals(ScriptRunResult.Status.COMPLETED, result.status(),
                "the example must run with no errors; entries: " + result.entries());
        assertTrue(result.entries().stream()
                .anyMatch(e -> e.kind() == ScriptRunResult.Kind.VALUE && e.text().equals("49")),
                "square(7) should report 49: " + result.entries());
    }
}

