package com.example.fhirviewer.ui;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.concurrent.Task;

/**
 * Runs blocking work off the JavaFX application thread and delivers the outcome back on it.
 *
 * <p>Every network call this application makes is synchronous from the caller's point of
 * view and may block on a socket, so all of them have to happen on a background thread. The
 * pattern for doing that was written out four times — once each in {@link ServerDialog},
 * {@link ServerSearchDialog}, {@link OpenFromServerDialog} and {@code MainWindow} — with
 * slightly different failure handling each time. This class is that pattern, written once.</p>
 *
 * <p>Two things are deliberately not left to each caller. The first is the failure shape: a
 * caller receives an {@link Attempt}, never an exception, so a dialog cannot accidentally
 * let a raw {@code HttpTimeoutException} escape into a JavaFX event handler, where it would
 * be logged by the toolkit and shown to nobody. The second is threading: the work runs on a
 * daemon thread and the callback is delivered through {@link Platform#runLater}, so every
 * caller gets the same guarantee about which thread it is touching nodes on.</p>
 *
 * <p><b>Cancellation.</b> A started task can be cancelled through the returned
 * {@link Running} handle, which interrupts the worker and delivers a cancelled
 * {@link Attempt}. Interruption is cooperative: a call already blocked in the HTTP client
 * is abandoned when the socket read returns or the deadline passes, so cancellation means
 * "stop waiting and stop listening", not "abort the request at the server". That is the
 * honest description of what a JavaFX cancel can do, and the UI is worded to match.</p>
 */
public final class BackgroundTasks {

    private BackgroundTasks() {
    }

    /**
     * One background attempt: either a value or a readable failure, never an exception.
     *
     * <p>The three states are distinguished because "the work did not run" is a different
     * thing to show a user than "the work failed", and a dialog that says "Operation
     * cancelled" is honest in a way that "Something went wrong" is not.</p>
     *
     * @param value     what the work produced, or {@code null} when it did not succeed
     * @param failure   the readable reason, or {@code null} when the work succeeded
     * @param cancelled whether the caller stopped it rather than it failing
     */
    public record Attempt<T>(T value, String failure, boolean cancelled) {

        /** A successful attempt carrying a value. */
        public static <T> Attempt<T> succeeded(T value) {
            return new Attempt<>(value, null, false);
        }

        /** A failed attempt carrying the reason to show. */
        public static <T> Attempt<T> failed(String reason) {
            return new Attempt<>(null, reason, false);
        }

        /** An attempt the caller stopped before it finished. */
        public static <T> Attempt<T> stopped() {
            return new Attempt<>(null, "The operation was cancelled.", true);
        }

        /** True when the work ran and produced a value. */
        public boolean succeeded() {
            return failure == null && !cancelled;
        }
    }

    /**
     * A handle on work that is in flight, so the caller can let the user stop it.
     *
     * <p>Held by a dialog for the lifetime of one request. A dialog that closes while a
     * call is running should cancel it rather than leave a callback to arrive against a
     * torn-down scene graph.</p>
     */
    public interface Running {

        /**
         * Asks the work to stop. Returns immediately; the callback fires later with a
         * cancelled attempt, so a caller must not expect the UI to change here.
         */
        void cancel();

        /** True until the work has finished, failed or been cancelled. */
        boolean isRunning();
    }

    /**
     * Runs {@code work} on a daemon thread and calls {@code done} on the JavaFX thread.
     *
     * @param threadName the thread name, so a stack dump says which screen was waiting
     * @param work       the blocking work; may throw, and the throwable becomes a failure
     * @param done       called on the JavaFX thread with the result, never {@code null}
     * @return a handle that can cancel the work
     */
    public static <T> Running run(String threadName, Callable<T> work, Consumer<Attempt<T>> done) {
        return start(threadName, new Task<>() {
            @Override
            protected Attempt<T> call() {
                return attempt(work);
            }
        }, done);
    }

    /**
     * Runs work that reports its own failure as a readable string.
     *
     * <p>For work whose failure is already a message — a plugin method raising
     * {@link com.example.fhirviewer.server.ServerOperationException}, or
     * {@code testConnection}, which never throws at all. Wrapping those again would
     * flatten a message the server actually wrote into a generic one.</p>
     */
    public static <T> Running runAttempt(String threadName, Callable<Attempt<T>> work,
            Consumer<Attempt<T>> done) {
        return start(threadName, new Task<>() {
            @Override
            protected Attempt<T> call() throws Exception {
                return work.call();
            }
        }, done);
    }

    /**
     * Starts a task on a daemon thread and wires its three outcomes.
     *
     * <p>The handlers fire on the JavaFX thread: {@link Task} posts its state change
     * through the application thread's event queue, which is what lets a dialog's callback
     * touch nodes without a {@code runLater} of its own.</p>
     */
    private static <T> Running start(String threadName, Task<Attempt<T>> task,
            Consumer<Attempt<T>> done) {
        task.setOnSucceeded(event -> done.accept(task.getValue()));
        task.setOnFailed(event -> done.accept(failureOf(task)));
        // A cancelled Task never completes with a value, so no other handler would fire;
        // this is where the cancelled attempt is manufactured instead.
        task.setOnCancelled(event -> done.accept(Attempt.stopped()));
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
        return handleFor(task);
    }

    /** The attempt for a task that ended without producing one. */
    private static <T> Attempt<T> failureOf(Task<Attempt<T>> task) {
        Throwable problem = task.getException();
        if (task.isCancelled() || isCancellation(problem)) {
            return Attempt.stopped();
        }
        return Attempt.failed(ServerErrors.describe(problem));
    }

    /** A handle over one task, so {@link Running} does not leak the JavaFX type. */
    private static <T> Running handleFor(Task<Attempt<T>> task) {
        return new Running() {
            @Override
            public void cancel() {
                task.cancel(true);
            }

            @Override
            public boolean isRunning() {
                return task.isRunning();
            }
        };
    }

    /**
     * Calls the work and turns any throwable into an {@link Attempt}.
     *
     * <p>Package-visible rather than private so the same wrapping is available to a caller
     * that must run work inline — a test, or a code path with no UI to report to.</p>
     */
    static <T> Attempt<T> attempt(Callable<T> work) {
        try {
            return Attempt.succeeded(work.call());
        } catch (InterruptedException e) {
            // Restores the flag so a thread pool above us can still see the interruption.
            Thread.currentThread().interrupt();
            return Attempt.stopped();
        } catch (Throwable problem) {
            if (isCancellation(problem)) {
                return Attempt.stopped();
            }
            return Attempt.failed(ServerErrors.describe(problem));
        }
    }

    /**
     * True when a throwable is really a cancellation.
     *
     * <p>{@link InterruptedException} and the {@code CancellationException} the toolkit
     * wraps it in are the two ways a cancel reaches a blocking call.</p>
     */
    private static boolean isCancellation(Throwable problem) {
        for (Throwable current = problem; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException
                    || current instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}