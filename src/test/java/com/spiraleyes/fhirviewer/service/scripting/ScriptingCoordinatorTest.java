package com.spiraleyes.fhirviewer.service.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ScriptingCoordinator} threading and lifecycle without starting
 * the JavaFX toolkit. Callbacks here arrive on the coordinator's worker thread,
 * the way a dialog wrapped them in {@code Platform.runLater}.
 */
class ScriptingCoordinatorTest {

    private ScriptingCoordinator coordinator;

    @AfterEach
    void closeCoordinator() {
        coordinator.close();
    }

    /**
     * Starts the session before a timing test begins. Without this the test's wait also covers
     * {@code JShell.create}, which under a loaded full suite can take longer than the wait - so
     * the test would time out on engine start-up rather than on the Stop it is about.
     */
    private void warmUp() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        coordinator.reset(ready::countDown);
        assertTrue(ready.await(60, TimeUnit.SECONDS), "the session did not start");
    }

    @Test
    @DisplayName("Runs execute on the worker thread and deliver a result")
    void runsExecuteAndDeliverAResult() throws Exception {
        coordinator = new ScriptingCoordinator();
        AtomicReference<ScriptRunResult> received = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        coordinator.run("40 + 2", result -> {
            received.set(result);
            done.countDown();
        });

        assertTrue(done.await(30, TimeUnit.SECONDS), "the run must complete on the worker");
        assertNotNull(received.get());
        assertEquals(ScriptRunResult.Status.COMPLETED, received.get().status());
    }

    @Test
    @DisplayName("A second run while one is in flight is refused immediately")
    void concurrentRunIsRefused() throws Exception {
        coordinator = new ScriptingCoordinator();
        warmUp();
        AtomicReference<ScriptRunResult> first = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        // Five minutes rather than thirty seconds. With a sleep as long as the wait below, a
        // passing run proved nothing about Stop: the snippet could have finished on its own
        // at the very instant the await expired, which is how this test failed once under a
        // loaded suite. At five minutes the only thing that can end the run inside the
        // timeout is stop().
        coordinator.run("Thread.sleep(300000);", result -> {
            first.set(result);
            finished.countDown();
        });
        long deadline = System.currentTimeMillis() + 10_000;
        while (!coordinator.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(coordinator.isRunning(), "the first run must still be in flight");

        AtomicReference<ScriptRunResult> second = new AtomicReference<>();
        coordinator.run("1 + 1", second::set);
        assertNotNull(second.get(), "a refused run still gets a result, synchronously");
        assertEquals(ScriptRunResult.Status.FAILED, second.get().status());
        assertTrue(second.get().first(ScriptRunResult.Kind.ERROR).text().contains("already running"),
                "the refusal must say a script is already running: " + second.get().entries());

        long stoppedAt = System.nanoTime();
        coordinator.stop();
        assertTrue(finished.await(30, TimeUnit.SECONDS),
                "stop must end the first run, which otherwise sleeps for five minutes");
        long takenMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stoppedAt);
        assertTrue(takenMillis < TimeUnit.SECONDS.toMillis(10),
                "stop took " + takenMillis + " ms to end a run with five minutes to go, so "
                        + "something other than stop ended it");
        assertEquals(ScriptRunResult.Status.STOPPED, first.get().status());
    }

    @Test
    @DisplayName("A Stop that arrives while the run is still starting ends it anyway")
    void stopDuringStartUpIsHonoured() throws Exception {
        coordinator = new ScriptingCoordinator();
        warmUp();

        // No wait for isRunning() here: Run returns, Stop is pressed immediately, which is
        // the window in which the worker used to lose it - it had marked the run started,
        // no session existed to interrupt yet, and the snippet then slept for five minutes.
        for (int attempt = 0; attempt < 10; attempt++) {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<ScriptRunResult> result = new AtomicReference<>();
            coordinator.run("Thread.sleep(300000);", stopped -> {
                result.set(stopped);
                finished.countDown();
            });
            coordinator.stop();

            assertTrue(finished.await(20, TimeUnit.SECONDS),
                    "attempt " + attempt + ": Stop did not end a run with five minutes to go");
            assertEquals(ScriptRunResult.Status.STOPPED, result.get().status(),
                    "attempt " + attempt + ": the run must report that it was stopped");
        }

        // A Stop consumed by one run must not end the next one.
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<ScriptRunResult> after = new AtomicReference<>();
        coordinator.run("1 + 1", result -> {
            after.set(result);
            ran.countDown();
        });
        assertTrue(ran.await(30, TimeUnit.SECONDS), "the run after a Stop must still finish");
        assertEquals(ScriptRunResult.Status.COMPLETED, after.get().status(),
                "a Stop that ended an earlier run must not end the next one");
    }

    @Test
    @DisplayName("Reset between runs starts a fresh session")
    void resetClearsStateBetweenRuns() throws Exception {
        coordinator = new ScriptingCoordinator();
        CountDownLatch ran = new CountDownLatch(1);
        coordinator.run("int named = 3;", ignored -> ran.countDown());
        assertTrue(ran.await(30, TimeUnit.SECONDS));

        CountDownLatch resetDone = new CountDownLatch(1);
        coordinator.reset(resetDone::countDown);
        assertTrue(resetDone.await(30, TimeUnit.SECONDS), "reset must complete");

        AtomicReference<ScriptRunResult> after = new AtomicReference<>();
        CountDownLatch ranAfter = new CountDownLatch(1);
        coordinator.run("named", result -> {
            after.set(result);
            ranAfter.countDown();
        });
        assertTrue(ranAfter.await(30, TimeUnit.SECONDS));
        assertEquals(ScriptRunResult.Status.COMPLETED_WITH_ERRORS, after.get().status(),
                "the variable from the old session must be forgotten");
    }

    @Test
    @DisplayName("Close is idempotent and later runs fail clearly")
    void closeIsIdempotentAndStopsFurtherRuns() {
        coordinator = new ScriptingCoordinator();
        coordinator.close();
        coordinator.close();

        AtomicReference<ScriptRunResult> after = new AtomicReference<>();
        coordinator.run("1 + 1", after::set);
        assertNotNull(after.get());
        assertEquals(ScriptRunResult.Status.FAILED, after.get().status());
        assertTrue(after.get().first(ScriptRunResult.Kind.ERROR).text().contains("closed"),
                "the failure must say the session is closed: " + after.get().entries());
        assertFalse(coordinator.isRunning());
    }
}
