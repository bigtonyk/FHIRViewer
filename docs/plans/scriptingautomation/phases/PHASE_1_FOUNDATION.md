# Phase 1 — Foundation

## Goal
Deliver a reliable Java 21 JShell foundation integrated with FHIRViewer: interactive exploration, saved `.jsh` scripts, and a documented initial API. Preserve the REST Workbench and reuse its underlying services.

## Before coding
1. Inspect the repository, build/packaging configuration, existing scripting code, and any previous plans.
2. Classify each requirement as implemented, partial, missing, or blocked.
3. Inspect how the REST Workbench executes requests and obtains server/authentication settings.
4. Preserve working code; merge requirements instead of duplicating completed work.

## Scope
### JShell runtime and console
- Use Java 21 `jdk.jshell`, compatible with the build and packaging.
- Establish session lifecycle, imports/classpath handling, shutdown, and documented interactive state behavior.
- Show input, output, compilation errors, and runtime exceptions clearly.
- Keep server calls and long-running work off the JavaFX Application Thread.
- Support clear output and restart/reset where feasible; do not promise cancellation unless it works reliably.

### Saved scripts
- Open, save, and run `.jsh` files using normal file dialogs.
- Prefer a fresh JShell context for each saved-script run, independent of the interactive session, unless the user explicitly chooses otherwise.
- Preserve source file/line details in diagnostics where feasible.

### Initial API
Expose narrow facades over existing services; final names follow repository conventions:
- `fhir`: use existing FHIR context/parser/resource support.
- `server`: inspect configured/active server and perform supported search/read operations through shared client/request services.
- `validation`: invoke existing validation services where available.
- `log`: send script messages to a scripting output/log view.
- `viewer`: publish messages/results through application-level APIs, not JavaFX controls.
Do not build a second HTTP/authentication stack. If the Workbench has no reusable service boundary, extract one minimally and add regression tests.

### Discoverability and safety
- Provide in-console help or equivalent API discovery.
- Document objects, methods, types, return values, examples, and unsupported methods.
- Do not expose tokens, secrets, passwords, or authorization headers.
- Keep write operations disabled or protected by an enforced confirmation policy; do not expose unrestricted destructive operations.
- Clearly state JShell is not a sandbox.

## Out of scope
Full assertion/test runner (Phase 2), batch workflows and transformations (Phase 3), plugin scripting SDK and script sharing ecosystem (Phase 4), rebuilding the REST Workbench, or direct JavaFX-control access.

## Tests and acceptance criteria
- Java 21 build and packaging succeed.
- JShell evaluates expressions/statements, reports compile/runtime errors, and shuts down cleanly.
- Interactive session state behaves as documented.
- Saved `.jsh` files can be opened, saved, and run in the documented context.
- Failures do not crash the app; diagnostics are useful.
- API calls use configured server and existing request/authentication services.
- UI stays responsive during server calls; secrets are not logged.
- Existing REST Workbench behavior passes regression tests.

Phase 1 is complete when the above criteria pass and results are recorded in `IMPLEMENTATION_STATUS.md`.

## Handoff to Phase 2
Record API limitations, error-reporting gaps, and extension points for assertions, test reports, and execution history. Do not start Phase 2 until Phase 1 passes or the user explicitly approves deferrals.
