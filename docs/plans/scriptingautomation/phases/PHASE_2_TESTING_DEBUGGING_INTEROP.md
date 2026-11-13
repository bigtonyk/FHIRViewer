# Phase 2 — Testing, Debugging, and Interoperability

## Goal
Make scripts useful for troubleshooting FHIR integrations and running repeatable checks. Build on Phase 1 and existing validation/logging/REST services.

## Before coding
Verify Phase 1 acceptance criteria. Inspect existing validation, resource display, request execution, and tests. Avoid creating duplicate request or validation engines.

## Scope
### Assertions and test results
- Add script-friendly assertions for equality, expected values, conditions, non-null values, and expected failures.
- Track pass/fail/skip counts and connect failures to named checks or script lines where feasible.
- Separate ordinary console output from test results.
- Define whether failures stop execution or allow remaining checks to run; document the default.

### Test-runner behavior
- Run saved scripts in test mode.
- Show overall status, check counts, elapsed time, and actionable failure details.
- Keep test runs separate from the interactive JShell session.
- Represent failed status internally in a way that can support future automation, without requiring a standalone CLI now.

### Diagnostics
- Improve exception/source-line details, optional stack traces, HTTP status, and OperationOutcome reporting.
- Add structured log levels and filtering/clear-output controls.
- Record script name, target server alias/base URL (never credentials), duration, and outcome.
- Avoid patient payloads and secrets in logs by default.

### Interoperability helpers
- Scriptable inspection of server metadata/CapabilityStatement when supported.
- Search/read resources through existing services, including paging.
- Summarize responses, OperationOutcome, HTTP errors, and validation results.
- Add reference or data-quality diagnostics only where existing application services support them.
- Distinguish local validation findings from server responses.

### REST Workbench integration
Reuse its request-execution and response-interpretation services where appropriate. Do not reproduce the manual request editor. Keep the scripting API independent of Workbench UI layout.

## Tests and acceptance criteria
- Assertions pass/fail accurately and reports show correct counts.
- Configured stop/continue behavior is verified.
- HTTP errors, OperationOutcome, validation findings, and pagination are represented correctly.
- Test reports are distinct from interactive output.
- No secrets appear in logs or reports; Workbench regression tests pass.
- Record tests and status before Phase 3.

## Handoff to Phase 3
Document assertion/report API, execution context, and limitations; identify required additions for parameters, batch processing, and transformations.
