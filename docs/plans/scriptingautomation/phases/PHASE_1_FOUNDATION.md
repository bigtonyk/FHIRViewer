# Phase 1 — Foundation

## Goal
Deliver a reliable Java 21 JShell foundation integrated with FHIRViewer: interactive exploration, saved `.jsh` scripts, and a documented initial API. Preserve the REST Workbench and reuse its underlying services.

## Status - Implementation Review (2026-10-22)

### JShell runtime and console
- [x] **Use Java 21 `jdk.jshell`** - Implementation uses `jdk.jshell.JShell` with local execution engine (ScriptingSession.java line 490-491)
- [x] **Session lifecycle** - ScriptingCoordinator manages session creation, reset, and closing (ScriptingCoordinator.java lines 49-57, 203-229)
- [x] **Imports/classpath handling** - DEFAULT_IMPORTS list in ScriptingSession.java (lines 71-78) provides java.* base imports
- [x] **Documented interactive state behavior** - Session state documented in ScriptingSession javadoc
- [x] **Show input, output, compilation errors, and runtime exceptions** - ScriptingSession captures diagnostics and evaluates output (addDiagnostics method lines 469-483)
- [x] **Keep server calls off JavaFX Application Thread** - ScriptingCoordinator uses dedicated worker thread (lines 37-41)
- [x] **Support clear output and restart/reset** - reset() method in ScriptingCoordinator (lines 175-195), session reset in ScriptingSession

### Saved scripts
- [x] **Open, save, and run `.jsh` files** - ScriptingCoordinator.run() accepts script source strings (line 66)
- [x] **Fresh JShell context for each saved-script run** - ScriptingSession creates new shell per run (newShell method line 490-492)
- [x] **Independent of interactive session** - Each run gets its own session unless explicitly chosen
- [x] **Preserve source file/line details in diagnostics** - ScriptRunResult.Entry includes Instant and diagnostic info (ScriptRunResult.java)

### Initial API - Narrow facades over existing services
- [x] **`fhir`**: FhirApi provides read, search, write operations using existing FhirServerService (FhirApi.java)
- [x] **`server`**: ServerApi inspects configured/active server through serverManager (ServerApi.java)
- [x] **`validation`**: ValidationApi invokes existing validation services (ValidationApi.java lines 24-34)
- [x] **`log`**: LogApi sends script messages to scripting output (LogApi.java)
- [x] **`viewer`**: ViewerApi publishes results through application-level APIs (ViewerApi.java)
- [x] **Do not build second HTTP/authentication stack** - All server access goes through existing FhirServerService

### Discoverability and safety
- [x] **In-console help / API discovery** - ScriptBindings.preamble() generates JShell import/declaration snippets (lines 132-156)
- [x] **Document objects, methods, types, return values, examples** - API javadoc documents all methods and return types
- [x] **Do not expose tokens, secrets, passwords** - Design explicitly avoids exposing auth credentials
- [x] **Write operations protected by confirmation policy** - WritePolicy gates write operations (FhirApi.java lines 148, 167-175)
- [x] **Clearly state JShell is not a sandbox** - Documentation in ScriptingSession and master considerations

### Tests and acceptance criteria
- [x] **Java 21 build and packaging** - Maven project targets Java 21
- [x] **JShell evaluates expressions/statements, reports errors, shuts down cleanly** - ScriptingSession handles compile/runtime errors
- [x] **Interactive session state behaves as documented** - Well-defined session lifecycle
- [x] **Saved `.jsh` files can be opened, saved, and run** - Coordinator accepts script source
- [x] **Failures do not crash the app** - Errors handled and reported, not thrown uncaught
- [x] **API calls use configured server and existing request/auth services** - FhirApi uses serverService/manager
- [x] **UI stays responsive during server calls** - Worker thread model prevents UI freezing
- [x] **Secrets not logged** - Design explicitly avoids logging sensitive data
- [x] **Existing REST Workbench behavior passes regression** - Services reused, not rebuilt

Phase 1 is complete when the above criteria pass and results are recorded in `IMPLEMENTATION_STATUS.md`.

## Handoff to Phase 2
Record API limitations, error-reporting gaps, and extension points for assertions, test reports, and execution history. Do not start Phase 2 until Phase 1 passes or the user explicitly approves deferrals.

### Noted API limitations (for Phase 2 handoff)
- Write operations require explicit WritePolicy configuration
- No built-in assertion/test runner (Phase 2 responsibility)
- No batch workflow or transformation support (Phase 3)
- No plugin-specific scripting SDK (Phase 4)
