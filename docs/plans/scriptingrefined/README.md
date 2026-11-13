# FHIRViewer JShell Scripting Implementation Plan

This package defines an incremental implementation plan for Java 21 scripting and automation inside FHIRViewer, using the public `jdk.jshell` API. It supports both an interactive JShell console and saved, repeatable workflows.

## Files
- `MASTER-CONSIDERATIONS.md` — architecture, execution modes, API contract, safety/write policy, packaging, and rules shared by all phases.
- `PHASE-1-BASIC-SCRIPTING-CONSOLE.md` — interactive JShell console and saved `.jsh` files.
- `PHASE-2-FHIRVIEWER-SCRIPTING-API.md` — documented facades for viewer, server, FHIR, validation, and logging.
- `PHASE-3-AUTOMATED-TESTING.md` — repeatable test suites, assertions, reports, and test-server workflows.
- `PHASE-4-SMART-ON-FHIR-INTEGRATION.md` — access to the existing authorized SMART session and context.
- `PHASE-5-ADVANCED-SCRIPTING.md` — parameters, script collections, batch transformations, and plugin workflows.
- `PHASE-6-SCRIPTING-API-DOCUMENTATION.md` — complete user-facing documentation for the actual exposed API.
- `IMPLEMENTATION-STATUS-TEMPLATE.md` — implementation tracking template.

## Cline instructions
1. Copy this folder to `docs/implementation/jshell-scripting/` (or the repository's established planning location).
2. Read `MASTER-CONSIDERATIONS.md` and the current phase file before making changes.
3. Inspect the repository and record the existing Java/Maven/JavaFX architecture and reusable services.
4. Implement **one phase at a time**. Do not silently implement later-phase features to satisfy an earlier phase.
5. Update `IMPLEMENTATION-STATUS.md` with files changed, tests actually run, blockers, security review, and the next recommended step.
6. Review the diff and run relevant tests before moving to the next phase.

## Overall delivery strategy
Start with interactive exploration and a stable facade, then add repeatable tests, SMART context, and finally broader batch automation. Keep the first usable version valuable without requiring scheduling, headless execution, or a full IDE/debugger.
