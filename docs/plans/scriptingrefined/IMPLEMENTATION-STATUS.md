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
| 3. Automated testing and repeatable workflows | In progress | Initial slice: `service/scripting/testing` parses `// case:` suites, `ScriptingCoordinator.runSuite` runs each case sequentially in a fresh `ScriptingSession` (stop interrupts the current case and skips the rest), and `SuiteRunResult` reports passed, failed and stopped counts that the console shows above the output pane, with each case's output and full diagnostics in a separate Test details tab; a stopped case is not counted as a failure, and opening another file clears the previous suite's results. The earlier engine was deleted in `7724874` because nothing could start it (R6-7 in `docs/CODE-ANALYSIS-ISSUES-ROUND6.md`). Still to build: per-check counts and names, skip/error verdicts, a suite screen, explicit target-server selection, progress reporting and report export. |
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
- Create/update/patch policy: routed through WritePolicy; the Scripting Console enables it only through the **Allow server writes** opt-in, which requires an active writable server and names that server in its warning.
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
- Summary: Added the explicit **Allow server writes** opt-in, rebuilt script bindings when the policy changes, added the FX-thread DELETE confirmation, reset writes when a file/example is loaded, and documented the write API.
- Files changed:
  - src/main/java/com/spiraleyes/fhirviewer/service/scripting/api/FhirApi.java
  - src/main/java/com/spiraleyes/fhirviewer/service/scripting/api/ScriptBindings.java
  - src/main/java/com/spiraleyes/fhirviewer/service/scripting/api/WritePolicy.java
  - src/main/java/com/spiraleyes/fhirviewer/ui/ScriptingConsoleDialog.java
  - src/test/java/com/spiraleyes/fhirviewer/service/scripting/api/FhirApiTest.java
  - src/test/java/com/spiraleyes/fhirviewer/service/scripting/api/ScriptBindingsInjectionTest.java
  - src/test/java/com/spiraleyes/fhirviewer/service/scripting/api/TestFhirServerService.java
  - src/test/java/com/spiraleyes/fhirviewer/ui/ScriptingConsoleSmokeTest.java
  - src/test/java/com/spiraleyes/fhirviewer/ui/UserGuideContentTest.java
  - docs/scripting/Scripting-Guide.md and its bundled help copy
- Tests/build commands run: `./mvnw -B -ntp -Dtest=ScriptingConsoleSmokeTest,FhirApiTest,ScriptBindingsInjectionTest,WritePolicyTest test` and `./mvnw -B -ntp test`
- Results: 51 focused tests and the full 1,095-test suite run, 0 failures, 0 errors.
- Known limitations: writes use saved server credentials, not the SMART session; `smart` remains read-only; writes cannot be undone.
- Packaging/platform checks: not run for this focused change.