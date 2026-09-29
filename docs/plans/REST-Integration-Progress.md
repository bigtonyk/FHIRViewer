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
| 22 | Smile CDR Plugin | **partial** — plugin exists; `SmileCdrPluginTest.java.hold` is still disabled |
| 23 | Firely Server Plugin | **done** |
| 24 | UI Asynchronous Operations | **done** |
| 25 | UI Error Handling | **done** |
| 26 | Logging and Diagnostics | **partial** — logged throughout; no diagnostics doc |
| 27 | Plugin Developer API | **partial** — loader, registry and a mock plugin in tests; no published guide |
| 28 | Testing | **done** — 499 tests, offline, `@TempDir` + localhost `HttpServer` |
| 29 | Backward Compatibility | **not started** |
| 30 | Security Review | **not started** |

### Phase 7 — Open from / Save to a FHIR Server

Delivered against `docs/plans/Phase-7-Open-Save-from-FHIR-Server.md`. All six steps are
done; see the note at the end of this file for what changed.

**Still open across the plan as a whole:** `08_SECOND_BASE_URL_FOR_ADMIN_APIS.md`
(a second base URL, needed for Smile CDR's administration API), and phases 29 and 30,
which are review activities rather than code.

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

**A note for Phase 7:** `openVendorTool` is still a stub, so *Tools → Server Tools...*
still reports that a vendor screen is not built. The generic operation screen is the
supported path for a vendor endpoint in the meantime, and Phase 7 should decide whether
vendor actions become entries in that list rather than a separate menu.

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

**Tests:** 499 passing, up from 468. New: `ServerDefinitionPersistenceTest` (11),
`ServerResourceCoordinatorTest` (14), `SearchCriteriaBuilderTest` (4), and two more
JavaFX smoke tests building the new dialogs on a real toolkit. All offline.

**Not done, deliberately:**

- A raw REST console. The Phase 7 plan rules it out of this phase; it needs its own auth,
  error mapping and paging, and belongs behind the plugin interface as a separate change.
- `extraHeaders()` is not persisted, because `ServerDefinition` has no way to carry one and
  a header *value* is a secret. Documented on `ServerDefinitionStore` rather than silently
  dropped.

### Operation discovery: Firely declared, the other two not

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

`FirelyPlugin` now declares fifteen operations — `$reindex`, `$reindex-all`, `$preload`,
`$reset`, and a read-only search over each of the eleven conformance resource types.
The paths were verified against a live Firely Server, which corrected two assumptions: the
branch is `/administration` (not `/admin`), and the API is CRUD on FHIR resources rather
than bespoke JSON endpoints. A `SearchParameter` search returned 1457 resources in a
correctly shaped Bundle.

`StandardFhirRestPlugin` and `SmileCdrPlugin` still declare nothing, deliberately rather
than by oversight:

- **Smile** cannot yet, because its JSON Admin API is served from a **different port**
  (9000) than the FHIR endpoint, and a plugin can only address the configured base URL.
  See `08_SECOND_BASE_URL_FOR_ADMIN_APIS.md`.
- **Standard FHIR REST** has no vendor admin API to declare. Worth a look against the HAPI
  test server, but that is its own piece of work.
