# Phase 5 — Advanced Scripting, Parameters, and Batch Workflows

## Goal
Build on the console, API, test runner, and SMART support to make scripts reusable for troubleshooting, repeatable integration testing, and batch resource transformations. Deliver incrementally; do not attempt all advanced features at once.

## Recommended implementation order

### A. Saved script library and workflow metadata
- Organize scripts/suites into folders or collections using existing application conventions.
- Add recent scripts, templates, duplicate/Save As, and optional descriptive metadata.
- Never execute scripts because they were opened, selected, imported, or loaded.
- Distinguish interactive `.jsh` files from saved workflows/test suites in the UI without inventing a new scripting language.

### B. Parameters and repeatable execution
- Support named parameters with types, defaults, required/optional status, and basic validation.
- Make target server/environment explicit for each run; display it before execution.
- Record non-sensitive parameter values and redact sensitive parameters.
- Provide a fresh JShell session for each saved workflow run by default.

### C. Batch processing and transformations
- Support sequential processing of a search result set or explicit resource list first.
- Provide progress, counts, per-item outcomes, error policy (stop on first error or continue), and a final summary.
- Support transformations using existing HAPI FHIR resources and APIs; preview changed resources before writes where practical.
- Keep concurrency bounded and off by default. Do not implement concurrent writes until ordering, rate limits, and server behavior are designed and tested.
- Apply write policy to every operation. Confirm destructive operations with a clear target/impact summary immediately before execution; if confirmation is unavailable, fail closed.
- Avoid loading unbounded result sets into memory; respect pagination and provide cancellation where feasible.

### D. Better authoring
- Consider syntax highlighting and line numbers only after evaluating dependencies, licensing, accessibility, and platform behavior.
- Add JShell-aware completion/help where feasible.
- Show source locations for diagnostics and provide API help/examples.
- Do not promise a full IDE, debugger, or language-server support.

### E. Server and plugin workflows
- Expose approved server-specific operations through existing plugin abstractions and only when available.
- Let scripts discover supported operations and report unavailable capabilities clearly.
- Avoid vendor-specific conditionals throughout core scripting code.
- Require explicit side-effect policy and confirmation for destructive plugin operations.

### F. Reporting and sharing
- Stabilize a JSON result schema and improve readable report exports.
- Make scripts and workflows easy to export/import as source plus metadata; imported scripts remain untrusted and never auto-run.
- Git/version control, scheduling, standalone CLI execution, and genuine isolation for untrusted scripts require separate design approval.

## Acceptance criteria
- Scripts and suites are easy to organize and rerun.
- Parameters are validated before execution and target environment is visible.
- Batch runs show progress and per-item outcomes and handle partial failure predictably.
- Batch processing respects pagination, cancellation limitations, and bounded resource usage.
- Writes and destructive operations consistently pass through the central policy service.
- Plugin operations are discoverable and respect server capabilities.
- Reports avoid leaking credentials or unnecessary sensitive data.
- Packaged builds are tested on supported platforms.

## Deliverable
Prioritized advanced features implemented incrementally, examples/tests, updated docs/status, and a list of deferred ideas.
