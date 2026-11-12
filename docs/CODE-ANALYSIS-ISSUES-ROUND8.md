# FHIRViewer — Code Analysis, Eighth Pass

Date: 2026-11-12
Continues `CODE-ANALYSIS-ISSUES.md`, `CODE-ANALYSIS-ISSUES-ROUND2.md`,
`CODE-ANALYSIS-ISSUES-ROUND3.md`, `CODE-ANALYSIS-ISSUES-ROUND4.md`,
`CODE-ANALYSIS-ISSUES-ROUND5.md`, `CODE-ANALYSIS-ISSUES-ROUND6.md` and
`CODE-ANALYSIS-ISSUES-ROUND7.md`. The seventh pass covered the pretty-view package; this pass
follows the suite test-runner work that landed after it.

Scope: the Phase 2 slice - `service/scripting/testing` (`TestCase`, `TestSuite`,
`SuiteCaseResult`, `SuiteRunResult`), the suite half of `service/scripting/ScriptingCoordinator`,
the suite UI in `ui/ScriptingConsoleDialog` (Run Tests button, report line, Output / Test details
tabs), and the tests that pin them (`TestSuiteTest`, `SuiteCaseResultTest`,
`ScriptingCoordinatorTest`, `ScriptingConsoleSmokeTest`).

Method: static reading of every file above, plus targeted test runs to confirm each finding.
No JavaFX window was opened during this pass; the one UI path that cannot be reached without a
session-creation failure (R8-1) was verified through the coordinator test rather than visually.

Status (2026-11-12): all four findings are fixed; the partial-suite rendering now has a UI test
that drives the real Run Tests button. The full suite is green at 1,052 tests.

## Priority legend

| Priority | Meaning |
|----------|---------|
| **P0** | Data loss, security exposure, or a crash in ordinary use. None found. |
| **P1** | A feature that does not work as presented, or a defect users hit in normal use. None found. |
| **P2** | Robustness or maintainability problem that costs work, or a user-visible defect in a secondary screen. |
| **P3** | Hygiene, naming, consistency, stale documentation. |

IDs are stable: `R8-*` are new here.

## 1. What was checked and found sound

- **Isolation is real.** `runSuite` creates a session through `sessionForRun(true)`, the same
  arming path saved scripts use, and closes it in a `finally`. A case cannot see the previous
  case's declarations; the interactive session is never touched. The coordinator test with two
  cases redeclaring the same name and a third reading it pins this.
- **Every result is delivered.** The worker body has a top-level `catch (RuntimeException |
  Error)` and reports from a `finally`, so a failed session creation, a stop or a throw cannot
  leave the console saying "Running" forever. The `onDone` callback always runs on the worker,
  matching the ordinary Run contract.
- **Stop is consistent with Run.** `stop()` interrupts whichever session is armed; between
  cases the request flag makes `sessionForRun` close the just-created session and return null,
  so no orphan session survives and remaining cases are skipped. Covered by the blocking-factory
  test.
- **The three surfaces are genuinely separate.** Counts live in `suiteReport`; per-case output
  and full diagnostics live in the Test details tab; the Output tab is never written by the
  runner. The smoke test asserts the Output tab contains neither `FAILED` nor `passed` and that
  the details tab contains both cases' prints and the full assertion text.
- **The parser refuses ambiguity.** Text before the first marker is an error rather than a
  shared preamble, and a blank buffer is refused. Markers are case-insensitive and tolerant of
  surrounding whitespace.
- **No I/O, threads or FHIR access were added** to the testing package; it holds no secrets, and
  case names are display text only.

## 2. Findings

### R8-1. A suite that ended with an error hid its counts and all case details

**Bug - P2. Fixed 2026-11-12.** `presentSuite` returned as soon as `result.message()` was
non-blank. That message is produced when a session cannot be created part-way through a suite,
after earlier cases have already run. The report line then showed only
"The suite could not finish: ...", the pass/fail counts disappeared, and `presentSuiteDetails`
was never called, so the Test details tab stayed empty (or held the previous run's text). The
completed cases' output was therefore unreachable even though `SuiteCaseResult` had retained it.

Fixed by always building the counts line, prepending the message when present, and always filling
the details tab. `ScriptingCoordinatorTest.suiteSessionCreationFailureIsReported` now has its
first case print `kept-output` and asserts the entry survives in the result.

The dialog gap itself is now covered: `ScriptingConsoleDialog` has a package-private constructor
that takes its coordinator, and the test-only `FailingScriptingSessions` helper builds a
coordinator whose session factory succeeds once and then throws. The smoke test
`failedSuiteKeepsReasonCountsAndDetails` presses the real Run Tests button against it and asserts
that the report keeps the reason and `1 passed, 0 failed (1 case(s)`, that the details tab keeps
`case: passes — PASSED` with `kept-case-output`, that the case that never started contributes
nothing, and that the output pane stays empty. Reinstating the early return makes that test fail
on the counts assertion.

### R8-2. A `// case:` marker without a name was folded into the previous case

**Bug - P2. Fixed 2026-11-12.** The marker pattern requires a name, so a bare `// case:` line did
not match. It fell through to the body, where it is a legal Java comment: the rest of the file
silently became part of the previous case, and results named the wrong case. Only a bare marker
before the first case was refused, and with a misleading "text before the first marker" message.
The class javadoc already claimed a nameless marker was refused.

Fixed with a `NAMELESS_MARKER` check that raises "The // case: marker on line N needs a name."
`TestSuiteTest.namelessMarkerIsRefused` pins it.

### R8-3. A parse refusal or cancelled trust prompt left the previous run's results on screen

**Bug - P3. Fixed 2026-11-12.** `onRunTests` parsed before clearing anything. A parse error
replaced the report line but left the previous Test details text, and cancelling the trust
prompt left both the old report and old details looking current. A user could read last run's
verdicts as this run's.

Fixed by clearing the details and hiding the report line before parsing, and by keeping the
report hidden if trust is cancelled. No separate test: the path only shows through the dialog's
own state, and the smoke test's successful path would mask a regression here.

### R8-4. The Run Tests tooltip still described the pre-details layout

**Bug - P3. Fixed 2026-11-12.** The tooltip said the counts "appear above the output pane",
which stopped being the whole story when the Test details tab was added.

Fixed to name both places: counts above the panes, per-case output in the Test details tab.

## 3. Limitations recorded, not fixed

- **Case-relative line numbers.** A case's source is evaluated without its `// case:` marker, so
  a JShell diagnostic's line number is relative to that case, not to the editor buffer. Showing
  editor line numbers would require tracking each case's start offset through the run model.
- **No per-assertion counts, skip verdicts or report export.** The runner counts cases; the
  phase's reporting criteria remain open in `docs/plans/scriptingautomation/IMPLEMENTATION_STATUS.md`.
- **A marker-looking line inside a Java text block is treated as a marker.** The format is
  line-based by design; documenting the marker syntax is the mitigation.

## 4. Verification

| Check | Result |
|---|---|
| `ScriptingCoordinatorTest` | 11 tests, 0 failures/errors/skips |
| `TestSuiteTest` | 5 tests |
| `SuiteCaseResultTest` | 3 tests |
| `ScriptingConsoleSmokeTest` | 3 tests (the class is skipped automatically when headless) |
| Full suite | 1,052 run, 0 failures, 0 errors, 0 skipped, exit 0 |

Red checks used during the pass: discarding `SuiteCaseResult.entries` failed the smoke test's
details assertions; disabling the between-case stop check failed
`stopBetweenSuiteCasesSkipsTheRest`; the nameless-marker test fails against the old parser; and
the old early return in `presentSuite` fails `failedSuiteKeepsReasonCountsAndDetails` on the
missing counts.
