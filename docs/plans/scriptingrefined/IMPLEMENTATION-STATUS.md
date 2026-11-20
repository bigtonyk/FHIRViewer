# FHIRViewer JShell Scripting — Implementation Status

## Repository assessment
- Java version: 21
- Maven/build command: .\mvnw.cmd
- JavaFX/navigation approach: JavaFX Dialog workspace (ScriptingConsoleDialog), consistent with RestConsoleDialog
- Module/runtime packaging: jpackage profile `dist`; JShell/compiler modules added to `--add-modules`
- Existing FHIR services reused: FhirService, FhirServerService, FhirServerManager
- Existing validation/FHIRPath services reused: FhirService validation APIs via ValidationApi
- Existing plugin abstractions reused: server plugin operations via existing server service
- Existing SMART implementation/status: Phase 4 complete (SmartApi)
- Existing write/confirmation patterns: WritePolicy central policy; destructive ops require confirmation
- Documentation conventions: docs/plans/scriptingrefined; user guide docs/scripting/Scripting-Guide.md

## Phase status

| Phase | Status | Notes |
|---|---|---|
| 1. Interactive console and saved `.jsh` files | Complete | Coordinator race fixed; unit tests pass; packaging module list updated |
| 2. FHIRViewer scripting API and write policy | Complete | fhir/viewer/validation/server/log facades; console write access is an explicit, non-persistent opt-in |
| 3. Automated testing and repeatable workflows | In progress | `service/scripting/testing` parses `// case:` suites and an optional `// cleanup:` block; `ScriptingCoordinator.runSuite` runs each case sequentially in a fresh `ScriptingSession` and then runs cleanup in another fresh session, even after a failed or stopped case (stop still skips remaining cases). `SuiteRunResult` reports passed, failed, stopped and cleanup verdicts, with each case's output and full diagnostics in Test details. Suites have a separate **Allow writes in test runs** opt-in, so an interactive write grant never leaks into Run Tests. The earlier engine was deleted in `7724874` because nothing could start it (R6-7 in `docs/CODE-ANALYSIS-ISSUES-ROUND6.md`). Still to build: per-check counts and names, skip/error verdicts, a suite screen, explicit target-server selection, progress reporting and report export. |
| 4. SMART on FHIR integration | Complete | SmartApi |
| 5. Advanced scripting and batch workflows | In progress | ScriptParameter and partial collections/reporting |
| 6. Scripting API documentation | Complete | docs/scripting/Scripting-Guide.md |

## Execution model decisions
- Interactive JShell session lifecycle: ScriptingCoordinator owns one interactive ScriptingSession; created lazily, reset closes/recreates, close on dialog hide/shutdown.
- Saved workflow session behavior: a buffer with a file behind it (opened or saved) runs in a fresh `ScriptingSession` created for that run and closed when it ends, so saved scripts are repeatable and leave nothing behind; the unsaved editor keeps the interactive session, which Reset Session replaces. There is still no workflow runner that starts a saved script on its own.
- Script storage/metadata approach: `.jsh` files via FileChooser; ScriptParameter metadata for workflows.
- Target server/environment selection: ScriptBindings binds active server; explicit target shown.
- Parameter handling: ScriptParameter typed/required/optional with defaults and redaction.
- Stop/cancel limitations: best-effort interrupt; arbitrary code may not stop.

## Write policy and security review
- Create/update/patch policy: routed through WritePolicy; the Scripting Console enables it only through the **Allow server writes** opt-in, which requires an active writable server and names that server and the current scope in its warning.
- Write scope: **Write scope...** limits a grant by operation and resource type; `WritePolicy` enforces it for every call, including old `fhir` references. A server marked **Read-only server** in Tools > FHIR Servers refuses both UI and script writes.
- Test-run writes: separate **Allow writes in test runs** opt-in, off by default; suites and their cleanup blocks run read-only unless explicitly allowed.
- Write opt-in lifetime: off when the console opens and reset when a file or the bundled example replaces the buffer; it is not persisted.
- Destructive operations covered: DELETE requires confirmation. Bulk destructive operations and destructive bundles are not exposed by this API.
- Confirmation dialog location and impact details: central WritePolicy/confirmation in FhirApi; the console marshals the prompt to the JavaFX thread and includes the server name/base URL and resource id.
- Confirmation cancellation test: covered by FhirApiTest (policy and handler cancellation) and ScriptingConsoleSmokeTest (cancelled delete sends no request).
- Bundle/batch destructive-operation handling: batch writes use the same FhirApi gate; no bulk destructive operation is exposed.
- Secret/resource redaction: LogApi redacts; ScriptParameter redacts sensitive values.
- Script trust warning: shown before first execution and after opening files.
- Known limitations (JShell is not a sandbox): scripts run with user permissions.

## Latest work
- Date: 2026-11-16
- Phase: 2 (console write access)
- Summary: Added operation/resource scopes and a persisted per-server read-only flag; a separate test-suite write opt-in with an optional `// cleanup:` block; a Cancel-default delete prompt; **Review Unknown** for timed-out writes; and journal-backed best-effort **Undo Last Write** using in-memory before-images. Undo is a separate confirmed recovery write outside the script scope; it is disabled when writes are off, failed reverts are journaled, and the service itself also refuses writes to a read-only server. Focused UI/fake-server tests cover scope narrowing, read-only refusal, suite isolation, actual undo execution and reconciliation. The existing live grant, body-free journal, 50-write budget, idle expiry, version-aware writes and trust prompts remain.
- Files changed:
  - src/main/java/com/spiraleyes/fhirviewer/service/scripting/api/FhirApi.java
  - src/main/java/com/spiraleyes/fhirviewer/service/scripting/api/WriteJournal.java
  - src/main/java/com/spiraleyes/fhirviewer/service/scripting/api/WriteOutcomeUnknownException.java
  - src/main/java/com/spiraleyes/fhirviewer/service/scripting/api/ScriptBindings.java
  - src/main/java/com/spiraleyes/fhirviewer/service/scripting/api/WritePolicy.java
  - src/main/java/com/spiraleyes/fhirviewer/server/StandardFhirRestPlugin.java
  - src/main/java/com/spiraleyes/fhirviewer/ui/ScriptingConsoleDialog.java
  - src/test/java/com/spiraleyes/fhirviewer/service/scripting/api/FhirApiTest.java
  - src/test/java/com/spiraleyes/fhirviewer/service/scripting/api/WriteJournalTest.java
  - src/test/java/com/spiraleyes/fhirviewer/service/scripting/api/ScriptBindingsInjectionTest.java
  - src/test/java/com/spiraleyes/fhirviewer/service/scripting/api/TestFhirServerService.java
  - src/test/java/com/spiraleyes/fhirviewer/ui/ScriptingConsoleSmokeTest.java
  - src/test/java/com/spiraleyes/fhirviewer/ui/UserGuideContentTest.java
  - src/test/java/com/spiraleyes/fhirviewer/server/ServerWriteTest.java
  - docs/scripting/Scripting-Guide.md and its bundled help copy
- Tests/build commands run: `./mvnw -B -ntp -Dtest=ScriptingCoordinatorTest,TestSuiteTest,FhirApiTest,WritePolicyTest,WriteJournalTest,WriteScopeTest,ServerDefinitionPersistenceTest,ScriptingConsoleSmokeTest,UserGuideContentTest test` and `./mvnw -B -ntp test`
- Results: 139 focused tests and the full 1,139-test suite run, 0 failures, 0 errors.
- Known limitations: writes use saved server credentials, not the SMART session; `smart` remains read-only; undo is best effort, in-memory and limited to writes made in the open console; the write budget and 15-minute expiry are fixed console defaults rather than user settings.
- Packaging/platform checks: not run for this focused change.