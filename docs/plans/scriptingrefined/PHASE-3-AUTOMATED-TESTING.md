# Phase 3 — Automated Testing and Repeatable Workflows

## Goal
Add a repeatable test-runner model that executes saved scripts or suites independently of interactive JShell history. Support FHIR server verification, assertions, validation checks, and clear results.

## Scope
1. Inspect existing test infrastructure and UI patterns before adding abstractions.
2. Define saved test/workflow metadata and a stable result model: pass, fail, error, skipped, duration, and sanitized details.
3. Run each saved test/workflow in a fresh JShell session by default; do not inherit variables from the interactive console.
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
