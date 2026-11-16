# Scripting Write Access — Implementation Plan

- **Branch:** `scriptwrite`
- **Base:** `develop`
- **Status:** Implemented on `scriptwrite`; focused and full suites pass
- **Date:** 2026-11-16

**Follow-up safety work (2026-11-16):** the grant is live, revocable and bound to
the approved server; every attempt appears in a body-free write journal with a
per-run summary; a run has a 50-write budget and an idle grant expires after 15
minutes; patch and delete accept a known version for `If-Match`; and a timed-out
write raises `WriteOutcomeUnknownException` instead of inviting a blind retry.

## Goal

Let a user run `fhir.create`, `fhir.update`, `fhir.patch`, and `fhir.delete` from
the Scripting Console against the active FHIR server. Writes stay off by default
and become available only after an explicit, non-persistent opt-in. Deleting a
resource still asks for confirmation immediately before the request is sent.

The existing `fhir` API already has these methods. The console currently builds
it with `WritePolicy.READ_ONLY`, so every write is refused before any network
call. This plan removes that console-level block without weakening the default.

## What already exists

| Piece | Current state | Consequence for this work |
|---|---|---|
| `FhirApi` | Has `create`, `update`, `patch`, and `delete`; each calls `writePolicy.requireWrite()` first. | No new write verbs are needed. |
| `WritePolicy` | Immutable `READ_ONLY`, `enabled()`, and `enabled(Confirmation)`; the confirmation is used by `delete`. | Keep it as the single policy gate. |
| `FhirServerService` | Already sends writes through the selected plugin, with saved credentials, `ServerWriteResult`, and version-aware updates. | Writes use the same server path as **Save to FHIR Server**. |
| `ScriptingConsoleDialog` | Constructs bindings once with `WritePolicy.READ_ONLY` and passes those bindings to every run. | The bindings must be rebuilt when the opt-in changes. |
| `BatchProcessor` | Calls script actions normally; write calls inside an action pass through `FhirApi`'s policy. | Batch scripts inherit the same gate without a separate path. |
| Tests | `FhirApiTest` already covers allowed and refused writes, including delete cancellation. | Add console-level and end-to-end scripting tests. |
| Documentation | The scripting guide explicitly says the console is read-only. | Must be updated, including the bundled help copy. |

## Decisions

1. **Read-only remains the default.** Every new console starts with writes off.
   No preference or workspace state persists the setting.

2. **The opt-in is per console session.** A checkbox is off when the dialog
   opens. Enabling it requires an explicit warning and a writable active server.
   Closing the dialog discards the opt-in.

3. **Opening a file resets the opt-in.** A script that arrives from disk is
   untrusted, just as it is for the existing trust acknowledgement. Loading the
   bundled example also resets writes off. A file cannot inherit write access
   from the script that was in the editor before it.

4. **Writes target only the active server.** The console is modal and the active
   server is selected before it opens. The target must still be checked when
   writes are enabled and when a run starts, with a clear refusal if no server
   is selected.

5. **Only `fhir` writes are enabled.** `server` remains an inspection API; it
   does not gain server configuration or arbitrary REST methods. This does not
   add a raw HTTP client to scripting.

6. **Deletes require a second, per-operation confirmation.** Create, update, and
   patch are explicit script calls and are covered by the opt-in. Delete shows
   the server, resource type, and resource id immediately before the request.
   Cancelling sends nothing.

7. **Writes use the active server's saved credentials.** They do not use the
   SMART session. `smart` stays read-only in this slice. SMART-authorized writes
   are a separate feature because they need scope checks, token handling, and
   their own confirmation rules.

8. **This is a guardrail, not a sandbox.** JShell can still use ordinary Java to
   access the network, files, and processes. The warning must say so. The write
   policy makes the application API opt-in and visible; it does not contain
   untrusted code.

9. **No automatic writes.** Opening, selecting, loading, or restoring a script
   never executes it. Only **Run** and **Run Tests** can execute, and only after
   the trust acknowledgement.

## User experience

### Toolbar

Add a checkbox to the existing toolbar:

```text
[ ] Allow server writes
```

The checkbox is disabled while a script is running. Its tooltip names the active
server when one is available. A short status line, set when it changes, makes the
current target visible without relying on a tooltip:

- `Server writes off.`
- `Server writes on: Test Server (https://test.example.com/fhir).`
- `Server writes unavailable: select a server in Tools > FHIR Servers.`
- `Server writes unavailable: this server's plugin does not support writes.`

### Enabling writes

Pressing the checkbox follows this sequence:

1. Read `serverManager.active()`.
2. If there is no active server, leave the box unchecked and show the
   unavailable status.
3. If `serverService.supportsWrite(server)` is false, leave it unchecked and
   show the plugin refusal. A plugin that cannot write must not be presented as
   writable.
4. Show a warning owned by the console:
   - Header: `Allow server writes?`
   - Body: the server name and base URL, the four enabled operations, the fact
     that deletes ask again, and that scripts are not sandboxed and changes
     cannot be undone.
   - Buttons: `Allow Writes` and `Cancel`.
5. Only `Allow Writes` rebuilds the bindings with an enabled policy. `Cancel`
   leaves the checkbox off.

The normal trust prompt remains. When writes are on, its text names the target
and says the script may modify server data. The acknowledgement still resets
when a file is opened.

### While running

The write toggle is disabled for the duration of a Run or Run Tests. A policy
cannot change under a script that is already executing.

Delete confirmation is the only per-call prompt. `create`, `update`, and `patch`
do not prompt again; the opt-in was the approval for those operations. A batch
or suite can therefore make several writes, but every delete still stops for
confirmation.

## Implementation

### 1. Keep the policy and API surface small

No new write verbs and no new REST client. Retain `WritePolicy` as the single
gate and `FhirApi` as the only script-facing write surface.

Changes in `src/main/java/com/spiraleyes/fhirviewer/service/scripting/api/`:

- Update `WritePolicy` javadoc to describe the console opt-in as the normal
  way writes become available. Keep `READ_ONLY` as the default.
- Keep `WritePolicy.enabled(Confirmation)`; it already carries the destructive
  confirmation.
- In `FhirApi.delete`, keep the pre-request confirmation and make the summary
  include the server name and base URL, for example:
  `Delete Patient/1 on Test Server (https://test.example.com/fhir)`.
- Keep the confirmation on `WritePolicy`; `FhirApi` has one constructor and no
  second confirmation path.
- Add `PatchFormat`, `ServerWriteResult`, and `WriteDeniedException` to the
  imports emitted by `ScriptBindings.preamble()` so a script can use them
  without fully qualified names. Also consider `IBaseResource` already imported.

Do not change `ServerApi`. It remains read-only.

### 2. Make the console bindings replaceable

In `src/main/java/com/spiraleyes/fhirviewer/ui/ScriptingConsoleDialog.java`:

- Replace the single pre-built `bindings` with a mutable field and a helper that
  builds a complete `ScriptBindings` for a given `WritePolicy`.
- Keep one `SmartApi` instance from the shared `SmartSessionHolder`, so
  rebuilding for a policy change does not lose the authorized session.
- Keep `ViewerApi`, `ValidationApi`, `ServerApi`, `LogApi`, `TestApi`, and
  `BatchProcessor` constructed from the same services.
- On opt-in change, assign a new bindings object. A run started after the
  change uses the new `fhir`; a run already in flight keeps the policy it
  started with.
- Add package-private test accessors for the checkbox and the current bindings,
  following the existing `*ForTest` convention.

The preamble re-binds `fhir` on every run, so replacing the bindings is enough;
there is no separate mutable policy inside `FhirApi`.

### 3. Confirmation on the JavaFX thread

The script runs on the coordinator's worker thread. The `WritePolicy`
confirmation therefore cannot call `Alert.showAndWait()` directly.

Reuse `WritePolicy.Confirmation` as the seam in `ScriptingConsoleDialog`; it is
already the contract `WritePolicy.enabled(Confirmation)` accepts.

Production implementation:

- If `Platform.isFxApplicationThread()`, show the alert directly.
- Otherwise create a `FutureTask<Boolean>`, post it with
  `Platform.runLater(...)`, and wait for the result. Restore the worker's
  interrupt flag if interrupted.
- Fail closed when the dialog or its owner window is no longer available:
  return `false`, and let `FhirApi.delete` throw
  `WriteDeniedException("Delete cancelled.")`.
- Use the existing dialog stylesheets and owner.
- Never call `showAndWait()` from the worker thread.

A package-private constructor overload can accept a `WritePolicy.Confirmation`
so UI tests can answer it without opening a native modal dialog. The production
constructor uses the real alert.

### 4. Reset rules

Set the checkbox off and rebuild read-only bindings in these places:

- dialog construction;
- `onOpen`, after a file is read and accepted;
- `onLoadExample`, after the example replaces the buffer.

Do not reset just because a run finished. A user may want to run, inspect the
output, fix the script, and run again without re-arming. The opt-in still ends
when the dialog closes.

The active server cannot normally change while the modal console is open. Check
it again immediately before a run when writes are on; if it disappeared or is
not the server named in the warning, refuse with `Select a server before running
a script with writes enabled.`

### 5. Server support check

Use `FhirServerService.supportsWrite(server)` when enabling the checkbox. This
is a plugin-level check, not a capability-statement check.

Do not add a network capability probe just to enable the checkbox. A server may
omit write interactions from its capability statement, and a probe would make
the option depend on a round trip. If the server later refuses an operation,
`FhirServerService` already maps the failure to a `ServerOperationException`, and
   `FhirApi` turns it into a `ScriptApiException` with the server's explanation.

### 6. Error behavior

Keep the current script-facing behavior:

- Writes while disabled throw `WriteDeniedException` before any request.
- A missing resource on read returns `null`.
- A write failure throws `ScriptApiException` with the server's message.
- A cancelled delete throws `WriteDeniedException("Delete cancelled.")`.
- A plugin that does not support writes reports `UNSUPPORTED`; the script sees
  an actionable message, not a null pointer.

The console must not convert a cancelled delete into a generic success. The
output and status should make clear that nothing was sent.

## Tests

### Unit / API tests

Extend `src/test/java/com/spiraleyes/fhirviewer/service/scripting/api/`:

- `FhirApiTest`
  - Every write is refused with a read-only policy and no service call is made.
  - `create`, `update`, and `patch` reach the service with an enabled policy.
  - `delete` reaches the service only after confirmation returns true.
  - Confirmation false makes no delete request.
  - The delete summary names the server and resource, and contains no
    credentials or token.
- `WritePolicyTest`
  - Existing read-only/enabled behavior remains.
  - An enabled policy with a confirmation still requires write permission.
- `ScriptBindingsInjectionTest`
  - A run with enabled bindings exposes a writable `fhir`.
  - The preamble includes `PatchFormat`, `ServerWriteResult`, and
    `WriteDeniedException`.

Add a JShell-level test, either in `ScriptingSessionTest` or a focused new test:

- With `WritePolicy.READ_ONLY`, evaluating `fhir.delete("Patient", "1")`
  produces a `WriteDeniedException` and the fake service records no call.
- With `WritePolicy.enabled()`, evaluating `fhir.create(...)` reaches the fake
  `FhirServerService` and its `ServerWriteResult` is available to the script.

### UI tests

Extend `ScriptingConsoleSmokeTest` or add `ScriptingWriteAccessTest`:

- The checkbox is off when the console opens.
- Enabling with no active server refuses and leaves it off.
- Enabling when `supportsWrite` is false refuses and leaves it off.
- Enabling with a supported server asks for confirmation; cancelling leaves it
  off, and accepting enables writes and shows the target in the status text.
- The native warning and delete confirmation are driven through their actual
  windows; a delete from the worker thread prompts on the FX thread.
- Opening a file or loading the example turns writes off.
- Changing to another writable server, or removing the active one, turns writes
  off before Run or Run Tests.
- Turning writes off revokes a `fhir` reference retained by the interactive session.
- A write budget pauses at its limit, and an idle grant is refused before the UI
  timer catches up.
- The Writes tab records success, failure, cancellation and an unknown outcome
  without resource bodies, and the run summary separates those counts.
- Timed-out writes are reported as unknown outcomes, and patch/delete send a
  supplied version.
- The checkbox is disabled during a run.
- With writes on, a `fhir.create` script reaches the fake server; with writes
  off, it is refused before the fake server is called.

Use a package-private `FhirServerService` test double in the UI test package,
overriding `supportsWrite`, `create`, `update`, `patch`, and `delete`, and
recording calls. Do not depend on a live server.

### Documentation tests

- Keep `docs/scripting/Scripting-Guide.md` and
  `src/main/resources/help/Scripting-Guide.md` byte-identical.
- Assert the guide no longer says the console is read-only in this build.
- Assert it documents:
  - the opt-in and default-off behavior;
  - the target server and reset rules;
  - create/update/patch/delete examples;
  - delete confirmation and cancellation;
  - that writes use saved server credentials, not the SMART session.

Run the focused tests first, then the full suite:

```text
./mvnw -B -ntp -Dtest=FhirApiTest,WritePolicyTest,ScriptBindingsInjectionTest,ScriptingSessionTest,ScriptingConsoleSmokeTest test
./mvnw -B -ntp test
```

## Documentation changes

1. `docs/scripting/Scripting-Guide.md`
   - Replace the `fhir` table entry “writes are refused in this build”.
   - Correct the `create` signature: it returns `ServerWriteResult`, not
     `IBaseResource`.
   - Add the missing `patch` signature to the first signatures list.
   - Replace the read-only sample preamble with write examples:
     - create a synthetic Patient;
     - update a resource read from the server;
     - patch with `PatchFormat.JSON_MERGE_PATCH`;
     - delete after confirmation.
   - Document `ServerWriteResult`: `resourceId()`, `versionId()`, `created()`.
   - Document that update uses the resource id and its `meta.versionId` for
     conflict checking when the server reports one.
   - Document the opt-in, target-server check, reset-on-file-open rule, and the
     fact that writes cannot be undone.
   - Keep the “not a sandbox” warning and explain that the policy is a guardrail,
     not isolation.

2. `src/main/resources/help/Scripting-Guide.md`
   - Copy the updated guide byte-identically.

3. `docs/plans/scriptingrefined/IMPLEMENTATION-STATUS.md`
   - Change the write policy row from “console builds read-only” to the opt-in
     behavior once implemented.
   - Record the tests and documentation updated.

4. `docs/plans/scriptingrefined/PHASE-6-SCRIPTING-API-DOCUMENTATION.md`
   - Update the write-policy description so it describes the console opt-in
     rather than implying writes are unavailable.

5. `RELEASE_NOTES.md`
   - Do not rewrite the already published 2.0 notes.
   - Add the feature to the next release’s notes when that release is prepared.

6. `docs/plans/README.md`
   - Index this plan as `scriptwrite/`.

## Delivery order

1. **Policy and API cleanup**
   - Update `WritePolicy` javadoc.
   - Improve the delete summary.
   - Add preamble imports.
   - Add focused `FhirApi` and JShell tests.
   - No console behavior changes yet.

2. **Console opt-in**
   - Add the checkbox, status text, warning, and bindings rebuild.
   - Reset on file open and example load.
   - Disable the checkbox while running.

3. **Delete confirmation**
   - Add the FX-thread confirmation seam.
   - Fail closed if no dialog is available.
   - Add cancellation and threading tests.

4. **Documentation**
   - Update the scripting guide and bundled copy.
   - Update the implementation status and plan index.
   - Run parity checks.

5. **Verification**
   - Focused tests, then the full suite.
   - Manually check the dialog on a display if available: enable, cancel, disable,
     open a file, and confirm a delete against a synthetic test server.

## Acceptance criteria

- A fresh console has writes off and every write is refused before a request.
- Writes can be enabled only with an active server whose plugin supports them.
- The user sees the target server before writes are enabled.
- `fhir.create`, `fhir.update`, and `fhir.patch` reach the server when enabled.
- `fhir.delete` asks immediately before sending; cancel sends nothing.
- Opening a file or the example returns writes to off.
- The write toggle cannot be changed while a script is running.
- A write failure or cancellation is visible and no success is reported.
- `server` remains read-only; no arbitrary REST method is added.
- The scripting guide no longer claims the console is read-only, and says
  writes use saved credentials rather than the SMART session.
- All focused tests and the full suite pass.
- No credentials, tokens, or full patient resources are added to logs.

## Non-goals

- Making writes the default.
- A general-purpose HTTP client or raw REST console inside scripts.
- Changing server definitions, credentials, or active selection from scripts.
- SMART-authorized writes; that needs a separate scope/token/confirmation
  design.
- Transaction bundles, conditional writes, or bulk destructive operations.
- Persisting the write opt-in across console windows.
- Treating JShell or the write policy as a security sandbox.
- Scheduling, headless execution, or unattended production writes.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| A user enables writes, then opens a different script and runs it. | Opening a file resets writes off and resets the trust acknowledgement; write status is visible. |
| A script updates many resources without another prompt. | The opt-in names the target and warns that changes cannot be undone; delete is separately confirmed. Start with synthetic data. |
| Confirmation blocks or deadlocks the JavaFX thread. | The script worker posts the prompt to the FX thread and waits; the dialog uses `showAndWait` only on the FX thread; fail closed if unavailable. |
| Active server disappears or changes. | The console is modal, and the target is checked when enabling and when starting a run. |
| A plugin advertises no write support. | `supportsWrite` refuses the opt-in rather than offering a button that will fail. |
| A script holds an old `fhir` reference after the toggle changes. | Rebuild bindings for each policy change and re-bind `fhir` in the preamble on every run. |
| The feature is mistaken for a sandbox. | Keep the warning and documentation explicit; do not claim that the policy contains arbitrary Java. |

## Open decision if a broader scope is approved later

SMART-authorized writes would use the existing authorization session and its
bearer token, but `SmartFhirAccess` currently implements reads only. That work
should be a separate plan covering write scopes, token expiry, per-operation
confirmation, and `SmartApi` signatures. It should not be folded into this
slice.
