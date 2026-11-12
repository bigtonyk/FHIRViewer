# FHIRViewer — Code Analysis, Ninth Pass

Date: 2026-11-12
Continues the first eight passes (`CODE-ANALYSIS-ISSUES.md` through
`CODE-ANALYSIS-ISSUES-ROUND8.md`). The eighth pass fixed the suite-failure rendering and its
follow-up added a UI test through an injected coordinator; this pass reviews the suite runner,
the console's suite UI, the new test seam and the documentation that describes them.

Scope: `service/scripting/testing` (`TestSuite`, `TestCase`, `SuiteCaseResult`,
`SuiteRunResult`), `ScriptingCoordinator.runSuite` and its stop/close paths, the suite half of
`ui/ScriptingConsoleDialog` (including the package-private coordinator constructor), and the
suite tests (`TestSuiteTest`, `SuiteCaseResultTest`, `ScriptingCoordinatorTest`,
`ScriptingConsoleSmokeTest`) plus `docs/scripting/Scripting-Guide.md` and the automation status.

Method: static reading of every file above and of the status documents, then targeted and full
test runs. No JavaFX window was opened by hand; the smoke tests drive real controls on the
toolkit themselves.

Status (2026-11-12): six findings, all fixed; one lifetime race recorded as a limitation. Full
suite green at 1,052 tests.

## Priority legend

| Priority | Meaning |
|----------|---------|
| **P0** | Data loss, security exposure, or a crash in ordinary use. None found. |
| **P1** | A feature that does not work as presented, or a defect users hit in normal use. None found. |
| **P2** | Robustness or maintainability problem that costs work, or a user-visible defect in a secondary screen. |
| **P3** | Hygiene, naming, consistency, stale documentation. |

IDs are stable: `R9-*` are new here.

## 1. What was checked and found sound

- **The test seam changes nothing in production.** The public constructor still builds its own
  coordinator; only a package-private constructor accepts one, and its javadoc now says the
  dialog closes whatever it is given. No test-only branch exists in the run path.
- **The failing factory exercises the real path.** `FailingScriptingSessions` succeeds once and
  then throws, so `runSuite` reaches its catch-all after a real case has run and been recorded;
  the test asserts through the actual Run Tests button, not a direct call to `presentSuite`.
- **Isolation and stop semantics are unchanged and covered**: cases use fresh sessions, a stop
  between cases discards the just-created session, and a suite and an ordinary run cannot
  interleave.
- **The parser refuses what it cannot represent** (nameless markers, text before the first
  marker, blank buffers) and keeps names and bodies in file order.
- **The three UI surfaces stay separate**: verdicts in the report line, case output and full
  diagnostics in Test details, ordinary output untouched.
- **The new smoke harness cleans up after itself**: both suite tests now close their coordinator,
  and the helper thread is interrupted on every path.

## 2. Findings

### R9-1. The guide still said the viewer had no suite runner

**Bug (documentation) - P2. Fixed 2026-11-12.** The "Scripting automation" section ended with
"There is no suite runner in the viewer: run a workflow by opening its script and pressing Run."
The same guide's "Suites" section, the Run Tests button and the runner tests all contradict it.
A reader following the automation section would never find the feature.

Fixed by naming both actions: Run executes the buffer as one script, Run Tests executes its
`// case:` suites.

### R9-2. A case that could not start was not named in the failure message

**Bug - P3. Fixed 2026-11-12.** When a case's session could not be created, the report said
"The suite could not finish: IllegalStateException: ..." with the factory's message. The case
name was lost, and the details tab lists only completed cases, so the reader could not tell
which case never started.

Fixed by recording the case being started and including it in the message:
`The suite could not finish at case "second": IllegalStateException: ...`.
`ScriptingCoordinatorTest.suiteSessionCreationFailureIsReported` asserts the name, and the
injected-factory smoke test asserts the full sentence in the report.

### R9-3. The automation status snapshot contradicted the tree

**Bug (documentation) - P3. Fixed 2026-11-12.** The status file's header still described the code
at `24d8f6d`, its summary said the facades were "covered by 85 tests", and the foundation row
still listed the dialog at 473 lines without Run Tests. The lower test rows had been updated,
so the file disagreed with itself.

Fixed by pointing the snapshot at `3e58901`, correcting the test count to 1,052, adding the Test
details tab and Run Tests to the summary, and refreshing the dialog and coordinator line counts.

### R9-4. The suite smoke tests left their coordinators open

**Bug (test hygiene) - P3. Fixed 2026-11-12.** Each suite test built a `ScriptingCoordinator`
and never closed it. Every coordinator registers a JVM shutdown hook and owns a single-thread
executor; `close()` removes the hook and drains the executor. The test JVM therefore accumulated
hooks, and a timeout left the suite running in the background into later test classes.

Fixed by wrapping both suite tests in try-with-resources, so the coordinator is closed on
success and on failure.

### R9-5. `SuiteRunResult` said a message means no cases ran

**Bug (documentation) - P3. Fixed 2026-11-12.** The class javadoc and the `message` parameter
said the message explains "a run that produced no cases at all" and is empty otherwise. Since
round 8 a partial run carries both completed cases and a message, so the model's own
documentation disagreed with its behaviour.

Fixed to say the message explains a run that did not execute every case, including a session
that could not be created.

### R9-6. The injected coordinator's ownership was undocumented

**Bug (documentation) - P3. Fixed 2026-11-12.** The package-private constructor accepts a
coordinator built elsewhere, while `setOnHidden` closes it. A future test that shared one
coordinator between two dialogs would have the first hidden dialog close it underneath the
second.

Fixed by stating in the constructor's javadoc that the dialog closes the coordinator when
hidden.

## 3. Limitations and follow-ups

- **Close during a suite's between-case window.** `close()` stopped the case session that was
  armed at that moment and interrupted the worker, but the suite loop did not re-check `closed`
  before creating the next case. If the window between one case ending and the next session
  being created happened to contain the close, one more case could start and run before the
  interrupt was seen.

  **Fixed after this pass (2026-11-12).** The loop now stops before creating a case once the
  coordinator is closed, `sessionForRun` checks `closed` under the same lock that arms a run
  and discards a session created just before the close, and `close()` reads the armed session
  under that lock. `ScriptingCoordinatorTest.closeBetweenSuiteCasesStartsNothing` closes from
  another thread while the worker sits in the second session factory, then hands the factory a
  real session; it asserts one case ran and the created session was discarded. Removing the
  `closed` check from `sessionForRun` makes it fail with `cancelled` false.
- **Case-relative line numbers** remain as recorded in round 8.
- **No per-assertion counts, skip verdicts or report export**; still the Phase 2 gap.

## 4. Verification

| Check | Result |
|---|---|
| `ScriptingCoordinatorTest` | 11 tests, 0 failures/errors/skips |
| `TestSuiteTest` | 5 tests |
| `SuiteCaseResultTest` | 3 tests |
| `ScriptingConsoleSmokeTest` | 3 tests |
| Full suite | 1,052 run, 0 failures, 0 errors, 0 skipped, exit 0 |

Red check for R9-2, run after the first version of this document (which asserted it before it
had been run): restoring the old `The suite could not finish: …` message failed exactly two
tests. `ScriptingCoordinatorTest.suiteSessionCreationFailureIsReported` failed on "the failure
must name the case that could not start: The suite could not finish: IllegalStateException: no
session available", and `ScriptingConsoleSmokeTest.failedSuiteKeepsReasonCountsAndDetails`
failed on "the report must keep the failure reason and name the case: The suite could not
finish: IllegalStateException: no session for the second case". Restoring the fix turned both
green. The full suite row above was run before this temporary revert; the source was restored to
the same code and the two affected tests were rerun green. The guide and status fixes were
checked by reading the affected sections after editing.
