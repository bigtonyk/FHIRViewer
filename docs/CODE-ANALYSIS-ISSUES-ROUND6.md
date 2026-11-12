# FHIRViewer — Code Analysis, Sixth Pass

Date: 2026-11-11
Continues `CODE-ANALYSIS-ISSUES.md`, `CODE-ANALYSIS-ISSUES-ROUND2.md`,
`CODE-ANALYSIS-ISSUES-ROUND3.md`, `CODE-ANALYSIS-ISSUES-ROUND4.md` and
`CODE-ANALYSIS-ISSUES-ROUND5.md`. This pass deliberately reviews what the fourth and fifth
passes did not: the classes the M1 split produced, the scripting package, and the server and
REST layers those passes only touched in passing.

Scope: `ui/DocumentSession`, `ui/DocumentPort`, `ui/DocumentViews`, `ui/MainMenuFactory`,
`ui/ServerScreens`, `ui/ServerResourceActions`, `ui/IgPackageActions`, `ui/AboutDialog`,
`service/scripting/ScriptingCoordinator`, `service/scripting/ScriptingSession`,
`service/scripting/api/*`, `service/scripting/testing/*`, `model/LoadedResource`, and targeted
searches across `server/rest` and `server/smart`.

Method: static reading of the files above, targeted searches for exception swallowing, raw
thread creation, file I/O charsets and raw-text/origin handling, and the 2026-11-11 toolkit
walkthrough as evidence about the dialogs. Line numbers are from `ad49136`. No fix was made
during this pass.

## Priority legend

| Priority | Meaning |
|----------|---------|
| **P0** | Data loss, security exposure, or a crash in ordinary use. None found. |
| **P1** | A feature that does not work as presented, or a defect users hit in normal use. |
| **P2** | Robustness or maintainability problem that costs work, or a user-visible defect in a secondary screen. |
| **P3** | Hygiene, naming, consistency, stale documentation. |

IDs are stable: `R6-*` are new here.

## 1. Why these files

| Area | Why it was not reviewed before |
|------|-------------------------------|
| `DocumentSession` (985 lines) | Created by M1 step 5 on 2026-11-11; the fourth and fifth passes reviewed the old `MainWindow` text, not the extraction. |
| `ServerScreens`, `ServerResourceActions`, `MainMenuFactory`, `DocumentPort`, `DocumentViews`, `IgPackageActions`, `AboutDialog` | Created or rewritten by M1 steps 1-6 on the same day, after both passes were written. |
| `service/scripting` | Added in an earlier phase and never in a pass's scope; only `ScriptingCoordinatorTest` exercises it. |
| `service/scripting/testing` (11 classes) | Added with the scripting feature and never in a pass's scope. It has no caller outside its own package, so no earlier pass could have reached it through a code path, and no pass read it. |
| `model/LoadedResource` | Read here because the M1 paths construct it in several places. |
| `server/rest`, `server/smart` | Searched, not read in full: exception handling, thread creation and charset use only. |

What was checked and found clean: the scripting threading model (`ScriptingCoordinator` owns
one daemon worker; `ScriptingSession`'s stop flags and shell are `volatile`, the stop-before-start
hand-off is gated, and `close()` is idempotent), and charset use in file I/O (every
`Files.readString`/`writeString` in `src/main` names `StandardCharsets.UTF_8`, `FileSupport`
centralises the rest, and every `new Thread(...)` in `src/main` sets a daemon thread except the
recovery scheduler, which is deliberately non-daemon). The report exporter writes both formats
with `StandardCharsets.UTF_8`, and `ScriptTestRunner` really does create one `ScriptingSession`
per test (line 14) and close it in a `finally`, so the isolation its javadoc promises holds.

## 2. Outstanding findings

### R6-1. Undo silently drops the server origin, and the displayed Bundle entry

**Bug — P2. Fixed 2026-11-11.** Undo now passes the current origin to `display` and re-shows
the entry that was on screen, found again in the restored Bundle by position and label, so the
server actions stay enabled and the user keeps their place. Covered by two new tests in
`DocumentSessionCheckTest` (`undoKeepsTheServerOrigin`, `undoKeepsTheDisplayedBundleEntry`).
The original defect, as written:

`ui/DocumentSession`, `undoEdit` at line 869 called `display(restored, null)`.
`display` (lines 180-186) assigns `origin = newOrigin` and `entry = null`, so an undo does two
things it should not:

- A resource loaded from a FHIR server stops looking like one. `origin()` returns `null`, so
  `ServerResourceActions.refresh()`, `patch()` and `delete()` all answer "This resource did not
  come from a configured server", and the three server-action menu items disable. The user has
  only undone an edit; the resource is still the server's copy.
- An undo performed while a Bundle entry is displayed returns to the whole Bundle root: `entry`
  is cleared, so `showingEntry()` becomes false and the tree, Pretty View and title are rebuilt
  for the Bundle. The undo itself is correct; the context it returns to is not.

The history is otherwise sound: `display()` clears `undo` and `undoEdit` restores the remainder,
and the cap and the clean-at-the-bottom rule behave as `DocumentSessionCheckTest` asserts.

### R6-2. A resource found by server search can never be written back

**Gap — P3. Fixed 2026-11-11.** `ServerSearchDialog` now returns a `Picked` record carrying
the `LoadedResource` and the `ServerOrigin` built from the server, type, id and version, and
`ServerScreens.search` shows the pick with that origin. A result the server sent without an id
deliberately gets no origin, because an origin with no id means "create" and a search hit must
not become a new resource on save. `ServerSearchResultTest` covers both cases. The original
defect, as written:

`ui/ServerScreens`, `search()` at lines 173-177 showed the resource the dialog returned with
`null` origin, while `open()` at lines 200-218 deliberately attaches one, with a comment saying
a later "Save to FHIR Server" needs the type, id and version.
The difference is not only at the call: `ServerSearchDialog extends Dialog<LoadedResource>`
(`ServerSearchDialog:47`), and `LoadedResource` has no origin field, so the server that produced
the result is gone before the caller sees it. The consequence matches the intent stated in
`restConsole()` and `operation()` ("a result to look at, not something to save back") - but
those two say so and search does not. Either search should return the origin it knows, or the
method should say why it does not.

### R6-3. `LoadedResource.rawText` is write-only, and the server paths put a label in it

**Bug — P3, latent. Fixed 2026-11-12.** The three server paths that passed a label as raw text
(`ServerResourceActions` after a write and after a reload, and `ServerScreens.open`) now pass
`null`: a server copy has no loaded text, and the field's contract says so. `ServerSearchDialog`
already did. The original finding, as written:

`ServerResourceActions` constructed `LoadedResource` twice with the server
label in the fourth argument, which is `rawText` and not `sourceName`: lines 188-190 (after a
write) and 253-255 (after a reload) passed `OpenFromServerDialog.serverLabel(target)` where the
serialized text belongs. `LoadedResource`'s constructor is
`(resource, format, sourceName, rawText[, sourcePath])` (`model/LoadedResource:39-47`), and
`getRawText()` at line 74 is read only by `FhirServiceTest:108` asserting it is not null. So
nothing breaks today - the field is dead state - but the value every server path supplies is a
display label such as `Shiny CDR (https://...)`, and the first reader to trust it (a raw-source
view, a workspace capture, an export that prefers the loaded text) would get that label instead
of FHIR.

### R6-4. `openPath` replaces the document without asking about unsaved changes

**Convention — P3.** `ui/DocumentSession`: `openFile()` guards with `confirmUnsavedChanges`
(line 355) and so does `openSample()` (line 383), but `openPath()` at line 377 does not. Its two
callers are the guarded `openFile()` and `MainWindow.openOnStartup`, which runs before anything
can be dirty, so no edit is lost today. The guard is a rule at the call sites rather than an
invariant of the method that replaces the document - the same shape as the `captureAllCurrent()`
finding in the fourth pass, which became a null pointer the moment a second caller appeared.

### R6-5. `themeDialog` is copied five times, and its 620 px pin travels with it

**Hygiene — P3.** The identical helper exists as three private methods -
`DocumentSession:981`, `ServerResourceActions:405` and `ServerScreens:333` - each adding the
chapter's stylesheets and setting `prefWidth(620)`. `MainWindow` and `WorkspaceActions` carry
the same two lines under their own names. A change to how dialogs are themed (a new stylesheet,
a different width, a dark-mode-only adjustment) has to be made in five places, and the fifth is
where the fixed 620 conflicts with dialogs that set their own width (Server Status asks for 720,
the operation runner for 1040, Add child sizes itself around a 430 x 360 list). **Fixed 2026-11-12.** All five copies now call one `ui/DialogTheme.apply(pane, themeManager)`,
which owns the stylesheet list and the 620 px constant, and the two inline pairs in `MainWindow`
that the finding counted go through it as well. The pin can now be re-tuned in one place, which
is the point: whether 620 is the right width remains a separate question from the duplication.

### R6-6. The window's title rule is reachable only through a static on `MainWindow`

**Hygiene — P3.** `MainWindow.titleFor(label, dirty)` was made package-private so
`DocumentSessionCheckTest` can assert the exact title the window shows, which is right; but the
rule now lives on the class M1 exists to shrink, and `DocumentSession` owns the inputs
(`label()`, `isDirty()`) while `MainWindow` owns the output. A `DocumentTitle` value or a method
on `DocumentSession` would put the rule with the state. Listed for the next M1-shaped cleanup
rather than as a defect: nothing is miscabled.

### R6-7. The test-suite subsystem has no caller in the application

**Dead code - P2.** Nothing in `src/main` outside `service/scripting/testing` refers to it: no
import, no constructor, no button. The only coverage is `TestReportExporterTest` (four tests,
76 lines, exercising JSON and HTML output); `TestSuiteRunner`, `ScriptTestRunner`, `TestSuite`,
`TestCase`, `TestProgressListener` and `ConfirmationHandler` have no test and no production use.
That is eleven classes, about 340 lines, compiled and shipped for a feature the UI never
reaches. Either the wiring was lost (there is no console or dialog that builds a `TestSuite`)
or the feature was abandoned before it was connected; either way the package is the only part
of the scripting area a user cannot touch, and the next two findings are latent for that reason.

### R6-8. Test outcomes are classified by searching message text

**Bug - P2, latent.** `ScriptTestRunner` decides FAILED versus ERROR by substring
(`ScriptTestRunner:27-28`): an entry text containing `AssertionError` or `expected` makes the
test FAILED, anything else ERROR. Both directions are wrong. A JShell assertion failure does
render as `java.lang.AssertionError`, but a plain runtime exception whose message says
"expected 2 elements, found 1" is reported as a failed assertion; and an assertion library that
reports `Assertion failed:` or `org.opentest4j.AssertionFailedError` is reported as an error.
The message shown in the report compounds it: lines 30-31 take `entries().get(0)` - the first
line of any kind, usually ordinary printed output - rather than the first `EXCEPTION` entry,
which `ScriptRunResult.first(Kind.EXCEPTION)` already provides. A failing test that prints
before it throws is reported with the printed line as its message.

### R6-9. The HTML report omits the cancelled count the result tracks

**Hygiene - P3.** `TestSuiteResult` exposes `cancelledCount()` and the stylesheet styles
`.CANCELLED`, but `TestReportExporter.toHtml` (lines 74-79) prints only Total, Passed, Failed,
Errors, Skipped and Duration, so a run the user stopped reads as an unexplained short total.
`toJson` writes `cancelled` (line 47) but not `cancelledCount()` or `allPassed()`, so the two
formats report different facts; whichever is kept, they should agree.

### R6-10. A Stop pressed while a run was starting could be lost

**Bug — P2. Fixed 2026-11-12.** Found by the verification of R6-2, not by reading: the full
suite failed `ScriptingCoordinatorTest.concurrentRunIsRefused` with "stop must end the first
run" after a thirty-second wait, while the same class passed on its own.

`ScriptingCoordinator` marked a run started before the `ScriptingSession` existed, and
`stop()` in that window did neither of the two things that could end the run: it skipped
`session.stop()` because the session was still `null`, and its before-start flag was never
read again because the worker had already passed it. The five-minute snippet then ran with
the Stop spent. The session had a second, smaller window of the same kind: a Stop could land
before the run registered its id, be recorded against the previous run, and be discarded as
stale.

The fix reads the stop flag again after the session exists (`ScriptingCoordinator.sessionForRun`)
and arms the session there, calls the session only while that run is armed, and has the session
apply a pending stop to the run that registers and clear it when that run ends - so the stop
cannot leak into the next run either. `ScriptingCoordinatorTest.stopDuringStartUpIsHonoured`
hammers the window ten times and then proves a normal run still completes.

A third window was an ordering bug in the session's own check: a Stop that wrote its run id
while the run was reading the flag was then overwritten by the run's `else` branch clearing it,
so the retry saw nothing to do after one early call. The run now leaves a stop that names it in
place, and only clears a stop that is already spent.

After the fix the full suite failed once more, this time on the test's own thirty-second wait:
under a loaded JVM `JShell.create` can outlast it, so the worker was still initialising the
engine when the await expired. Both timing tests now warm the session first, so they measure
Stop rather than engine start-up.

Measured against a scratch JShell, one `stop()` call is not reliable on its own either: with a
snippet sleeping for a minute it left the snippet running in one case out of three, and
repeating the call ended every attempt in under 210 ms. `ScriptingSession.stop` therefore
retries it on a daemon thread, bounded at about 800 ms and guarded by the run id, so it cannot
interrupt a later run and the Stop button still returns immediately.

## 3. Limitations

- Static reading plus the earlier toolkit walkthrough. No dialog was opened during this pass,
  and no test was run beyond the suite that was already green at `ad49136` (1,041 tests).
- R6-1's first half is certain from the code: `display(restored, null)` clears `origin`. That
  the menu items then disable follows `MainWindow.updateServerActions`, which was read but not
  executed for this pass.
- R6-3's impact is stated as latent on purpose: `getRawText()` has no production reader, so no
  user-visible failure was reproduced.
- R6-2 may be intended. It is listed because the code says nothing either way, and the two
  neighbouring methods that drop an origin both explain themselves.
- The scripting package's threading was read rather than stress-tested, with one exception:
  the stop path (R6-10) is covered by `stopDuringStartUpIsHonoured`, six consecutive runs of
  `ScriptingCoordinatorTest` and a clean full suite. No sustained-load or repeated-reset run
  was made for the rest of the package.
- R6-7 is why R6-8 and R6-9 were established by reading rather than by running a suite: the
  runner has no path from the application, so no user-visible reproduction exists. If the
  subsystem is wired up, the classification needs a real test before it is trusted.
- `pretty/` (`PrettyModelBuilder`, `DataTypeFormatter`, 818 lines) is named in no pass's
  documented scope either. It was not read here; it is the obvious subject of a seventh pass.
- `server/rest` and `server/smart` were searched, not read: the searches covered exception
  handling, thread creation, charset use and `ServerErrors`, not their protocol logic.

## 4. Suggested order

1. **R6-1** — done on 2026-11-11 (see above). It was the only finding here a user could hit in
   ordinary use: edit, undo, then try to save back to the server.
2. **R6-2** — done on 2026-11-11 (see above); the dialog result type now carries the origin.
3. **R6-3** — done on 2026-11-12 (see above). The parameter is absent on the server paths, so
   the field cannot be filled with the wrong thing again.
4. **R6-4** and **R6-6** as hygiene, together with any further `MainWindow` work (**R6-5** was
   fixed on 2026-11-12).
5. **R6-7** needs a decision before R6-8 or R6-9 are worth touching: wire the suite runner to a
   console, or delete the package. If it is wired, fix the classification first - a suite report
   that calls a runtime exception a failed assertion is worse than no report.
