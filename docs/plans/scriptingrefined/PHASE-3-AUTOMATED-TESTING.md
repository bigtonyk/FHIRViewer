# Phase 3 — Automated Testing and Repeatable Workflows

## Status — 2026-11-12

**The engine was built, then deleted because nothing could start it.** The execution half of
this plan existed as `service/scripting/testing`: a suite and result model, a sequential runner
that gave each case a fresh `ScriptingSession`, progress callbacks and best-effort
cancel (item 7), a confirmation gate for destructive cases (the mechanism item 10 asks for),
and JSON and HTML report writers (the mechanism item 9 asks for) - 329 lines of tests across
three classes (item 11), using the Phase 2 facades as item 6 intends. Item 5's richer assertions
were never added beyond the basic `TestApi` set.

Nothing in the application ever constructed a `TestSuite`, and the reason is item 2: there was
never a saved-suite format or metadata to build one from. The screen, the per-run server
selection and the export button that items 7, 9 and 10 assume did not exist either. Supplying
them in a defect pass would have meant inventing a suite definition format, which Phase 5 rules
out, so the package and its three test classes were removed in `7724874`. The finding and the
reasoning are R6-7 in `docs/CODE-ANALYSIS-ISSUES-ROUND6.md`; git history keeps the engine for
the day this phase is built properly.

**Still in place:** the `test` assertion facade (`TestApi`), which the console exposes and the
guide documents, and the central write policy that refuses unconfirmed destructive calls.

**What a future implementation still needs:**

1. A saved-suite format and metadata - case names, sources, target server, expected outcome -
   readable and writable beside `.jsh` scripts, with a stable result model (item 2).
2. A suite screen: load and edit a suite, run it with per-case progress, and stop it (items 7
   and 10), with the target server chosen before the run rather than inherited from the console.
3. Destructive cases labelled in the suite and confirmed immediately before execution, with no
   later case running after cancellation.
4. Report export, rewritten: both writers are gone. Outcomes must be classified from the event
   kind rather than by searching message text, and every format must carry the cancelled count -
   those were the two defects the deleted engine had (R6-8 and R6-9).
5. Assertions beyond the basic set for resource existence, HTTP outcomes, validation findings
   and expected failures (item 5), plus synthetic fixtures and examples (item 8).

## Goal
Add a repeatable test-runner model that executes saved scripts or suites independently of interactive JShell history. Support FHIR server verification, assertions, validation checks, and clear results.

## Scope
1. Inspect existing test infrastructure and UI patterns before adding abstractions.
2. Define saved test/workflow metadata and a stable result model: pass, fail, error, skipped, duration, and sanitized details.
3. Run each saved test/workflow in a fresh JShell session, as the console now does for saved scripts; do not inherit variables from the interactive console.
4. Add a `test` facade for named assertions, checks, and grouped results.
5. Support assertions for resource existence, expected fields/values, HTTP outcomes, validation findings, and expected failures where practical.
6. Allow scripts to use Phase 2 APIs for search/read/validation and optionally explicit writes under the central write policy.
7. Add suite execution, sequential by default, progress reporting, stop/cancel best effort, and per-test isolation.
8. Add synthetic test fixtures and examples; never require real patient data.
9. Produce a summary and exportable JSON/HTML report if consistent with current dependencies. Redact credentials and sensitive data.
10. Make target server/environment explicit before run. Clearly label potentially destructive tests and require confirmations for destructive operations.
11. Add mock-based tests for assertion outcomes, isolation, failures, reporting, cancellation, and policy enforcement.

## Initial use cases
- Check that a server advertises expected resource types and interactions.
- Search for a synthetic patient and assert expected results.
- Validate a resource against a loaded profile/IG and assert the expected issues.
- Compare behavior across configured test servers without assuming vendor equivalence.

## Acceptance criteria
- Saved suites run independently from interactive console state.
- Results are deterministic enough to reproduce and clearly identify server/configuration.
- Failures identify the script/test and useful sanitized diagnostics.
- Reports can be exported and contain no tokens or unnecessary patient data.
- Destructive operations are confirmed immediately before execution and cannot proceed after cancellation.
- Tests run without live patient data and do not depend on a real server unless explicitly marked as an integration test.

## Out of scope
SMART-specific APIs (Phase 4), batch transformations at scale, scheduling, CI service, and unattended production execution.

## Deliverable
Test facade/runner, assertion examples, repeatable suite execution, report output, focused tests, and updated status.
