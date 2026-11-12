package com.spiraleyes.fhirviewer.service.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.spiraleyes.fhirviewer.service.scripting.testing.SuiteRunResult;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestCase;
import com.spiraleyes.fhirviewer.service.scripting.testing.TestSuite;

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
    @DisplayName("A saved-script run neither sees nor leaves interactive state")
    void freshSessionRunsAreRepeatable() throws Exception {
        coordinator = new ScriptingCoordinator();
        assertEquals(ScriptRunResult.Status.COMPLETED,
                runBlocking("int repeatableProbe = 7;", false).status());

        ScriptRunResult blind = runBlocking("repeatableProbe", true);
        assertTrue(hasFailureEntry(blind),
                "a fresh session must not see the interactive declaration: " + blind.entries());

        assertEquals(ScriptRunResult.Status.COMPLETED,
                runBlocking("int freshProbe = 1;", true).status());
        ScriptRunResult second = runBlocking("freshProbe", true);
        assertTrue(hasFailureEntry(second),
                "each saved-script run must start clean: " + second.entries());

        ScriptRunResult interactive = runBlocking("repeatableProbe", false);
        assertEquals(ScriptRunResult.Status.COMPLETED, interactive.status(),
                "the interactive session must be untouched: " + interactive.entries());
        assertTrue(interactive.entries().stream().anyMatch(entry ->
                entry.kind() == ScriptRunResult.Kind.VALUE && entry.text().contains("7")),
                "the interactive declaration should still be there: " + interactive.entries());
    }

    @Test
    @DisplayName("A suite submitted to a closed coordinator is refused with the reason")
    void suiteOnClosedCoordinatorIsRefused() throws Exception {
        coordinator = new ScriptingCoordinator();
        coordinator.close();

        SuiteRunResult result = runSuiteBlocking(new TestSuite("closed.jsh", List.of(
                new TestCase("never", "1 + 1;"))));

        assertEquals(0, result.total());
        assertTrue(result.message().contains("closed"), result.message());
    }

    @Test
    @DisplayName("A suite submitted during another run is refused without disturbing it")
    void suiteDuringARunIsRefused() throws Exception {
        coordinator = new ScriptingCoordinator();
        warmUp();
        CountDownLatch finished = new CountDownLatch(1);
        coordinator.run("Thread.sleep(300000);", result -> finished.countDown());
        long deadline = System.currentTimeMillis() + 10_000;
        while (!coordinator.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(coordinator.isRunning(), "the ordinary run must still be in flight");

        SuiteRunResult refused = runSuiteBlocking(new TestSuite("busy.jsh", List.of(
                new TestCase("never", "1 + 1;"))));

        assertEquals(0, refused.total());
        assertTrue(refused.message().contains("already running"), refused.message());
        coordinator.stop();
        assertTrue(finished.await(60, TimeUnit.SECONDS), "the interrupted run must report");
    }

    @Test
    @DisplayName("Stop between suite cases skips the remaining cases")
    void stopBetweenSuiteCasesSkipsTheRest() throws Exception {
        AtomicInteger created = new AtomicInteger();
        CountDownLatch creatingSecondCase = new CountDownLatch(1);
        CountDownLatch mayCreate = new CountDownLatch(1);
        coordinator = new ScriptingCoordinator(() -> {
            if (created.incrementAndGet() == 2) {
                creatingSecondCase.countDown();
                try {
                    mayCreate.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    // Clear the flag so creating the session below does not run interrupted;
                    // the Stop itself is carried by the coordinator's request flag.
                }
            }
            return new ScriptingSession();
        });
        TestSuite suite = new TestSuite("stopped.jsh", List.of(
                new TestCase("first", "int first = 1;"),
                new TestCase("second", "int second = 2;")));

        AtomicReference<SuiteRunResult> received = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        coordinator.runSuite(suite, null, result -> {
            received.set(result);
            done.countDown();
        });

        assertTrue(creatingSecondCase.await(120, TimeUnit.SECONDS),
                "the suite should get as far as the second case's session");
        coordinator.stop();
        mayCreate.countDown();

        assertTrue(done.await(120, TimeUnit.SECONDS), "the stopped suite must report");
        SuiteRunResult result = received.get();
        assertNotNull(result);
        assertTrue(result.cancelled(), "the suite should report that Stop ended it");
        assertEquals(1, result.total(), "the second case must not run: " + result.cases());
        assertEquals("first", result.cases().get(0).name());
        assertEquals(2, created.get(),
                "the second session is created but discarded once Stop is seen");
    }

    @Test
    @DisplayName("A session that cannot be created reports instead of leaving the suite running")
    void suiteSessionCreationFailureIsReported() throws Exception {
        AtomicInteger created = new AtomicInteger();
        coordinator = new ScriptingCoordinator(() -> {
            if (created.incrementAndGet() == 2) {
                throw new IllegalStateException("no session available");
            }
            return new ScriptingSession();
        });
        TestSuite suite = new TestSuite("broken.jsh", List.of(
                new TestCase("first", "System.out.println(\"kept-output\");"),
                new TestCase("second", "int second = 2;")));

        SuiteRunResult result = runSuiteBlocking(suite);

        assertEquals(1, result.total(), "the case that ran keeps its result");
        assertTrue(result.cases().get(0).entries().stream()
                        .anyMatch(entry -> entry.text().contains("kept-output")),
                "a partial suite must keep what the completed case printed: "
                        + result.cases().get(0).entries());
        assertTrue(result.message().contains("could not finish"), result.message());
        assertTrue(result.message().contains("at case \"second\""),
                "the failure must name the case that could not start: " + result.message());
        assertTrue(result.message().contains("no session available"), result.message());
    }

    private SuiteRunResult runSuiteBlocking(TestSuite suite) throws Exception {
        AtomicReference<SuiteRunResult> received = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        coordinator.runSuite(suite, null, result -> {
            received.set(result);
            done.countDown();
        });
        assertTrue(done.await(120, TimeUnit.SECONDS), "the suite must finish");
        assertNotNull(received.get());
        return received.get();
    }

    private ScriptRunResult runBlocking(String source, boolean freshSession) throws Exception {
        AtomicReference<ScriptRunResult> received = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Consumer<ScriptRunResult> sink = result -> {
            received.set(result);
            done.countDown();
        };
        if (freshSession) {
            coordinator.runInFreshSession(source, null, sink);
        } else {
            coordinator.run(source, sink);
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "the run must finish");
        assertNotNull(received.get());
        return received.get();
    }

    private static boolean hasFailureEntry(ScriptRunResult result) {
        return result.entries().stream().anyMatch(entry ->
                entry.kind() == ScriptRunResult.Kind.DIAGNOSTIC
                        || entry.kind() == ScriptRunResult.Kind.EXCEPTION);
    }

    @Test
    @DisplayName("Suite cases run in fresh sessions and count passes and failures")
    void suiteCasesAreIsolated() throws Exception {
        coordinator = new ScriptingCoordinator();
        TestSuite suite = new TestSuite("checks.jsh", List.of(
                new TestCase("declares", "int suiteProbe = 1;"),
                new TestCase("redeclares", "int suiteProbe = 2;"),
                new TestCase("sees nothing earlier", "suiteProbe"),
                new TestCase("prints", "System.out.println(\"ok\");")));

        AtomicReference<SuiteRunResult> received = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        coordinator.runSuite(suite, null, result -> {
            received.set(result);
            done.countDown();
        });

        assertTrue(done.await(120, TimeUnit.SECONDS), "the suite must finish");
        SuiteRunResult result = received.get();
        assertNotNull(result);
        assertEquals(4, result.total());
        assertEquals(3, result.passedCount(),
                "a case must not inherit the previous case's declarations: " + result.cases());
        assertEquals(1, result.failedCount());
        assertEquals("sees nothing earlier", result.cases().get(2).name());
        assertFalse(result.cases().get(2).passed());
        assertTrue(result.cases().get(2).detail().contains("suiteProbe"),
                "the failure should name the symbol: " + result.cases().get(2).detail());
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
