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
| `displayName()`, `description()` | abstract | Shown in `ServerDialog`'s plugin combo |
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
plugins for its own operations, and `MainWindow` only uses it inside `openServerTools()`.

### Threading

Networking is already kept off the JavaFX thread, but the pattern is duplicated rather
than shared:

| Class | Mechanism |
|---|---|
| `ServerDialog` | `javafx.concurrent.Task` on a daemon `Thread` named `fhir-server-test` |
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
`ServerDialog.readableFailure`. `MainWindow` handles `CONFLICT` specially and offers
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
is the only writer. `FhirServerService` never consults it, and `ServerDialog` cannot enter
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
| CapabilityStatement reading | `StandardFhirRestPlugin.capabilitiesOf` |
| Bundle to page conversion | `StandardFhirRestPlugin.pageOf` |
| Search request model | `SearchRequest`, `SearchCriterion`, `SearchResultPage` |
| Write safety | `ServerOrigin`, `ServerWriteResult`, `If-Match` interception |
| Cross-server write guard | `FhirServerService` (compares `ServerOrigin.baseUrl`) |
| UI display of a pulled resource | `MainWindow.display(LoadedResource, ServerOrigin)` |
| Vendor operation declaration | `ServerVendorAction` + `FhirServerPlugin.vendorActions()` |
| Background execution | the existing daemon-`Thread` + `Platform.runLater` pattern (needs extracting) |
| Offline test server | `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0`, as in `StandardFhirRestPluginTest`, `ServerWriteTest`, `FirelyPluginTest` |

## 8. Gaps that need to be filled

Ordered roughly by how much they block the later phases.

**Transport and protocol**

1. **No common REST abstraction.** Phases 2–4 assume one has to be designed. HAPI's
   `IGenericClient` already *is* the transport for standard FHIR, and
   `rawHttpRequest()` + `getHttpClient()` cover arbitrary vendor calls. What is missing is
   a thin, UI-neutral, vendor-agnostic request/response model (method, path, query,
   headers, body, content type, accept, status, response headers, body, error) that sits
   *above* HAPI — not a second HTTP stack. Phase 2 must decide this explicitly.
2. **Timeouts are ignored.** `timeoutMillis()` is on the configuration and read by nobody.
3. **No response-header or status access** outside HAPI's exception types.
4. **No `OperationOutcome` is ever parsed or surfaced.** `convertFailure` keeps only the
   HTTP status and writes its own generic message, so the server's `issue[].diagnostics`
   are lost. This is the most repeated requirement across the whole plan.
5. **No TLS truststore support** for servers behind a corporate CA.
6. **No cancellation** on any in-flight server operation.

**Authentication**

7. **No bearer, OAuth 2.0 or SMART on FHIR.** `ServerAuthentication` cannot express them.
8. **Saved credentials are not wired in.** `PluginSettingsStore` is never consulted by
   `FhirServerService`, and `ServerDialog` collects no credentials.

**Plugin / vendor layer**

9. **`openVendorTool` is a stub, and `MainWindow` does not even call it.** The private
   `MainWindow.openVendorTool(server, action)` only writes a status line. The declaration
   side (`vendorActions()`) is live and listed under *Tools -> Server Tools...*; the
   execution side does nothing.
10. **`vendorActions()` is coarse.** It models a whole screen, not a callable operation
    with parameters. The plan's UI wants parameterised, result-bearing operations that are
    discoverable, invokable and displayable.
11. **Vendor plugins carry no vendor behaviour.** `SmileCdrPlugin` and `FirelyPlugin` only
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

15. **`FhirServerManager` is in-memory only** — `add` / `remove` / `setActive`, no load or
    save. Every configured server is lost on restart. Its own javadoc flags this and
    points at a `FhirServerRepository` that does not exist.
16. **`PluginSettingsStore` is keyed by plugin id, not by server**, so multiple servers on
    one plugin (or one server switching plugins) cannot be represented.
17. **`loadsOnStart` is written but never acted on** — nothing in `MainWindow` reads it to
    auto-add a server at start-up.

**UI**

18. **No generic operation discovery/invocation UI.** *Tools -> Server Tools...* is a
    `ChoiceDialog` of labels; there is no parameter model, no result rendering and no
    `OperationOutcome` display.
19. **Duplicated background-task plumbing** across three dialogs plus `MainWindow`.
20. **No request cancellation or progress reporting** beyond a busy label.
21. **`MainWindow.openVendorTool` leaks an implementation detail** into the status bar: it
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
    `PluginJarScanner`, `ServerDialog`, `ServerSearchDialog`, `OpenFromServerDialog` or
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
   the `Attempt<T>` record) into one small helper used by `ServerDialog`,
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

`ServerDialog` still collects no credentials — a credential entry UI is Phase 6 work. The
`Authorization` header is not logged or displayed anywhere. No vendor endpoints, no
Smile/Firely APIs, and `PluginSettingsStore`'s file format and plugin-id keying are
untouched.

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


