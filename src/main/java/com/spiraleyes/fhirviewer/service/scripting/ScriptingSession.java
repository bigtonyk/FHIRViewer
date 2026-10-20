package com.spiraleyes.fhirviewer.service.scripting;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import jdk.jshell.Diag;
import jdk.jshell.EvalException;
import jdk.jshell.JShell;
import jdk.jshell.JShellException;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis.Completeness;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;

/**
 * One embedded JShell session: split a buffer into snippets, evaluate them,
 * capture what they printed, and turn JShell's events into
 * {@link ScriptRunResult} entries.
 *
 * <p><b>Execution engine: {@code local}.</b> Snippet code runs inside this JVM,
 * never in a child process — no subprocess to spawn from a packaged runtime, no
 * JDI to configure, and {@link #stop()} takes effect immediately (verified
 * against both a sleeping snippet and a busy loop). The default engine, by
 * contrast, runs snippets out of process, which is why console output has to be
 * captured the way this class does it.</p>
 *
 * <p><b>Output capture.</b> Snippets write to the process-global
 * {@code System.out}/{@code System.err} — {@code JShell.Builder.out()} does not
 * intercept them. So each evaluation swaps both streams onto a per-snippet
 * buffer and restores them in a {@code finally}, which keeps a script's prints
 * in the result and the application's own console intact afterwards. The known
 * cost: anything the application itself prints from another thread during that
 * window lands in the script's output too. It is a short window, the UI thread
 * prints nothing by convention, and the alternative — a custom
 * {@code ExecutionControl} — buys nothing for a trusted, in-process script.</p>
 *
 * <p><b>Reset is close-and-recreate.</b> The public API has no
 * {@code JShell.reset()}: a fresh engine with the default imports re-applied is
 * what "reset" means, and it is the only way previously defined variables,
 * methods and classes are guaranteed gone.</p>
 *
 * <p><b>Threading.</b> {@link #run}, {@link #reset} and {@link #close} belong to
 * one thread (the coordinator's worker); {@link #stop} may be called from any
 * thread, which is how a Stop button reaches a snippet already running. This
 * class logs nothing and touches no JavaFX type.</p>
 *
 * <p>JShell executes ordinary Java with the user's permissions. Nothing here
 * sandboxes it — see the trust warning the console shows before the first run.</p>
 */
public final class ScriptingSession implements AutoCloseable {

    /**
     * The imports every new session starts with.
     *
     * <p>JShell's own API gives snippets <em>no</em> default imports (the
     * command-line tool adds its own; an embedded session starts bare), so the
     * sensible defaults the phase asks for are applied explicitly. This list is
     * the jshell tool's own set: everyday {@code java.*} packages, no FHIR
     * classes and no application classes — Phase 2 decides what of the
     * application scripts may see, not a default import list.</p>
     */
    static final List<String> DEFAULT_IMPORTS = List.of(
            "import java.util.*;",
            "import java.util.stream.*;",
            "import java.util.function.*;",
            "import java.util.concurrent.*;",
            "import java.util.regex.*;",
            "import java.io.*;",
            "import java.math.*;",
            "import java.net.*;",
            "import java.nio.file.*;",
            "import java.time.*;");

    /** Never shared across sessions; volatile because {@link #stop()} reads it from other threads. */
    private volatile JShell shell;
    /** Set by {@link #stop()} from any thread; checked between snippets and at run start. */
    private volatile boolean stopRequested;
    private volatile boolean closed;

    /** Creates a session with the local engine and the default imports applied. */
    public ScriptingSession() {
        this.shell = newShell();
        applyDefaultImports();
    }

    /**
     * Runs one editor buffer: split, evaluate, capture, report.
     *
     * <p>The buffer is analysed first and evaluated only if <em>every</em> part
     * of it is complete — a script that ends inside a brace or a string runs
     * nothing at all, because executing the half JShell understands is not
     * something the user asked for.</p>
     *
     * @param source the editor buffer; never {@code null}
     * @return the ordered entries and the status; never {@code null}
     */
    public ScriptRunResult run(String source) {
        Objects.requireNonNull(source, "source");
        long start = System.nanoTime();
        if (closed) {
            return ScriptRunResult.failed(
                    "The scripting session is closed. Close and reopen the console to run again.");
        }
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isBlank()) {
            return ScriptRunResult.failed("There is nothing to run — the editor is empty.");
        }
        // A stop aimed at an earlier run must not colour this one.
        stopRequested = false;

        Split split = split(normalized);
        if (split.stuckAt() != null) {
            return incomplete(split.stuckAt(), start);
        }
        if (split.parts().isEmpty()) {
            // Only comments and blank space: nothing to execute, and that is fine.
            return new ScriptRunResult(ScriptRunResult.Status.COMPLETED, List.of(), 0,
                    Duration.ofNanos(System.nanoTime() - start));
        }
        return evaluate(split.parts(), start);
    }

    /** Stops the snippets running right now, from any thread. Best effort, and honest about it. */
    public void stop() {
        stopRequested = true;
        JShell current = shell;
        if (current != null) {
            current.stop();
        }
    }

    /**
     * Drops all session state: variables, methods, classes and imports.
     *
     * <p>Close-and-recreate, because the public API offers no reset and a half
     * reset would leave definitions behind — the one thing a Reset button must
     * never do. Must not be called while {@link #run} is executing; the
     * coordinator's single worker thread guarantees that ordering.</p>
     */
    public void reset() {
        stopRequested = false;
        JShell previous = shell;
        if (previous != null) {
            quietly(previous);
        }
        if (closed) {
            return;
        }
        shell = newShell();
        applyDefaultImports();
    }

    /** Stops any running snippet and closes the engine; further runs are refused clearly. */
    @Override
    public void close() {
        closed = true;
        JShell current = shell;
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException ignored) {
                // A dead engine is already closed as far as anyone cares.
            }
            quietly(current);
        }
    }

    // ------------------------------------------------------------------
    // Analysis
    // ------------------------------------------------------------------

    /** The result of splitting a buffer: the complete parts, plus the source an incomplete tail got stuck at. */
    private record Split(List<String> parts, String stuckAt) {
    }

    /**
     * Splits a buffer into complete snippets using JShell's own source analysis
     * — the same analysis the command-line REPL uses, so "complete" means what
     * JShell means by it, not what a hand-rolled parser would guess.
     *
     * <p>Fed line by line and flushed as soon as a prefix is complete, the way
     * the REPL sees input arrive. This matters: analysing a whole buffer at
     * once glues a semicolon-less expression onto the statement after it
     * ("{@code 2 + 2}" followed by "{@code int x = 3;}" becomes one "complete"
     * fragment missing a semicolon), which then fails as if the <em>author</em>
     * had written a syntax error. Feeding line by line lets each complete
     * prefix out on its own, before the next line can reach back and corrupt
     * it; a multi-line method or loop still holds until its final line, and a
     * buffer that ends mid-statement is refused whole in {@link #run}.</p>
     *
     * @return every complete part in order; {@code stuckAt} carries the
     *         incomplete tail when the buffer ends inside something
     */
    private Split split(String normalized) {
        List<String> parts = new ArrayList<>();
        StringBuilder held = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            held.append(line).append('\n');
            drainComplete(held, parts);
        }
        String leftover = held.toString();
        if (leftover.isBlank()) {
            return new Split(parts, null);
        }
        CompletionInfo last = shell.sourceCodeAnalysis().analyzeCompletion(leftover);
        if (last.completeness() == Completeness.EMPTY) {
            // Trailing comments only: dropped, not run.
            return new Split(parts, null);
        }
        if (last.completeness().isComplete() && last.source() != null
                && !last.source().isBlank()) {
            parts.add(last.source());
            return new Split(parts, null);
        }
        return new Split(parts, leftover);
    }

    /**
     * Flushes every snippet that is complete within the held text, leaving any
     * remainder — an incomplete tail, trailing comments, blank space — in
     * place. A completion with no usable source ends the drain rather than
     * looping on it.
     */
    private void drainComplete(StringBuilder held, List<String> parts) {
        while (!held.toString().isBlank()) {
            CompletionInfo info = shell.sourceCodeAnalysis().analyzeCompletion(held.toString());
            Completeness completeness = info.completeness();
            if (completeness == Completeness.EMPTY || !completeness.isComplete()) {
                return;
            }
            if (info.source() == null || info.source().isBlank()) {
                return;
            }
            parts.add(info.source());
            String rest = info.remaining();
            if (rest == null || rest.length() == held.length()) {
                return;
            }
            held.setLength(0);
            held.append(rest);
        }
    }

    /**
     * Refuses an incomplete buffer whole: one diagnostic entry names where the
     * analysis got stuck, and nothing has been executed at that point.
     */
    private ScriptRunResult incomplete(String stuckAt, long start) {
        String shown = stuckAt.strip();
        if (shown.length() > 160) {
            shown = shown.substring(shown.length() - 160);
        }
        String message = "The script is incomplete — JShell stopped at: " + shown
                + " ... Close the statement, brace or string it ends inside and run again."
                + " Nothing was executed.";
        return new ScriptRunResult(ScriptRunResult.Status.INCOMPLETE,
                List.of(new ScriptRunResult.Entry(Instant.now(),
                        ScriptRunResult.Kind.DIAGNOSTIC, message)),
                0, Duration.ofNanos(System.nanoTime() - start));
    }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    /**
     * Hands each complete part to JShell in order, with the console captured
     * around every evaluation, and folds the events into entries.
     *
     * <p>One part's failure never stops the buffer: a rejected snippet is
     * reported and the next one runs, exactly like the REPL — the later
     * diagnostic that says "cannot find symbol" is itself useful information
     * about which earlier part failed. A Stop between parts stops the loop; a
     * Stop during a part is what {@link JShell#stop()} is for.</p>
     */
    private ScriptRunResult evaluate(List<String> parts, long start) {
        List<ScriptRunResult.Entry> entries = new ArrayList<>();
        boolean rejected = false;
        boolean raised = false;
        int evaluated = 0;

        for (String part : parts) {
            if (stopRequested) {
                break;
            }
            ByteArrayOutputStream printed = new ByteArrayOutputStream();
            PrintStream capture = new PrintStream(printed, true, StandardCharsets.UTF_8);
            List<SnippetEvent> events;
            PrintStream previousOut = System.out;
            PrintStream previousErr = System.err;
            try {
                System.setOut(capture);
                System.setErr(capture);
                events = shell.eval(part);
            } catch (IllegalStateException broken) {
                closed = true;
                return ScriptRunResult.failed(
                        "The scripting session closed while the script was running.");
            } catch (RuntimeException engineProblem) {
                entries.add(new ScriptRunResult.Entry(Instant.now(),
                        ScriptRunResult.Kind.ERROR,
                        "The session could not evaluate this snippet: "
                                + messageOf(engineProblem)));
                return new ScriptRunResult(ScriptRunResult.Status.FAILED, entries,
                        evaluated, Duration.ofNanos(System.nanoTime() - start));
            } finally {
                System.setOut(previousOut);
                System.setErr(previousErr);
                capture.flush();
            }

            for (String line : linesOf(printed.toString(StandardCharsets.UTF_8))) {
                entries.add(new ScriptRunResult.Entry(Instant.now(),
                        ScriptRunResult.Kind.OUTPUT, line));
            }
            for (SnippetEvent event : events) {
                if (event.status() == Snippet.Status.REJECTED) {
                    rejected = true;
                    addDiagnostics(entries, event.snippet());
                } else if (event.exception() != null) {
                    raised = true;
                    entries.add(new ScriptRunResult.Entry(Instant.now(),
                            ScriptRunResult.Kind.EXCEPTION,
                            describeFailure(event.exception())));
                } else if (event.value() != null && !event.value().isBlank()) {
                    entries.add(new ScriptRunResult.Entry(Instant.now(),
                            ScriptRunResult.Kind.VALUE, event.value()));
                }
            }
            evaluated++;
        }

        ScriptRunResult.Status status = stopRequested
                ? ScriptRunResult.Status.STOPPED
                : (rejected || raised)
                        ? ScriptRunResult.Status.COMPLETED_WITH_ERRORS
                        : ScriptRunResult.Status.COMPLETED;
        return new ScriptRunResult(status, entries, evaluated,
                Duration.ofNanos(System.nanoTime() - start));
    }

    /** Appends one diagnostic per compiler message; a rejection with none still says so. */
    private void addDiagnostics(List<ScriptRunResult.Entry> entries, Snippet snippet) {
        boolean any = false;
        if (snippet != null) {
            for (Diag diag : List.of(shell.diagnostics(snippet).toArray(Diag[]::new))) {
                any = true;
                entries.add(new ScriptRunResult.Entry(Instant.now(),
                        ScriptRunResult.Kind.DIAGNOSTIC, messageOf(diag)));
            }
        }
        if (!any) {
            entries.add(new ScriptRunResult.Entry(Instant.now(),
                    ScriptRunResult.Kind.DIAGNOSTIC,
                    "JShell rejected this snippet without an explanation."));
        }
    }

    // ------------------------------------------------------------------
    // Session mechanics
    // ------------------------------------------------------------------

    /** A fresh local-engine shell: no child process, no JDI, stop takes effect in-process. */
    private static JShell newShell() {
        return JShell.builder().executionEngine("local").build();
    }

    /** Best-effort default imports; each is an independent snippet and a failure is not fatal. */
    private void applyDefaultImports() {
        for (String statement : DEFAULT_IMPORTS) {
            try {
                shell.eval(statement);
            } catch (RuntimeException ignored) {
                // Imports are convenience; a session without one still runs scripts.
            }
        }
    }

    private static void quietly(JShell shell) {
        try {
            shell.close();
        } catch (RuntimeException ignored) {
            // Closing twice, or closing a dead engine, is not an error to report.
        }
    }

    /** Renders a snippet exception as one line: exception class, then its message. */
    private static String describeFailure(JShellException failure) {
        if (failure instanceof EvalException eval) {
            String message = eval.getMessage();
            return eval.getExceptionClassName()
                    + (message == null || message.isBlank() ? "" : ": " + message);
        }
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private static String messageOf(Diag diag) {
        String message = diag.getMessage(Locale.getDefault());
        return message == null || message.isBlank() ? diag.getCode() : message;
    }

    private static String messageOf(Throwable problem) {
        String message = problem.getMessage();
        if (message == null || message.isBlank()) {
            return problem.getClass().getSimpleName();
        }
        return problem.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Splits captured console text into entry lines. A trailing newline ends
     * the last line rather than inventing an empty one; blank lines in the
     * middle are kept, because they were really printed.
     */
    static List<String> linesOf(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] split = normalized.split("\n", -1);
        int count = split.length;
        if (count > 0 && split[count - 1].isEmpty()) {
            count--;
        }
        return List.of(split).subList(0, count);
    }
}

