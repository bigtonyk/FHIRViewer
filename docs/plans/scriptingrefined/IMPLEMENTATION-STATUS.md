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
| 2. FHIRViewer scripting API and write policy | Complete | fhir/viewer/validation/server/log facades |
| 3. Automated testing and repeatable workflows | Not started | Only the `TestApi` assertion facade exists, exposed by the console; the suite engine (`service/scripting/testing`) was deleted in `7724874` because nothing could start it (R6-7 in `docs/CODE-ANALYSIS-ISSUES-ROUND6.md`). Suite format, screen, per-run server selection, progress/cancel and report export are all still to build. |
| 4. SMART on FHIR integration | Complete | SmartApi |
| 5. Advanced scripting and batch workflows | In progress | ScriptParameter and partial collections/reporting |
| 6. Scripting API documentation | Complete | docs/scripting/Scripting-Guide.md |

## Execution model decisions
- Interactive JShell session lifecycle: ScriptingCoordinator owns one ScriptingSession; created lazily, reset closes/recreates, close on dialog hide/shutdown.
- Saved workflow session behavior: there is no saved-workflow runner and no fresh session per saved script; a saved `.jsh` file opens in the console and is not auto-run, and every run, typed or opened, uses the one console session until Reset Session replaces it.
- Script storage/metadata approach: `.jsh` files via FileChooser; ScriptParameter metadata for workflows.
- Target server/environment selection: ScriptBindings binds active server; explicit target shown.
- Parameter handling: ScriptParameter typed/required/optional with defaults and redaction.
- Stop/cancel limitations: best-effort interrupt; arbitrary code may not stop.

## Write policy and security review
- Create/update/patch policy: routed through WritePolicy.
- Destructive operations covered: DELETE, bulk destructive, destructive bundles require confirmation.
- Confirmation dialog location and impact details: central WritePolicy/confirmation in FhirApi.
- Confirmation cancellation test: covered by FhirApiTest (policy and handler cancellation).
- Bundle/batch destructive-operation handling: policy enforced per operation.
- Secret/resource redaction: LogApi redacts; ScriptParameter redacts sensitive values.
- Script trust warning: shown before first execution and after opening files.
- Known limitations (JShell is not a sandbox): scripts run with user permissions.

## Latest work
- Date: 2026-10-21
- Phase: 1 (validation/fix pass under refined plan)
- Summary: Fixed ScriptingCoordinator.stop() race where the worker had not yet published the session; added jdk.jshell and jdk.compiler to packaged runtime modules; created refined implementation status.
- Files changed:
  - src/main/java/com/spiraleyes/fhirviewer/service/scripting/ScriptingCoordinator.java
  - pom.xml
  - docs/plans/scriptingrefined/IMPLEMENTATION-STATUS.md
- Tests/build commands run: .\mvnw.cmd -Dtest=ScriptingSessionTest,ScriptingCoordinatorTest test; .\mvnw.cmd -Pdist -DskipTests package
- Results: 20 tests run, 0 failures, 0 errors; packaged app-image build succeeded.
- Known limitations: advanced Phase 5 features remain partial; packaged runtime smoke-tested only via successful jpackage build.
- Packaging/platform checks: jpackage app-image built successfully on Windows with jdk.jshell and jdk.compiler in module list.
- Recommended next step: decide whether Phase 3 is built properly (starting with the saved-suite format) before continuing Phase 5 advanced scripting work.