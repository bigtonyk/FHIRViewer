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
| 2 | Define the Common REST API | not started |
| 3 | Create a Server REST Request/Response Model | not started |
| 4 | Implement the Common HTTP Transport | not started |
| 5 | Authentication Architecture | not started |
| 6 | Standard FHIR REST Operations | not started |
| 7 | High-Level FHIR Client API | not started |
| 8 | Custom/Vendor REST Operations | not started |
| 9 | Separate Standard and Vendor APIs | not started |
| 10 | Server Capabilities | not started |
| 11 | Server Status | not started |
| 12 | UI Plugin Operations API | **done** |
| 13 | UI Operation Discovery | **done** |
| 14 | UI Operation Model | **done** |
| 15 | UI Parameter/Input Handling | **done** |
| 16 | UI Results Handling | **done** |
| 17 | UI Server Connection Workflow | **done** |
| 18 | UI Resource Operations | **done** |
| 19 | UI Search | **done** |
| 20 | UI Custom Operations | **done** |
| 21 | Generic FHIR Server Plugin | not started |
| 22 | Smile CDR Plugin | not started |
| 23 | Firely Server Plugin | not started |
| 24 | UI Asynchronous Operations | **done** |
| 25 | UI Error Handling | **done** |
| 26 | Logging and Diagnostics | not started |
| 27 | Plugin Developer API | not started |
| 28 | Testing | not started |
| 29 | Backward Compatibility | not started |
| 30 | Security Review | not started |

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
