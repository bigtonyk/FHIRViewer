# FHIRViewer — Code Analysis, Fifth Pass

Date: 2026-11-10
Continues `CODE-ANALYSIS-ISSUES.md` (2026-11-02), `CODE-ANALYSIS-ISSUES-ROUND2.md`
(2026-11-04), `CODE-ANALYSIS-ISSUES-ROUND3.md` (2026-11-10) and
`CODE-ANALYSIS-ISSUES-ROUND4.md` (2026-11-10). This pass is a review of the fourth pass
rather than a new sweep: each open `R4-*` item was re-read against the code as it stands at
`448f7fe`, the defects fixed since the fourth pass were checked off, and two P3 findings
noticed while doing that were added. Nothing closed in the fourth pass has regressed.

Scope: `service/PackageInstaller`, `service/PackageRegistryService`,
`service/NpmPackageReader`, `service/IgPackageManager`, `server/ServerDefinitionStore`,
`workspace/WorkspaceArchiveWriter`, `workspace/WorkspaceService`, `ui/MainWindow`, and the
two preference-writing test classes (`ui/RestConsoleStateTest`, `ui/WorkspaceWiringTest`).

Method: static reading of the files above, plus targeted searches for `java.util.logging`
usage, provider construction and start-up wiring. The line numbers are from `448f7fe`. Tests
were run during this pass's own changes in the fourth pass's tail — `46` and `26` tests green
across the workspace, console and provider classes — but no new test was written for the
findings in §2, which are all still unfixed.

## Priority legend

| Priority | Meaning |
|----------|---------|
| **P0** | Data loss, security exposure, or a crash in ordinary use. None found. |
| **P1** | A feature that does not work as presented, or a defect users hit in normal use. |
| **P2** | Robustness or maintainability problem that costs work, or a user-visible defect in a secondary screen. |
| **P3** | Hygiene, naming, consistency, stale documentation. |

IDs are stable: `H*`, `M*`, `L*` from the first two passes, `R3-*` from the third, `R4-*`
from the fourth, and `R5-*` are new here.

---

## 1. Status of the fourth pass

| Item | Priority | State |
|------|----------|-------|
| R4-1 registry request deadline | P2 | Fixed 2026-11-10 — connect and request timeouts from one `REQUEST_TIMEOUT` |
| R4-2 registry file name unsanitised | P2 | Fixed 2026-11-10 — id/version validated, download path checked |
| R4-3 unmet dependencies misattributed | P3 | Fixed 2026-11-10 — an install reports only the gaps it added |
| R4-4 planner drops version conflicts | P3 | Fixed 2026-11-10 — the losing requirement is reported before installing |
| R4-5 over-large block index aborts start-up | P3 | Fixed 2026-11-10 — indices are ordered without parsing |
| R4-6 package layer logs through JUL | P3 | **Open** — see §2 |
| R4-7 writer NPEs for a parentless target | P3 | **Open** — see §2 |
| R4-8 `closingFromAction` never cleared | P3 | Fixed 2026-11-10 — flag lasts one close |
| R4-9 `captureAllCurrent()` NPE at start-up | P3 | Fixed 2026-11-10 — throws `IllegalStateException` |
| R4-10 mangled console javadoc bullet | P3 | Fixed 2026-11-10 |
| M1 `MainWindow` size | P3 | **Open** — 2,705 lines; split plan is R4 §7 |
| M7 workspace restore | P1 | **Open in part** — start-up restore is silent, two providers unwired; see §2 |
| L9 passphrase as un-zeroable `String` | P3 | Fixed 2026-11-10 — `char[]` end to end on the session path |
| L10 diagnostics charset | P3 | Fixed 2026-11-10 — export writes UTF-8 explicitly |
| L11 test names promise more than they check | P3 | Fixed 2026-11-10 — six `restore…` tests now assert the state they name |
| L12 hardcoded locations, placeholder package names | P3 | Fixed 2026-11-10 — recovery home overridable, preference nodes scoped and migrated |

---

## 2. Outstanding findings

### R4-6. Two logging systems, and the package layer's half reaches nobody

**Issue — P3.** Eight files still import `java.util.logging`; eighteen import SLF4J.

The package-layer four are the ones that matter: `service/IgPackageManager:18`,
`service/NpmPackageReader:10`, `service/PackageInstaller:10`,
`service/PackageRegistryService:18`. Only `slf4j-simple` is on the class path, so JUL writes
through its default `ConsoleHandler` to stderr and the SLF4J binding never sees it. In
development that is visible in the terminal; in a packaged image launched from the Start menu
there is no console, so `Skipping package …`, `No 4.0 release of … is published` and
`Cannot read dependencies of …` are shown to nobody.

The other four — `ui/IgPackageDialog`, `ui/MainWindow`, `ui/PluginManagerDialog`,
`ui/RestConsoleState` — matter less: `RestConsoleState` logs at `FINE` deliberately and
tolerates a missing backend, and the dialogs log their own recoverable failures.

**Fix:** migrate each file to SLF4J as it is touched. No new dependency: the binding is
already there.

### R4-7. `WorkspaceArchiveWriter` throws `NullPointerException` for a target with no parent

**Bug — P3.** `workspace/WorkspaceArchiveWriter:20-21`.

```java
Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
Files.createDirectories(target.getParent());
```

`Path.of("untitled.fhirworkspace").getParent()` is `null`, so a relative file name with no
directory — which public `WorkspaceService.saveAs(Path)` accepts — throws an NPE before
anything is written. `WorkspaceActions.save` catches `Exception`, so the user sees
`Failed to save workspace: null` in the status line instead of a readable reason.
`ServerDefinitionStore.writeProperties` and `PluginSettingsStore.writeProperties` both guard
the same call with `toAbsolutePath().getParent()`, so this is an inconsistency rather than an
unavoidable one.

**Fix:** `Path parent = target.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);`

### M1. `MainWindow` is 2,705 lines

**Enhancement — P3.** `ui/MainWindow.java`; 2,705 lines at `448f7fe` (2,693 in the fourth
pass — the M7 call site and the L12 recovery-home accessor added the twelve). The
decomposition plan, the two shared seams and an order that keeps each step reviewable are in
R4 §7; it has not been started.

### M7. The start-up restore is silent, and two providers are still unwired

**Enhancement — P1, open in part.** `workspace/WorkspaceService:60-61`, `ui/MainWindow:340`.

The workspace machinery now exists and runs: `MainWindow` constructs the service with the
server manager, plugin registry and package manager, and `WorkspaceActions.openLastWorkspace()`
reopens whatever the previous session had. What is still missing is the part M7 asked for:

- **No prompt.** `openLastWorkspace()` reopens and writes a status line. There is no offer
  to restore, so a user who does not want last session's workspace never gets the choice.
- **`TreeTabsWorkspaceStateProvider` is constructed with no callbacks** (`:60`), so its
  `capture()` returns an empty map and its `restore()` is documented as a no-op: the tree
  selection and open tabs are not actually saved.
- **`FhirPathWorkspaceStateProvider` is constructed the same way** (`:61`), so saved FHIRPath
  expressions are neither captured nor restored.

**Fix:** pass the tree/tabs and FHIRPath view's capture and apply callbacks at construction,
and put the restore behind the same kind of prompt `handleCloseDecision()` uses for closing.

---

## 3. New findings

### R5-1. `RestConsoleStateTest`'s javadoc describes a test isolation it does not have

**Issue (documentation) — P3.** `ui/RestConsoleStateTest`, class javadoc and `node()`.

The class says both that the tests "read and write the same preferences node the application
uses and clear it afterwards" and that "they do use a node of their own rather than the real
one, so running the suite cannot disturb a developer's remembered console settings". The two
claims contradict each other, and neither holds: `node()` delegates to
`RestConsoleState.nodeForTest()`, which returns `Preferences.userRoot().node(NODE)` — the
application's own `com/spiraleyes/fhirviewer/restConsole` node — and there is no `@AfterEach`
or equivalent clearing it.

The delegation is the right decision, and the javadoc argues for it well: a mock would not
catch a secret reaching the disk. What is wrong is the paragraph next to it, which promises
the opposite. This is the same defect shape as R4-10: a privacy-sensitive class whose
statement of intent was left saying two things.

**Fix:** keep the first justification and replace the contradictory paragraph with what the
tests actually do — they use the application's node on purpose, and therefore replace the
developer's remembered console state when the suite runs.

### R5-2. The preference-writing tests leave the developer's real settings changed

**Issue (test isolation) — P3.** `ui/RestConsoleStateTest`, `ui/WorkspaceWiringTest`.

Following from R5-1: both classes write the real user-root preference nodes, and neither
restores what was there. Running the suite replaces the remembered REST console request
(`RestConsoleStateTest`), and the recorded last workspace, including its `lastPath`
(`WorkspaceWiringTest`). On a developer machine that means the next application launch opens
whatever the last test wrote, or asks where to save because the test's archive was in a
temporary directory that is now gone.

The fix is small and does not cost the coverage the tests were written for: capture the
node's values in a `@BeforeEach` and put them back in an `@AfterEach`, or run each class
against `Preferences.userRoot().node(appNode + "-test")` seeded from nothing. The first keeps
the "same node as the application" property the javadoc argues for; the second is cleaner but
gives up that property.

**Fix:** save and restore the node contents around each test class.

---

## 4. Consolidated backlog

| ID | Type | Priority | Item | Where |
|----|------|----------|------|-------|
| R4-7 | Bug | **P3** | `WorkspaceArchiveWriter` NPEs for a relative target with no parent; the status line then says "Failed to save workspace: null" | `WorkspaceArchiveWriter:20-21` |
| R4-6 | Issue | **P3** | Package/IG layer still logs through `java.util.logging`, which a packaged build never shows | `IgPackageManager`, `PackageInstaller`, `PackageRegistryService`, `NpmPackageReader` |
| R5-1 | Issue | **P3** | `RestConsoleStateTest`'s javadoc promises a test isolation the class does not have | `RestConsoleStateTest` |
| R5-2 | Issue | **P3** | The preference-writing tests leave the developer's real settings changed | `RestConsoleStateTest`, `WorkspaceWiringTest` |
| M7 | Enhancement | **P3** | Start-up restore is silent; tree/tabs and FHIRPath providers still unwired | `WorkspaceService:60-61`, `MainWindow:340` |
| M1 | Enhancement | **P3** | `MainWindow` is 2,705 lines; the six extraction candidates are mapped in R4 §7 | `MainWindow` |

Closed since the fourth pass: R4-1, R4-2, R4-8, R4-9, R4-10, L9, L10, L11 and L12.
Closed since this pass was written: R4-3, R4-4 and R4-5.

---

## 5. Limitations

- Static reading only. No profile, no runtime exercise of the GUI, no measurement.
- The findings in §2 that remain open are carried over from the fourth pass and re-read,
  not reproduced: no malformed properties file and no relative save target without a parent
  was created for this pass.
- R4-3's fix is covered by two tests in `PackageInstallerTest` — a stale manager-wide gap is
  ignored, a gap the install itself creates is still reported —  and R4-4's by two more: a
  diamond that names two versions records one conflict, a diamond that agrees on one version
  records none. Both messages reach the user through `InstallResult.describe()` and
  `InstallPlan.describe()`, which the tests assert on; neither alert window was rendered.
- R4-5's fix is covered by loading a hand-written file whose highest block index has twenty
  digits, which also pins the numeric ordering across index lengths; the window that loads it
  at start-up was not launched.
- R4-3's fix compares the manager's unmet set before and after the install, so one case goes
  unreported: a package reinstalled while the same dependency key is still unmet. The key is
  already in the snapshot when `resolveDependencies` re-adds it, so it reads as unchanged.
- R5-1 and R5-2 were confirmed by reading the two test classes and by running their suites,
  which themselves write the real nodes. The effect on a developer's settings was not
  observed before and after a run.
- M7's unwired providers were confirmed from the two constructor calls; how much of the tree
  selection and FHIRPath expression state a user would miss was not exercised.
- R4 §7's line spans are from the fourth pass and have not been re-measured against 2,705
  lines.

---

## 6. Suggested order

1. **R4-7** — small, user-visible, and in a path two other writers already guard; a save that
   fails with an `NullPointerException` the status line renders as "null" is the worst thing
   left.
2. **R5-1 and R5-2** — a doc paragraph and a save/restore pair around the two test classes.
3. **R4-6 as each file is touched**, rather than as a sweep.
4. **M7**, then **M1** as opportunity allows; M1's plan is already written and its first two
   steps are mechanical.
