# FHIRViewer — Code Analysis, Second Pass

Date: 2026-11-04
Continues `CODE-ANALYSIS-ISSUES.md`; numbering carries on from that document, so this one
starts at H6, M7 and L10.

Scope: the whole of `src/main/java` again, weighted towards the files the H/M/L fixes
touched and towards the layers the first pass only sampled — `workspace/`, the
workspace-to-UI boundary, and the test helpers.

Method: static reading plus pattern searches. The build and the focused suites were run
during the fix work earlier today and passed (`ServerUiSmokeTest` 61 tests,
`ValidationApiTest` 2, and 35 scripting/recovery/HTTP tests), so these findings are not
about code that fails to compile or fails its own tests.

---

## Status of the first pass

| Item | State |
|------|-------|
| H1 close prompt | Code fixed; reachable since H6's wiring (2026-11-04) |
| H2 `stop()` | Fixed. No wait on the calling thread; `running` is cleared only in the worker's `finally`; `stopRequested` plus `runGate` close the hand-off window |
| H3 `Files.list` | Fixed, try-with-resources |
| H4 HTTP pools | Fixed and effective: `ownsHttpClient` guards shutdown, `awaitTermination(2 s)` then `shutdownNow()`, and every caller that builds a client also closes it (`PluginOperationClient:433`, `FhirOperationClient:127`, `TokenEndpointClient:79`) |
| H5 UI tests in CI | Fixed: `xvfb-run -a --server-args=...` on the Linux leg (`ci.yml:75-80`) |
| M1 `MainWindow` size | First slice only — workspace file actions moved to `WorkspaceActions`; the window is still 2,685 lines |
| M2 duplicated `run` | Fixed: one private `submit` |
| M3 script output capture | **Not fixed.** The tee was reverted: JShell evaluates snippets on its own thread, so a thread-scoped tee captured nothing |
| M4, M5, M6 | Fixed (SLF4J, directory-counted retention, README) |
| L1–L8 | Fixed. L9 deliberately unchanged |

---

## 1. High

### H6. The workspace and recovery features never run

Four independent links of the same chain are missing, and each one alone is enough to stop
the feature:

1. **Nothing opens a workspace in the window that uses one.** `MainWindow:159` builds its
   own `WorkspaceService`, but the window never calls `open()`, `openLastFromPreferences()`
   or `newWorkspace()`. `Main.java:34-42` does call `openLastFromPreferences()`, on a
   *second, throwaway* instance that is discarded, inside an `if` whose body is a comment.
   So in a normal session `current == null`.
2. **Nothing marks the workspace dirty.** `WorkspaceService.markDirty()` has exactly one
   caller in the tree: `WorkspaceRecoveryService.restoreFromLastSnapshot()`. Edits mark
   `LoadedResource`, never the workspace (`MainWindow:1049, 1385, 1447, 2329`).
3. **So the close prompt cannot appear.** `handleCloseDecision()` returns `true` as soon as
   `isDirty()` is false, which is always. The H1 fix is correct in isolation and inert in
   the application: closing or replacing the workspace with unsaved state asks nothing,
   exactly as before.
4. **Nothing snapshots or restores.** `takeSnapshotNow`, `onMeaningfulChange`,
   `saveCurrentArchive`, `hasRecoverySnapshot` and `restoreFromLastSnapshot` have no
   callers in production code — only definitions. `WorkspaceActions:60` even passes
   `() -> { }` as the snapshot callback. The class javadoc ("periodic snapshots, debounced
   scheduling, … Restore/Discard/Inspect decisions") describes nothing that runs, and the
   dialog offers only Save Workspace / Discard / Cancel: no Restore, no Inspect.

One observable consequence: Workspace → Save in a fresh session. `getCurrentPath()` is
null, so `WorkspaceActions.save` re-enters as Save As, the user picks a file, and
`WorkspaceService.saveAs` throws `IllegalStateException("No workspace open")`, which the
catch turns into the status line "Failed to save workspace: No workspace open".

**Fix:** open the last workspace on the window's own service at startup, call
`recovery.onMeaningfulChange()` from the edit paths, and offer Restore when
`hasRecoverySnapshot()` is true. Until then H1, M4 and M5 are code in search of a caller.

**Fixed 2026-11-04, except the restore offer.** What was wired:

- `Main` no longer builds a second, discarded `WorkspaceService`; `MainWindow` opens the
  previous session's workspace on its own service after building the layout
  (`WorkspaceActions.openLastWorkspace()`).
- `WorkspaceService.openLastFromPreferences()` now returns `null` instead of inventing a
  `(new)` workspace, so start-up is clean and the first Save asks where to put the file.
- `WorkspaceActions.save` creates the workspace when none is open, which is what the
  "Failed to save workspace: No workspace open" symptom needed.
- `persistServers()` and the IG package manager mark the workspace dirty and take a
  snapshot (`markWorkspaceChanged()`), so the close prompt has something to ask about and
  `hasRecoverySnapshot()` can become true.
- Replacing the workspace (New, Open) forgets the previous snapshot, so it is never offered
  for a different workspace.
- The recovery service no longer carries the unused `onSnapshotTaken` callback and its
  unguarded `Platform.runLater`, and it skips a snapshot when no workspace is open.

**Still open:** the start-up Restore prompt. `ConnectionsWorkspaceStateProvider.restore`
and `ValidationWorkspaceStateProvider.restore` only log warnings - they apply nothing - so
a Restore button today would restore nothing. That is M7's work, and the offer should be
added with it.

---

## 2. Medium

### M7. Workspace state providers capture Preferences, not the open UI

`RestWorkbenchWorkspaceStateProvider.capture()` reads
`Preferences.userRoot().node("com/example/fhirviewer/restConsole")` and `restore()` writes
back to that same node (`:31-70`). A "snapshot" is therefore a round trip through the store
it is meant to be saved to; it never reads the live workbench.

The other providers are no-ops by construction: `WorkspaceService` builds them with their
no-arg constructors (`:44-48`), each documented as "capture/restore are no-ops until
wired", and nothing anywhere calls a wiring setter — `StateSnapshot` and `StateApplier`
appear only inside `TreeTabsWorkspaceStateProvider` itself. So even once H6 is wired, Save
would carry almost no live state.

**Fix:** wire the providers to the live screens, or drop them and capture the state the
window actually owns.

### M8. Closing asks the same question twice

`stage.setOnCloseRequest` (`MainWindow:340-345`) and `closeWindow()` (`:1507-1512`) both run
`confirmUnsavedChanges(...)` and then `workspaceActions.closeDecision()` — two modal dialogs
in a row, one for the edited resource and one for the workspace, both headed "unsaved
changes". A user who cancels the first never sees the second; a user who accepts both has
been asked twice about the same intent.

**Fix:** one prompt that names what will be lost, or skip the workspace prompt when the
resource prompt already resolved it.

### M9. `WorkspaceService.open()` has no rollback

`open()` (`:66-78`) assigns `current` and `currentPath` and *then* calls `restoreAll`, which
runs provider code with no catch. A provider failure leaves the service pointing at the new
archive while the UI still shows the old state, and the caller's catch reports an open that
has half happened. `Main.java` also calls `openLastFromPreferences()` without handling a
runtime exception. The archive reader itself is careful (see strengths), so this is about
partial application rather than corrupt input.

**Fix:** read and restore into locals, and assign the fields only once the restore succeeded.

---

## 3. Low

### L10. The diagnostics export ignores the project's UTF-8 rule

`ui/SmartDiagnosticsPane.java:86` calls `Files.writeString(file, text)` with no charset, so
the file is written in the platform default (1252 on a Windows desktop); every other write
in the tree passes `StandardCharsets.UTF_8`. Diagnostic text contains URLs and server
messages, so non-ASCII content will be mangled.

### L11. Tests whose names promise a check and assert nothing

`RestWorkbenchWorkspaceStateProviderTest` has four `restore…` tests (`:44, 52, 75, 83`)
whose bodies are a call plus a comment such as `// should not throw`;
`restorePopulatesStateWithoutExecuting` never inspects the populated state.
`FhirPathWorkspaceStateProviderTest:77, 83`, `ValidationWorkspaceStateProviderTest:57, 73,
80, 88` and `TreeTabsWorkspaceStateProviderTest:62, 68` follow the same pattern. They do
verify that restore does not throw — worth keeping — but the names claim more.

### L12. Hardcoded locations and a placeholder package name

- `WorkspaceActions:59` roots the recovery directory at
  `System.getProperty("user.home")` inside the class, so no test and no portable mode can
  redirect it.
- `WorkspaceService:15` uses `Preferences.userRoot().node("workspace")` — a global node
  named after a common word rather than a package-scoped node.
- `RestWorkbenchWorkspaceStateProvider:22` uses `com/example/fhirviewer/restConsole`; the
  project is `com.spiraleyes.fhirviewer`. The node name is persisted identity, so fixing it
  later needs a migration.

### L13. Recovery service: one dead field and two lifecycle gaps

- `lastSnapshotTime` is written in four places and read nowhere.
- `doSnapshot()` ends with `Platform.runLater(onSnapshotTaken)`. Called with the toolkit
  never started (as in a unit test) or already shut down, that throws
  `IllegalStateException`, which the surrounding `catch (IOException)` does not cover.
- The `ScheduledExecutorService` is never shut down: one daemon thread per service
  instance, and there is no `close()` to call.
- Latent ordering bug in `saveCurrentArchive()`: it queues a snapshot, saves, then clears
  `lastSnapshotPath` — but the queued snapshot can finish afterwards and set it again, so a
  later Restore would offer a snapshot taken *before* the save. Moot while nothing calls it
  (H6), a bug the moment something does.

### L14. Unused import

`MainWindow:38` imports `WorkspaceRecoveryService` and never names it; the window reaches
recovery only through `workspaceActions`.

---

## 4. Re-verified strengths

- **Archive reading is defensive:** `WorkspaceArchiveReader` validates absolute paths, `..`
  traversal, duplicate entries, entry count (500) and total size (10 MB) before parsing, and
  rejects schema versions outside 1..3.
- **Archive writing is atomic:** `WorkspaceArchiveWriter` writes `target.tmp` then
  `Files.move(..., ATOMIC_MOVE)`.
- **Every HTTP path has a deadline:** `JdkHttpRestClient` sets connect and request timeouts;
  `SmartDiscoveryService`, `SmartJwksFetcher` and `SmartTokenExchangeService` take both as
  constructor arguments and apply them.
- **No disabled tests** (`@Disabled`/`@Ignore` match zero files), no `TODO`/`FIXME`, no empty
  catch, no `printStackTrace`.
- **Clients are closed by whoever builds them** — the property H4's fix depends on.

---

## 5. Suggested order

1. **H6** — wire the workspace feature or delete it; as it stands the H1, M4 and M5 work is
   unreachable.
2. **M7, M9** — providers that read live state; restore into locals.
3. **M8** — one close prompt.
4. **M3** — still the one acknowledged unfixed item; it needs a decision between a custom
   `ExecutionControl` and moving application logging off `System.err`.
5. **L10–L14** — local cleanups.

---

## Limitations

- Static reading again: no profiling, and no runtime exercise of the workspace feature.
  There is no test that drives `MainWindow`, which is exactly why H6 survived a green suite.
- "No callers" conclusions come from whole-tree search of the main sources; a reflective or
  external caller would not appear.
- `service/PackageInstaller`'s download path delegates to HAPI's package registry; its size
  and integrity behaviour was not audited here.
