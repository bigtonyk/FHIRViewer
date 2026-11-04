package com.spiraleyes.fhirviewer.service.scripting;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.spiraleyes.fhirviewer.service.scripting.api.ScriptBindings;

/**
 * Owns one {@link ScriptingSession} and serializes every call to it on a
 * dedicated worker thread.
 *
 * <p>The JavaFX Application Thread never runs a snippet: {@link #run} hands the
 * work to a single daemon worker, so a script that sleeps for thirty seconds
 * freezes nothing but the workbench's own Run button, and two Runs can never
 * interleave in one session — a second press while one is in flight is refused
 * immediately rather than queued behind it, because "run this now" and "queue
 * this for later" are different promises and the button means the first.</p>
 *
 * <p>Callbacks arrive on the worker thread (or the caller's, when the
 * coordinator is already closed); a JavaFX caller marshals with
 * {@code Platform.runLater}, exactly as {@code BackgroundTasks} does for the
 * rest of the application.</p>
 *
 * <p>{@link #stop} reaches the session from any thread — that is how the Stop
 * button interrupts a snippet already running — and returns immediately, so
 * pressing it on the JavaFX thread never waits for a script that may take
 * minutes to unwind. The run stays in flight until the worker itself reports
 * it finished; {@link #close} stops the session, drains the worker, closes the
 * engine and unregisters the JVM shutdown hook that otherwise closes it if the
 * application exits first, so no JShell outlives the dialog either way.
 * Closing is idempotent.</p>
 */
public final class ScriptingCoordinator implements AutoCloseable {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "fhirviewer-scripting");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Thread workerThread;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Set by {@link #stop()} and consumed by the worker it is aimed at. */
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    /** True only while the session is executing a snippet, never between submission and start. */
    private volatile boolean runStarted;
    /** Closes the hand-off window between {@link #stop()} and the worker's start-up. */
    private final Object runGate = new Object();
    /** Created lazily on the worker; read by {@link #stop()} from other threads. */
    private volatile ScriptingSession session;
    private final Thread shutdownHook;

    /** A coordinator with no session yet; the engine is created on first use. */
    public ScriptingCoordinator() {
        shutdownHook = new Thread(this::close, "fhirviewer-scripting-close");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException shuttingDown) {
            // The JVM is already closing; the hook would never get the chance anyway.
        }
    }

    /**
     * Runs one editor buffer on the worker thread.
     *
     * @param source the script; never {@code null}
     * @param onDone receives the result on the worker thread (or the calling
     *                thread when this coordinator is closed); never {@code null}
     */
    public void run(String source, Consumer<ScriptRunResult> onDone) {
        submit(source, null, onDone);
    }

    /**
     * Runs one editor buffer on the worker thread with script API bindings.
     *
     * @param source   the script; never {@code null}
     * @param bindings the {@code fhir}, {@code viewer}, {@code validation},
     *                 {@code server} and {@code log} objects exposed to the
     *                 script, or {@code null} to run without them
     * @param onDone   receives the result on the worker thread; never {@code null}
     */
    public void run(String source, ScriptBindings bindings, Consumer<ScriptRunResult> onDone) {
        submit(source, bindings, onDone);
    }

    /**
     * The one submission path both {@code run} overloads use.
     *
     * <p>One copy, not two: the closed check, the single-run guard, the stop-before-start
     * hand-off and the failure text all have to agree. When they were duplicated, a fix to
     * one overload left the other with the old behaviour — which is how the two copies came
     * to disagree about an em dash in the same user-facing message.</p>
     *
     * @param bindings the script API objects, or {@code null} for none
     */
    private void submit(String source, ScriptBindings bindings, Consumer<ScriptRunResult> onDone) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(onDone, "onDone");
        if (closed.get()) {
            onDone.accept(ScriptRunResult.failed(
                    "The scripting session is closed. Close and reopen the console to run again."));
            return;
        }
        if (!running.compareAndSet(false, true)) {
            onDone.accept(ScriptRunResult.failed(
                    "A script is already running \u2014 wait for it to finish, or press Stop."));
            return;
        }
        try {
            worker.execute(() -> {
                workerThread = Thread.currentThread();
                ScriptRunResult result;
                if (stoppedBeforeStart()) {
                    // Stop raced the worker's start-up: nothing ran, and saying so is
                    // more honest than calling the snippet and pretending it was cut short.
                    result = new ScriptRunResult(ScriptRunResult.Status.STOPPED,
                            List.of(), 0, Duration.ZERO);
                } else {
                    try {
                        result = session().run(source, bindings);
                    } catch (RuntimeException problem) {
                        result = ScriptRunResult.failed(
                                "The script could not be run: " + problem.getClass().getSimpleName()
                                        + (problem.getMessage() == null ? ""
                                                : ": " + problem.getMessage()));
                    } finally {
                        runStarted = false;
                    }
                }
                // A Stop that arrived after the run ended must not colour the next one.
                synchronized (runGate) {
                    stopRequested.set(false);
                }
                // The guard is cleared here and nowhere else: from submission to this
                // point the run really is in flight, whatever Stop may have asked for.
                running.set(false);
                workerThread = null;
                onDone.accept(result);
            });
        } catch (RejectedExecutionException racedClose) {
            running.set(false);
            onDone.accept(ScriptRunResult.failed(
                    "The scripting session was closed before the run could start."));
        }
    }

    /**
     * Marks the work as started, or reports that a Stop beat it there. Called once per
     * submission, before the session is touched, so {@link #stop()} and the worker agree
     * on whether there is anything to interrupt.
     */
    private boolean stoppedBeforeStart() {
        synchronized (runGate) {
            boolean stopped = stopRequested.getAndSet(false);
            if (!stopped) {
                runStarted = true;
            }
            return stopped;
        }
    }

    /**
     * Stops whatever the worker is executing right now. Callable from any thread, and
     * returns without waiting for the snippet to unwind: the Stop button runs on the
     * JavaFX thread, where a five-second wait freezes the whole window.
     */
    public void stop() {
        if (!running.get()) {
            return;
        }
        boolean interruptRun;
        synchronized (runGate) {
            stopRequested.set(true);
            interruptRun = runStarted;
        }
        if (interruptRun) {
            ScriptingSession current = session;
            if (current != null) {
                // Best effort, exactly as the console says: a snippet that swallows
                // interruption can ignore it, and this returns when it does.
                current.stop();
            }
        }
        Thread worker = workerThread;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /**
     * Resets the session on the worker thread, after any run already queued —
     * the single worker makes that ordering automatic.
     *
     * @param onDone invoked on the worker thread once the reset has happened
     *                (or immediately when the coordinator is closed); never null
     */
    public void reset(Runnable onDone) {
        Objects.requireNonNull(onDone, "onDone");
        if (closed.get()) {
            onDone.run();
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    session().reset();
                } catch (RuntimeException problem) {
                    // A reset that failed still ends with the old session gone;
                    // the next run creates a fresh one either way.
                    session = null;
                }
                onDone.run();
            });
        } catch (RejectedExecutionException racedClose) {
            onDone.run();
        }
    }

    /** True while a script is in flight; the console greys Run and Reset on this. */
    public boolean isRunning() {
        return running.get();
    }

    /** Stops the session, drains the worker, closes the engine, unregisters the hook. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScriptingSession current = session;
        if (current != null) {
            current.stop();
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
        if (current != null) {
            current.close();
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException shuttingDown) {
            // We are inside shutdown already; the hook did its job by calling us.
        }
    }

    /** The session, created on first use so opening the console costs nothing. */
    private ScriptingSession session() {
        ScriptingSession current = session;
        if (current == null) {
            current = new ScriptingSession();
            session = current;
        }
        return current;
    }
}
