# FHIRViewer — Code Analysis, Third Pass

Date: 2026-11-10
Continues `CODE-ANALYSIS-ISSUES.md` (2026-11-02) and `CODE-ANALYSIS-ISSUES-ROUND2.md`
(2026-11-04). This pass re-examines the whole tree, reports what changed since the second
pass, adds six new findings (`R3-*`), and folds every open item into one prioritized
backlog.

Scope: `src/main/java` (235 files, ~43,800 lines), `src/test/java` (113 files, ~20,200
lines), `pom.xml`, `.github/workflows/ci.yml`, and the `docs/` tree.

Method: static reading plus targeted pattern searches (broad `catch`, `Thread` creation,
`System.out`/`err`, `Class.forName`, raw `Files.writeString`, `Preferences` nodes, string
building of paths, `setDaemon`, escape sequences). No build and no test run was performed
*for this pass*. The tree did compile earlier in the same session (`test-compile`: 234 main
and 113 test sources) and 48 workspace tests passed while H6 was being fixed, so nothing
below is about code that fails to compile or fails its own tests.

## Priority legend

| Priority | Meaning |
|----------|---------|
| **P0** | Data loss, security exposure, or a crash in ordinary use. None found. |
| **P1** | A feature that does not work as presented, or a defect users hit in normal use. |
| **P2** | Robustness or maintainability problem that costs work, or a user-visible defect in a secondary screen. |
| **P3** | Hygiene, naming, consistency, stale documentation. |

IDs are stable: `H*`, `M*`, `L*` come from the first two passes so earlier references still
resolve; `R3-*` are new here.

---

## 1. Status of the earlier passes

| Item | Priority | State |
|------|----------|-------|
| H1 close prompt | P1 | Fixed. Reachable since H6's wiring |
| H2 `ScriptingCoordinator.stop()` | P1 | Fixed. No wait on the calling thread; `running` cleared only in the worker's `finally` |
| H3 `Files.list` leak | P2 | Fixed, try-with-resources |
| H4 HTTP client pools | P1 | Fixed and effective (`ownsHttpClient`, bounded shutdown, every builder closes) |
| H5 UI tests on the Linux CI leg | P2 | Fixed, `xvfb-run` |
| H6 workspace/recovery never runs | P1 | Fixed 2026-11-04, except the restore offer — see M7 |
| M1 `MainWindow` size | P3 | First slice only: workspace file actions moved out; the window is still 2,697 lines |
| M2 duplicated `run` | P3 | Fixed, one `submit` |
| M3 script output capture | P1 | **Fixed.** `ScriptingSession.evaluatePart` (`:412-433`) captures `System.out`/`err` around `shell.eval` and reports the lines as `ScriptRunResult.Entry.Kind.OUTPUT`. Round 2's table said "not fixed"; that row is corrected |
| M4, M5, M6 | P3 | Fixed (SLF4J, directory-counted retention, README) |
| L1–L8 | P3 | Fixed |
| L9 `SecretBox` passphrase as `String` | P3 | Deliberately unchanged (documented in the class) |
| L10 diagnostics charset | P3 | Open, see backlog |
| L11 test names promise more than they check | P3 | Open |
| L12 hardcoded locations, placeholder package names | P3 | Open |
| L13 recovery service lifecycle | P3 | Partly fixed — the unguarded `Platform.runLater` callback is gone; the rest is R3-6 |
| L14 unused import in `MainWindow` | P3 | Fixed with the H6 change |

Net effect: the High section of the first pass is closed, and the one acknowledged unfixed
Medium (M3) turned out to be fixed in the tree already. M7 was then fixed on 2026-11-10
(section 2.1), so no P1 is open, and the R3 findings are closed as well. M8 and M9 were
fixed on 2026-11-10, leaving the P3 tail.

---

## 2. New findings

### 2.1. M7 fixed (2026-11-10)

Reading the code for the fix corrected one claim in the second pass: `MainWindow` already
passed the live `serverManager`, `serverService.registry()` and the validation package
manager into `WorkspaceService`, so connections and packages were captured properly. The gap
was the other direction - `restore` only logged warnings. What changed:

- Connections restore applies. `ConnectionsWorkspaceStateProvider.restore` now adds each
  archived server that is not already configured, keeping the archived id (the credential
  key, so an already-saved password still resolves for that server) and the recorded plugin,
  FHIR version and timeout. It still never connects, never overwrites a server the user has
  configured since, and skips an entry whose plugin is not loaded - a server that cannot
  answer a request would only create a failure to trip over.
- Validation restore applies. `ValidationWorkspaceStateProvider.restore` now calls
  `IgPackageManager.setActive` for every archived package that is installed at the archived
  version. Packages the archive does not name are left alone, so installing something after
  the save is not undone by opening an older workspace.
- `MainWindow` refreshes the edit, server and profile state after the start-up open, which
  makes a restored server list visible immediately rather than after the next dialog.
- Both providers log through SLF4J instead of `System.err`, which a desktop build shows to
  nobody.

Tests added: `ConnectionsWorkspaceStateProviderTest.restoreAddsAServerThatIsNotConfigured`,
`restoreLeavesAnAlreadyConfiguredServerAlone`, and `ValidationRestoreActivationTest`
(re-activate, de-activate, and leave unnamed packages alone).

Still open under M7, deliberately: `TreeTabsWorkspaceStateProvider` and
`FhirPathWorkspaceStateProvider` remain unwired, because this window holds one document at a
time and no durable FHIRPath expression - their callbacks exist for the day that changes. And
the start-up Restore prompt is still not in the UI: opening a workspace archive now restores
state, but the snapshot taken on close is not offered back at start-up.

### R3-1. "Loaded Packages" shows literal `\n` and reaches the package manager by reflection

**Bug — P2.** `MainWindow:2659-2693`, wired to the menu at `:2625`.

Two defects in one method:

1. **Doubled escapes.** Eight of the strings use `\\n` instead of `\n` (`:2673-2686`), so the
   dialog renders `No Implementation Guide packages are currently loaded.\n\nTo load
   packages:\n1. …` on one line. Verified by counting the two-backslash-plus-`n` sequence in
   the file: 8 lines, all inside this method. The neighbouring dialogs (`:1879-1881`,
   `:2035-2036`) use the single-escape form correctly.
2. **Unnecessary reflection.** The method resolves
   `Class.forName("com.spiraleyes.fhirviewer.service.ValidationService")`, then
   `getMethod("getPackageManager")`, then `name()`/`version()`/`fhirVersion()` on every
   package — although `IgPackageManager` is imported at `:37` and called directly twenty
   lines earlier in `openIgPackageManager` (`:2641`). A rename compiles cleanly and fails
   only when a user opens the dialog, into `Failed to get package information`.

**Fix:** call the typed API (`fhirService.validationService().getPackageManager()
.getLoadedPackages()`, `IgPackageInfo.name()/version()/fhirVersion()`), and use `\n`. The
method then needs no `try`/`catch (Exception)` at all.

**Fixed 2026-11-10:** the method now reads the installed packages through
`IgPackageManager.getLoadedPackages()`, prints real line breaks, and marks packages that are
not used for validation. Both reflection calls and the two-backslash escapes are gone from
`MainWindow` (verified by search: no `Class.forName`, no doubled escapes left in the file).

### R3-2. The IG registry search runs on a non-daemon thread

**Bug — P2.** `IgPackageDialog:275`.

```java
new Thread(() -> {
    var results = registryService.searchPackages(query);
    ...
```

No `setDaemon(true)`. Every other worker in the class sets it (`:342` planner, `:374`
installer), as does every worker in `MainWindow` (`:376, 1824, 1925, 1993, 2065`). Close the
window while a registry search is in flight and the JVM stays alive until the HTTP call
returns — a process still in the task manager after the user quit, which is the class of
report nobody can reproduce on a fast connection.

**Fix:** make the thread a daemon, or route the call through `BackgroundTasks`, which
already guarantees a daemon worker and a JavaFX-thread callback (and is what the rest of the
UI uses).

**Fixed 2026-11-10:** the search now runs through a small `daemonThread` helper in
`IgPackageDialog`, the same guarantee the planner and installer threads in that class already
had by setting the flag themselves. Its result still arrives through `Platform.runLater`, so
the dialog code is otherwise unchanged.

### R3-3. The REST console persists the request body while its own documentation says it does not

**Bug (documentation vs behaviour, privacy) — P2.** `ui/RestConsoleState.java`.

The class javadoc is explicit (`:36-38`):

> **The request body.** A body is far more likely to be a patient resource than a
> credential, and writing one to disk unasked is a decision this tool should not make
> silently.

`RestConsoleStateTest:99` agrees: `@DisplayName("There is nowhere in the state to put a
password, token or body")`. But the record has a `body` component, `save()` writes it
(`node.put("body", body)`, `:151`), and `load()` reads it back (`:112`). Comments in the
test (`:111`) and above `toString()` (`:196`) are stale in the same way.

So a patient resource typed into the console is written in plaintext to the user preference
store — the store the encrypted `SecretBox` credential store exists to avoid — while the
code's own documentation promises otherwise. The token and password exclusions *are* honoured.

**Fix:** decide which is intended. Restoring the documented posture is deleting one line in
`save()` (and the field if nothing else reads it); keeping the behaviour means correcting
the javadoc, the test name and the user guide, because a user reading either will believe
the body is not on disk. Raise to P1 if the product position is that bodies never persist.

**Fixed 2026-11-10, on the documentation's side.** Nothing reads a remembered body
(`RestRequestPane` builds the saved state with `body = ""`), and the class's own bullet is
an explicit privacy decision, so the code was the wrong half. The `body` component is gone
from `RestConsoleState` entirely: it is not written, not read, and `save()` now removes a
`body` key left behind by an earlier build. `RestWorkbenchWorkspaceStateProvider` no longer
captures or restores a body either, so a workspace archive cannot carry one. The structural
test now asserts the record has no `body`, `password`, `token` or `secret` component, and a
new case checks that an old stored body is purged rather than resurrected.

### R3-4. Nothing tests `MainWindow` or `WorkspaceActions`

**Enhancement — P2.**

A whole-tree search of `src/test` finds no construction of either class. The application
does test other screens on a real toolkit (`ServerUiSmokeTest`, 1,852 lines;
`SmartLaunchWorkbenchUiTest`, 521), so the approach exists and works. The main window is
the one screen without it — and that is precisely why H6, four independent dead links in
the workspace feature, survived a fully green suite.

**Fix:** add a smoke test in the style of `ServerUiSmokeTest`, pointed at a temporary
`user.home`, asserting the wiring rather than the pixels: start-up opens the recorded
workspace, a server change marks it dirty, Save with nothing open creates a workspace, and
the close path asks once. That is the cheapest insurance against the entire H6 class of
defect.

**Fixed 2026-11-10:** `WorkspaceWiringTest` builds `WorkspaceActions` against a real
`WorkspaceService` and covers five cases: start-up opens the recorded archive, start-up with
nothing recorded invents no workspace, a recorded archive that has moved is forgotten, a
captured change marks the workspace dirty, and closing a clean workspace asks nothing. It
snapshots and restores the application's `workspace` preference node around every test, so a
run cannot decide what the developer's next session reopens. What it does not cover:
constructing `MainWindow` itself, which needs a `Stage` and `HostServices` and would start
the IG auto-load from the real package directory, and the two actions behind a
`FileChooser`.

### R3-5. `PackageStorage` builds its default path by string concatenation

**Issue — P3.** `service/PackageStorage.java:21-22`.

```java
private static final String DEFAULT_DIR =
        System.getProperty("user.home") + "/.fhirviewer/packages";
```

A hardcoded separator inside a `String`, frozen in a static initializer, while every other
location uses `Path.of(...)` (`FhirServerManager:96`, `SmartDiagnosticsPane:81`,
`WorkspaceActions:60`). Windows accepts `/`, so this is consistency rather than a live
defect; it also means a test cannot redirect it without setting `user.home` before the class
loads. `getStorageDirectory()` still reads the legacy `com/example/fhirviewer/ui` node
(`:107`), the same placeholder-package problem as L12.

**Fixed 2026-11-10:** the constant is gone. `getStorageDirectory()` now falls through to
`defaultDirectory()`, which builds `Path.of(user.home, ".fhirviewer", "packages")` on every
call, so the default follows the current property instead of the one present at class load,
and no separator is hardcoded. `PackageStorageTest` pins both halves: the default is under
the current home, and moving home moves it; a chosen directory still wins, and listing an
archive still splits id from version. The legacy preference read is deliberately unchanged -
it is the migration path for directories chosen by earlier builds.

### R3-6. Recovery-service lifecycle, now that the service is actually used

**Issue — P3.** `workspace/WorkspaceRecoveryService.java`.

H6 made the service live, so what was inert is now worth fixing:

- `scheduler` (`:49`) is never shut down and the class has no `close()`. One daemon thread
  per instance, so the process still exits; but a window closed and reopened accumulates
  one, and there is nowhere to release it.
- `lastSnapshotTime` (`:57`) is written in `doSnapshot` and reset in `forgetSnapshot`, and
  read nowhere.
- `saveCurrentArchive()` (`:147`) still queues a snapshot, saves, then calls
  `forgetSnapshot()`. The queued snapshot can finish afterwards and set `lastSnapshotPath`
  again, so a later restore would offer a snapshot taken *before* the save. Nothing calls it
  yet; it is the first thing the M7 work will call.

**Fixed 2026-11-10:** all three. `close()` shuts the scheduler down and waits up to five
seconds for snapshots already queued or in progress to finish, so the files on disk match
what the service last reported; it is idempotent, a snapshot requested after it is ignored
instead of throwing, and
`MainWindow`'s close request now calls it through `WorkspaceActions.close()` on both the
close button and the Exit action. `lastSnapshotTime` is gone: it was written twice and read
nowhere, and the on-disk file name already carries the time. `saveCurrentArchive()` saves
first and resets the baseline after, and the reset bumps a generation counter that a queued
or in-progress snapshot compares against — one that no longer matches is skipped, or its
file deleted if it was already written. That also protects the existing
`forgetSnapshot()` calls on New, Open and start-up, which had the same race without it.
`WorkspaceRecoveryServiceTest` grew four cases: a save leaves nothing to restore, a queued
snapshot is discarded by a reset, a snapshot queued before a save is not offered after it,
and closing stops the thread while keeping (and restoring) a snapshot it finished.

---

## 3. Consolidated backlog

Every open item, highest priority first. Type is Bug / Issue / Enhancement.

| ID | Type | Priority | Item | Where |
|----|------|----------|------|-------|
| ~~M7~~ | Issue | Fixed | Connections and validation state restore on open (§2.1). Tree/tabs and FHIRPath remain unwired; no start-up restore prompt yet | `workspace/*WorkspaceStateProvider` |
| M8 | Bug | **P2** | Closing asks twice: `confirmUnsavedChanges(...)` then `workspaceActions.closeDecision()` | `MainWindow:344-345, 1511` |
| ~~M9~~ | Bug | Fixed | `open()` applies an archive's sections before adopting it, so a provider failure reports an `IOException` with the previous workspace, path and dirty flag intact | `WorkspaceService` |
| ~~R3-1~~ | Bug | Fixed | "Loaded Packages" lists installed packages from the typed API with real line breaks and an inactive marker | `MainWindow` |
| ~~R3-2~~ | Bug | Fixed | Registry search runs on a daemon thread, so a closed window no longer leaves the JVM waiting for the request | `IgPackageDialog` |
| ~~R3-3~~ | Bug | Fixed | The request body is no longer persisted anywhere: the component is removed, old values are purged, and workspace archives no longer carry it | `RestConsoleState` |
| ~~R3-4~~ | Enhancement | Fixed | `WorkspaceWiringTest` covers start-up open, a moved archive, dirty marking and the clean close decision | `src/test/.../ui` |
| M1 | Enhancement | **P3** | `MainWindow` is still 2,697 lines with 17 `catch (Exception)` blocks; one slice moved out | `MainWindow` |
| L10 | Bug | **P3** | Diagnostics export writes with the platform charset, not UTF-8 | `SmartDiagnosticsPane:86` |
| L11 | Issue | **P3** | Several `restore…` tests assert only "does not throw" while their names promise a populated state | `*WorkspaceStateProviderTest` |
| L12 | Issue | **P3** | Recovery directory from `user.home` inside the class; `workspace` as a global preference node; `com/example/fhirviewer/...` node names in a `com.spiraleyes` project | `WorkspaceActions:60`, `WorkspaceService:17`, `RestConsoleState:66`, `PackageStorage:107` |
| L9 | Issue | **P3** | `SecretBox` keeps the passphrase in an un-zeroable `String`; deliberate, documented | `SecretBox:108-115` |
| ~~R3-5~~ | Issue | Fixed | Default package directory resolved per call from `user.home` via `Path.of`; covered by `PackageStorageTest` | `PackageStorage` |
| ~~R3-6~~ | Issue | Fixed | Recovery service has a `close()` wired to the window close, drops the unused timestamp, and disowns snapshots a save or reset has overtaken | `WorkspaceRecoveryService` |

Closed since the second pass: H1–H6, M2, M3, M4, M5, M6, L1–L8, L14.

---

## 4. Re-verified strengths

- **Archive I/O is defensive.** `WorkspaceArchiveReader` rejects absolute paths, `..`
  traversal, duplicate entries, more than 500 entries, more than 10 MB and schema versions
  outside 1..3; the writer goes through `target.tmp` plus `ATOMIC_MOVE`.
- **Credentials are encrypted properly.** `SecretBox` uses PBKDF2-HMAC-SHA256 at 210,000
  rounds with a per-encryption salt and AES-GCM with a per-encryption IV, and documents
  exactly what that does and does not protect.
- **Asynchronous work has one shape.** `BackgroundTasks` wraps every network call in a
  daemon thread with a JavaFX-thread callback, a typed failure and cooperative
  cancellation. R3-2 is the single place that bypasses it.
- **Every HTTP path has a deadline**, including SMART discovery, JWKS and token exchange.
- **No leftover markers.** `TODO`, `FIXME`, `XXX`, `HACK`, `printStackTrace` and empty
  `catch` blocks match zero files; no `@Disabled`/`@Ignore` tests.
- **CI is real.** `verify` on Windows and under Xvfb on Linux, so the UI suites run on both
  legs rather than skipping themselves.
- **Script output capture works** (`ScriptingSession:412-433`), including output from the
  framework's preamble, which is captured and discarded rather than leaked to the user.

---

## 5. Limitations

- Static reading. No profiling, no runtime exercise of the GUI, no measurement of start-up
  or memory. This pass ran no build and no test suite of its own.
- "Nothing calls X" conclusions come from whole-tree searches of the main sources; a
  reflective or service-loaded caller would not appear.
- M7's conclusion rests on the providers' no-argument constructors and the absence of any
  wiring setter. Wiring done outside the tree (a plugin, an external launcher) would change
  the picture.
- HAPI's package registry, `PackageInstaller`'s download path, and the JShell internals
  behind snippet evaluation were not audited.
- File counts and the test-to-source ratio are not a coverage measurement, and the absence
  of tests is reported where it matters (R3-4) rather than as a metric.

---

## 6. Suggested order

1. **M7 follow-up** — the state that matters now restores. What is left is offering the
   recovery snapshot at start-up, and deciding whether tree/tabs and FHIRPath ever carry
   durable state in a single-document window.
2. **P3 tail** — L10–L12, M1 as opportunity allows.
