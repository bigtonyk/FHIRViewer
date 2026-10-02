# Phase Tracker — FHIR Server REST Integration

Plan: `docs/plans/FHIR_Server_REST_Integration_Plan.md` (30 phases)
Branch: `server-rest-integration` (from `develop` @ a63dff8)
Started: 2026-09-24

**This file is the source of truth for progress.** Update it on every commit so
work can be resumed without re-reading the plan.

## Status

| # | Phase | Status |
|---|-------|--------|
| 1 | Inspect the Existing Plugin Architecture | **done** |
| 2 | Define the Common REST API | **done** (`f67a339`) |
| 3 | Create a Server REST Request/Response Model | **done** (`f67a339`) |
| 4 | Implement the Common HTTP Transport | **done** (`f67a339`) |
| 5 | Authentication Architecture | **done** (`45663be`) |
| 6 | Standard FHIR REST Operations | **done** |
| 7 | High-Level FHIR Client API | **done** (`FhirOperationClient`) |
| 8 | Custom/Vendor REST Operations | **done** |
| 9 | Separate Standard and Vendor APIs | **done** |
| 10 | Server Capabilities | **done** |
| 11 | Server Status | **done** |
| 12 | UI Plugin Operations API | **done** |
| 13 | UI Operation Discovery | **done** |
| 14 | UI Operation Model | **done** |
| 15 | UI Parameter/Input Handling | **done** |
| 16 | UI Results Handling | **done** |
| 17 | UI Server Connection Workflow | **done** |
| 18 | UI Resource Operations | **done** |
| 19 | UI Search | **done** |
| 20 | UI Custom Operations | **done** |
| 21 | Generic FHIR Server Plugin | **done** |
| 22 | Smile CDR Plugin | **partial** — plugin exists and declares operations; `SmileCdrPluginTest.java.hold` is still disabled, though its operation declarations are covered by `SmileCdrPluginOperationsTest` |
| 23 | Firely Server Plugin | **done** |
| 24 | UI Asynchronous Operations | **done** |
| 25 | UI Error Handling | **done** |
| 26 | Logging and Diagnostics | **partial** — logged throughout; no diagnostics doc |
| 27 | Plugin Developer API | **partial** — loader, registry and a mock plugin in tests; no published guide |
| 28 | Testing | **done** — 614 tests, offline, `@TempDir` + localhost `HttpServer` |
| 29 | Backward Compatibility | **done** — see `Phase-29-Backward-Compatibility.md` |
| 30 | Security Review | **done** — 10 items inspected, 8 clean, 3 findings; 1 fixed. See `Phase-30-Security-Review.md` |

### Phase 7 — Open from / Save to a FHIR Server

Delivered against `docs/plans/Phase-7-Open-Save-from-FHIR-Server.md`. All six steps are
done; see the note at the end of this file for what changed.

### Where the whole integration actually stands

Recorded here so the table above cannot drift from reality again.

**Working and tested:** the REST core and transport; three plugins (standard, Smile, Firely)
all declaring operations; read, search, create, update, patch and delete; server status and
capabilities; the generic operation screen; the server manager with per-server
authentication; and persistence of both servers and credentials across restarts.

**Deliberately not built**, each stated in the user guide's *Known gaps* rather than left to
discover: a raw REST console, transaction bundles, conditional create, `$everything`,
subscriptions, multi-parameter search, and a FHIRPath patch.

**Genuinely outstanding:**

| | Why it matters |
|---|---|
| **Phase 30 — security review** | **Done** — see `Phase-30-Security-Review.md`. Ten items inspected, eight clean, three findings; the one medium finding (credentials silently sent over plain HTTP) is fixed. It was written by the author of the code it reviews, so a second reader is still worth having. |
| **Phase 29 — backward compatibility** | **Done** — see `Phase-29-Backward-Compatibility.md`. Nothing broke. The proof is a compile: `LegacyPlugin` implements only what the interface required before the REST work, so a method that lost its default fails the build. The one finding: a plugin-keyed credential fallback existed with no test. |
| **Phase 9 — second base URL** | **Done** — an optional `administrationBaseUrl()` on the configuration, a flag on the request rather than a path convention, and one field in the add-server form. Nine Smile JSON Admin API operations declared; **documentation-derived and unverified**, which is recorded in the code beside them. |
| **Phase 10 — standard operations and input UI** | **Mostly done** — see `10_STANDARD_OPERATIONS_AND_INPUT_UI.md`. Sixteen specification operations declared, so a plain server offers 18 rather than 2; the operation screen has a named body, a content-type selector, and pre-fills from the resource already open; the status screen names the plugin serving each server. Two of its "make it honest" items remain: offering the detected plugin when a server is added, and marking operations the server does not advertise. |
| `SmileCdrPluginTest.java.hold` | Disabled, so Smile's connection and detection half is untested. Its operation declarations are covered separately. |
| **Phase 26 — diagnostics doc** | Partial. Logging is thorough; there is no written diagnostic guide. |
| **Phase 27 — plugin developer guide** | Partial. The loader, registry and a worked example exist in tests; nothing is published for a third-party author. |

**Not yet done by anyone:** the dialogs have never been looked at in a running window. The
tests press real buttons on a real toolkit, and that is how two real bugs were caught — but
they cannot see layout, and the last three commits changed a lot of layout.

**Still open across the plan as a whole:** `09_SECOND_BASE_URL_FOR_ADMIN_APIS.md`
(a second base URL, needed for Smile CDR's **Admin JSON API** — its reindex operations are
on the FHIR endpoint and already work), and phases 29 and 30, which are review activities
rather than code.

Phase 30 is now done - see `Phase-30-Security-Review.md`.

**Newly planned:** `10_STANDARD_OPERATIONS_AND_INPUT_UI.md`. A standard FHIR server offers
only `$export` and `$import`; the specification's operations are declared nowhere in the
codebase. This is not an authentication limitation - discovery applies no auth filter at all.
The plan covers declaring them, verifying each against a live server, the input UI needed to
make `$validate` and friends usable, and why a Firely server can end up served as a plain one.

## Notes

(append observations here as work proceeds)

### Phase 6 — Viewer UI Integration (this phase)

Delivered against `06_VIEWER_UI_INTEGRATION.md`, which supersedes old phases 12–20, 24
and 25 above.

**Added (all in `ui`, all reached through `FhirServerService`):**

| Class | Purpose |
|---|---|
| `BackgroundTasks` | The one `Task` + daemon-thread + callback pattern, replacing four copies. Adds cancellation |
| `ServerErrors` | One readable message per failure kind/status, with credential redaction |
| `ServerOperationForm` | Generates a form from a `ServerOperation` descriptor and builds the invocation. JavaFX-free |
| `ServerOperationResults` | Classifies a result into FHIR / Bundle / Outcome / JSON / XML / text / empty. JavaFX-free |
| `ServerOperationDialog` | The generic "run an operation" screen; lists what the plugin offered |
| `ServerStatusDialog` | Connect / Disconnect / Test / Status / Capabilities |
| `PatchResourceDialog` | Patch format + body, reusing the existing `JsonView` |

`MainWindow` gained **Refresh from FHIR Server**, **Patch on FHIR Server** and **Delete
from FHIR Server** (enabled only for a resource that has a server origin and an id), plus
**Server Status and Capabilities...** and **Run Server Operation...**. `ServerSearchDialog`
gained First / Previous / Last pagination from the Bundle's own links.

**Two real bugs found by the new tests, in Phase 5 code:**

- `ServerOperationInvocation` copied its parameter maps with `Map.copyOf`, which discards
  iteration order — contradicting its own contract and shuffling every generated query
  string. Fixed with an unmodifiable `LinkedHashMap`, the same fix `FhirOperationRequest`
  already documents.
- `ServerOperationResult` classification treated a blank body as non-empty, so a `204` was
  classified as text rather than as having no body.

Also fixed while writing `ServerOperationForm`: its first version checked for a required
body without ever being given the body, so no operation requiring one could run.

**A note for Phase 7:** `openVendorTool` was still a stub at this point, so
*Tools > Server Tools...* reported that a vendor screen was not built. Resolved later — see
*Server management: list, add, edit, delete* below. The menu item is now removed.

**Not done, deliberately:**

- Search still takes **one** search parameter. Several would need a criteria list widget;
  the request model already accepts many, so this is UI-only and can be a later increment.
- The status screen does not yet gate operations on `CapabilityStatement` resources. A
  plugin can narrow `supportedOperations` by capability today, and the standard plugin
  does not yet do so.
- Cancellation is cooperative: a cancel stops the UI waiting and stops the callback
  delivering, but a request already on the wire may still reach the server. The dialog's
  tooltip says so rather than overstating it.

### Phase 7 — Open from / Save to a FHIR Server

Steps 1–6 of `docs/plans/Phase-7-Open-Save-from-FHIR-Server.md` are complete. Steps 2 and
3 (the plugin write verbs and `FhirServerService`) had landed earlier; the rest is new.

| Step | Delivered as |
|---|---|
| 1. Persist server definitions | `ServerDefinitionStore` (properties, atomic write, no secrets) + `FhirServerManager.load/save`; loaded in the `MainWindow` constructor, saved after an add |
| 2. Write verbs | already present: `FhirServerPlugin` defaults, `StandardFhirRestPlugin` with `If-Match` |
| 3. Service + coordinator | `ServerResourceCoordinator` — **JavaFX-free**, owns validate-then-write, create-vs-update, conflict, force and origin rebasing |
| 4. Open from Server | `OpenFromServerDialog` gained parameter/value fields and a results table; shared criteria building extracted to `SearchCriteriaBuilder` |
| 5. Save to Server | `SaveToServerDialog` — target server, create/overwrite summary, version situation, force and unversioned checkboxes |
| 6. Menu wiring | **File → Open from FHIR Server...** and **Save to FHIR Server...**, both state-dependent; removed from Tools, where they did not belong |

**The two design decisions that mattered most:**

- `LoadedResource` was **not** touched. `ServerOrigin` lives in `MainWindow` beside
  `displayedResource`, so the file, sample and paste paths are unchanged and a resource
  read from a server cannot be written back after the user opens an unrelated file.
- Every decision a push makes moved out of the `MainWindow` handlers and into
  `ServerResourceCoordinator`. That class is free of JavaFX, which is what makes
  validate-before-push, the conflict outcome and the forced write assertable in a headless
  build. `MainWindow` now only moves the result onto the FX thread and reacts to it.

**Bugs found and fixed by the new tests:**

- `ServerDefinitionStore` let a malformed block's `IllegalArgumentException` escape, so one
  hand-edited entry with a bad base URL cost the user *every* server in the file. The bad
  block is now skipped and logged.
- `Properties.store` escapes the colons in a URL (`http\://...`), so the "no secret in the
  file" test has to assert on a parsed round trip rather than on raw text. Worth knowing
  before anything greps this file.
- `withPlaceholder(ListView)` could not be reused for the new `TableView`; the results
  table got its own, since neither control has a placeholder API of its own.

**Tests:** 499 passing at the end of this phase, up from 468. New: `ServerDefinitionPersistenceTest` (11),
`ServerResourceCoordinatorTest` (14), `SearchCriteriaBuilderTest` (4), and two more
JavaFX smoke tests building the new dialogs on a real toolkit. All offline.

**Not done, deliberately:**

- A raw REST console. The Phase 7 plan rules it out of this phase; it needs its own auth,
  error mapping and paging, and belongs behind the plugin interface as a separate change.
- `extraHeaders()` is not persisted, because `ServerDefinition` has no way to carry one and
  a header *value* is a secret. Documented on `ServerDefinitionStore` rather than silently
  dropped.

### Operation discovery: all three plugins now declare

Reported as "Run Server Operations says this plugin offers no operations for firely". Two
causes, both addressed.

**The message.** An empty list is the interface default, so it means "this plugin has not
declared any" — not a fault in the user's setup. The screen now says that, and separates
the two ways a list can be empty: a plugin that declares nothing, versus one whose
operations all need credentials that are locked this session. The second points at Tools →
Server Plugins, which is where the answer is.

**The gap.** No shipped plugin overrode `availableOperations()`; only the two test fixtures
did. The whole machinery around it had therefore only ever run against test plugins, which
is why it looked complete in the suite and empty in the product.

Checked against each vendor's own documentation afterwards, which found **more missing than
the first pass had declared** — and one wrong assumption about where they live.

| Plugin | Declares | Source |
|---|---|---|
| `StandardFhirRestPlugin` | `$export`, `$import` | FHIR specification |
| `SmileCdrPlugin` | `$reindex` (system), instance reindex, `$reindex-dryrun`, `$mark-all-resources-for-reindexing` | Smile "Search Parameter Reindexing" |
| `FirelyPlugin` | the five admin operations, eleven conformance searches, and `$cql` / `$evaluate` / `$evaluate-measure` / `$data-requirements` | Firely Administration API and DQM documentation |

Things the documentation check corrected, each of which would have produced a screen that
looked entirely plausible and failed on every real server:

- **Firely's `$import-resources` was missing.** Documented, and it answers `403` on the live
  server exactly as its network-protection setting says it should. It is now declared.
- **Firely's measure operations are not administration operations.** They run on the main
  FHIR endpoint, so they are declared at the base URL with no `administration/` prefix. A
  prefix there would 404 on every server; a test now pins the two groups apart.
- **Smile was wrongly assumed to have no addressable operations at all.** Its reindex family
  is served from the **FHIR endpoint**, not the JSON Admin API, so it works with the base
  URL already configured. `09_SECOND_BASE_URL_FOR_ADMIN_APIS.md` is corrected accordingly;
  what still needs a second URL is the Admin JSON API, which is a much smaller piece of work
  than that plan originally implied.
- **Bulk operations belong on the base class.** Declared on `StandardFhirRestPlugin` so both
  vendor plugins inherit them. Both overrides concatenate `super` rather than replacing it —
  an override returning only its own list would have silently dropped bulk export and import
  from a server that supports them.

`$cql` returns `501 Not Implemented` against the public `server.fire.ly`, which is the DQM
module simply not being deployed there rather than a wrong path. No declared path returns
`404` on either test server.

### Server management: list, add, edit, delete

Reported as five separate problems: *Server Tools* did nothing, a configured server could
not be edited, authentication stopped at anonymous, and the add dialog's messages were cut
off with the fields not growing when the window was resized. Persistence to config was
already working from Phase 7 and was verified rather than rebuilt.

**`ServerDialog` is gone**, replaced by `ServerManagerDialog`: a list of configured servers
on the left, the selected server's form on the right, and Add / Save / Delete / Test
connection. Everything applies immediately, so there is no result to unwrap and no Cancel
that would have to roll back three kinds of change; what the user did comes back on the
dialog instead. `ServerFormPanel` holds the fields, so the field set and the list logic can
change independently.

- **Editing** goes through a new `FhirServerManager.replace`, which keeps the server in
  place and keeps the active selection pointing at it. Delete-then-add would have reordered
  the list and briefly left the server unconfigured.
- **Authentication** is per server: anonymous, HTTP Basic, or a bearer token. The form only
  shows the fields a kind needs. `ServerCredentialSaver` keeps the secret and the passphrase
  out of the dialog entirely, and refuses to store a password when no passphrase is set — a
  password nobody can decrypt looks saved and never works.
- **A blank password field means "leave the stored one alone"**, not "clear it". A password
  field is never echoed back, so treating blank as empty would sign the user out of their own
  server every time they corrected a URL. Removing a password means choosing Anonymous.
- **Layout**: the form sits in a `ScrollPane` that fits to width, so fields grow with the
  window, and the status line wraps at full width instead of being truncated at the label
  column.

**A test caught a real bug**: pressing Add cleared the editing reference, which also
disabled Save — so the first server could never be added at all. Fixed with a separate
`addingNew` flag, because Add *starts* the job and Save *finishes* it.

### Credentials are now filed per server, not per plugin

The settings file was keyed by **plugin id**, so `save()` overwrote outright. Two servers
of the same type could not have separate logins: saving the second server's password
replaced the first's, and the first then silently signed in as nobody, with nothing on
screen to say why.

Keyed now by `FhirServerConfiguration.credentialKey()`, which `ServerDefinition` overrides
with a **generated id**. Generated rather than derived because a name- or URL-derived key
breaks when a user edits either, and correcting a URL is exactly what someone does when a
connection is failing — the password would be lost at the moment it was most needed.

Three supporting pieces, each of which would otherwise have silently reintroduced the bug:

- the id is **persisted** in `server-definitions.properties`, or a restart would orphan every
  password;
- the **form carries it across an edit**, or pressing Save would re-generate it;
- the interface method **defaults to the base URL**, so a plugin's own configuration type
  keeps working with nothing added to it.

A plugin-keyed entry is still read as a fallback **when its saved base URL matches**, so a
file written before this change keeps working. The URL check stays strict, because a
credential must never be sent to a host it was not saved for.

### Removed: Tools → Server Tools

`openVendorTool` always reported `UNSUPPORTED`, so the item could only open a picker and
then say the screen was not implemented; only Firely declared a vendor action at all, and
every other plugin declared none. A menu item that cannot do anything is worse than no
menu item, so it is gone along with the handler and the `OfferedAction` record. The
endpoints remain reachable individually under **Run Server Operation...**, and
`FhirServerPlugin.vendorActions()` remains as the seam a future vendor screen would use.

**Tests:** 539 passing. New for this work: `ServerCredentialSaverTest` (8), plus five in
`ServerUiSmokeTest` driving the manager's real buttons, and four in
`ServerDefinitionPersistenceTest` for `replace`. Verified the credential tests fail against
the old keying rather than assuming they would. All offline.
