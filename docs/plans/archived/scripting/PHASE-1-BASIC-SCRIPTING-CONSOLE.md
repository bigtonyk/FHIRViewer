# Phase 1 — Basic Scripting Console

## Goal
Add a basic Java scripting workspace using the embedded Java 21 JShell API. Prove integration and packaging before exposing FHIRViewer services.

## Scope
1. Inspect JavaFX navigation/layout/styles, logging, preferences, file dialogs, build, tests, and packaging.
2. Add a Scripting workspace consistent with the existing UI.
3. Implement a service around `jdk.jshell.JShell`.
4. Add a multiline editor and output/diagnostics console.
5. Add Run, Stop/Cancel where feasible, Reset Session, Open, Save/Save As, and Clear Output.
6. Support `.jsh` files and sensible default imports.
7. Use JShell source analysis to handle multiple snippets and incomplete input.
8. Display output, compile diagnostics, runtime exceptions, timestamps, and execution status.
9. Serialize JShell calls on a dedicated worker; never block the JavaFX Application Thread.
10. Close sessions on reset and shutdown.
11. Show a trust warning before first execution: Java scripts run with the user's permissions and are not sandboxed.
12. Include a harmless example using arithmetic, strings, and method definitions. No server access in this phase.

## Notes
- Prefer `JShell.builder()` and public `jdk.jshell` APIs.
- Verify output capture with the selected execution engine.
- Be truthful about cancellation limitations.
- If using `module-info.java` or `jlink`, update and test module configuration.
- Avoid third-party editor dependencies unless justified by repository inspection.

## Acceptance criteria
- Workspace opens without disrupting existing navigation.
- Expressions, statements, variables, methods, and multi-snippet scripts run.
- State persists until Reset Session.
- Syntax/incomplete input produces useful feedback.
- UI stays responsive.
- `.jsh` Open/Save works.
- Cleanup works on reset/shutdown.
- Build/tests pass or blockers are documented.
- Packaged runtime includes JShell/compiler modules or a follow-up is documented.

## Out of scope
No FHIRViewer API, server access, test runner, SMART integration, or plugin operations.

## Deliverable
Working console, tests, harmless sample script, and updated `IMPLEMENTATION-STATUS.md`.
