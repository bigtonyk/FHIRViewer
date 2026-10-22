# Phase 1 — Basic Scripting Console and Saved Scripts

## Goal
Add an interactive Java 21 JShell workspace and basic saved `.jsh` file handling. Prove execution, UI responsiveness, and runtime packaging before exposing FHIRViewer services.

## Scope
1. Inspect JavaFX navigation/layout/styles, logging, preferences, file dialogs, build, tests, and packaging.
2. Add a Scripting workspace consistent with the existing UI.
3. Implement a lifecycle-managed service around `jdk.jshell.JShell`.
4. Add a multiline editor and output/diagnostics console.
5. Add Run, best-effort Stop/Cancel, Reset Session, Open, Save/Save As, and Clear Output.
6. Support `.jsh` files and sensible default imports; do not add server APIs yet.
7. Use JShell source analysis to handle complete snippets and incomplete input.
8. Display output, compile diagnostics, runtime exceptions, timestamps, and execution status.
9. Serialize JShell calls on a dedicated worker; never block the JavaFX Application Thread.
10. Close sessions on reset and application shutdown.
11. Show a trust warning before first execution: Java scripts run with the user's permissions and are not sandboxed.
12. Include harmless examples using arithmetic, strings, collections, and method definitions.
13. Establish the distinction between the persistent interactive session and the later fresh-session saved-workflow/test runner; do not implement a full workflow engine in this phase.

## Notes
- Prefer `JShell.builder()` and public `jdk.jshell` APIs.
- Verify output capture with the selected execution engine.
- Be truthful about cancellation limitations.
- If using `module-info.java` or `jlink`, update and test module configuration.
- Avoid third-party editor dependencies unless repository inspection justifies them.
- Opening a script must never execute it automatically.

## Acceptance criteria
- Workspace opens without disrupting existing navigation.
- Expressions, statements, variables, methods, and multi-snippet scripts run.
- Interactive state persists until Reset Session.
- Syntax errors and incomplete input produce useful feedback.
- UI stays responsive during execution.
- `.jsh` Open/Save works and does not auto-run.
- Cleanup works on reset/shutdown.
- Build/tests pass or blockers are documented.
- Packaged runtime includes JShell/compiler modules or a follow-up is documented.

## Out of scope
No FHIRViewer API, server access, test runner, SMART integration, or plugin operations.

## Deliverable
Working interactive console, Open/Save for `.jsh`, tests, harmless sample script, and updated `IMPLEMENTATION-STATUS.md`.
