# Implementation Status

Filled in on 2026-11-12 against the code at `24d8f6d`, replacing the unfilled template, and
refilled the same day after the suite runner, its failure-path tests and the run-mode changes
landed. Update at the end of each phase.

## Current phase

- Phase: 1 complete; Phase 2 partly delivered; Phase 3 partial; Phase 4 not started.
- Date: 2026-11-12
- Branch or commit: `workspace` at `a89e513`
- Summary: the interactive JShell console, saved `.jsh` runs, the `fhir`/`server`/`validation`/
  `log`/`viewer`/`smart`/`test`/`batch` facades, the central write policy and the batch
  processor are in the tree and covered by 1,052 tests. Phase 1's saved-script criterion is now
  met: a buffer with a file behind it runs in a fresh JShell session created for that run and
  closed when it ends, while the unsaved editor keeps the interactive session until Reset.
  A first test-runner slice exists: `// case:` suites run each case in a fresh session and
  report passed/failed counts apart from console output, with every case's output and full
  diagnostics in the Test details tab. There is no report export, no parameter prompt, and no
  plugin scripting facade.

## Phase status

| Phase | Status | Evidence / notes |
|---|---|---|
| 1. Foundation | Complete | `ui/ScriptingConsoleDialog` (693 lines) with Run, Run Tests, Stop, Reset Session, Open, Save, Save As, Example and Clear Output; `ScriptingCoordinator` (450) owns one lazy interactive `ScriptingSession` (605) on a worker thread and creates a temporary `ScriptingSession` for each run of a file-backed buffer, closing it when the run ends; `ScriptBindings` injects `fhir`, `server`, `validation`, `log`, `viewer`, `test`, `smart` and `batch`; saved `.jsh` files load into the editor and run in that fresh session, while the unsaved editor keeps the interactive session Reset replaces; `api/WritePolicy` (94) gates create/update/patch/delete; packaging adds `jdk.jshell` and `jdk.compiler` to the runtime module list (2026-10-21 status entry); guide in `docs/scripting/Scripting-Guide.md`. |
| 2. Testing, Debugging, and Interoperability | Partial | Assertions exist: `api/TestApi` (116 lines) provides `assertTrue/False/Equals/NotNull/Null/Throws/DoesNotThrow/fail`, each throwing `AssertionError`, which the console reports by name. Interoperability helpers exist: `ServerApi.capabilities/supports/fhirVersion`, `FhirApi.read/search/searchRaw/nextPage/previousPage`, `ValidationApi.validate`. A run does record `ScriptRunResult` with a status, the number of snippets, elapsed time and ordered text entries. A test-runner slice now exists: `service/scripting/testing` parses `// case:` suites, `ScriptingCoordinator.runSuite` runs every case sequentially in a fresh `ScriptingSession`, and `SuiteRunResult` carries passed/failed counts shown in the console's own report line instead of the output pane; each `SuiteCaseResult` keeps the case's entries, which the console's Test details tab shows with the full diagnostics. Missing: per-assertion counts and names, skip/error verdicts, log-level filtering, and any report export. |
| 3. Interoperability Automation | Partial | Batch processing exists: `BatchProcessor` (153 lines, exposed as `batch`) runs sequentially with `BatchErrorPolicy.STOP_ON_FIRST_ERROR` or `CONTINUE`, records per-item outcomes and timings, supports best-effort cancel between items, and routes writes through `WritePolicy`. `ScriptParameter` (66) models typed/required/defaulted parameters and redacts sensitive names. Missing: nothing constructs `ScriptParameter` (no parameter prompt or validation at run time), no collection validation summaries, no CSV/JSON report export, no dry-run preview, and no run history. |
| 4. Extensibility and Ecosystem | Not started | No plugin facade is bound into `ScriptBindings`; `ServerInfo.pluginId()` is the only plugin fact a script can read, and the plugin operations the REST Workbench offers (`ServerOperation`, `PluginOperationClient`) are not exposed to scripts. No compatibility policy or script-library metadata beyond `.jsh` files. |

## Existing functionality found during inspection

- JShell runtime: Java 21 `jdk.jshell`; `ScriptingSession` evaluates snippets, reports compile
  and runtime errors, and retries `JShell.stop()` briefly; snippets still swap the process-wide
  `System.out`/`System.err` for capture (documented in the class), which is why a log line from
  another thread can land in script output.
- Interactive console: `ui/ScriptingConsoleDialog`, opened from the Tools menu; Run/Stop/Reset,
  file open/save dialogs, a sample, clear output, and a status line; output is a read-only text
  area, not a structured result table.
- Saved scripts: `.jsh` files via `FileChooser`; opening one loads its text and never runs it. A
  buffer with a file behind it runs in a fresh `ScriptingSession` created for that run and closed
  afterwards, so saved scripts are repeatable; the unsaved editor keeps the interactive session
  until Reset Session.
- REST Workbench shared services: the facades call `server/FhirServerService` and
  `server/FhirServerManager` (and `ServerWriteResult`); no HTTP client exists in
  `service/scripting`, so the Workbench and scripts share one request path.
- FHIR resource/client services: `FhirService`, the parser/serializer, `FhirContextFactory` and
  `R4ModelAdapter` supply parsing, encoding and the version-neutral model; `FhirApi` wraps
  search/read and the write path.
- Validation: `ValidationApi.validate(resource)` and `validate(resource, profileCanonical)` over
  the existing validation service; the report carries issues rather than throwing.
- SMART on FHIR: `SmartApi` exposes session state, granted scopes, launch patient/encounter ids
  and a scope-respecting `read`, never raw tokens.
- Plugin API: server plugins advertise operations to the REST Workbench and the plugin id is
  visible to scripts through `ServerInfo`; scripts cannot call plugin operations.
- Tests/build: full suite 1,052 tests in 110 test classes, 0 failures/errors/skips; scripting
  contributes 98 `@Test` methods in 13 classes under `service/scripting`,
  `service/scripting/api` and `service/scripting/testing`, and the console smoke test drives
  the suite failure path through the Run Tests button.

## Tests executed

| Test / command | Result | Notes |
|---|---|---|
| `./mvnw -B -ntp test` | 1,052 run, 0 failures, 0 errors, 0 skipped (exit 0) | Run on 2026-11-12 on `workspace` after the suite runner, its failure-path tests, the round-8 fixes and the Run Tests smoke tests, including the injected-factory partial-failure case; includes the 98 scripting tests above. |
| Package build | Succeeded 2026-10-21 | `mvnw -Pdist -DskipTests package` with `jdk.jshell` and `jdk.compiler` in the module list, recorded in the scriptingrefined status file; not rebuilt for this entry. |

## Known issues and deferred work

- `ScriptParameter` is dead code: no production code constructs it and no test covers it, so the
  Phase 3 parameter contract is a model without a caller.
- The suite runner counts cases, not assertions: a case is one pass or one fail and there are no
  skip/error verdicts. The report line names only the first exception or diagnostic, but the Test
  details tab keeps every entry the case produced (Phase 2).
- No suite screen, run history or report export. The earlier scriptingrefined Phase 3 engine was
  deleted in `7724874` because nothing could start it (R6-7 in `docs/CODE-ANALYSIS-ISSUES-ROUND6.md`);
  the new runner starts from the saved-suite format instead, and the remaining report work is
  still to build.
- No in-console help: discovery relies on `docs/scripting/Scripting-Guide.md` and the Example
  button.
- Process-wide output capture means concurrent log output can be attributed to a script, and two
  sessions cannot run at once safely.
- JShell is trusted-code execution, not a sandbox; scripts run with the user's permissions and
  the write policy only guards FHIR writes made through `fhir`.
- No plugin scripting: mutating plugin operations remain reachable only through the REST
  Workbench, where the existing confirmation rules apply.

## Next-phase readiness

- Acceptance criteria passed: Phase 1's build, facade, write-policy, documentation and
  saved-script criteria are met and tested; a file-backed buffer runs in a fresh JShell session
  per run. Phase 2's assertion and interoperability halves are in place, and its runner now
  executes suite cases in fresh sessions with counts separate from output; per-check counts and
  reporting are not finished.
- Outstanding blockers: Phase 2 needs named check counts and a result/report shape before it can
  claim its reporting criterion, and that shape is the one the refined plan records for its
  Phase 3, so the two plans must not grow two incompatible result models.
- Recommendation: extend the suite runner with named checks and skip/error verdicts on top of
  the existing `SuiteRunResult`, then add report export; wire `ScriptParameter` into a prompt
  before starting Phase 3's transformations and reports, and defer Phase 4 until a plugin
  operation is actually exposed to scripts.
