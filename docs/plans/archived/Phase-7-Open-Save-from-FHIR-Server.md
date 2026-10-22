# Phase 7 - Open from / Save to a FHIR Server

Plan only. Nothing in this phase is implemented yet.

## Goal

Let a user pull a resource off a configured FHIR server into the editor, edit it, and
push it back. Concretely:

- `File > Open from Server...` - search a server, pick a resource, display it in the
  tree / pretty / JSON / XML / FHIRPath tabs like any loaded file.
- `File > Save to Server...` (or `File > Sync > Push to Server`) - write the currently
  displayed resource back to the server it came from, or to a chosen server.
- Menu items enable and disable with editor state, matching the existing File menu rules.

## What exists today

| Piece | State |
| --- | --- |
| `FhirServerPlugin` | Read-only: `capabilities`, `testConnection`, `search`, `nextPage`, `read`, `localCopy`. **No write verbs.** |
| `FhirServerService` | Same read-only set. No create/update/delete. |
| `StandardFhirRestPlugin` | Holds `protected IGenericClient newClient(ServerSession)` - the extension hook a write implementation needs. |
| `ServerSearchDialog` | `Tools > Search FHIR Server...` can search and read, but has no route into the editor. |
| `MainWindow.display(LoadedResource)` | The single private seam that populates every view. This is where a pulled resource must land. |
| `LoadedResource` | File-shaped: `resource`, `format`, `sourceName`, `rawText`, `sourcePath`, `dirty`. Knows nothing about servers. |
| `hapi-fhir-client` | Already a dependency, so the transport for writes is available. |
| `FhirServerManager` | `add`, `remove`, `servers()`, `setActive`, `active()`. **In-memory only - definitions are never persisted.** |
| `PluginSettingsStore` | Per *plugin* base URL + user name + encrypted password + load-on-start. Not per *server*. |

## Answering the open question from the last review

There is **no** UI to send arbitrary REST GET/POST/PUT/DELETE today, and the server
layer exposes no way to do it. That is deliberate rather than missing: the plugin
interface only has read verbs.

This phase deliberately does **not** add a raw REST console. A free-form request dialog
would need its own auth handling, its own error mapping and its own paging story, and it
would bypass the plugin abstraction that keeps HTTP out of the UI. The workflows people
actually want - find a patient, edit it, write it back - are covered by Open/Save below.
A raw REST console is a reasonable follow-up; see "Optional follow-up" at the end.

## Design decisions

**DD1 - Write verbs go on `FhirServerPlugin`, with defaults that throw.**
A vendor plugin that cannot write should still load. Add `create`, `update` and `delete`
to the interface as `default` methods that throw `ServerOperationException`. This keeps
third-party plugins source-compatible and keeps the "no HTTP in the UI" rule intact.

The `ServerOperationException.Kind` enum today is `UNREACHABLE`, `UNAUTHORIZED`,
`FORBIDDEN`, `NOT_FOUND`, `BAD_REQUEST`, `SERVER_ERROR` - there is no value for either
case this phase needs, so add two:
- `UNSUPPORTED` - this plugin or this server does not implement the verb.
- `CONFLICT` - the server rejected the write because the resource changed (`412`).

**DD2 - Do not touch `LoadedResource`; hold the origin in `MainWindow`.**
`LoadedResource` is file-shaped and used by the file-open, sample and paste paths. It
already keeps a `sourcePath` that means "a file on disk". Server origin is a different
concept. Follow the existing `displayedEntry` pattern: add a `ServerOrigin` field to
`MainWindow` alongside `loadedResource`, so no model class changes and the file paths
stay untouched.

```java
public record ServerOrigin(String pluginId, String baseUrl,
                           String resourceId, String versionId) { }
```

`versionId` is the server's `meta.versionId` at read time, used for optimistic
concurrency on write.

**DD3 - Optimistic concurrency, not blind overwrite.**
On update, send `If-Match: W/"<versionId>"`. If the server answers `412 Precondition
Failed`, report "this resource changed on the server since you loaded it - reload or
force". Offer a force-write choice rather than silently clobbering someone else's edit.
A viewer that overwrites by default is a data-loss risk in a clinical tool.

**DD4 - Validate before push.**
`ValidationService` already exists and the editor already shows a report. Run it on the
current resource and show blocking errors before writing. Warn-but-allow on warnings.

**DD5 - Credentials only ever travel via `ServerSession`.**
Write ops take a `ServerSession` built from `ServerAuthentication`. Decrypt the stored
password through `SecretBox` (PBKDF2 + AES-GCM) at the moment of use, prompt for the
passphrase, and never log the token or the plaintext.

**DD6 - Persist server definitions first.**
This is a prerequisite, not optional. Without it, "Open from Server" only works for
servers added in this one session. Add load/save of definitions to
`PluginSettingsStore` (or a sibling `server-definitions.properties`) reusing the same
`FileSupport` path resolution. Credentials stay in `SecretBox` as they are today.

## Files

**New - `server/`**
- `ServerOrigin.java` - the record above.
- `ServerWriteResult.java` - returned id, versionId, and whether the server created or
  updated, so the UI can rebase the origin after a successful push.

**New - `ui/`**
- `OpenFromServerDialog.java` - server picker + type/id + search, results table, Open.
- `SaveToServerDialog.java` - target server, dry-run/conflict summary, force checkbox.
- `ServerResourceCoordinator.java` - assembles session + auth + plugin call. Keeps
  JavaFX out of the transport logic and makes the flows testable without a stage.

**Modified - `server/`**
- `ServerOperationException.java` - add the `UNSUPPORTED` and `CONFLICT` kinds.
- `FhirServerPlugin.java` - add `create` / `update` / `delete` defaults.
- `StandardFhirRestPlugin.java` - implement the three via `newClient`, map HAPI
  exceptions through the existing `convertFailure`.
- `FhirServerService.java` - thin delegating methods, matching its existing shape.
- `FhirServerManager.java` - add `load` / `save` for definitions.

**Modified - `ui/`**
- `MainWindow.java` - `ServerOrigin` field, `displayResource(...)` overload that accepts
  an origin, File menu items, enable/disable wiring, `saveToServer` handler.

## Steps

Each step is independently committable and leaves the build green.

**Step 1 - Persist server definitions.**
`FhirServerManager.load(Path)` / `save(Path)`, reusing `PluginSettingsStore` helpers and
`FileSupport`. Non-secret fields only; the password keeps living in `SecretBox`.
*Done when:* definitions survive a restart.

**Step 2 - Write verbs on the plugin interface.**
`ServerOrigin`, `ServerWriteResult`, the three default methods, and the
`StandardFhirRestPlugin` implementations with `If-Match` support.
*Done when:* `StandardFhirRestPluginTest` grows write cases against the canned server.

**Step 3 - Service + coordinator.**
`FhirServerService` delegates; `ServerResourceCoordinator` builds the session, decrypts
credentials and calls the plugin.
*Done when:* coordinator tests cover anonymous, basic-auth and wrong-passphrase paths
with a fake plugin - no network, no JavaFX.

**Step 4 - Open from Server.**
`OpenFromServerDialog` plus the `MainWindow` seam. Prefer reusing
`ServerSearchDialog`'s search request building rather than duplicating it.
*Done when:* a resource pulled from the canned server renders in the tree and all four
document tabs.

**Step 5 - Save to Server.**
`SaveToServerDialog`, the coordinator's validate-then-write path, the `412` conflict
dialog, and rebasing `ServerOrigin` from `ServerWriteResult`.
*Done when:* push, conflict, and force-push are all covered by tests.

**Step 6 - Menu wiring.**
File menu items, disabled when no resource is loaded or no server is configured.

## Tests

All offline, following the existing conventions in `StandardFhirRestPluginTest` and
`PluginSettingsStoreTest` - a `com.sun.net.httpserver.HttpServer` on localhost, and
`@TempDir` for file state. No Internet access, no JavaFX toolkit needed.

- `ServerOrigin` rebase after write; versionId moves forward.
- `create` issues a POST and returns the server-assigned id.
- `update` on a loaded resource issues a PUT with `If-Match`.
- `update` on a new resource is refused, or promoted to create - decide which, and test
  the chosen behaviour.
- A `412` becomes a clean conflict result, not an exception.
- A plugin that does not override the write verbs throws `UNSUPPORTED`, and the UI shows
  a readable message.
- `delete` on a read-only server reports permission denied cleanly.
- Coordinator: wrong passphrase yields a clear error, and the password never appears in
  `toString()` or any log line.
- Definitions round-trip through save/load, including a definition with a password
  still encrypted at rest.
- Push runs validation first: a resource with a blocking error is not sent.

## Risks

**Untested JavaFX wiring.** The dialogs cannot be covered by these tests, and the
plugin-manager bug earlier in this branch was exactly a UI/API mismatch that compiled
but threw at runtime. Mitigation: keep dialog classes to layout plus handler wiring,
push every decision into `ServerResourceCoordinator`, and hand-check each dialog once in
a running window before merging.

**`IF-Match` support is uneven.** Not every server honours conditional update. Treat a
missing `versionId` as "no conflict detection available" and say so in the UI rather
than pretending the check happened.

**Write support is a real safety change.** This viewer can now modify data on a clinical
system. The conflict handling and the validate-before-push step are requirements, not
polish, and should not be dropped to save time.

## Optional follow-up

A raw REST request console (free-form GET/POST/PUT/DELETE with a response viewer). It
belongs behind the plugin interface like everything else, needs its own auth and error
mapping, and should be a separate phase - not bolted onto this one.

## Not in scope

- Conditional create (`If-None-Exist`) and server-driven `$everything` operations.
- Transaction bundles for multi-resource writes.
- Subscription (`SubscriptionTopic`) or push notifications.

