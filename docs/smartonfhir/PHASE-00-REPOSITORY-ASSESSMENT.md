# Phase 00 — Repository assessment and architecture

**Status: findings recorded. No SMART code written. No production behaviour changed.**

- Branch inspected: `smartonfhir` at `4ee6533` (which contains `084ed0a`, the REST console
  body/copy work).
- Baseline verified: `mvnw.cmd -q compile` exit 0; `mvnw.cmd -q test` exit 0 —
  **760 tests, 68 test classes, 0 failures, 0 errors, 0 skipped**.
- Only this document and `IMPLEMENTATION-STATUS.md` were written. `git status` shows no
  production file modified.

The original Phase 00 brief is preserved at the end as *Original brief*, so each item on its
checklist can be traced to a finding below.

---

## 1. Versions and dependencies

From `pom.xml` — a single Maven module, `com.spiraleyes.fhirviewer:fhir-viewer:1.0.0`,
`jar` packaging, GPL-3.0-only:

| Item | Version | Notes |
|---|---|---|
| Java | **21** | `<maven.compiler.release>21</maven.compiler.release>`, applied by the compiler plugin as `<release>` |
| JavaFX | **21.0.8** | `javafx-controls` only, from `org.openjfx`; no manual SDK install |
| JavaFX theme | **2.1.0** | `io.github.mkpaz:atlantafx-base` (Primer Light / Primer Dark) |
| HAPI FHIR | **8.12.0** | `-base`, `-structures-r4`, `-client`, `-validation`, `-validation-resources-r4`, `-caching-caffeine` |
| Jackson | transitive via HAPI | `jackson-databind` **is** used directly by application code but is **not declared** in the POM |
| Markdown | 0.21.0 | `commonmark` + `commonmark-ext-gfm-tables`, for Help > User Guide |
| Logging | SLF4J **2.0.19** | with the `slf4j-simple` binding |
| Tests | JUnit Jupiter **5.14.4** | scope `test` only |
| Build plugins | compiler 3.14.1, surefire 3.5.6, dependency 3.8.1, resources 3.3.1, exec 3.6.4, javafx 0.0.8 | |

Transitively present, and relevant to Phases 03 and 04:

| Artifact | Version | Why it matters |
|---|---|---|
| `com.nimbusds:nimbus-jose-jwt` | **10.0.2** (compile scope) | JOSE/JWT parsing and signature verification is already on the class path — through HAPI, not by choice |
| `com.squareup.okhttp3:okhttp-jvm` | 5.3.2 | HAPI's HTTP transport; **no** application code uses it |
| `org.apache.httpcomponents:httpclient` | 4.5.14 | Same; no application code uses it |

There is **no OAuth 2.0 or OIDC library** of any kind (`oauth2-oidc-sdk`, `appauth`,
`spring-security-oauth2`). `nimbus-jose-jwt` is an accident of the HAPI dependency graph, not a
decision, and must not be relied on as though it were a supported dependency.

---

## 2. Maven module and package structure

One module, one jar, no multi-module reactor, no Gradle. 158 main-source files, 75
test-source files:

```
src/main/java/com/spiraleyes/fhirviewer/
├── Main.java, Launcher.java ......... 2   JavaFX entry points (see §3)
├── ui/ ............................. 44   JavaFX only; no FHIR logic, no logging
├── service/ ........................ 17   application layer; FhirService is the door
├── server/ ......................... 57   FHIR server plugins, auth, persistence
├── server/rest/ .................... 14   the project's own HTTP transport layer
├── fhir/ ............................ 8   version-independence boundary over HAPI
├── model/ .......................... 10   small records (ResourceNode, ValidationReport, ...)
├── pretty/ .......................... 5   Pretty View rendering model
└── util/ ............................ 1   FileSupport (BOM stripping, path helpers)
```

`src/test/java` mirrors the package tree exactly: `ui` 14, `server` 28, `server/rest` 10,
`service` 15, `fhir` 3, `model` 2, `pretty` 2, root 1.

**Conventions to follow:** packages are flat and named for a *role*, not for a feature; a
server-side sub-system earns a sub-package (`server/rest/` for the transport). There is no
`*Impl` suffix, no dependency-injection container, and no interface/implementation split for its
own sake — the only `Abstract*` in the tree is HAPI's own `SimpleRequestHeaderInterceptor`. The
single service entry point is `FhirService`, whose collaborators (`ResourceEditorService`,
`ValidationService`, `FHIRPathService`, `IgPackageManager`) are plain classes with plain
constructors.

---

## 3. Application entry point and JavaFX navigation

Two entry points, and the difference matters for Phase 07 packaging:

- **`Launcher`** — the jar's `Main-Class` (maven-jar-plugin) and the jpackage entry point.
  Deliberately does **not** extend `javafx.application.Application`, because the JVM launcher
  refuses such a main class when JavaFX is on the class path rather than the module path
  (*"JavaFX runtime components are missing"*). It calls `Application.launch(Main.class, args)`.
- **`Main`** — extends `Application`; used by `mvn javafx:run` (module path). It builds
  `new MainWindow(stage, getHostServices())`, a `Scene` at 1180x760 (minimum 860x520), applies
  `ThemeManager.Theme.PRIMER_LIGHT`, sets the window icon from `/FHIRViewer-icon-64.png`, and
  opens an optional first command-line argument as a resource.

Navigation is **one window plus modal dialogs** — no routing, no view stack, no FXML, no
controller annotations anywhere in the tree:

- `MainWindow` composes `new VBox(buildMenuBar(), buildHeaderBar(), buildToolBar())` at the top
  of a `BorderPane` and owns every `MenuItem` as a field with a `setOnAction(...)` handler.
- Feature screens are `javafx.scene.control.Dialog<T>` subclasses shown with `showAndWait()`.
  Established precedent: `RestConsoleDialog`, `ServerManagerDialog`, `PluginManagerDialog`,
  `OpenFromServerDialog`, `SaveToServerDialog`, `ServerSearchDialog`, `ServerOperationDialog`,
  `TokenFetchDialog`, `IgPackageDialog`, `AddChildDialog`, `PatchResourceDialog`,
  `UserGuideDialog`, `AboutInfo`.
- A non-trivial dialog is split into a form / state / pane triple: `RestConsoleDialog` +
  `RestConsoleForm` and `RestConsoleState` + `RestRequestPane` / `RestResponsePane`; likewise
  `ServerOperationDialog` + `ServerOperationForm` + `ServerOperationResults`.
  `RestConsoleDialog` also shows the two house idioms to copy: a `private static final class
  VBoxWithPadding extends VBox` for card layout, and a constant block for
  `MARGIN` / `DEFAULT_WIDTH` / `MIN_WIDTH`.

**Recommendation for Phase 01:** the SMART workbench is a `Dialog<T>` in `ui/` opened from a
Tools-menu `MenuItem` on `MainWindow`, following the `RestConsoleDialog` triple. No new
navigation mechanism should be introduced.

---

## 4. View / controller / service conventions

There are no controllers. The UI layer is plain JavaFX with **no FXML, no `@FXML`, no
`Controller` suffix**; everything is built in constructors. What looks like a "controller" in this
project is a `Dialog<T>` subclass in `ui/` that owns:

- a `record` or small class for its state (`RestConsoleState`, `SearchMemory`,
  `ValidationProfile`),
- one or more `*Pane` / `*Form` classes that build and lay out controls and expose typed accessors
  the dialog reads,
- an `Outcome` result type returned to the caller when the dialog closes.

The service layer behind it is `FhirService` (`service/`): the UI never imports
`org.hl7.fhir.*`. The `fhir/` package is the only place that does, and inside it `R4ModelAdapter`
is the **only** class importing `org.hl7.fhir.r4.model`. `FhirService` exposes
`openFile`/`openSample`/`openText`, `toJson`/`toXml`/`serialize`, `validate`,
`evaluateFHIRPath`, `bundleEntries`, `isBundle`, `fhirVersion` — a narrow, string-in-string-out
surface.

**Reusable for SMART:** `FhirService.toJson(...)` for the request body and for rendering a
`Patient`/`Encounter` context, `ResourceEditorService` for edits, and the `ui/` dialog triple for
the workbench screens. The Pretty View layer (`pretty/PrettyDocument`, `PrettyModelBuilder`,
`PrettyRow`) is also available if context should be rendered the way resources already are.

---

## 5. FHIR server configuration and persistence

Two stores, both `java.util.Properties` files under `~/.fhirviewer/`, both written through a
temporary file and an atomic `Files.move`:

| Concern | Class | File |
|---|---|---|
| Server list: name, base URL, FHIR version, plugin id, timeout, administration base URL, active | `ServerDefinitionStore` via `FhirServerManager` | `~/.fhirviewer/server-definitions.properties` |
| Credentials, auth kind, load-on-start | `PluginSettingsStore` | keyed by `FhirServerConfiguration#credentialKey()` |

Design rules already enforced, which a SMART store must respect:

- **No secret in the server file.** `extraHeaders()` is deliberately not persisted — a header
  *value* is a secret even when its name is not. The class comment states the file is safe to
  hand to a support request.
- **Credentials are keyed per server, not per plugin**, with a plugin-id fallback that applies
  only when the saved base URL matches exactly.
- **Secrets at rest** go through `SecretBox`: PBKDF2-HMAC-SHA256, 210 000 iterations, a 256-bit
  key, AES-GCM with a 96-bit IV, stored as `v1$<salt>$<iv>$<<SECRET_37f1e739>>`. A wrong
  passphrase and a tampered file produce the same `SecretBoxException`, on purpose.
- **The passphrase is never stored.** `ServerPassphrase` holds it in a `char[]`, overwrites on
  `set`/`clear`, is `volatile` + `synchronised` (the UI thread sets it, a background thread reads
  it), and exposes **no** `String` getter.
- The configuration model is `FhirServerConfiguration` (interface) implemented by
  `ServerDefinition` (builder: `named`, `forSmileCdr`, `forFirely`), plus `ServerAuthKind`
  (`ANONYMOUS`, `BASIC`, `BEARER`) which drives which fields the form shows.
- `ServerSession` pairs a configuration with a `ServerAuthentication` for one request or one
  batch — which is exactly why persisting a server can never persist a credential.

**Recommendation for Phases 01/02:** a SMART launch configuration store is a third `*Store` class
on the same pattern — properties under `~/.fhirviewer/`, atomic write, nothing secret in the
clear. Because the client is a public native desktop client there is **no client secret to
store**; only client id, redirect URI, scopes and `iss`.

---

## 6. HAPI FHIR client construction and authentication mechanisms

Two transports coexist, deliberately:

**HAPI's own client**, used by the three server plugins. `StandardFhirRestPlugin` (and
`SmileCdrPlugin`, `FirelyPlugin`) build an `IGenericClient` through a
`IRestfulClientFactory` and then register HAPI interceptors:

```java
IGenericClient client = factoryFor(server).newGenericClient(server.baseUrl());
for (extraHeaders) client.registerInterceptor(new StaticHeaderInterceptor(k, v));
client.registerInterceptor(new StaticHeaderInterceptor("If-Match", "W/" + origin.versionId()));
```

`StaticHeaderInterceptor extends ca.uhn.fhir.rest.client.interceptor.SimpleRequestHeaderInterceptor`
— an in-package helper, not application code.

**The project's own transport**, `server/rest/` (14 classes), used by the REST console and by
`TokenEndpointClient`. It is `java.net.http.HttpClient` with a small daemon pool, no third-party
HTTP library:

```
CurlCommand      renders a request as a curl command
FhirOperationClient, JdkHttpRestClient   the HTTP work
RestAnswer, RestAnswers, RestResponse, RestHeaders, RestMethod, RestRequest, RestUrls
RestOperationOutcome, RestOutcomeParser, RestFailures   OperationOutcome parsing and error mapping
```

**Authentication** is the interface `ServerAuthentication` (`type()`, `displayName()`,
`isAnonymous()`, `requestHeaders()`), with three implementations:

| Class | What it does | Notes for SMART |
|---|---|---|
| `AnonymousServerAuthentication` | sends nothing | |
| `BasicServerAuthentication` | `Authorization: Basic <base64 user:pass>` | secrets redacted in headers and `toString` |
| `BearerServerAuthentication` | `Authorization: Bearer <token>` | **this is the SMART integration point** — its own javadoc says the token is "the half of OAuth 2.0 and SMART on FHIR that is genuinely needed now", and that a browser flow, client registration and token cache "do not belong in a transport phase" |
| `AdhocAuthentication` | wraps a kind plus optional token, for the REST console | |

Every implementation redacts in `toString()` and in the headers it contributes, and
`RequestHeaders` applies the same treatment. `ServerSession` pairs one
`FhirServerConfiguration` with one `ServerAuthentication`, so a token never reaches a
persistence class.

`TokenEndpointClient` already exists and already posts a form to a token endpoint and parses the
answer (`client_secret_post` by default, `client_secret_basic` supported). It is currently used
only by `TokenFetchDialog`, where a user pastes a token or a client secret themselves. **It is the
natural home for the Phase 03 authorization-code + PKCE exchange**, extended rather than
replaced; `TokenFetchResult` already models `access_token`, `token_type` and `expires_in` and
deliberately never prints the token.

**Phase 04 recommendation:** a SMART access token becomes a `BearerServerAuthentication` wrapped
in a `ServerSession`. No plugin changes. A `ServerSession` that refreshes on `401` would be new,
but is not required by the phase plan.

---

## 7. Jackson configuration

There is **no shared, configured `ObjectMapper` and no Jackson module registration anywhere.**
Every use constructs its own `new ObjectMapper()` at the point of use:

- `TokenFetchResult` — a private static `ObjectMapper JSON` for reading the grant document.
- `service/PackageRegistryService` and `service/IgPackageManager` — parsing IG package index JSON.
- `server/rest/RestOperationOutcome` and `RestOutcomeParser` — parsing `OperationOutcome`.
- `ui/JsonView` — pretty printing.

Serialization of resources does **not** go through Jackson at all: `FhirService.toJson` and
`toXml` delegate to `fhir/ResourceSerializer`, which uses HAPI's `IParser`. So SMART JSON work
splits cleanly in two: **parse** discovery and token documents with a plain
`new ObjectMapper()` (following `TokenFetchResult`), and **serialize** FHIR resources through
`FhirService` / `ResourceSerializer`, never by hand-building JSON.

A shared `SmartJson` helper that wraps one `ObjectMapper` would match the codebase better than
spreading `new ObjectMapper()` further, and is a Phase 02/03 candidate. It must not silently
change how the existing four call sites behave.

---

## 8. Plugin interfaces and extension mechanisms

`server/FhirServerPlugin.java` is the only extension point for *servers*:

```java
String id();  String displayName();  String vendor();
List<ServerOperation> operations(); ...
```

Implemented by `StandardFhirRestPlugin`, `SmileCdrPlugin`, `FirelyPlugin`. Discovery is
`server/PluginLoader.java`:

- `META-INF/services/com.spiraleyes.fhirviewer.server.FhirServerPlugin` lists
  `StandardFhirRestPlugin` and `SmileCdrPlugin`.
- `fhirviewer-plugins.properties` on the class path adds `FirelyPlugin` through
  `fhirviewer.plugins.enabled`, and supports `fhirviewer.plugins.disabled`.
- `-Dfhirviewer.plugins.config=/path` redirects the properties file; `PluginJarScanner` scans a
  plugin directory for third-party jars.
- A plugin that fails to instantiate is logged and skipped, not fatal.

**SMART does not belong here.** A plugin is a *server dialect* (Smile CDR's admin API, Firely's
operations). SMART is a *credential* and a *session*. Adding a `SmartFhirPlugin` would wrongly
suggest that SMART is a server feature, and would make every server look like it needs a SMART
plugin. The extension point for SMART is `ServerAuthentication` — `BearerServerAuthentication`
already is it — plus, if Phase 04 needs one, a `ServerSession` factory. Phase 30's security
review already recorded (finding F2) that plugin config resolves against the working directory,
which is a further reason not to grow this mechanism.

---

## 9. Logging, error reporting, and testing conventions

**Logging.** SLF4J 2.0.19 with the `slf4j-simple` binding, declared once per class as
`private static final Logger log = LoggerFactory.getLogger(X.class);` (or fully qualified
`org.slf4j.Logger` — both conventions appear). It is used in `server/` and `service/`, for
example `PluginLoader` and `ServerDefinitionStore`. **No class in `ui/` logs at all**; the UI
reports through dialogs and the status bar instead. Keep that separation: SMART services log,
SMART dialogs display.

**Error reporting.** `ui/ServerErrors.java` is the single place a failed server call becomes one
line a user can act on. It maps the status list Phase 30 asked for (401, 403, 404, 409, 422, 429,
5xx, timeout, network error, TLS error, `OperationOutcome`) and prefers *what the server said*
over what the status implies. Two rules are load-bearing for Phase 06:

- **No credentials, ever.** `redact(String)` strips `Bearer ...` and `Basic ...` from any text
  before it reaches a dialog, on the reasoning that "a screenshot of this window in a bug report
  containing a live credential" is the failure mode to design against.
- Cause chains are walked (`UnknownHostException`, `SocketTimeoutException`, `SSLException`,
  `ConnectException`) so a wrapper's null message cannot hide the real explanation.

SMART diagnostics must run every message through `ServerErrors.redact` before display — a token
response or an error redirect is exactly where a credential would otherwise leak.

**Testing.** JUnit Jupiter 5.14.4, 68 test classes, 760 tests, none skipped. Conventions:

- Unit tests named after the class under test (`TokenFetchResultTest`,
  `ServerDefinitionPersistenceTest`, `TransportSecurityTest`, `OperationPathTemplateTest`).
- **No Mockito anywhere.** Stubs are hand-written: `ui/StubServer.java` is a
  `com.sun.net.httpserver.HttpServer` on a loopback port, and `ShapePlugin`, `MockVendorPlugin`
  and `LegacyPlugin` stand in for plugins. `RestConsoleIntegrationTest` drives real HTTP against
  that stub.
- JavaFX is tested for real but defensively: `ServerUiSmokeTest` starts the toolkit once and, if
  it is unavailable, records the failure and **skips** rather than fails — "an unavailable toolkit
  must skip the test, not fail it".
- Security-sensitive behaviour is asserted directly: `TransportSecurityTest`,
  `ServerAuthenticationTest`, and `ServerUiSmokeTest`'s `noCredentialIsPrinted` case.

**Consequence for SMART:** a loopback callback can be tested with the same `StubServer` idiom. The
browser launch cannot, so it belongs behind a narrow interface (`BrowserLauncher`) that tests
substitute — the one place where a small interface genuinely earns its keep.

---

## 10. Existing HTTP utilities, background-task patterns, and preferences storage

**HTTP.** `server/rest/JdkHttpRestClient.java` — `java.net.http.HttpClient`, injectable, a small
daemon pool so the JDK's default executor is not drained, `Redirect.NORMAL`. `TokenEndpointClient`
uses the same approach with a form body. **No OkHttp and no Apache HttpClient is used by
application code**, despite both being on the class path through HAPI.

**Background tasks.** `ui/BackgroundTasks.java` is the only concurrency pattern in the UI:

```java
BackgroundTasks.runAttempt("rest-console", () -> BackgroundTasks.attempt(() -> { ... }));
```

It wraps a unit of work, marshals results back to the JavaFX thread, and returns a
`BackgroundTasks.Running` handle so a dialog can track or cancel it; `RestConsoleDialog` holds one
such field. Every SMART network call — discovery, token exchange, FHIR reads — must go through it.
A direct `HttpClient` call on the FX thread would freeze the window.

**Preferences storage.** Two mechanisms, both files under `~/.fhirviewer/`:

| What | Where |
|---|---|
| Server definitions | `~/.fhirviewer/server-definitions.properties` |
| Encrypted plugin settings / credentials | `~/.fhirviewer/plugin-settings.properties` |

There is **no `java.util.prefs.Preferences` use** — preferences are deliberately visible files
rather than registry entries, which suits a cross-platform desktop tool. A SMART store should
follow the same rule: a properties file under `~/.fhirviewer/`, not `Preferences`.


---

## 11. Java module descriptor and packaging requirements

**There is no `module-info.java` anywhere in the repository.** A recursive search returns nothing.
The project compiles against JDK 21 with `<release>21` but runs entirely on the **class path**,
which is exactly why `Launcher` exists (§3).

`maven-jar-plugin` sets `Main-Class: com.spiraleyes.fhirviewer.Launcher`, and the runtime
distribution is the flat set of dependency jars under `target/lib/` plus `target/classes`:

```
java -cp "target/classes;target/lib/*" com.spiraleyes.fhirviewer.Launcher   # Windows
java -cp "target/classes:target/lib/*" com.spiraleyes.fhirviewer.Launcher   # macOS / Linux
```

`mvn javafx:run` is the only module-path route, and it goes through `Main`.

**Implication for SMART:** a new dependency must work on the class path. This is the decisive
argument against several OAuth libraries that assume JPMS or shade aggressively, and it means a
class-path-only, zero-new-dependency implementation (§12) is both the safest and the most
consistent choice. Nothing about adding SMART classes breaks packaging: no `module-info` needs
editing, no `--add-opens` flags, no `jlink` configuration.

---

## 12. Proposed dependency changes

**None.** No version is changed and no library is added.

| Dependency | Decision | Reason |
|---|---|---|
| `oauth2-oidc-sdk` / `appauth` | **Not added** | A SMART standalone launch needs one GET for discovery, one redirect, one loopback GET, one form POST for the token and one resource GET. All are covered by `java.net.http.HttpClient` (`server/rest/`) plus Jackson. The brief's own condition — *"do not add it if existing dependencies already provide the needed functionality"* — is met. A new dependency would also risk class-path packaging (§11) for no capability gain. |
| `nimbus-jose-jwt` 10.0.2 | **Not added, not declared** | Already present transitively via HAPI; optional for reading launch-context JWT claims in Phase 05. Because it is transitive it may move or vanish on a HAPI upgrade, so Phase 05 must not *depend* on it. If claim inspection becomes a requirement, the correct move is a declared dependency with a stated reason, not a silent reliance. |
| `okhttp`, `httpclient` | **Not used** | Present via HAPI; the project has its own `java.net.http` transport and should keep one transport. |
| Jackson | **Not declared** | Already used directly by four call sites (§7). If Phase 02/03 adds a shared mapper, declaring the dependency then is the honest fix; continuing to use it without declaring it is not. |
| Java 21 / JavaFX 21.0.8 / HAPI 8.12.0 | **Unchanged** | No reason to upgrade for SMART. JavaFX 21.x is the newest line a JDK 21 javac targets, so a launch flow that assumed newer `WebView` behaviour would force a version change — avoided by using `Desktop.browse` rather than an embedded `WebView`. |

**Protocol code.** The one cryptographic piece SMART needs is PKCE: 32 random bytes,
base64url-encoded as `code_verifier`, SHA-256 and base64url-encoded again as `code_challenge`.
That is `SecureRandom`, `MessageDigest` and `Base64` from the JDK, following the pattern
`SecretBox` already establishes. It is not a protocol implementation written from scratch in the
---

## 13. Proposed responsibility boundaries

The brief asked for repository-appropriate packages and classes for six areas. Given §8 and §12,
SMART belongs in **one new sub-package** plus touch points that already exist:

```
com.spiraleyes.fhirviewer
└── server/smart/                      NEW — the only new package
    ├── SmartLaunchConfig.java             record: name, iss, clientId, redirectUri, scopes, mode
    ├── SmartLaunchConfigStore.java        ~/.fhirviewer/smart-launches.properties (atomic write)
    ├── SmartConfiguration.java            record for the /.well-known/smart-configuration document
    ├── SmartDiscoveryService.java         GET + validate the discovery document
    ├── SmartDiscoveryException.java       failure carrying a user-facing message
    ├── Pkce.java                          verifier + challenge (JDK only)
    ├── SmartAuthorizationRequest.java     builds the authorize URL
    ├── LoopbackCallbackServer.java        com.sun.net.httpserver on 127.0.0.1, ephemeral port
    ├── BrowserLauncher.java               narrow interface over Desktop.browse
    ├── DesktopBrowserLauncher.java        the production implementation
    ├── TokenExchangeResult.java           TokenFetchResult + scope + patient + encounter
    └── SmartLaunchContext.java            record: patient, encounter, raw claims, expiry

ui/
    ├── SmartLaunchDialog.java             the workbench (Phase 01)
    ├── SmartLaunchContextPane.java        renders patient/encounter (Phase 05)
    └── SmartDiagnosticsPane.java          structured event list (Phase 06)

EXISTING, TOUCHED ONLY
    server/ServerAuthentication            BearerServerAuthentication already presents a SMART token
    server/TokenEndpointClient             extended for authorization_code + PKCE (Phase 03)
    service/FhirService                    unchanged; rendering and request bodies
    ui/BackgroundTasks                     every network call goes through it
    ui/ServerErrors                        redaction for every diagnostic line
```

Three boundaries are deliberate:

1. **No plugin.** SMART is not a server dialect (§8). A `SmartFhirPlugin` would be a category
   error and would imply every server needs one.
2. **`server/smart/` is a sub-package, not a top-level one.** The precedent is `server/rest/`: a
   server-side sub-system earns a package under `server/`. SMART credentials and sessions are
   server concerns, and keeping them there leaves `ui/` JavaFX-only.
3. **The token never reaches a store.** `SmartLaunchConfig` has no secret field (public client);
   `TokenExchangeResult` is held in memory for the session only, like `ServerPassphrase`. A
   refresh token, if Phase 04 keeps one, goes through `SecretBox`/`PluginSettingsStore`, never into
   `smart-launches.properties`.
---

## 14. Reusable components

| Needed for | Reuse | Where |
|---|---|---|
| Server configuration: add, edit, remove, list, active | `FhirServerManager`, `ServerDefinition`, `ServerDefinitionStore` | `server/` |
| Per-server credentials, encrypted at rest | `PluginSettingsStore`, `SecretBox`, `ServerPassphrase` | `server/` |
| Presenting an access token to the transport | `BearerServerAuthentication` + `ServerSession` | `server/` |
| Discovery GET and token POST | `JdkHttpRestClient`, `TokenEndpointClient`, `TokenFetchResult` | `server/rest/`, `server/` |
| Parsing JSON documents | plain `ObjectMapper`, following `TokenFetchResult` | `server/` |
| Rendering a resource and the launch context | `FhirService.toJson` / `serialize`; `pretty/` for Pretty View | `service/`, `pretty/` |
| Dialog + form + state layout | the `RestConsoleDialog` / `ServerOperationDialog` triple | `ui/` |
| Background network work off the FX thread | `BackgroundTasks` | `ui/` |
| Diagnostics wording and credential redaction | `ServerErrors.describe` / `ServerErrors.redact` | `ui/` |
| Loopback test server | `StubServer` (`com.sun.net.httpserver`) | `src/test/.../ui/` |
| Launch context as a FHIR resource | `FhirService.openText`, `bundleEntries`, `isBundle` | `service/` |

Nothing on this list needs to be built twice. The genuinely new code is small: a discovery record
and parser, PKCE, a loopback listener, a browser launcher, and the dialog.

sense the brief warns against — the negotiation, discovery, state validation and token parsing are
plain HTTP and JSON that the existing transport already performs.
---

## 15. Risks and test approach

| # | Risk | Mitigation |
|---|---|---|
| R1 | **Loopback port conflict or a firewall prompt.** The callback listener needs a free port and inbound connections to `127.0.0.1`. | Bind to `127.0.0.1` only, use port `0` for an ephemeral port, and put the real port in the redirect URI — SMART's own approach. `StubServer` already proves a loopback server works in tests. |
| R2 | **The browser never returns, or the user closes it.** | A timeout on the `BackgroundTasks.Running` handle, an explicit *Cancel*, and a status line saying the app is waiting. Never spin forever; never leave the dialog modal and unusable. |
| R3 | **Callback `state` mismatch — CSRF.** | A `SecureRandom` state parameter compared with `MessageDigest.isEqual`, rejected loudly when it differs. |
| R4 | **A token or `code` reaching a log, dialog or bug report.** | Every line through `ServerErrors.redact`; `TokenFetchResult.toString` already omits the token and is the pattern to copy. Add a `noCredentialIsPrinted` case per new diagnostic event. |
| R5 | **A server that is not SMART-capable.** | Discovery returns 404 or a document missing `authorization_endpoint` → a clear message, no half-launch. Phase 01 keeps discovery and authorize visibly unavailable, per its own brief. |
| R6 | **A headless or restricted desktop where `Desktop.browse` is unsupported.** | `BrowserLauncher` returns a result rather than throwing; the dialog reports it and offers the URL as text to copy. Tests substitute the interface. |
| R7 | **An embedded `WebView` instead of the system browser.** | Rejected — it would force a JavaFX upgrade beyond what a JDK 21 javac targets (§11, §12). Use `Desktop.browse`. |
| R8 | **Transitive `nimbus-jose-jwt` disappearing on a HAPI upgrade.** | Phase 05 treats JWT claim inspection as optional and degrades to "no launch context" rather than failing (§12). |
| R9 | **Freezing the UI during discovery or exchange.** | Every call through `BackgroundTasks` (§10). |

**Test approach** — matching existing conventions (§9), no Mockito:

- Pure logic as plain unit tests: `PkceTest` (verifier length, base64url charset, challenge
  stability), `SmartConfigurationTest` (missing endpoint, missing `iss`), `SmartLaunchConfigStoreTest`
  (round trip, atomic write, bad entry skipped).
- Loopback and discovery against `StubServer`: happy path, mismatched `state`,
  `error=access_denied`, timeout, and a 404 non-SMART server.
- Token exchange by extending `TokenEndpointClientTest`'s existing stub approach for
  `authorization_code` + `code_verifier`.
- JavaFX through `ServerUiSmokeTest`'s tolerant toolkit start, so a headless CI machine skips
  rather than fails.
- Redaction asserted explicitly for every new diagnostic event.

---

## 16. Phase 00 conclusion

Nothing needs to change to *host* SMART work. The integration points already exist and are named:
`ServerAuthentication` for how a request is credentialed, `FhirServerService` and the plugins for
transport, `FhirService` for rendering, `BackgroundTasks` for concurrency, `ServerErrors` for
redaction, and a `*Store` under `~/.fhirviewer/` for persistence. The two genuinely new pieces of
infrastructure are a loopback HTTP listener (§14 R1) and browser launching (§14 R6), both JDK.

The single most important finding is what **not** to do: SMART must not become a
`FhirServerPlugin`, must not add an OAuth dependency, must not introduce JPMS, and must not let a
token reach a properties file.

**Phase 00 is complete. Stopping here for review before starting Phase 01.**


---

## Appendix — the original Phase 00 brief

Kept verbatim for traceability. Every checklist item is answered above.

### Objective

Understand the existing FHIRViewer implementation and determine how SMART on FHIR support can be
added without disrupting existing functionality.

### Inspect the repository and document

- Maven module and package structure — §2
- Java, JavaFX, HAPI FHIR, and Jackson versions — §1
- Application entry point and JavaFX navigation — §3
- View/controller/service conventions — §4
- FHIR server configuration and persistence — §5
- HAPI FHIR client construction and authentication mechanisms — §6
- Jackson configuration — §7
- Plugin interfaces and extension mechanisms — §8
- Logging, error reporting, and testing conventions — §9
- Java module descriptor and packaging requirements, if present — §11 (none present)
- Existing HTTP utilities, background-task patterns, and preferences storage — §10
- Any existing OAuth/OIDC code or dependencies — §1 and §6 (none exist)

Identify reusable components for server configuration, connection management, resource rendering,
JSON formatting, diagnostics, and session handling — §14.

### Proposed responsibility boundaries

Recommend packages and classes for SMART UI, discovery, authorization, authorized FHIR access,
launch context, and diagnostics — §13.

### Constraints, and how they were met

- *Do not implement SMART functionality in this phase.* No SMART class was created; `git status`
  shows no production file modified.
- *Do not modify unrelated production code.* Only this document and `IMPLEMENTATION-STATUS.md`
  were written.
- *Do not upgrade dependencies without a specific, documented reason.* No version changed; §12
  records why each existing one stays.
- *Do not assume all FHIR servers support SMART.* §8 and §15 (R5) treat SMART as orthogonal to the
  three plugins, and require a server without `/.well-known/smart-configuration` to fail clearly
  rather than half-launch.
- *Keep SMART client authorization separate from server-specific plugin logic.* §8: the extension
  point is a `ServerAuthentication`, never a plugin.
- *Prefer plain Java services and small data models; no DI framework.* §13 follows the existing
  plain-class, record-based convention.
- *Evaluate Nimbus OAuth 2.0 SDK as an optional dependency; do not add it if existing dependencies
  already provide the needed functionality; do not write cryptographic protocol implementations
  from scratch.* §12: no new dependency; PKCE is a JDK hash-and-base64 of random bytes rather than
  a hand-rolled protocol; `nimbus-jose-jwt` is already present for optional token inspection.

### Acceptance criteria

| Criterion | Met |
|---|---|
| Architecture and integration points are documented | §2 to §14 |
| Current versions and dependencies are known | §1 |
| Proposed dependency changes are justified | §12 — none proposed |
| Risks and test approach are documented | §15 |
| No unrelated production code has changed | verified: compile exit 0, 760 tests 0 failures, no production file modified |
| Stop and report findings before starting Phase 01 | §16, and the report to the user |

