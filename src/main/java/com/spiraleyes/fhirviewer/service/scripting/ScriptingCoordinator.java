package com.spiraleyes.fhirviewer.service.scripting;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
 * button interrupts a snippet already running. {@link #close} stops the
 * session, drains the worker, closes the engine and unregisters the JVM
 * shutdown hook that otherwise closes it if the application exits first, so no
 * JShell outlives the dialog either way. Closing is idempotent.</p>
 */
public final class ScriptingCoordinator implements AutoCloseable {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "fhirviewer-scripting");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
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
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(onDone, "onDone");
        if (closed.get()) {
            onDone.accept(ScriptRunResult.failed(
                    "The scripting session is closed. Close and reopen the console to run again."));
            return;
        }
        if (!running.compareAndSet(false, true)) {
            onDone.accept(ScriptRunResult.failed(
                    "A script is already running — wait for it to finish, or press Stop."));
            return;
        }
        try {
            worker.execute(() -> {
                ScriptRunResult result;
                try {
                    result = session().run(source);
                } catch (RuntimeException problem) {
                    result = ScriptRunResult.failed(
                            "The script could not be run: " + problem.getClass().getSimpleName()
                                    + (problem.getMessage() == null ? ""
                                            : ": " + problem.getMessage()));
                } finally {
                    running.set(false);
                }
                onDone.accept(result);
            });
        } catch (RejectedExecutionException racedClose) {
            running.set(false);
            onDone.accept(ScriptRunResult.failed(
                    "The scripting session was closed before the run could start."));
        }
    }

    /** Stops whatever the worker is executing right now. Callable from any thread. */
    public void stop() {
        ScriptingSession current = session;
        if (current != null) {
            current.stop();
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

