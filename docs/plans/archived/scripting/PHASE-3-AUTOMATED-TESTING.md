# Phase 3 — Automated Testing

## Goal
Make scripts useful for repeatable FHIR server tests and validation workflows.

## Scope
1. Add a minimal `test` API with assertions such as `assertTrue`, `assertEquals`, and `fail`.
2. Define structured outcomes: passed, failed, error, and optionally skipped/cancelled.
3. Distinguish assertion failures, compilation errors, runtime exceptions, network failures, and cancellation.
4. Run saved tests in fresh JShell sessions; keep interactive session behavior separate.
5. Add a runner for one script or an ordered set of scripts.
6. Report durations, summary counts, and useful failure details.
7. Export results in a simple format such as JSON or CSV, following existing conventions.
8. Provide synthetic-data examples for a test server or mock.
9. Pin the target server for each run.
10. Keep destructive tests disabled by default; require explicit opt-in/confirmation for writes/deletes.
11. Clean up resources where feasible and isolate runs from previous JShell state.
12. Test assertions, result aggregation, errors, cancellation, and cleanup.

## Illustrative example
```java
var results = fhir.search("Patient", Map.of("_count", "5"));
test.assertTrue(results != null, "Search should return a result");
test.assertTrue(results.size() <= 5, "Result count should be at most five");
```
Adapt signatures to the actual Phase 2 API.

## Acceptance criteria
- Saved tests run independently of interactive history.
- Assertion failures are reported as failed tests.
- Compile/runtime/network errors are distinguished.
- Summary includes total, passed, failed, and error counts.
- Results can be exported.
- UI stays responsive.
- Example tests run against a test server or mock.
- Destructive operations require explicit opt-in.

## Out of scope
No SMART-specific behavior, scheduling, parallel execution, CI service, or advanced report designer.

## Deliverable
Test runner, assertion API, examples, report export, tests, and updated status.
