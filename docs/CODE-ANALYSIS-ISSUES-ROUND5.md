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
| R4-6 package layer logs through JUL | P3 | Fixed 2026-11-10 — every file now logs through SLF4J |
| R4-7 writer NPEs for a parentless target | P3 | Fixed 2026-11-10 — the parent directory is resolved before it is created |
| R4-8 `closingFromAction` never cleared | P3 | Fixed 2026-11-10 — flag lasts one close |
| R4-9 `captureAllCurrent()` NPE at start-up | P3 | Fixed 2026-11-10 — throws `IllegalStateException` |
| R4-10 mangled console javadoc bullet | P3 | Fixed 2026-11-10 |
| M1 `MainWindow` size | P3 | **Fixed 2026-11-11** — 1,099 lines; all six R4 §7 steps done |
| M7 workspace restore | P1 | Fixed 2026-11-10 — restore is offered at start-up; tree/tabs and FHIRPath state wired |
| L9 passphrase as un-zeroable `String` | P3 | Fixed 2026-11-10 — `char[]` end to end on the session path |
| L10 diagnostics charset | P3 | Fixed 2026-11-10 — export writes UTF-8 explicitly |
| L11 test names promise more than they check | P3 | Fixed 2026-11-10 — six `restore…` tests now assert the state they name |
| L12 hardcoded locations, placeholder package names | P3 | Fixed 2026-11-10 — recovery home overridable, preference nodes scoped and migrated |

---

## 2. Outstanding findings

### M1. `MainWindow` is 1,099 lines

**Enhancement — P3, complete.** `ui/MainWindow.java`; 1,099 lines now (1,271 before the menu factory moved, 1,923 before the editor core moved, 2,071 before the file operations, 2,292 before step 4, 2,599 before step 3, 2,777 before the first
split step, 2,705 at `448f7fe`, 2,693 in the fourth pass). R4 §7's order was followed:

- **Step 1 done** — `AboutDialog` (174 lines) took the dialog, its logo cache, its link
  handling and the `hostServices` field with it; the window keeps the Help menu item and one
  `AboutDialog` field, and the logo statics left `MainWindow` entirely.
- **Step 2 done** — `IgPackageActions` (104 lines) took the Implementation Guide menu, the
  package manager dialog and the loaded-package summary, together with the rule that the
  profile picker is always refreshed while the workspace is marked dirty only when the
  installed count actually moved.
- **Step 3 done** — `ServerResourceActions` (435 lines) took save-to-server, patch, delete,
  refresh and reload, the conflict question, and the `serverFor`/`versionOf` helpers. It
  reaches the window through the shared `DocumentPort` (resource, origin, show, confirm
  discard, close, show validation, busy) plus a status sink; the write rules themselves stay in
  `ServerResourceCoordinator`.
- **Step 4 done** — `ServerScreens` (337 lines) took the server manager, the plugin manager,
  server search, open-from-server, the SMART workbench, the connection screen, the operation
  runner and the REST and scripting consoles. It needs the stage, the two services,
  `themeManager`, the search memory, the capabilities cache and the credential passphrase,
  plus the two callbacks that stay in the window: persist the server list, refresh menu state.
  The document seam became its own file, `DocumentPort` (eight methods), because steps 3 and 4
  need the same one.
- **Step 5 done** — `DocumentSession` (985 lines) owns the document: the loaded resource, its
  origin, the displayed Bundle entry, the label, the tree selection, the undo history, the view
  rendering (`display`, `refreshTree`, `displayBundleEntry`, `updateDocumentViews`,
  `refreshAfterEdit`), the element edits, the source-apply path, `closeResource` and the
  unsaved-changes prompt. `MainWindow` keeps the views because `buildLayout` builds them and
  hands them over once through `DocumentViews`; the session is also the `DocumentPort` the
  server classes talk to, so the anonymous port implementation is gone. File operations moved
  with it, which collapsed the earlier 5a/5b split into one class.
- **Step 6 done** — `MainMenuFactory` (314 lines) builds the File, Edit, View, Tools, Help,
  Workspace and IG menus and returns the ten items whose enabled state the window sets
  (`Items`), so `updateEditActions` and `updateServerActions` still own the enabling, exactly
  as R4 §7 intended. Handlers come from the objects that own them; the six window-level ones
  go through a small `WindowActions` interface. `DocumentViews` gained the five tab accessors
  the View menu needs.

---

## 3. New findings

Both findings this pass added are fixed: R5-1's javadoc now states the real-node cost, and R5-2's save/restore pair keeps the developer's console node out of the suite. They are listed with the other closed items in §4.

---

## 4. Consolidated backlog

| ID | Type | Priority | Item | Where |
|----|------|----------|------|-------|
| M1 | Enhancement | **P3** | `MainWindow` is 1,099 lines; `AboutDialog`, `IgPackageActions`, `ServerResourceActions`, `ServerScreens`, `DocumentSession` and `MainMenuFactory` extracted; M1 complete | `MainWindow` |

Closed since the fourth pass: R4-1, R4-2, R4-8, R4-9, R4-10, L9, L10, L11 and L12.
Closed since this pass was written: R4-3, R4-4, R4-5, R4-6, R4-7, M7, R5-1 and R5-2.

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
- R4-7's fix is covered by saving to a relative file name with no directory; the status line
  `WorkspaceActions.save` writes on failure was not observed on a running window.
- R4-6's migration is verified by a search showing `java.util.logging` is gone from the source
  and by the suites that exercise the package and console paths still passing. Whether a
  packaged image now shows the messages was not observed: no image was built or launched, so
  the claim established here is that the eight files use the binding the other eighteen use.
- R4-3's fix compares the manager's unmet set before and after the install, so one case goes
  unreported: a package reinstalled while the same dependency key is still unmet. The key is
  already in the snapshot when `resolveDependencies` re-adds it, so it reads as unchanged.
- R5-1's fix is a corrected javadoc paragraph, which no test can check. R5-2's fix is covered
  by the suite itself: the class snapshots the console node before each test, starts from an
  empty one and restores the developer's afterwards, and the run that shows the tests still
  pass is the run that exercises that restore. A before-and-after comparison of a developer's
  node across a real `mvnw test` was not made.
- M7's fix is covered by tests that the start-up offer is asked and can be declined, and that
  tree/tab and FHIRPath state passed to the service round-trips through a workspace archive.
  The window itself was not launched, so the prompt's wording, the restored tab selection and
  the restored expressions were not seen on screen.
- R4 §7's line spans are from the fourth pass and have not been re-measured against 1,099
  lines; the M7 wiring and the two split steps have moved every region after the fields.
- M1's steps were checked at three levels: `test-compile` after every slice, the full suite
  (1,039 tests, 0 failures) after the editor-core move, and one real launch of the window on
  this machine with a sample file passed on the command line — it started, loaded the sample
  through the new session and stayed up for 25 seconds with no stack trace. Interaction is not
  covered: no edit, undo, delete, source apply or Save As was performed, so the dirty flag,
  undo depth, asterisk and tree rebuild order are still unobserved, as are the About and
  Implementation Guide dialogs and the nine server dialogs.

---

## 6. Suggested order

1. **M1** as opportunity allows; its plan is already written and its first four steps are
   mechanical.
