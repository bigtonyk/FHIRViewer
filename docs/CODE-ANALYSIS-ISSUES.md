# FHIRViewer — Code Analysis Issue List

Date: 2026-11-02
Scope: `src/main/java` (233 production files, ~43,300 lines), `src/test/java` (112 files),
`pom.xml`, `.github/workflows/`, repository layout, and the `docs/` plans.
Method: static reading plus targeted pattern searches. **No build, no test run, no runtime
profiling was performed** — see [Limitations](#limitations).

Severity legend

| Level | Meaning |
|-------|---------|
| **High** | Defect a user can hit in normal use, or a resource/thread leak that grows |
| **Medium** | Maintainability or robustness problem that actively costs work |
| **Low** | Hygiene, consistency, stale documentation |

No blocker was found: the build configuration is coherent (`maven.compiler.release=21` in
`pom.xml`, JDK 21 in both workflows, JDK 21 requirement in `README.md`, and `.clinerules`
agree), and there is no trust-all TLS, no empty catch block, and no committed secret.

---

## 1. High

### H1. Workspace close discards unsaved changes without ever asking

`src/main/java/com/spiraleyes/fhirviewer/workspace/WorkspaceRecoveryService.java:65-72`

```java
public boolean handleCloseDecision() {
    if (!workspaceService.isDirty()) return true;
    if (lastSnapshotPath != null && Files.isRegularFile(lastSnapshotPath)) {
        onRecoveryOffer.run(); // UI will show dialog and return user choice
        return true;           // Close proceeds after UI decision
    }
    // No recovery snapshot – close proceeds (user will lose unsaved changes)
    return true;
}
```

Every path returns `true`, so the method cannot express "the user cancelled". The comment on
`onRecoveryOffer.run()` claims the UI "returns user choice", but the callback is a `Runnable`
with no return value. A dirty workspace therefore closes and loses work even when the user
chooses Inspect or Discard-cancel, and the class-level doc ("Restore/Discard/Inspect
decisions") does not match the code.

**Fix:** make the callback return the decision (`Function<…, Decision>`) and return `false`
when the user cancels or asks to inspect.

### H2. `ScriptingCoordinator.stop()` blocks the UI thread and defeats the single-run guard

`src/main/java/com/spiraleyes/fhirviewer/service/scripting/ScriptingCoordinator.java:148-176`

Two distinct problems:

1. **Busy-wait up to 5 s on the calling thread.** The Stop button calls this from the JavaFX
   application thread; the loop `while (current == null && running.get() && …) Thread.sleep(1)`
   (`:153-160`) freezes the whole window for up to five seconds. The class doc claims "a script
   that sleeps for thirty seconds freezes nothing" — that is not true of Stop.
2. **The in-flight guard is cleared while work is still running.** After `current.stop()` it
   does `running.set(false)` (`:167`), but the worker task has not necessarily finished. A
   second `run()` then passes `running.compareAndSet(false, true)` and is submitted to the
   single-threaded executor, where it queues behind the still-blocked snippet. That second run
   never starts until the first ends, and its callback is not invoked in any useful order.

**Fix:** track the in-flight `Future`/latch and wait for actual completion instead of polling;
clear `running` only in the worker task's `finally`, never from `stop()`.

### H3. `Files.list` stream is never closed

`WorkspaceRecoveryService.java:134`

```java
List<Path> s = Files.list(recoveryDir).filter(…).sorted().toList();
```

`Files.list` returns a stream that owns an open directory handle. `PluginJarScanner` does this
correctly (`try (var entries = Files.list(folder))`), but this call leaks a handle on every
prune.

**Fix:** wrap it in try-with-resources.

### H4. HTTP client pools are created per instance and never closed

`src/main/java/com/spiraleyes/fhirviewer/server/rest/JdkHttpRestClient.java:209, 298-308`

Each client allocates `Executors.newCachedThreadPool(...)` and builds an `HttpClient` on that
executor. `close()` shuts the executor down but never closes the `HttpClient` (an
`AutoCloseable` since Java 21), so the client's selector thread and connection pool survive.
Because the class is public and cheap-looking to construct, plugin code can create one per
operation; each leaks a selector until GC. The class doc promises "a small daemon pool", but a
*cached* pool is unbounded by design.

**Fix:** close the `HttpClient` in `close()`, and cache one client per base URL rather than
constructing one per call site.

### H5. The only end-to-end UI tests never run in CI

`src/test/java/com/spiraleyes/fhirviewer/ui/ServerUiSmokeTest.java` (1,841 lines) and
`SmartLaunchWorkbenchUiTest.java` (521 lines)

Both start a real JavaFX toolkit and skip themselves with
`if (GraphicsEnvironment.isHeadless()) return;`. `ubuntu-latest` in `ci.yml` has no display, so
on the Linux leg the two suites that actually build screens are skipped — which is exactly the
regression class their own comments describe ("a dialog whose constructor throws while
assembling its scene graph is invisible to those tests"). The comment in `ci.yml`
("CI never tries to open a window") is inaccurate.

**Fix:** run the Linux leg under `xvfb-run`, or add a headless-capable surefire profile, so the
smoke tests execute somewhere in CI.

---

## 2. Medium

### M1. `MainWindow` is a 2,698-line god class

`src/main/java/com/spiraleyes/fhirviewer/ui/MainWindow.java` — menus, toolbar, file open/save,
undo wiring, tree selection, document-tab synchronisation, IG package auto-load background
thread, server coordinator wiring, workspace restore, and dialog launching.

This contradicts the project's own rule in `.clinerules` ("Prefer multiple focused classes over
one large class … Keep UI, validation, FHIR processing, plugin interfaces, and utilities
separated"). The next tier shows the same drift: `SmartLaunchDialog` 931, `RestRequestPane`
805, `PluginManagerDialog` 715, `ServerOperationDialog` 671, `ServerSearchDialog` 658,
`OpenFromServerDialog` 655, `IgPackageDialog` 606, `RestConsoleDialog` 602 lines.

**Fix (incremental):** extract cohesive units from `MainWindow` — file actions, document-tab /
view sync, server actions — without behaviour change.

### M2. `ScriptingCoordinator.run(...)` is duplicated almost verbatim

`ScriptingCoordinator.java:67-104` vs `:113-146` — two overloads that differ only by the
`bindings` argument, each repeating the closed check, the `running.compareAndSet` guard, the
`RejectedExecutionException` catch and the wrapped failure text. They have already drifted: the
same user-facing message uses a literal em dash in one and an escaped `\u2014` in the other
(`:123`).

**Fix:** one private `submit(String, ScriptBindings, Consumer<…>)` that both overloads delegate
to.

### M3. Per-snippet redirection of global `System.out`/`System.err`

`src/main/java/com/spiraleyes/fhirviewer/service/scripting/ScriptingSession.java` (`evaluatePart`)

Every snippet swaps the process-wide streams and restores them in a `finally`. This is
documented, but the side effect is broader than the comment admits: the application runs
`slf4j-simple`, which writes to `System.err`, so REST/validation log lines emitted by another
thread during a run are captured into the script's output pane — and a second
`ScriptingSession` (reachable through the test runner) would race on the same globals.

**Fix:** route application logging through an explicitly captured SLF4J stream, or use one
reference-counted process-wide tee instead of swapping per snippet.

### M4. Recovery service mixes three logging mechanisms and ships debug output

`WorkspaceRecoveryService.java` declares `java.util.logging.Logger LOG` (`:12`, `:24`) and never
uses it, while emitting eleven `System.out.println` / `System.err.println` calls
(`:54, :78, :85, :94, :97, :108, :116, :119, :127, :128, :136`), including console noise such as
`"Snapshot: …"`, `"Restored from recovery"` and `"Archive saved; recovery baseline reset"`.
The rest of the application uses SLF4J. Wildcard imports (`java.io.*`, `java.nio.file.*`,
`java.util.*`, `java.util.concurrent.*`) are unique to this class and hide its dependency
surface. The `currentWorkspaceArchive` field (`:27`, `:51`) is assigned and never read.

**Fix:** SLF4J at `debug`/`info`, delete the unused logger and field, drop the wildcard imports.

### M5. Snapshot retention is per-process and barely effective

`WorkspaceRecoveryService.java:130-138` guards on `snapshotCount <= 50`, but `snapshotCount` is
an instance field starting at `0` on every launch while the `.snap` files persist in
`~/.fhirviewer-recovery`. A user taking five snapshots per session needs ten sessions before any
pruning occurs. Retention should count the directory, not an in-memory counter.

### M6. Stale build/packaging instructions in `README.md`

- The `jpackage` examples name `fhir-viewer-0.1.0-SNAPSHOT.jar`, but `pom.xml` declares
  `<version>1.0.0</version>`; the commands as written fail.
- The Tests table names 13 classes; `src/test/java` holds 112 files, including entire unmentioned
  suites (`server/rest/*`, `server/smart/*`, `ui/*`, `service/scripting/*`).
- The Roadmap lists FHIR server connectivity, FHIRPath and themes as future work, all of which
  the README documents as implemented earlier in the same file.

---

## 3. Low — hygiene and consistency

### L1. 20 debris files are tracked in Git

Fourteen at the repository root — `facts.txt`, `facts01.txt`, `facts02.txt`, `facts03.txt`,
`facts1.txt`, `fix_script.py`, `inv.txt`, `out.txt`, `probe.txt`, `script-test-output.txt`,
`test-log.txt`, `test-out.txt`, `test-output.txt`, `workspace-tree.txt` — plus six exploratory
sources under `spike/` (`Spike.java` … `Spike6.java`).

**Fix:** `git rm --cached` them and ignore root `*.txt` scratch output and `spike/`.

### L2. 49 log files sit in the working tree

`.gitignore` correctly ignores `*.log`, but the checkout still holds `app.log`, `c.log`,
`d1.log` … `z5.log` and the various Maven/build logs. They make naive tooling at the project
root (`find`, `du`, recursive grep) slow enough to time out. Worth a cleanup pass.

### L3. Undocumented helper scripts in the repository root

`ci-check-window.ps1`, `ci-launch-console.ps1`, `launch-app.cmd`, `launch-app.ps1` and
`fix_script.py` are neither described in `README.md` nor referenced from the workflows —
`release.yml` inlines its own equivalent of `ci-check-window.ps1`. They are either dead code or
a second, divergent implementation of release verification.

### L4. Three parallel, near-identical scripting plan trees

`docs/plans/scripting/`, `docs/plans/scriptingrefined/` and `docs/plans/scriptingautomation/`
each contain `MASTER-CONSIDERATIONS`, `IMPLEMENTATION-STATUS` and `PHASE-1` … `PHASE-6` with the
same titles. A reader cannot tell which is authoritative. Archive the superseded ones the way
`docs/plans/archived/` already does. Two further plan documents sit in the repository root
(`FHIRViewer FHIR Server Plugin Architecture — …`, `FHIRViewer — Two-Layer Smile CDR …`)
although `README.md` states "Nothing outside `docs/` is required…".

### L5. Scratch output left in a committed test

`src/test/java/com/spiraleyes/fhirviewer/service/scripting/api/ValidationApiTest.java:40-46`
prints six `"SCRATCH …"` lines with `System.out.println`. These are exploratory diagnostics,
not assertions.

### L6. Timing-based waits in the UI smoke tests

`ServerUiSmokeTest.java:756` and `:1030` use `Thread.sleep(1500)` to wait for background work,
alongside `Thread.sleep(50)` polling loops at `:170`, `:977`, `:1492`. These are the classic
source of intermittent failures once the suites actually run in CI (see H5).

### L7. Duplicated, self-contradicting comment blocks in `pom.xml`

The `maven-clean-plugin` configuration carries two long overlapping explanations of the
read-only `FHIRViewer.exe` problem, one of which describes an approach the other says was
abandoned. Useful history, but it now contradicts itself about the mechanism in use.

### L8. Redundant compiler properties

`pom.xml:42-44` sets `maven.compiler.release`, `maven.compiler.source` and
`maven.compiler.target` all to `21`. The plugin uses `<release>`, so the other two are inert —
but they will silently disagree the first time someone bumps only one of them. Delete
`source`/`target`.

### L9. `SecretBox` passphrase is held as an immutable `String`

`src/main/java/com/spiraleyes/fhirviewer/server/SecretBox.java:108-115` converts the passphrase
to `char[]` and calls `PBEKeySpec.clearPassword()` in a `finally`, which is correct for the spec
object — but the original `String` cannot be zeroed and stays on the heap. This is inherent to
the API shape rather than a mistake; if a passphrase ever becomes long-lived, accept `char[]`.
Noted so the choice stays deliberate.

---

## 4. Verified strengths (no action)

Recorded because a defect list alone misrepresents the code:

- **No TLS weakening anywhere.** Zero matches for `TrustManager`, `HostnameVerifier`,
  `checkServerTrusted` or trust-all contexts; `JdkHttpRestClient.newHttpClient()` installs no
  `SSLContext`.
- **No empty catch blocks.** Zero matches for `catch (Exception e) {}`; every broad catch found
  logs, converts, or restores the interrupt flag.
- **Credential storage is sound.** `SecretBox` uses PBKDF2-HMAC-SHA256 (210,000 rounds,
  per-encryption 16-byte salt), AES-256-GCM (per-encryption 96-bit IV, 128-bit tag), a versioned
  self-describing format, and deliberately identical error text for "wrong passphrase" and
  "tampered file".
- **SMART OAuth callback is defended.** `LoopbackCallbackServer` binds `127.0.0.1` on an
  ephemeral port only, accepts exactly one callback, validates `state`, and answers in
  `text/plain` with `Cache-Control: no-store` so no reflected parameter has an HTML sink.
- **Secret redaction is centralised.** `RequestHeaders.SECRET_NAMES` with a redacting
  `toString()`, and `JdkHttpRestClient.describe()` logs verb + path only — no query, body or
  headers — keeping tokens and PHI out of log lines and dialogs.
- **No stray `printStackTrace`, no `TODO`/`FIXME` markers, no `System.exit`** in production code.
- **Resource handling is otherwise disciplined:** `Files.list` and `Files.newDirectoryStream`
  are wrapped in try-with-resources in `PluginJarScanner`, `PackageStorage` and
  `IgPackageManager`.
- **Scratch-free production tree:** no `System.out` debugging in the UI or FHIR layers; the
  scripting API's printing is intentional console output.

---

## 5. Suggested order of work

1. **H1** — silent loss of unsaved work is the highest user-facing risk.
2. **H2** — Stop freezing the window and swallowing the next run.
3. **H3, H4** — small, self-contained leak fixes.
4. **H5, L6** — enable the UI suites under Xvfb, then replace `sleep`-based waits with latches.
5. **M2, M4, M5** — cheap local cleanups in two files.
6. **M1** — incremental extraction; no urgency, but the cost grows with every feature.
7. **L1-L4** — repository and documentation cleanup.

---

## Limitations

- **No build or test execution.** Findings are from static reading; the JDK here is 25.0.3 while
  CI uses 21, so a local build would not reproduce the CI toolchain in any case.
- An initial read of `pom.xml` in this session returned a stale copy showing
  `maven.compiler.release=25`. The file on disk (and `git diff`, which is empty) shows `21`, so
  the observation was discarded rather than reported; treat the current file as authoritative.
- Unused-code observations cover the declaring class and the call sites found by search; they
  are not exhaustive.
- Only the patterns in section 4 were searched exhaustively across the tree; the remaining
  findings come from reading the specific files named.
- No dependency CVE audit, no profiling, no memory analysis.
