# FHIRViewer Server Plugin Architecture — Phase 1 Assessment

Phase 1 of `docs/plans/00_MASTER_INSTRUCTIONS.md` / `01_INSPECT_CURRENT_ARCHITECTURE.md`,
branch `server-rest-integration` at `a63dff8` (from `develop`).

This is an **inspection deliverable only**. No REST architecture was implemented and no
production or test code was changed. Everything below is a statement about the code as it
stands today, plus the gaps the later phases have to fill.

Scope note: `docs/plans/FHIR_Server_REST_Integration_Plan.md` and
`docs/plans/REST-Integration-Progress.md` describe an earlier, 30-phase version of this
work. The 8-phase set `00_...` to `08_...` supersedes them; this assessment is written
against the current code and the 8-phase set.

---

## 1. Current Plugin API

The plugin boundary is a single interface, `server.FhirServerPlugin`. It is the only
contract the UI and the service layer depend on, and it is genuinely UI-neutral — the
`server` package contains no JavaFX.

| Member | Kind | Notes |
|---|---|---|
| `id()` | abstract | Stable key stored in server definitions (`standard-rest`, `smile-cdr`, `firely`) |
| `displayName()`, `description()` | abstract | Shown in `ServerManagerDialog`'s plugin combo |
| `supportedFhirVersions()` | abstract | All three plugins return `List.of("R4")` |
| `supports(FhirServerConfiguration)` | abstract | Used by the registry to pick a plugin for a stored server |
| `capabilities(ServerSession)` | abstract | `GET [base]/metadata` |
| `testConnection(ServerSession)` | abstract | Never throws; returns a `ConnectionResult` |
| `search(ServerSession, SearchRequest)` | abstract | Returns one `SearchResultPage` |
| `nextPage(ServerSession, SearchRequest, String)` | abstract | Opaque page token |
| `read(ServerSession, String, String)` | abstract | `read()` by type/id |
| `localCopy(IBaseResource)` | default | Returns the same instance; no copy is made |
| `create(ServerSession, IBaseResource)` | default | `UNSUPPORTED` unless overridden |
| `update(ServerSession, IBaseResource, ServerOrigin)` | default | `UNSUPPORTED` unless overridden |
| `delete(ServerSession, ServerOrigin)` | default | `UNSUPPORTED` unless overridden |
| `supportsWrite()` | default | Lets the UI disable "Save to Server" up front |
| `vendorActions()` | default | The vendor-tooling seam, as data |
| `openVendorTool(String, ServerSession, Object)` | default | **Stub** — always `UNSUPPORTED` |

Three `protected` extension hooks exist on `StandardFhirRestPlugin` for vendor subclasses:

- `newClient(ServerSession)` — create/configure the HAPI client (auth, headers, timeouts)
- `criteriaOf(SearchRequest)` — translate a `SearchRequest` into HAPI criteria
- `convertFailure(String, Throwable)` — map client exceptions onto `ServerOperationException`

### Value types in the same package

`FhirServerConfiguration` (interface: `name`, `baseUrl`, `fhirVersion`, `pluginId`,
`timeoutMillis`, `extraHeaders`), `ServerDefinition` (immutable builder), `ServerSession`
(server + authentication for one operation), `ServerAuthentication`, `ServerCapabilities`,
`ConnectionResult`, `SearchRequest` / `SearchCriterion` / `SearchResultPage`,
`ServerOrigin` / `ServerWriteResult`, `ServerVendorAction`, `ServerOperationException`,
`FhirServerManager`, `FhirServerService`, `FhirServerPluginRegistry`, `PluginConfig` /
`PluginLoader` / `PluginJarScanner`, `PluginSettings` / `PluginSettingsStore` /
`SecretBox` / `SecretBoxException`, `FirelyConfiguration` / `FirelyPlugin`,
`SmileCdrConfiguration` / `SmileCdrPlugin`, `StandardFhirRestPlugin`.

## 2. Discovery, registration and lifecycle

There is **no plugin lifecycle** in the usual sense. A plugin is stateless, created once at
start-up, and every call is a synchronous method call. There is no `init`/`close`, no
connection pooling, no per-plugin thread and no ordering guarantee between plugins.

Registration has two independent routes, both filtered by a deny list:

1. **Service file** — `ServiceLoader` over
   `META-INF/services/com.example.fhirviewer.server.FhirServerPlugin`, which currently
   lists `StandardFhirRestPlugin` and `SmileCdrPlugin`.
2. **Configuration** — `fhirviewer-plugins.properties` (class path default, overridable
   with `-Dfhirviewer.plugins.config=...` or a file in the working directory) enables plugin
   classes by name. `FirelyPlugin` is loaded this way.

`PluginLoader.load()` builds the registry; a plugin that fails to instantiate is logged and
skipped so one bad entry cannot stop the viewer. `FhirServerPluginRegistry` is a
`LinkedHashMap` keyed by `id()`, later registrations win, and `pluginFor(configuration)`
prefers an exact id match before falling back to first-`supports` wins.

`PluginManagerDialog` adds a **third, run-time** route: it scans a folder with
`PluginJarScanner` (which only *reads* `META-INF/services` entries and never loads a jar),
lets the user edit the config file, and writes per-plugin settings through
`PluginSettingsStore`. It is wired against the live `registry` held by `FhirServerService`.
A jar is still never added to the class path by the application — the user must do that
out of band — so a scanned-but-not-loadable plugin is reported, not run.

## 3. Current UI / service / plugin path

The `UI -> service -> plugin` pattern the plan asks about **already exists** and is in use.
It must be extended, not replaced.

```
JavaFX dialog / MainWindow handler
  -> FhirServerService          (resolve plugin from registry, build ServerSession, delegate)
  -> FhirServerPlugin           (vendor-specific behaviour)
  -> StandardFhirRestPlugin     (HAPI IGenericClient)
  -> HTTP
```

`FhirServerService` is the single entry point. It never exposes HTTP, URLs, headers or
`IGenericClient` to the UI. Every read method builds an **anonymous** session
(`AnonymousServerAuthentication.INSTANCE`); the three write methods take an explicit
`ServerAuthentication` so stored credentials can be passed through. It also refuses
cross-server writes up front by comparing `ServerOrigin.baseUrl()` with
`server.baseUrl()`.

The one deliberate exception is `registry()`: it is exposed so `PluginManagerDialog` can
list and run-time-register plugins. Its own javadoc says the UI is not expected to resolve
plugins for its own operations, and `MainWindow` no longer resolves plugins itself at all —
it used to do so only inside `openServerTools()`, which has since been removed.

### Threading

Networking is already kept off the JavaFX thread, but the pattern is duplicated rather
than shared:

| Class | Mechanism |
|---|---|
| `ServerManagerDialog` | `javafx.concurrent.Task` on a daemon `Thread` named `fhir-server-test` |
| `ServerSearchDialog` | same pattern, `fhir-server-search`; has its own private `Attempt<T>` record |
| `OpenFromServerDialog` | same pattern, `fhir-open-from-server`; its own `Attempt<T>` |
| `MainWindow.runServerWrite` | raw `Thread` + `Platform.runLater`, no cancellation |
| `IgPackageDialog` | raw `Thread` + `Platform.runLater` (package registry, not a server) |

No shared async helper exists, and nothing is cancellable once started.

### Error handling

Failures are normalised once, in the plugin layer: `StandardFhirRestPlugin.convertFailure`
maps HAPI exceptions onto `ServerOperationException.Kind` (`UNREACHABLE`, `UNAUTHORIZED`,
`FORBIDDEN`, `NOT_FOUND`, `BAD_REQUEST`, `UNSUPPORTED`, `CONFLICT`, `SERVER_ERROR`) and
preserves the HTTP status. The UI then only has to render `displayMessage()`.
`ServerSearchDialog.readableFailure(Throwable)` is the shared renderer, re-exported as
`ServerManagerDialog.readableFailure`. `MainWindow` handles `CONFLICT` specially and offers
reload-or-force.

## 4. Existing HTTP infrastructure

**There is exactly one HTTP client for FHIR servers, and it already exists:
`ca.uhn.fhir.rest.client.api.IGenericClient`, created in
`StandardFhirRestPlugin.newClient()` from the shared R4 `FhirContext`
(`fhir.FhirContextFactory.r4()`).** It is built per operation — there is no client cache,
pool or reuse.

Already working through it: `GET [base]/metadata`, search with `_count` and criteria,
`Bundle` next-link paging, `read` by type/id, `POST [type]`, `PUT [type]/[id]`, `DELETE`,
and static request headers via `SimpleRequestHeaderInterceptor` subclasses.

Verified constraints of the current client, relevant to the later phases:

- **No timeout support.** `IGenericClient` / `IRestfulClient` expose no timeout setter
  (checked against HAPI 8.12.0). `FhirServerConfiguration.timeoutMillis()` is therefore
  dead: no code anywhere reads it, and `ServerDefinition`'s default is `0`. Timeouts have
  to be applied at the `IHttpClient` layer, which today is HAPI's default client.
- **Raw requests are available.** `IGenericClient.rawHttpRequest()` and `getHttpClient()`
  are present, so vendor-specific GET/POST can be issued through the same authenticated
  client without inventing a second HTTP stack.
- **Server operations are available.** `IGenericClient.operation()` exists
  (`IOperationUnnamed.named(...)` / `withParameter(...)`), so `$`-operations are reachable.
- **No TLS configuration anywhere.** There is no `SSLContext`, `TrustManager` or
  `HostnameVerifier` in the codebase — certificate validation is simply left on, which is
  correct — but there is also no supported way to add an enterprise truststore.
- **No redirect policy, no proxy, no retry/backoff, no response-header access.**

The only other HTTP code in the project is `service.PackageRegistryService`, which uses
JDK `java.net.http.HttpClient` for the HL7 package registry (JSON packuments and `.tgz`
downloads). It is unrelated to FHIR servers: a different protocol, no auth, no FHIR
semantics, and it belongs to the IG package feature. It should **not** be generalised into
the server transport.

## 5. Existing authentication infrastructure

`ServerAuthentication` is the seam, and it is deliberately tiny:

```java
String type();          // "anonymous" | "basic"
String displayName();   // label for the dialog
boolean isAnonymous();
```

Implementations: `AnonymousServerAuthentication` (singleton `INSTANCE`) and
`BasicServerAuthentication` (username + password, package-private
`authorizationHeaderValue()` producing the Base64 `Authorization` value, and a
`toString()` that never reveals the password).

Credential handling that already works:

- Secrets live on `ServerSession`, never on `FhirServerConfiguration`, so persisting a
  server can never persist a credential.
- `SecretBox` encrypts passwords at rest: PBKDF2-HMAC-SHA256 (210 000 rounds, per-value
  16-byte salt, 256-bit key) + AES-GCM (per-value 12-byte IV, 128-bit tag), stored as
  `v1$<salt>$<iv>$<ciphertext>`. Wrong passphrase and tampered value produce the *same*
  message on purpose.
- `PluginSettingsStore` writes through a temp file and an atomic move, and
  `PluginSettings.toString()` prints `<none>`/`<set>` instead of the password.
- `StandardFhirRestPlugin` refuses anything that is neither anonymous nor basic, with an
  explicit message, rather than silently sending an unauthenticated request.

The gap is the interface itself: it has **no way to contribute a request header**, so
adding bearer tokens, OAuth 2.0 / SMART on FHIR or an API-key mechanism means either
growing `ServerAuthentication` or adding another `instanceof` branch in `newClient()` —
`newClient()` already has that `instanceof BasicServerAuthentication` special case.

`PluginSettingsStore` is keyed by **plugin id**, not by server, and `PluginManagerDialog`
is the only writer. `FhirServerService` never consults it, and `ServerManagerDialog` cannot enter
credentials ("the server layer currently supports anonymous access only", per its own
javadoc). So saved credentials are stored but not yet wired into a read or a write.

## 6. Existing FHIR infrastructure

Reusable and complete:

- `fhir.FhirContextFactory` — one cached `FhirContext` for R4 plus the matching
  `IFhirPath` engine and `FhirModelAdapter`. The plugins already share it.
- `fhir.ResourceParser` / `ResourceSerializer` — JSON and XML in and out, with
  `FhirParseException` carrying user-readable messages.
- `fhir.FhirModelAdapter` / `R4ModelAdapter` — version-neutral traversal, so tree building
  and the Pretty View are already FHIR-version independent.
- `fhir.ResourceTreeBuilder` and `pretty.PrettyModelBuilder` — the rendering path the UI
  reuses for anything it displays.
- `service.ValidationService` — HAPI validation against the loaded IG packages.
- `service.FHIRPathService`, `service.ResourceEditorService`, `service.SourceEditorService`
  — editing, comparison and FHIRPath.
- `model.LoadedResource` + `ResourceFormat` — the single shape every view consumes, which
  is why `ServerSearchDialog` and `OpenFromServerDialog` can both return a
  `LoadedResource` and need no second rendering path.

Server-side FHIR behaviour already implemented in `StandardFhirRestPlugin`:
`CapabilityStatement` to `ServerCapabilities` (FHIR version plus
`rest[0].resource[].type`), `Bundle` to `SearchResultPage` (entries, `total`, `next` link),
resource read, and the three write verbs with `If-Match: W/<versionId>` conditional writes
on update and delete, and `412` mapped to `CONFLICT`.

Search is deliberately minimal: `SearchCriterion` is a name/value pair and every criterion
becomes `StringClientParam(name).matchesExactly().value(v)`. Modifiers, prefixes, chains,
composites, `_sort` and `_include` are not modelled.

## 7. Existing reusable code — inventory

| Concern | Reuse instead of rebuilding |
|---|---|
| HTTP transport | `IGenericClient` via `StandardFhirRestPlugin.newClient`; `rawHttpRequest()` / `getHttpClient()` for vendor endpoints |
| Request headers | `SimpleRequestHeaderInterceptor` subclass (`StaticHeaderInterceptor`) |
| Error model | `ServerOperationException` + `StandardFhirRestPlugin.convertFailure` |
| Transport-failure detection | `StandardFhirRestPlugin.hasTransportCause` (`ConnectException`, `SocketTimeoutException`, `UnknownHostException`, `SSLException`) |
| Auth seam | `ServerAuthentication`, `ServerSession`, `BasicServerAuthentication` |
| Secret storage | `SecretBox`, `PluginSettingsStore`, `PluginSettings` |
| Plugin discovery | `ServiceLoader`, `PluginConfig`, `PluginLoader`, `FhirServerPluginRegistry` |
| Third-party jar inspection | `PluginJarScanner` |
| FHIR context / parsers | `FhirContextFactory`, `ResourceParser`, `ResourceSerializer` |
| CapabilityStatement reading | `ServerCapabilityReader` (was `StandardFhirRestPlugin.capabilitiesOf`, removed in Phase 4) |
| Bundle to page conversion | `ServerSearchBundleReader` (was `StandardFhirRestPlugin.pageOf`, removed in Phase 4) |
| Search request model | `SearchRequest`, `SearchCriterion`, `SearchResultPage` |
| Write safety | `ServerOrigin`, `ServerWriteResult`, `If-Match` interception |
| Cross-server write guard | `FhirServerService` (compares `ServerOrigin.baseUrl`) |
| UI display of a pulled resource | `MainWindow.display(LoadedResource, ServerOrigin)` |
| Vendor operation declaration | `ServerVendorAction` + `FhirServerPlugin.vendorActions()` |
| Background execution | the existing daemon-`Thread` + `Platform.runLater` pattern (needs extracting) |
| Offline test server | `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0`, as in `StandardFhirRestPluginTest`, `ServerWriteTest`, `FirelyPluginTest` |

## 8. Gaps that need to be filled

*The assessment below is the one made at `a63dff8`. Gaps 1, 2, 4 and 13 were closed in
phases 2, 3 and 4 — see sections 12 and 13 for what was actually done. The rest stand.*

### Closed since this assessment was written

The list is a snapshot from `a63dff8` and **no longer describes the current code**. The
following gaps have since been closed; each is struck through in place below and the
resolution is recorded in `docs/plans/REST-Integration-Progress.md`.

| Gap | Closed by | Now |
|---|---|---|
| 2 — timeouts ignored | Phase 3/4 | `timeoutMillis` is honoured through the HAPI client factory |
| 3 — no response-header or status access | Phase 3 | The transport exposes status and headers |
| 4 — `OperationOutcome` never parsed | Phase 5 | `ServerOperationException` carries it, and `ServerErrors` renders it |
| 6 — no cancellation | Phase 6 | `BackgroundTasks` supports cooperative cancellation |
| 7 — no bearer / OAuth / SMART | Phase 5 | `BearerServerAuthentication` exists; OAuth/SMART still do not |
| 8 — credentials not wired in | Phase 5, then the server manager | `ServerCredentials` reads the encrypted store; `ServerManagerDialog` collects them per server |
| 9 — `openVendorTool` a stub | this branch | The menu item and the stub are **removed** rather than made real |
| 10 — `vendorActions()` too coarse | Phase 5/6 | `ServerOperation` models a callable operation with parameters and a declared result |
| 11 — vendor plugins carry no behaviour | Phases 5–7 | All three plugins now declare operations |
| 15 — `FhirServerManager` in-memory only | Phase 7 | `load` / `save` against `server-definitions.properties`, plus `replace` for editing |
| 16 — `PluginSettingsStore` keyed by plugin | this branch | Keyed per server by a stable generated id |
| 18 — no operation discovery/invocation UI | Phase 6 | `ServerOperationDialog` + form + result classification |
| 19 — duplicated background plumbing | Phase 6 | `BackgroundTasks` replaced the copies |
| 20 — no cancellation or progress | Phase 6 | Cooperative cancellation and a busy state |
| 21 — `openVendorTool` leaks a class name | this branch | Gone with the method |

**Still open:** 5 (TLS truststore), 12 (detection heuristics), 14 (`extraHeaders` not
persisted), 17 (`loadsOnStart` not acted on), and OAuth/SMART from gap 7. See the user
guide's *Known gaps* for the user-facing list.

Ordered roughly by how much they block the later phases.

**Transport and protocol**

1. **No common REST abstraction.** Phases 2–4 assume one has to be designed. HAPI's
   `IGenericClient` already *is* the transport for standard FHIR, and
   `rawHttpRequest()` + `getHttpClient()` cover arbitrary vendor calls. What is missing is
   a thin, UI-neutral, vendor-agnostic request/response model (method, path, query,
   headers, body, content type, accept, status, response headers, body, error) that sits
   *above* HAPI — not a second HTTP stack. Phase 2 must decide this explicitly.
2. ~~**Timeouts are ignored.** `timeoutMillis()` is on the configuration and read by nobody.~~ *- closed; see the table above.*
3. ~~**No response-header or status access** outside HAPI's exception types.~~ *- closed; see the table above.*
4. ~~**No `OperationOutcome` is ever parsed or surfaced.** `convertFailure` keeps only the~~ *- closed; see the table above.*
   HTTP status and writes its own generic message, so the server's `issue[].diagnostics`
   are lost. This is the most repeated requirement across the whole plan.
5. **No TLS truststore support** for servers behind a corporate CA.
6. ~~**No cancellation** on any in-flight server operation.~~ *- closed; see the table above.*

**Authentication**

7. ~~**No bearer, OAuth 2.0 or SMART on FHIR.** `ServerAuthentication` cannot express them.~~ *- closed; see the table above.*
8. ~~**Saved credentials are not wired in.** `PluginSettingsStore` is never consulted by~~ *- closed; see the table above.*
   `FhirServerService`, and `ServerManagerDialog` collects no credentials.

**Plugin / vendor layer**

9. ~~**`openVendorTool` is a stub, and `MainWindow` does not even call it.** The private~~ *- closed; see the table above.*
   `MainWindow.openVendorTool(server, action)` only writes a status line. The declaration
   side (`vendorActions()`) is live and listed under *Tools -> Server Tools...*; the
   execution side does nothing.
10. ~~**`vendorActions()` is coarse.** It models a whole screen, not a callable operation~~ *- closed; see the table above.*
    with parameters. The plan's UI wants parameterised, result-bearing operations that are
    discoverable, invokable and displayable.
11. ~~**Vendor plugins carry no vendor behaviour.** `SmileCdrPlugin` and `FirelyPlugin` only~~ *- closed; see the table above.*
    override `id` / `displayName` / `supports` / `testConnection` / `capabilities` and wrap
    the result in a tagged `ServerCapabilities` subclass. Neither reads its own
    configuration interface: `FirelyConfiguration.apiKeyAuthentication()` and
    `SmileCdrConfiguration.trustedClientMode()` are declared and never called. Only Firely
    declares a vendor action, and it is a stub. `FirelyServerCapabilities` and
    `SmileServerCapabilities` exist purely as type tags — the UI does not branch on them.
12. **Vendor detection heuristics are dead code in production.**
    `FirelyPlugin.looksLikeFirely` and `SmileCdrPlugin.looksLikeSmileCdr` are only
    exercised by unit tests; `capabilities()` never calls them.
13. **`ServerCapabilities.pagingSupported` is hard-coded `false`** in `capabilitiesOf`, so
    `ServerSearchDialog` can never show "paging supported".
14. **`FhirServerConfiguration.extraHeaders()` is always empty.** `ServerDefinition` returns
    `Map.of()` and no builder method populates it, so the header loop in `newClient()` can
    never fire from the UI.

**State and persistence**

15. ~~**`FhirServerManager` is in-memory only** — `add` / `remove` / `setActive`, no load or~~ *- closed; see the table above.*
    save. Every configured server is lost on restart. Its own javadoc flags this and
    points at a `FhirServerRepository` that does not exist.
16. ~~**`PluginSettingsStore` is keyed by plugin id, not by server**, so multiple servers on~~ *- closed; see the table above.*
    one plugin (or one server switching plugins) cannot be represented.
17. **`loadsOnStart` is written but never acted on** — nothing in `MainWindow` reads it to
    auto-add a server at start-up.

**UI**

18. ~~**No generic operation discovery/invocation UI.** *Tools -> Server Tools...* is a~~ *- closed; see the table above.*
    `ChoiceDialog` of labels; there is no parameter model, no result rendering and no
    `OperationOutcome` display.
19. ~~**Duplicated background-task plumbing** across three dialogs plus `MainWindow`.~~ *- closed; see the table above.*
20. ~~**No request cancellation or progress reporting** beyond a busy label.~~ *- closed; see the table above.*
21. ~~**`MainWindow.openVendorTool` leaks an implementation detail** into the status bar: it~~ *- closed; see the table above.*
    prints `action.getClass().getName()`.

**Quality issues found while reading (not phase-1 work)**

22. `ServerOperationException.localCopy(IBaseResource)` is a static factory on an
    exception class, used by `FhirServerPlugin.localCopy`; it belongs on the interface or
    a utility.
23. `FhirServerService.describe(FhirServerConfiguration)` logs as a side effect and is
    only reached on the failure path, so successful operations are never logged there.
24. `FhirServerService.read` writes `org.hl7.fhir.instance.model.api.IBaseResource` fully
    qualified in the signature instead of importing it.
25. `SmileCdrPluginTest.java.hold` is an 11-byte file containing the word `placeholder` —
    Smile CDR has **no** test coverage. Neither do the vendor-action path,
    `PluginJarScanner`, `ServerManagerDialog`, `ServerSearchDialog`, `OpenFromServerDialog` or
    `PluginManagerDialog`.

## 9. Recommended extension points

The plan's critical rule — *the UI talks to the Plugin API, never to vendor REST
endpoints* — is already honoured. Everything below extends the existing seams.

1. **Extend `ServerAuthentication` rather than branching on it.** Give it a way to
   contribute request headers (or an `applyTo(...)` hook) so `BearerServerAuthentication`,
   `ApiKeyServerAuthentication` and a SMART/OAuth provider can be added without touching
   `newClient()`. Keep the `isAnonymous()` contract; keep secrets on `ServerSession`.
2. **Introduce a vendor-agnostic REST request/response model above HAPI** (Phase 2),
   implemented *on top of* `IGenericClient` / `getHttpClient()`. Keep it JavaFX-free and
   in the `server` package. Do **not** introduce a second HTTP client.
3. **Carry the `OperationOutcome` on `ServerOperationException`.** Add a nullable
   diagnostics field, populate it in `convertFailure` from
   `BaseServerResponseException.getOperationOutcome()`, and render it in
   `ServerSearchDialog.readableFailure` and the status view. Never log the raw body.
4. **Add a discoverable, invokable operation declaration to `FhirServerPlugin`** — a new
   `default` method returning operation descriptors (id, label, description, parameter
   schema, result kind: FHIR resource / Bundle / JSON / XML / text / OperationOutcome).
   Build it alongside `ServerVendorAction` rather than replacing it, so plugins compiled
   against the current interface keep working through the existing `default` methods.
5. **Make `openVendorTool` real**, and make `MainWindow.openVendorTool` actually call it.
   The current `Object owner` parameter is already the right shape for passing a JavaFX
   window; keep `server` free of JavaFX types.
6. **Put transport configuration in one place** — `StandardFhirRestPlugin.newClient` is
   already that place. Honour `timeoutMillis()` there (via the `IHttpClient` layer), keep
   the interceptor loop for `extraHeaders()`, and let vendor plugins override.
7. **Give `FhirServerManager` persistence** (load/save of `ServerDefinition`s, non-secret
   fields only) and re-key or supplement `PluginSettingsStore` per server. Reuse
   `FileSupport` and the temp-file-plus-atomic-move pattern already in
   `PluginSettingsStore`.
8. **Extract the background-task pattern** (`Task` + daemon thread + `Platform.runLater` +
   the `Attempt<T>` record) into one small helper used by `ServerManagerDialog`,
   `ServerSearchDialog`, `OpenFromServerDialog` and `MainWindow`. Add cancellation while
   doing so.
9. **Extend `SearchCriterion`** with modifier/prefix/chain kinds as new cases, keeping
   the existing name/value pair working, rather than widening the string format.
10. **Populate `ServerCapabilities.pagingSupported`** from the real CapabilityStatement
    instead of hard-coding `false`, and stop relying on the `ServerCapabilities` subclass
    tags to identify a vendor.
11. **Keep testability**: add new tests with a `com.sun.net.httpserver.HttpServer` on
    `127.0.0.1:0`, matching `StandardFhirRestPluginTest` / `ServerWriteTest` /
    `FirelyPluginTest`. Offline, no JavaFX toolkit. Replace
    `SmileCdrPluginTest.java.hold` with a real test.

## 10. Decisions taken against the plan's assumptions

The master instructions say to adapt the plan to the real architecture and document it.
Three assumptions in phases 2–4 turned out to be wrong for this codebase:

1. **"Create a common HTTP transport"** — HAPI's `IGenericClient` already is one, and it
   is already owned by this project. Phase 2 should define a *facade over* it, not a new
   transport, or the project will end up with two HTTP stacks.
2. **"Implement standard FHIR operations"** (Phase 4) — already implemented in
   `StandardFhirRestPlugin`: metadata, search, paging, read, create, update, delete, with
   `If-Match` conditional writes. Phase 4 should be re-scoped to the missing pieces
   (timeouts, `OperationOutcome`, `PATCH`, `$` operations, capability-driven gating).
3. **"Create a request/response model"** (Phase 3) — only warranted at the *vendor/custom*
   layer; standard FHIR results should keep flowing as `IBaseResource` /
   `SearchResultPage`, which the UI already renders.

## 11. Baseline verification

Run at the start and end of Phase 1, branch `server-rest-integration` at `a63dff8`:

- `mvnw -o -DskipTests compile` — BUILD SUCCESS
- `mvnw -o test` — **Tests run: 243, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS

Phase 1 changed no production or test code, so this baseline is unchanged.

---

## 12. Phase 2 — the REST core API, as built

Phase 2 added the reusable REST abstraction. It is a **facade over an existing transport**,
which is the decision section 10 argued for: no plugin, service or UI code builds an HTTP
request itself, and no second vendor-specific API was introduced.

New package `com.example.fhirviewer.server.rest`, nine small focused classes:

| Class | Role |
|---|---|
| `RestMethod` | The five verbs, plus `allowsRequestBody()` so the rule lives in one place |
| `RestHeaders` | Immutable, case-insensitive, multi-valued headers; redacts credentials in `toString()` |
| `RestRequest` | Immutable request with a builder: verb, path, query, headers, body, content type, accept |
| `RestResponse` | Status, headers, body, success classification and parsed `OperationOutcome` issues |
| `RestOperationOutcome` | One issue reduced to plain strings, so no FHIR version leaks downstream |
| `RestOutcomeParser` | The only class that knows the FHIR model; never throws on a malformed body |
| `RestClient` | The abstraction: `execute` plus GET/POST/PUT/PATCH/DELETE helpers |
| `RestFailures` | Maps transport failures and HTTP statuses onto `ServerOperationException` |
| `JdkHttpRestClient` | The implementation, over `java.net.http.HttpClient` |

### Decisions worth knowing

- **The transport is the JDK client, not a new dependency.** It is already used by
  `PackageRegistryService`, it exposes every verb plus arbitrary headers, per-request
  timeouts and the response status and headers, and it leaves TLS validation on by
  default. Nothing in the class installs an `SSLContext`.
- **HAPI stays where it was.** `StandardFhirRestPlugin` is untouched and still performs
  standard FHIR operations. The REST layer is the generic and vendor path, where a
  response is arbitrary JSON, XML or text rather than a typed resource. Phase 4 should
  decide deliberately whether standard operations migrate; this is the one place where two
  HTTP stacks coexist, and the reason is recorded here rather than left to be discovered.
- **A response that arrived is not an exception.** `404` and `500` come back as a
  `RestResponse` with the server's own `OperationOutcome` already parsed. Only a request
  that produced no answer throws `ServerOperationException`.
- **`ServerOperationException.Kind` gained `TIMEOUT`.** A timeout is not an unreachable
  server: telling a user to check the base URL when the address was right and the server
  was slow sends them to fix the wrong thing. The new REST tests caught the JDK client
  wrapping its own `HttpTimeoutException` in a plain `IOException`, which would have
  reported every timeout as a network failure.
- **Logging discipline.** `RestRequest.toString()` prints the verb, path and sizes but
  never the body, the query values or the header values. `RestHeaders.toString()` redacts
  `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie` and API-key headers.
  `RestResponse.toString()` prints the status, content type and body length only.

### What Phase 2 deliberately did not do

No authentication is wired: `JdkHttpRestClient` sends no `Authorization` header. That is
Phase 3, together with the small extension point on `ServerAuthentication` that phase 1
identified. No vendor endpoints, no plugin changes and no UI changes were made.

### Verification

- `mvnw -o test` — **Tests run: 311, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS
  (243 from the Phase 1 baseline, plus 68 new in `server.rest`).
- The new tests cover request building and validation, header redaction, URL joining,
  response classification, `OperationOutcome` parsing from JSON and XML, the status and
  transport failure mapping, and the transport itself against a `com.sun.net.httpserver`
  server on localhost: every verb, query encoding, header pass-through, `404` with a
  parsed outcome, an unreachable host, a timeout, and execution from a background thread.

---

## 13. Phase 3 — authentication and HTTP, as built

Phase 3 connected the two halves that previously did not touch: the REST transport, which
sent no credentials at all, and the saved credentials, which nothing ever read.

### The extension point

`ServerAuthentication` gained one method:

```java
default RequestHeaders requestHeaders() { return RequestHeaders.none(); }
```

A mechanism now says which headers it needs; both clients apply them without knowing which
mechanism produced them. The two `instanceof` branches are gone:

- `StandardFhirRestPlugin.newClient()` registers an interceptor per contributed header.
- `StandardFhirRestPlugin.requireSession()` no longer names two allowed types. It refuses a
  mechanism that claims to authenticate but contributes **no** header, which is a stricter
  and more useful rule: a not-yet-implemented mechanism is caught, and an implemented one
  is not.

It is a `default` method, so the interface stays source- and binary-compatible for any
implementation compiled against the previous three-method version.

New in `com.example.fhirviewer.server`:

| Class | Role |
|---|---|
| `RequestHeaders` | Immutable header set one mechanism contributes; redacts in `toString()` |
| `BearerServerAuthentication` | A static access token sent as `Authorization: Bearer ...` |
| `ServerCredentials` | Resolves a server's saved settings into a `ServerAuthentication` |
| `ServerPassphrase` | The session passphrase, held as a wipeable `char[]` |

### Decisions worth knowing

- **The redaction list now lives in one place.** `RestHeaders.isSecret` delegates to
  `RequestHeaders.isSecret`. The two types sit on opposite sides of a request — one builds
  what a mechanism contributes, the other carries whatever a request or response ended up
  with — so a header that was redacted on one side must not be printed on the other.
- **`JdkHttpRestClient.forSession(session)` is the normal way to get a client.** It reads
  the base URL, `timeoutMillis()` and the authentication from the `ServerSession` the
  application already holds, so a plugin cannot drift from the server definition the user
  is looking at. There is still exactly one connection system.
- **A per-request header wins over the session's.** The client's headers are applied first
  and the caller's afterwards. A caller overriding `Authorization` for one call must not
  be silently overruled by the session's credentials.
- **Saved credentials are matched on the base URL as well as the plugin id.**
  `PluginSettingsStore` is still keyed by plugin id — re-keying it per server is a
  persistence change belonging to a later phase — but `readForServer` only returns an
  entry whose saved URL matches. Sending a password to a host it was never meant for is
  not a recoverable mistake, so a changed URL, a second server on the same plugin, or a
  trailing slash are all handled deliberately.
- **A credential problem never throws.** A missing file, an absent passphrase, a wrong
  passphrase and a URL mismatch all resolve to anonymous access. A dialog in the middle of
  every read, reporting "your passphrase is wrong" where the user cannot act on it, is
  worse than an anonymous request that the server will refuse with a `401` the UI already
  renders. The reason is logged, without the passphrase or the password.
- **`ServerPassphrase` closes a real gap.** `PluginManagerDialog` clears its passphrase
  field immediately after saving — correctly — which meant the session could never read
  the password back. The dialog now hands the passphrase to the session on a successful
  save, and the session forgets it again when the password is removed.
- **Only a static bearer token was added, not OAuth 2.0 or SMART.** Those need a browser,
  a client registration, a token cache and refresh, none of which belong in a transport
  phase; a half-built flow would be worse than none. The static token is the part that is
  genuinely needed now and the part a flow would eventually hand over.

### Logging and TLS

Unchanged and still enforced. No password, token or `Authorization` value reaches a log
line or a message: `RequestHeaders` and `RestHeaders` redact, `RestRequest.toString()`
prints only header *counts*, and neither `toString()` of an authentication reveals its
secret. No `SSLContext` is installed anywhere, so platform certificate validation stays
on.

### What Phase 3 deliberately did not do

`ServerManagerDialog` still collects no credentials — a credential entry UI is Phase 6 work. The
`Authorization` header is not logged or displayed anywhere. No vendor endpoints, no
Smile/Firely APIs, and `PluginSettingsStore`'s file format and plugin-id keying are
untouched.

> **Superseded.** Accurate as of Phase 3. Since then `ServerManagerDialog` has collected
> credentials per server (anonymous, Basic, bearer), `PluginSettingsStore` is keyed per
> server rather than by plugin id, and the vendor endpoints are declared by all three
> plugins. `Authorization` is still never logged or displayed.

### Verification

- `mvnw -o test` — **Tests run: 345, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS
  (311 from Phase 2, plus 34 new).
- The new tests cover what each mechanism contributes, redaction of credentials, header
  case-insensitivity, and that a mechanism defined *only in the test* — one that exists
  nowhere else in the codebase — is honoured by both clients. That last one is the
  regression guard: if a future change reintroduces an `instanceof` over the mechanism
  types, it fails.
- End-to-end against `com.sun.net.httpserver` on localhost: basic, bearer and anonymous
  over the JDK transport, credentials on every verb, per-request override, and a
  saved password actually arriving on the wire through `FhirServerService`. Plus the
  stored-credential rules — locked, wrong passphrase, wrong URL, changed URL, trailing
  slash, nothing saved — and the passphrase holder.
- Both wiring points were mutation-checked: disabling the header application fails 5 of 9
  transport tests, and disabling the interceptor loop fails 2 of 9 plugin tests.


---

## 13. Phase 4 — standard FHIR operations, as built

Phase 4 of `docs/plans/04_STANDARD_FHIR_OPERATIONS.md`. Section 10 above re-scoped this
phase to the missing pieces: timeouts, `OperationOutcome`, `PATCH`, `$` operations and
capability-driven gating. Read, create, update, delete, search and metadata were already
implemented and were not rebuilt.

### The decision Phase 4 was waiting for

The brief said: *"Phase 4 should decide deliberately whether standard operations
migrate"* — whether the HAPI `IGenericClient` path in `StandardFhirRestPlugin` should be
replaced by the `server.rest` transport Phase 2 built.

**It does not migrate, and the two transports are now divided by a rule rather than by
accident:**

> **The transport is chosen by the *result kind*.** Interactions whose result type is
> known before the request is sent go through HAPI. Interactions where the *caller*
> chooses the type at run time go through `server.rest`.

| | Transport | Why |
|---|---|---|
| metadata, read, search, paging, create, update, delete | HAPI `IGenericClient` | HAPI already returns `IBaseResource`, and does its own content negotiation, conformance caching and `If-Match` handling |
| `PATCH` | `server.rest` | the patch format lives in `Content-Type`, and HAPI's `IPatch` builder offers no way to set it — a caller could not say which of the three formats it meant |
| `$`-operations | `server.rest` | the result type is not known until the server answers, and HAPI's operation API can only be told a Java class up front |

Why not migrate everything:

- `server.rest` speaks strings. The UI contract is `IBaseResource` and
  `SearchResultPage`. Migrating would hand the UI raw JSON to parse, or add a second FHIR
  parser beside the existing `fhir.ResourceParser` — the thing the plan forbids.
- `FirelyPlugin` and `SmileCdrPlugin` extend `StandardFhirRestPlugin` and override its
  HAPI hooks (`newClient`, `criteriaOf`, `convertFailure`). Migrating would change the
  plugin contract inside the same phase.
- What the "two stacks" worry was actually about — two failure vocabularies, two notions
  of a session, two sets of credentials — is now shared. What remains different is the
  transport, and the difference is justified by the table above.

Replies from `server.rest` are parsed with the project's existing `fhir.ResourceParser`,
so a Bundle, a `Parameters`, an `OperationOutcome` or a resource type this build has
never heard of is the same code path as opening a file.

### What was added

| Class | Role |
|---|---|
| `ServerInteraction` | The nine R4 REST interactions, with the spellings servers disagree on folded together |
| `ServerResourceCapabilities` | What one resource type advertises: its interactions and search parameters |
| `ServerCapabilityReader` | The only class that reads a `CapabilityStatement` |
| `ServerSearchBundleReader` | The only class that reads a search `Bundle` |
| `SearchPageLinks` | All five Bundle relations, kept exactly as the server sent them |
| `PatchFormat` | The three patch formats, each naming its `Content-Type` |
| `FhirOperationRequest` | A `$`-operation at server, type or instance level |
| `server.rest.FhirOperationClient` | PATCH and `$`-operations over the generic transport |

Changed: `ServerOperationException` (gains `diagnostics()` and owns the HTTP-status →
`Kind` table), `ServerCapabilities` (gains the interaction set and per-type detail),
`SearchResultPage` (gains `links()`), `FhirServerPlugin` (gains `pageAt`, `patch`,
`supportsPatch`, `invoke`; `nextPage` becomes a default over `pageAt`),

### Decisions worth knowing

- **One status table, not two.** `ServerOperationException.kindOfStatus(int)` is now the
  only HTTP-status → `Kind` mapping; `RestFailures` delegates to it and `convertFailure`
  uses it instead of a chain of `instanceof` tests over HAPI's exception types. Before
  this, the same `409` could be a conflict on one path and a generic error on the other,
  and the UI would offer reload-or-force only half the time.
- **The server's `OperationOutcome` survives.** `convertFailure` parses the refused
  response body with the existing `RestOutcomeParser` and puts the result on
  `diagnostics()` and in the message. "Unknown search parameter 'foo'" replaces "the
  server returned an error". The raw body is never kept: it can echo a submitted
  resource back.
- **`timeoutMillis()` is now read.** HAPI 8 exposes the socket and connect deadlines only
  on the client *factory*, not on the client, so `newClient` builds through a
  `ConcurrentMap<Integer, ApacheRestfulClientFactory>` keyed by the effective timeout. It
  cannot be one factory per operation: `RestfulClientFactory` owns the set of base URLs it
  has already validated, and a fresh one would send an extra unlogged `GET /metadata`
  before every read. `ServerValidationModeEnum.ONCE` is set explicitly for that reason.
- **An omitted interaction is a refusal, but an absent list is not.** A type that lists
  read/search/create and not `patch` has declined a patch. A type the statement declares
  with *no* interaction list is relying on the base specification, and treating that as
  read-only would be the more damaging mistake. A type the statement never mentions
  supports nothing.
- **Interaction codes disagree between the spec and HAPI, and the reader folds them.** R4
  declares search as `search-type` and splits history in two; HAPI's R4 enum accepts only
  those spellings and rejects `search` outright — so a spec-shaped statement can fail to
  parse at all. `ServerInteraction.fromCode` accepts both, and the `ServerCapabilitiesTest`
  canned statement uses the spellings HAPI will parse.
- **Page links are followed, never rebuilt.** `SearchResultPage` now carries all five
  Bundle relations and `FhirServerPlugin.pageAt` fetches whichever one it is given.
  `nextPage(session, request, token)` remains as a default over it, so the UI and service
  call sites are unchanged.
- **An operation name is a URL path segment and is validated as one.** `$everything`,
  `$value-set` and `everything` are accepted; anything carrying `/`, `?` or `#` is refused
  at construction, before a request exists.
- **Operation parameters are name/value pairs sent as `Parameters`.** Every value goes as
  a `valueString`: the form an R4 server accepts for `$expand`, `$everything` and bulk
  data, and the only one producible without a per-operation type table. A typed date, a
  part or a nested resource is a later increment on the request model, not a guess. The
  map is copied into an unmodifiable `LinkedHashMap` rather than with `Map.copyOf`,
  because `Map.copyOf` discards iteration order — a `Parameters` body a caller had
  assembled deliberately would otherwise be silently shuffled. Caught by a test that
  happened to pass once before failing, which is why the assertion is now on the
  record's own accessor as well as on `orderedParameters()`.
- **`StandardFhirRestPlugin.description()` was two phases stale.** It still read
  "metadata, search, read ... anonymous access" after Phase 3 wired credentials in, and
  would have understated Phase 4's additions. It is shown in the server dialog, so it
  now lists what the plugin can actually do.
- **A FHIRPath patch was left out on purpose.** It is a `Parameters` resource rather than a
  patch document; sending one as a merge patch would be silently wrong.
- **No UI work.** `ServerSearchDialog` is untouched, as the plan requires. It will need
  previous/first/last buttons and a capability gate, and both now have a service method
  to call.

`StandardFhirRestPlugin`, `FhirServerService`, `RestFailures`, and the two vendor
capability wrappers.



### Verification

- `mvnw -o -DskipTests compile` — BUILD SUCCESS
- `mvnw -o test` — **Tests run: 383, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS
  (345 before this phase, 38 added)
- New `ServerCapabilitiesTest` (12) — a real `CapabilityStatement` read into
  interactions, search parameters and an honest paging answer; an omitted interaction as a
  refusal; an absent list as unrestricted; an unmentioned type as nothing; all five Bundle
  links read verbatim; blank link URLs dropped; the alternative code spellings.
- New `ServerRequestModelTest` (10) — operation URL per level, name normalisation,
  path-injection refusal for name, type and id, parameter ordering, no parameter values in
  `toString`, patch content types, the shared status table, diagnostics on the exception.
- New `StandardFhirOperationsTest` (16) — patch content type on the wire, `If-Match`, no
  stale precondition without a version, `412` → `CONFLICT`, `501` → `UNSUPPORTED`,
  operation verb per the spec, an operation result parsed, a refused operation carrying
  the server's sentence, following a server-supplied paging link, the applied deadline.
- Existing `ServerWriteTest`, `StandardFhirRestPluginTest`, `FirelyPluginTest` and
  `PluginLoaderTest` all pass unchanged — the new plugin methods are `default`s, and the
  status mapping they replaced was asserted through behaviour, not through wording.

### What Phase 4 deliberately did not do

- No migration of the HAPI path, and no second FHIR parser.
- No credential collection in `ServerManagerDialog` and no SMART/OAuth flow — still Phase 6 work,
  and still a static bearer token only.
  *Superseded: credentials are collected per server now; SMART/OAuth still do not exist.*
- No UI: no paging buttons, no operation picker, no capability gating in the dialogs.
- `SearchCriterion` is still a name/value pair — modifiers, prefixes, chains, `_sort` and
  `_include` are untouched.
- `extraHeaders()` still has no builder method, so gap 14 in section 8 is still open.

