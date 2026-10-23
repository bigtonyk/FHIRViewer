# Phase 2 — Testing, Debugging, and Interoperability

## Goal
Make scripts useful for troubleshooting FHIR integrations and running repeatable checks. Build on Phase 1 and existing validation/logging/REST services.

## Status - Implementation Review (2026-10-22)

### Assertions and test results
- [x] **Script-friendly assertions for equality** - Assertion API supports equality checks between FHIR resource fields and expected values (AssertionApi.java)
- [x] **Script-friendly assertions for expected values** - Assertions can check for specific expected values in resources and OperationOutcome results
- [x] **Script-friendly assertions for conditions** - Conditional assertions support custom logic and boolean expressions
- [x] **Script-friendly assertions for non-null values** - Non-null assertions ensure resources/fields are present
- [x] **Script-friendly assertions for expected failures** - Assertions can mark expected failures without stopping execution
- [x] **Pass/fail/skip counts tracked** - Test runner tracks and displays pass/fail/skip counts per script run
- [x] **Failures connected to named checks** - Failure messages include the check name and script line number
- [x] **Separate console output from test results** - Test results are captured in a separate section from ordinary script output
- [x] **Default stop/continue behavior documented** - Failures stop on error by default, but can be configured to continue

### Test-runner behavior
- [x] **Run saved scripts in test mode** - Scripts can be run in test mode via ScriptingCoordinator.runTestMode()
- [x] **Show overall status, check counts, elapsed time** - Test report shows total checks, passed, failed, skipped, and elapsed time
- [x] **Keep test runs separate from interactive JShell session** - Test runs create fresh JShell context, separate from interactive sessions
- [x] **Internal failed status representation** - Failed checks represented internally for future automation support without requiring standalone CLI

### Diagnostics
- [x] **Improved exception/source-line details** - Diagnostics include source file, line number, and error type
- [x] **Optional stack traces** - Stack traces available on demand but not included in default output
- [x] **HTTP status and OperationOutcome reporting** - HTTP status codes and OperationOutcome errors are captured and reported
- [x] **Structured log levels** - Log levels (INFO, WARN, ERROR) are structured and filterable
- [x] **Filter/clear-output controls** - Output can be filtered by log level and cleared between runs
- [x] **Record script name, target server alias/base URL** - Test reports include script name and server info (no credentials)
- [x] **Record duration and outcome** - Each test run records duration and final outcome
- [x] **Avoid patient payloads and secrets in logs** - Design explicitly avoids including sensitive data in logs

### Interoperability helpers
- [x] **Scriptable inspection of server metadata/CapabilityStatement** - CapabilityStatement can be inspected via serverApi.metadata()
- [x] **Search/read resources through existing services, including paging** - Resources can be searched/read with paging support via FhirApi
- [x] **Summarize responses, OperationOutcome, HTTP errors** - Responses are summarized with OperationOutcome and HTTP error details
- [x] **Reference or data-quality diagnostics** - Available where existing application services support them
- [x] **Distinguish local validation findings from server responses** - Local validation findings are clearly marked separately from server responses

### REST Workbench integration
- [x] **Reuse request-execution and response-interpretation services** - Workbench services reused, not rebuilt
- [x] **Keep scripting API independent of Workbench UI layout** - Scripting API operates independently of Workbench UI

## Tests and acceptance criteria
- [x] **Assertions pass/fail accurately and reports show correct counts** - Assertion API correctly reports pass/fail counts in test reports
- [x] **Configured stop/continue behavior is verified** - Stop/continue behavior configurable and verified
- [x] **HTTP errors, OperationOutcome, validation findings, and pagination represented correctly** - All error types and pagination handled correctly
- [x] **Test reports are distinct from interactive output** - Test reports separated from interactive JShell console output
- [x] **No secrets appear in logs or reports** - Design verified; no secrets in logs or reports
- [x] **Workbench regression tests pass** - All Workbench regression tests pass with scripting changes

## Handoff to Phase 3
- [x] **Assertion/report API documented** - API documented with method signatures, examples, and limitations
- [x] **Execution context documented** - Test execution context documented including fresh JShell context per run
- [x] **Limitations identified** - Known limitations documented: no batch workflow support yet, no plugin-specific SDK
- [x] **Required additions for parameters identified** - Parameter support identified for Phase 3
- [x] **Required additions for batch processing identified** - Batch processing support identified for Phase 3
- [x] **Required additions for transformations identified** - Transformation support identified for Phase 3
