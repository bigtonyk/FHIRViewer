# FHIRViewer — Code Analysis, Fourth Pass

Date: 2026-11-10
Continues `CODE-ANALYSIS-ISSUES.md` (2026-11-02), `CODE-ANALYSIS-ISSUES-ROUND2.md`
(2026-11-04) and `CODE-ANALYSIS-ISSUES-ROUND3.md` (2026-11-10). This pass reads the layers
the earlier passes touched least — FHIR package installation, the package registry, saved
server settings, the REST client, SMART, and the workspace file writers — and adds ten new
findings (`R4-*`). Nothing from the third pass has regressed. Section 7, written after the pass, is the M1
decomposition plan rather than a finding.

Scope: the package/IG layer (`service/PackageInstaller`, `PackageRegistryService`,
`NpmPackageReader`, `IgPackageManager`), server persistence (`ServerDefinitionStore`,
`PluginSettingsStore`, `ServerCredentialSaver`, `SecretBox`), transport
(`JdkHttpRestClient`, `TransportSecurity`, `Pkce`), the workspace file code
(`WorkspaceService`, `WorkspaceArchiveReader/Writer`, `WorkspaceActions`), and the close
path in `MainWindow`.

Method: static reading, roughly twenty files in full or in the relevant half, plus targeted
searches for thread creation, interrupted-exception handling, number parsing,
`java.util.logging` usage and path construction. No build and no test run was performed
*for this pass*; the suite was green earlier the same day for the R3-6 change
(16 tests in the three workspace/package/persistence classes).

## Priority legend

| Priority | Meaning |
|----------|---------|
| **P0** | Data loss, security exposure, or a crash in ordinary use. None found. |
| **P1** | A feature that does not work as presented, or a defect users hit in normal use. |
| **P2** | Robustness or maintainability problem that costs work, or a user-visible defect in a secondary screen. |
| **P3** | Hygiene, naming, consistency, stale documentation. |

IDs are stable: `H*`, `M*`, `L*` come from the first two passes, `R3-*` from the third, and
`R4-*` are new here.

---

## 1. Status of the earlier passes

| Item | Priority | State |
|------|----------|-------|
| H1–H6 | P1 | Fixed |
| M1 `MainWindow` size | P3 | Open — 2,693 lines; split plan in §7 |
| M2–M6 | P3 | Fixed |
| M7 workspace restore | P1 | Fixed except the start-up restore offer and the two unwired providers |
| M8 double close prompt | P2 | **Fixed 2026-11-10** — see R4-8 |
| M9 `WorkspaceService.open()` ordering | P2 | **Fixed 2026-11-10** — see the note below the table |
| L1–L8, L14 | P3 | Fixed |
| L9 `SecretBox` passphrase as `String` | P3 | Open by design, documented |
| L10 diagnostics charset | P3 | Open |
| L11 test names promise more than they check | P3 | Open |
| L12 hardcoded locations, placeholder package names | P3 | Open — `RestConsoleState` still uses `com/example/fhirviewer/restConsole` |
| R3-1 … R3-6 | P2/P3 | Fixed, see the third pass |

**Fixed after this pass:** M8, whose flag now lasts exactly one close (R4-8), and M9, whose
`open()` now applies an archive's sections before adopting it — a provider failure is
reported as an `IOException` with the previous workspace, path and dirty flag intact, and is
covered by `WorkspaceServiceIntegrationTest`.

---

## 2. New findings

### R4-1. Package registry requests have no deadline at all

**Bug — P2.** `service/PackageRegistryService:47-52`, `:199-204`, `:375-380`.

```java
this.httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
...
HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Accept", "application/json")
        .GET()
        .build();
```

Neither the shared client nor either request sets a connect or request timeout. Every
other HTTP path in the application does: `JdkHttpRestClient` resolves a deadline per
request from the server configuration and falls back to twenty seconds, and SMART
discovery, JWKS and token exchange each carry their own. This is the one exception, and it
is the path with the worst failure mode: a registry that accepts the connection and then
stalls — a captive portal, a proxy that holds sockets open, a registry under load — leaves
the search or planner thread blocked indefinitely. The dialog shows "Searching…" with no
failure, no progress and no way forward except closing the window. Interruption works
(`:204-208` translates it and restores the flag), so Cancel still ends the wait, but a user
who does not press Cancel waits forever.

The same client is used for the `.tgz` download at `:375-380`, where a stalled body read has
the same effect on an install the user has already confirmed.

**Fix:** give the class a named deadline constant (twenty seconds matches `JdkHttpRestClient`),
set `.connectTimeout(...)` on the builder and `.timeout(...)` on both requests, and let the
existing `PackageRegistryException` reporting carry the failure.

### R4-2. A downloaded package's file name comes from registry metadata unsanitised

**Bug (robustness, remote-controlled path) — P2.** `service/PackageRegistryService:399`, used at
`:344` and `:370`.

```java
public static String fileNameOf(PackageInfo packageInfo) {
    return packageInfo.getPackageId() + "-" + packageInfo.getVersion() + ".tgz";
}
...
Path targetPath = destination.resolve(fileNameOf(packageInfo));
```

`packageId` and `version` are not validated anywhere between the packument JSON and
`destination.resolve(...)`. `parsePackument` takes the package id from the document's
`name` field and the version from the packument's `versions` keys (`:240-249`). A value
containing a path separator or `..` therefore resolves outside the package directory, and
`HttpResponse.BodyHandlers.ofFile(targetPath)` writes the response body to whatever path
results — a directory traversal driven by a remote document, into the user's own profile.

The registry is `https://packages.fhir.org` and is trusted today, so this is not a live
attack; it is the one place in the application where remote metadata becomes a filesystem
path, and the workspace archive reader by contrast refuses absolute paths and any `..`.
`fileNameOf` is also `public static`, so a plugin can call it with anything.

**Fix:** reject a package id or version that is not `[A-Za-z0-9._+-]+` (a FHIR package id and
a semver both are), and assert the resolved path's parent is `destination` before handing it
to the body handler.

### R4-3. An install reports every unmet dependency in the session as its own failure

**Bug — P3.** `service/PackageInstaller:278`.

```java
for (String unmet : packageManager.getUnmetDependencies()) {
    failures.add("missing dependency " + unmet);
}
```

`getUnmetDependencies()` is manager-wide state, not this install's result. It is cleared
only for the packages a later `register()` happens to add, so a dependency recorded during
an earlier install (or by the start-up auto-load) is still in the set when the next package
is installed — and the dialog then reports "Could not install: missing dependency X" for a
package the user just added successfully. The plan itself already knows which dependencies
it resolved, so the information needed to report this correctly is in hand.

**Fix:** snapshot the set before installing and report only additions, or ask the loader for
unmet dependencies of the packages this plan touched.

### R4-4. The planner dedupes by package id and drops version conflicts silently

**Issue — P3.** `service/PackageInstaller:200`, `:208-209`, `:226`.

`planned.containsKey(id)` means the first requirement for a package id wins, whatever
version it asked for. A diamond that requires `hl7.fhir.uv.genomics-reporting#2.0.0` through
one path and `#3.0.0` through another installs whichever was resolved first and never
compares the second; the other version is not fetched, and the mismatch surfaces later as
"missing dependency" from `IgPackageManager.resolveDependencies`, which is exactly the
message R4-3 then misattributes. Nothing in `InstallPlan` records that two versions were
requested, so the dialog cannot warn either.

The same method also calls `packageManager.getPackage(id)` twice per dependency (`:208-209`)
and carries each queue entry as a positional `String[]` (`:196`, `:227`).

**Fix:** key the plan by package id but keep the requested versions, and either fail the
plan with "X is required at 2.0.0 and 3.0.0" or state which version won and why.

### R4-5. A hand-edited server file can abort start-up with `NumberFormatException`

**Bug — P3.** `server/ServerDefinitionStore:220`.

```java
ordered.sort(Comparator.comparingInt(Integer::parseInt));
```

The index is checked to be digits (`:215`) but not to be an `int`, so an index such as
`server.99999999999999999999.name=…` throws `NumberFormatException` out of
`blockIndices()`, `parse()` and `read()`. `FhirServerManager.load` catches `IOException` only
(`:120-126`), and `MainWindow.loadSavedServers` (`:1548-1549`) does not catch at all, so the
window constructor fails — a file that the class elsewhere goes out of its way to survive
(one bad block is skipped, not fatal) crashes the application instead.

**Fix:** skip an index that does not parse as an `int`, or sort numerically by length and
then lexicographically so no parse is needed.

### R4-6. Two logging systems, and the package layer's half reaches nobody

**Issue — P3.** `service/IgPackageManager`, `service/PackageInstaller`,
`service/PackageRegistryService`, `service/NpmPackageReader`; also referenced in
`ui/IgPackageDialog`, `ui/MainWindow`, `ui/PluginManagerDialog`, `ui/RestConsoleState`.

Eight files still use `java.util.logging`; twenty use SLF4J. Only `slf4j-simple` is on the
class path, so JUL writes through its default `ConsoleHandler` to stderr, and the SLF4J
binding never sees it. In development that is visible in the terminal; in a packaged image
launched from the Start menu there is no console at all, so the messages that matter most
for this layer — `Skipping package …`, `No 4.0 release of … is published`, `Cannot read
dependencies of …` — are shown to nobody. This is the same defect shape the third pass fixed
for `System.err` in the workspace providers and for the toolkits in M4.

`RestConsoleState` logs at `FINE` deliberately and tolerates a missing backend, so it is the
least important of the eight; the four `service/` classes are the ones worth converting.

**Fix:** migrate each file to SLF4J as it is touched. No new dependency: the binding is
already there.

### R4-7. `WorkspaceArchiveWriter` throws `NullPointerException` for a target with no parent

**Bug — P3.** `workspace/WorkspaceArchiveWriter:20-21`.

```java
Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
Files.createDirectories(target.getParent());
```

`Path.of("untitled.fhirworkspace").getParent()` is `null`, so a relative file name with no
directory — which `WorkspaceService.saveAs(Path)` accepts, since it is public — throws an
NPE before anything is written. `WorkspaceActions.save` catches `Exception` (`:184`), so the
user sees `Failed to save workspace: null` in the status line instead of a readable reason.
`ServerDefinitionStore.writeProperties` and `PluginSettingsStore.writeProperties` both
guard the same call with `toAbsolutePath().getParent()`, so this is also an inconsistency
rather than an unavoidable one.

**Fix:** `Path parent = target.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);`

### R4-8. `MainWindow.closingFromAction` is set and never cleared

**Bug (latent) — P3.** `ui/MainWindow:323`, `:347`, `:1522`.

```java
private void closeWindow() {
    if (!confirmUnsavedChanges("exiting") || !workspaceActions.closeDecision()) {
        return;
    }
    closingFromAction = true;
    stage.close();
}
```

The flag exists so the Exit action does not ask twice, and the close-request handler skips
both prompts while it is set. Nothing clears it if the stage does not actually close — a
close request can be consumed by any handler, and the window already has one that consumes
it (`:346-354`). After a vetoed close, every later close and every later Exit skips the
unsaved-resource prompt and the workspace decision entirely, so work is discarded without a
question. That is a narrow path today and a trap for the next handler added.

**Fix:** reset the flag when the stage is still showing after `stage.close()`, or drive the
whole path from one handler and let the action call `stage.fireEvent(new WindowEvent(…))`
instead of setting a flag.

**Fixed 2026-11-10.** `closeWindow()` now sets the flag inside a `try` and clears it in a
`finally`. The close request that `stage.close()` fires still runs inside that window and
still skips its prompts; a close another handler vetoes leaves the flag clear, so the next
Exit or window close asks about unsaved work again. Compile-checked only: no test constructs
`MainWindow`, and the veto path needs a live window to reproduce.

### R4-9. `WorkspaceService.captureAllCurrent()` throws when no workspace is open

**Bug (API trap) — P3.** `workspace/WorkspaceService:145-147`.

```java
public WorkspaceMetadata captureAllCurrent() {
    return captureAll(current);          // current is null at start-up, by design
}
```

`captureAll` dereferences `base.schemaVersion()` immediately (`:161`), so the method throws
NPE whenever nothing is open — the state the class deliberately keeps at start-up, and the
state `openLastFromPreferences()` documents returning `null` for. Its only caller wraps it in
`catch (Exception)` (`WorkspaceRecoveryService:197`), so the trap is invisible today; the next
caller that trusts the declared return type gets an NPE where `save()` throws a readable
`IllegalStateException("No workspace open")`.

**Fix:** return `null` (and say so) or throw the same `IllegalStateException` `save()` uses.

### R4-10. The REST console's javadoc bullet was mangled by the R3-3 edit

**Issue (documentation) — P3.** `ui/RestConsoleState:37-40`.

```
 *   <li><b>The request body.</b> A body is far more likely to be a patient resource than a
 *       credential, and writing one to disk unasked is a decision this tool should not make
 *       silently. It is a one-line change if that trade is judged wrong.</li>
 *       silently. It has no component in this record at all, so a later edit to the capture
 *       code cannot start persisting one by accident.</li>
```

Two versions of the same sentence were merged instead of one replacing the other. The first
fragment is now false — there is no one-line change, the component is gone — and the bullet
reads as though it were written twice. It is the class's statement of a privacy decision,
so it is worth reading correctly.

**Fix:** keep the second half only: "It has no component in this record at all, so a later
edit to the capture code cannot start persisting one by accident."

---

## 3. Consolidated backlog

Every open item, highest priority first. Type is Bug / Issue / Enhancement.

| ID | Type | Priority | Item | Where |
|----|------|----------|------|-------|
| R4-1 | Bug | **P2** | Registry requests and downloads have no deadline, so a stalled registry blocks the search or install thread indefinitely | `PackageRegistryService:47-52, 199-204, 375-380` |
| R4-2 | Bug | **P2** | Downloaded package file name is built from registry metadata unsanitised; `..` or a separator escapes the package directory | `PackageRegistryService:399, 344, 370` |
| R4-3 | Bug | **P3** | An install reports every unmet dependency in the session as its own failure | `PackageInstaller:278` |
| R4-4 | Issue | **P3** | Planner keys dependencies by id only, so a version conflict is dropped silently | `PackageInstaller:200, 226` |
| R4-5 | Bug | **P3** | An over-large numeric block index throws `NumberFormatException` out of `read()` and aborts start-up | `ServerDefinitionStore:220` |
| R4-6 | Issue | **P3** | Package/IG layer still logs through `java.util.logging`, which a packaged build never shows | `IgPackageManager`, `PackageInstaller`, `PackageRegistryService`, `NpmPackageReader` |
| R4-7 | Bug | **P3** | `WorkspaceArchiveWriter` NPEs for a relative target with no parent; the status line then says "Failed to save workspace: null" | `WorkspaceArchiveWriter:20-21` |
| R4-8 | Bug | **P3** | `closingFromAction` is never cleared, so a vetoed close makes every later close skip both prompts | `MainWindow:323, 347, 1522` |
| R4-9 | Bug | **P3** | `captureAllCurrent()` NPEs when nothing is open; only its one caller's blanket catch hides it | `WorkspaceService:145-147` |
| R4-10 | Issue | **P3** | REST console javadoc bullet duplicated and half-false after the R3-3 edit | `RestConsoleState:37-40` |
| M1 | Enhancement | **P3** | `MainWindow` is 2,693 lines; the six extraction candidates are mapped in §7 | `MainWindow` |
| L10 | Bug | **P3** | Diagnostics export writes with the platform charset, not UTF-8 | `SmartDiagnosticsPane:86` |
| L11 | Issue | **P3** | Several `restore…` tests assert only "does not throw" while their names promise a populated state | `*WorkspaceStateProviderTest` |
| L12 | Issue | **P3** | Hardcoded locations and placeholder package names, including `com/example/fhirviewer/...` preference nodes | `WorkspaceActions:60`, `WorkspaceService:17`, `RestConsoleState:70`, `PackageStorage:105` |
| L9 | Issue | **P3** | `SecretBox` keeps the passphrase in an un-zeroable `String`; deliberate, documented | `SecretBox:53, 79` |
| M7 | Enhancement | **P3** | Start-up restore prompt still absent; tree/tabs and FHIRPath providers still unwired | `workspace/*WorkspaceStateProvider` |

Closed since the third pass: R3-1 … R3-6, and M8 and M9 in this pass.

---

## 4. Re-verified strengths

- **Archive I/O is still defensive.** `WorkspaceArchiveReader` rejects absolute paths,
  traversal, duplicate entries, more than 500 entries, more than 10 MB and schema versions
  outside 1..3; the writer goes through `target.tmp` then `ATOMIC_MOVE`. R4-2 is notable
  precisely because the package importer has no equivalent.
- **Credentials are encrypted properly, and keyed per server.** `SecretBox` is PBKDF2-HMAC-SHA256
  at 210,000 rounds with a per-encryption salt, AES-GCM with a per-encryption IV, one error
  message for a wrong passphrase and a corrupt file, and a `toString()` that cannot print a
  secret. `PluginSettingsStore` files by credential key and refuses a legacy entry whose
  saved URL does not match, so a password cannot be sent to a host it was not saved for.
- **The REST client is careful in the ways that matter.** It owns and closes its executor
  and HTTP client, applies the session's headers without letting them override a
  per-request one, percent-encodes both halves of every query pair, and keeps query, body and
  headers out of failure messages.
- **Transport warnings are real.** `TransportSecurity` treats loopback as safe, refuses to be
  fooled by `127.0.0.1.example.com`, handles a bracketed IPv6 host and userinfo, and
  `ServerCredentialSaver` warns before a password crosses plain HTTP.
- **PKCE is standard-library cryptography**, not a hand-written protocol: `SecureRandom`,
  `SHA-256`, base64url, verifier never printed.
- **Asynchronous work still has one shape.** `BackgroundTasks` wraps every network call in a
  daemon thread with a JavaFX-thread callback, a typed failure and cooperative cancellation.
- **The recovery service has a lifecycle now.** `close()` drains queued snapshots, is
  idempotent, and a generation counter disowns snapshots a save or reset has overtaken.
- **Tests exist for the risky parsing.** `ServerDefinitionStore`, `PluginSettingsStore`,
  `SmartIdTokenValidator`, `RestUrls`, `ServerOrigin`, `TransportSecurity` and the archive
  round-trip all have cases; the gaps are named above rather than absent.

---

## 5. Limitations

- Static reading only. No profiling, no runtime exercise of the GUI, no measurement. No build
  and no test run was performed for this pass.
- The package layer was read in full but not exercised: no registry request was made, so
  R4-1 and R4-2 are conclusions from the code, not from an observed stall or write outside
  the directory.
- R4-5's crash path assumes a hand-edited or otherwise malformed properties file; the
  application never writes an index that large.
- R4-8 depends on a close request being vetoed, which no current handler does except this
  one; it is reported as latent.
- HAPI's package registry format, the NPM tarball parser and the JShell internals remain
  outside this pass, as in the earlier ones.
- `IgPackageDialog`, `RestRequestPane` and most of `MainWindow` were skimmed rather than read
  line by line; findings there would come from a targeted pass.

---

## 6. Suggested order

1. **R4-1, R4-2** — the registry's missing deadline and the unsanitised file name; one small
   edit each, in the one layer that talks to a third party and writes what it says.
2. **R4-3, R4-4, R4-5** — the install report, the version conflict and the start-up crash.
3. **P3 tail** — R4-6 through R4-10, then L10–L12 and M1 as opportunity allows.

---

## 7. M1: what a `MainWindow` split would actually cost

`MainWindow` is not one job done badly; it is five jobs that happen to share six fields
(`loadedResource`, `displayedResource`, `displayedEntry`, `displayedOrigin`, `selectedNode`,
`undoStack`) and one stage cursor. Every candidate extraction moves one of those jobs, which
means the work is less about cutting lines than about choosing where the seam goes. The
method spans below were read for this section; the sizes are whole span lengths, so imports,
field declarations and blank lines are counted once in the first row rather than in each.

| Region | Lines | Size | Job | Candidate |
|--------|-------|------|-----|-----------|
| Constants, fields, constructor, start-up IG load | 105–386 | 282 | composition root | stays |
| Layout, header bar, toolbar | 388–448, 450–498, 729–739 | 121 | shell assembly | optional `WindowChrome` |
| File/Edit/View/Tools/Help/IG menus, guides | 500–727, 2626–2643 | 227 | construction + enable state | `MainMenuFactory`, last |
| Wiring, theme menu items, navigation helpers | 741–849 | 109 | shell assembly | stays |
| Open, display, Bundle navigation | 851–1036 | 186 | document state | `DocumentSession` |
| New, Save, Save As, write to file | 1038–1141 | 104 | document state | `DocumentSession` |
| Element editing, undo, unsaved-changes prompt | 1143–1500 | 358 | editing | `ElementEditorActions` + history |
| Workspace delegation | 1502–1525 | 24 | already delegated | stays (`WorkspaceActions`) |
| Dialog theming | 1527–1546 | 20 | shared infrastructure | `Dialogs` helper |
| Server configuration, server screens | 1548–1769, 2083–2173 | 313 | server screens | `ServerScreens` |
| Save/patch/delete/refresh/conflict on a server | 1771–2081 | 311 | server writes | `ServerResourceActions` |
| Validation and profile picker | 2175–2262 | 88 | validation | `ValidationActions` |
| Export, edited source text, close resource | 2264–2386 | 123 | document state | `DocumentSession` |
| Help, About, logo | 2388–2499 | 112 | self-contained | `AboutDialog` |
| Status line, busy cursor, alerts, action enablement | 2524–2624 | 101 | shared infrastructure | `UiFeedback` + `ActionState` |
| IG menu and package dialogs | 2645–2691 | 47 | self-contained | `IgPackageActions` |

### The two seams everything needs

1. **Document state.** Eight methods outside the editor — `saveToServer`, `export`,
   `openFromServer`, the REST console, the SMART workbench, `displayBundleEntry`,
   `closeResource`, the tree selection handler — need the same six fields and the same three
   operations: *show a resource*, *ask before replacing it*, *save it*. That is a five-method
   port (`show`, `confirmReplace`, `save`, `current`, `currentJson`), not a class hierarchy.
2. **Feedback.** `setStatus`, `setBusy`, `showAlert`, `showFailure`, `applyDialogTheme`,
   `updateEditActions`, `updateServerActions`. The first four are called from about forty
   places, so extracting them saves ~100 lines and changes forty call sites: real but small
   payoff, and the worst churn-per-line ratio in the list.

The project already has this pattern twice: `WorkspaceActions` (235 lines) takes
`(Stage, WorkspaceService, ThemeManager, Consumer<String> status)` and the window passes
`this::setStatus`, and `ServerResourceCoordinator` (279 lines) takes no JavaFX at all. A
split that copies that shape is an extension of the house style, not a re-architecture.

### Order that keeps each step reviewable

1. **`AboutDialog`** (112 lines). No shared state; `themeManager`, `hostServices` and the
   cached logo go with it. Removes the `applicationLogo`/`applicationLogoFailed` statics.
   → ~2,580 lines.
2. **`IgPackageActions`** (47 lines plus the 18-line menu). Needs the package manager,
   `status`, `refreshProfileChoices` and `workspaceActions.markWorkspaceChanged()`. → ~2,515.
3. **`ServerResourceActions`** (311 lines). The most cohesive block in the file: it is
   already one job — write the displayed resource, resolve a conflict, patch, delete, reload
   — and its only view dependency is the five-method port plus `status`. Behaviour moves
   unchanged, thread hops included; the risk is a missed `updateServerActions()` call, which
   the compiler and one launch will show. → ~2,200.
4. **`ServerScreens`** (313 lines). Dialogs only: `manageServers`, `managePlugins`,
   `searchServer`, `openFromServer`, `openSmartLaunchWorkbench`, `showServerStatus`,
   `runServerOperation`, `openRestConsole`, `openScriptingConsole`, plus `serverFor` and
   `versionOf`. Needs `stage`, the two services, `themeManager`, `searchMemory`,
   `capabilitiesCache` and the same port. → ~1,890.
5. **`DocumentSession` + `ElementEditorActions`** (186 + 104 + 123 + 358 ≈ 770 lines). The
   invasive step, and the only one that can change behaviour: dirty flag, undo depth, the
   title asterisk, the tree rebuild order in `refreshAfterEdit`, and the fact that `display`
   clears the history that `applySourceText` then restores. Do it alone, after everything
   above, on its own branch, with a manual launch at the end. → ~820.
6. **`MainMenuFactory`** last, because menu items are enable-state *fields* read by
   `updateEditActions()`. By this point the handlers are elsewhere and the factory needs a
   record of the nine state-dependent items plus a dozen `Runnable`s. → ~600–750 lines total.

Realistic end state: 600–750 lines in `MainWindow` (fields, constructor, layout, wiring,
`updateEditActions`, `updateServerActions`) and five to six classes of 100–350 lines. That
is the same shape as the rest of `ui/`: `SmartLaunchDialog` is 931 lines, `RestRequestPane`
805, `PluginManagerDialog` 715 — the package has no culture of tiny classes, so splitting
below this level would be out of character and would not buy anything.

### What not to do

- **Don't split by menu** (`FileMenuActions`, `ToolsMenuActions`). It reproduces the same
  handlers in more files, each still needing the document state, with more hops and no
  cohesion at all.
- **Don't extract `display()` on its own.** `display`, `refreshTree`, `displayBundleEntry`,
  `refreshAfterEdit`, `undoEdit` and `closeResource` all write the same six fields; splitting
  them is precisely where the dirty flag or the asterisk stops matching the file.
- **Don't introduce a presenter or MVC rewrite.** Two extractions and nine rounds of fixes
  have settled on method-reference-plus-`Consumer` seams; a new architecture would put every
  one of those decisions back in play for no user-visible gain.
- **Don't chase a line count.** M1 is P3 because the file is unpleasant to review, not
  because it misbehaves; a mechanical split that adds a 28-method host interface is worse
  than the monolith it replaces.

### Verification available for each step

No file-length rule exists to satisfy — `pom.xml` has no Checkstyle, PMD or SpotBugs plugin —
and no test constructs the window: `ServerUiSmokeTest` builds the dialogs on a real toolkit
and skips itself headless, so it will not notice a broken `MainWindow`. Each slice therefore
needs `./mvnw -B -ntp -DskipTests compile` plus one manual launch that opens a file, edits an
element, saves, and exercises the server dialogs it touched. Record that launch in the commit
message; there is no automated substitute.

One ordering constraint: M8 (the stale `closingFromAction`) lived in the close path that
`WorkspaceActions.close()` and the window handler share. It is fixed now, and step 5 must
keep that flag's lifetime to one close rather than reintroduce the old bug.
