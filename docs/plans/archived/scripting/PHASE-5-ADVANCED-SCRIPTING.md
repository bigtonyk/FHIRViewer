# Phase 5 — Advanced Scripting

## Goal
Add productivity features after the core console, script API, test runner, and SMART integration are stable.

## Prioritized scope

### A. Reusable script collections
- Organize scripts/test suites into folders or collections using existing conventions.
- Add recent scripts, templates, and Save As/duplicate support.
- Never execute scripts merely because they were opened.

### B. Parameters and repeatable runs
- Allow named parameters with types, defaults, and required/optional status.
- Validate parameters before execution.
- Record non-sensitive values with results and redact sensitive values.
- Make selected server/environment explicit for each run.

### C. Better authoring
- Consider syntax highlighting and line numbers after evaluating dependencies, licensing, and platform behavior.
- Add JShell-aware completion/help where feasible.
- Show source locations for diagnostics.
- Provide API help and example browsing.
- Do not promise a full IDE, debugger, or language-server support.

### D. Server/plugin workflows
- Expose approved server-specific operations through existing plugin abstractions.
- Let scripts discover supported operations and report when unavailable.
- Avoid vendor-specific conditionals throughout core scripting code.
- Require checks/confirmation for side effects.

### E. Reporting
- Improve summaries and exports; consider a stable JSON report schema.
- Keep reports useful without implementing a CI service.

### F. Future ideas requiring separate design
Git/version control, batch/concurrent execution, scheduling, CLI runner, and genuine isolation for untrusted scripts. Do not implement automatically.

## Acceptance criteria
- Scripts/suites are easy to organize and rerun.
- Parameterized runs validate inputs and record target environment.
- Editor improvements do not destabilize JavaFX or add unjustified dependencies.
- Plugin operations are discoverable and respect server capabilities.
- Reports avoid leaking credentials or unnecessary sensitive data.
- Packaged builds are tested on supported platforms.

## Deliverable
Prioritized advanced features, tests, documentation, and updated status.
