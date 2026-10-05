# Phase 11 — REST Console UI

> **V1 is built.** Phases A–E and the V1 Postman-parity items (P1 cURL, P5 headers, P6
> Bundle entries, P8 bytes-and-timing) are implemented on branch `feature/rest-ui`; 726 tests
> pass. The **V2** items — request tabs, saved collections, `{{variables}}`,
> CapabilityStatement-driven suggestions — are listed under *Postman parity* below and are
> **not** built. OpenAPI import and Postman-style test scripts are not planned.
>
> What is implemented differs from the plan in two small ways, both forced by the code as it
> stands: the server picker uses a small `ServerChoice` record rather than a cell converter
> (`ComboBoxCellConverter` does not exist in JavaFX 25), and `RestAnswers` fills in
> `OperationOutcome` issues from the resource it has already parsed when a caller-built
> response carries none.

## Goal

A screen where a user can type in **any** REST request — base URL, path, method,
parameters, body and credentials — and see exactly what the server sent back. When the
answer turns out to be a FHIR resource, it can be opened in the main viewer.

This closes a gap the existing plans name twice and leave open:

- `10_STANDARD_OPERATIONS_AND_INPUT_UI.md`, *What this does not do*: "**No raw REST
  console.** It would need its own authentication, error mapping and paging, and is a
  different decision from a curated operation list."
- `REST-Integration-Progress.md`, *Deliberately not built*: "a raw REST console,
  transaction bundles, conditional create, …"

Almost all of the machinery that sentence says the console "would need" was built for the
plugin layer and is sitting there unused by the UI. This plan is mostly about exposing it.

## Requirements

| # | Requirement | Where it lands |
|---|-------------|----------------|
| R1 | Get a bearer token | Auth selector + `Bearer` token field, plus a **Fetch token…** button that runs an OAuth client-credentials grant against a token endpoint (Phase E). Held in memory for the console's lifetime. |
| R2 | Enter user name and password | Auth selector + user/password fields → `BasicServerAuthentication` |
| R3 | Enter a REST URL base and the rest | Base URL field + path field, joined by `RestUrls` |
| R4 | Enter parameters | Editable, ordered, repeatable query-parameter grid |
| R5 | View the raw returned data | Status line, headers table, body text area — always the raw body, never only a parsed view |
| R6 | Open a FHIR resource in the main viewer | *Open in FHIR Viewer* button, enabled only when the body parses as a resource |

## What already exists, and is reused rather than rebuilt

| Concern | Existing type | Used for |
|---|---|---|
| Transport | `server.rest.RestClient` / `JdkHttpRestClient` | The one HTTP path in the project. No second client. |
| Request model | `server.rest.RestRequest` (+ `RestMethod`) | Method, path, ordered query parameters, headers, body |
| Response model | `server.rest.RestResponse` | Status, headers, body, parsed `OperationOutcome` issues, `diagnostics()` |
| URL joining | `server.rest.RestUrls` | Base + path, absolute-path passthrough, trailing-slash handling |
| Header redaction | `server.rest.RestHeaders` | Response headers render with `Authorization`/cookie values redacted |
| Authentication | `server.ServerAuthentication` + `AnonymousServerAuthentication`, `BasicServerAuthentication`, `BearerServerAuthentication` | All three auth kinds already exist |
| Auth kind enum | `server.ServerAuthKind` | The three choices the console offers, with `needsUserName()` |
| Session | `server.ServerSession` | Server + auth for one operation; secrets never touch a config object |
| Plain-HTTP warning | `server.TransportSecurity` | `warningFor(url)` drives the unencrypted-credentials warning |
| FHIR parsing | `fhir.ResourceParser`, `fhir.ResourceFormat` | Turns the raw body into `IBaseResource` for the viewer |
| Background work | `ui.BackgroundTasks` | Daemon thread + `Attempt<T>` + cooperative `Running` cancellation |
| Error wording | `ui.ServerErrors` | `describe(Throwable)` and `redact(String)` for every message shown |
| Display path | `MainWindow.display(LoadedResource, ServerOrigin)` | The single rendering path; the console returns a resource rather than opening anything |

## What is genuinely missing

1. **No UI for an ad-hoc URL.** `JdkHttpRestClient.forSession(ServerSession)` needs a
   `FhirServerConfiguration`, which today only comes from `ServerDefinition` saved through
   the server manager. A one-off URL typed by a user has nowhere to live.
2. **No free-form parameter editor.** `ServerOperationForm` renders the parameters a
   *plugin declared*. Nothing can add, remove or repeat a parameter the user invents.
3. **No classifier for a `RestResponse`.** `ServerOperationResults` classifies a
   `ServerOperationResult` (plugin-declared kind, parsed resource, issues). A raw
   `RestResponse` has no equivalent, and R6 needs one.
4. **No menu entry**, and no route from a returned resource into the main window other
   than the operation dialog's fixed path.

Everything else — auth, transport, error mapping, TLS, timeouts, redaction, background
threading — already exists and must not be rebuilt.

## Decisions

**DD1 — The console is an *extra* entry point, not a replacement.**
`Tools → Run Server Operation...` stays, and stays curated. The console is for the case
the curated list cannot serve: a vendor endpoint nobody declared, an operation with a
parameter nobody modelled, a debugging round trip. Nothing in the existing screens is
changed or routed through the console, so no existing test changes meaning.

**DD2 — Reuse `RestClient`; do not add an HTTP client.**
`JdkHttpRestClient` is already the project's one transport, already handles every verb,
timeouts, TLS defaults, redirects and cancellation. The console is a *caller* of it.

**DD3 — Credentials are typed per request and never persisted.**
The console holds a `ServerAuthentication` in a field for as long as the window is open
and nothing else. It does **not** write to `PluginSettingsStore` and does not read from
it. Persistence belongs to the server manager, which already asks for a passphrase and
stores encrypted; a second, weaker persistence path in a debugging tool would be a
regression in the security model for no user benefit. The one exception is a "use the
selected server's saved credentials" convenience, which *reads* through the existing
`ServerCredentials` and never writes.

**DD4 — Reuse `ServerAuthKind` for the selector.**
Three choices already exist with labels and a `needsUserName()` rule. A console-local
enum would be a second list to keep in step with `BasicServerAuthentication` and
`BearerServerAuthentication`.

**DD5 — A separate small factory for console authentication, not `instanceof` in the UI.**
`server/AdhocAuthentication.of(kind, userName, password, token)` returns the right
`ServerAuthentication` or throws with a sentence a form can show. Keeps the `instanceof`
chain out of JavaFX, where `docs/architecture/Current-Plugin-Architecture.md` already
criticises it existing in `newClient()`.

**DD6 — The base URL is a real field, and joining stays in `RestUrls`.**
The user types a base and a path separately (R3), and `RestUrls.join` decides how they
combine. An absolute path typed into the path field passes through unchanged, which is
already supported and is what makes one-off debugging URLs work.

**DD7 — Parameters are an ordered, repeatable list, not a map.**
`_include` may legitimately repeat and order matters on some servers. `RestRequest`
already preserves insertion order through an unmodifiable `LinkedHashMap` view — the
console must not undo that by passing a `HashMap`. The editable grid therefore edits a
list, and the list is what builds the query.

**DD8 — Raw body is always shown, even when the body parses as a resource.**
R5 and R6 are different panes, not alternatives. A user debugging a REST call needs the
bytes the server sent; a user viewing a resource needs the tree. Showing the parsed
resource *instead of* the raw body would make the console worse at its own job.

**DD9 — Classification lives in `server/rest`, not in the dialog.**
`RestAnswers.classify(RestResponse)` returns a `RestAnswer` carrying the kind, the raw
body, the issues and — when it parses — the `IBaseResource`. The dialog picks a pane from
the kind; it never decides. This mirrors the split the codebase already made for
`ServerOperationForm`/`ServerOperationResults`.

**DD10 — The dialog returns a resource; `MainWindow` displays it.**
Exactly the arrangement `ServerOperationDialog` uses. The console never touches the main
window's nodes, so there remains exactly one rendering path and the unsaved-changes guard
is honoured in one place.

**DD11 — Writes are offered, but never silently.**
`POST`, `PUT`, `PATCH` and `DELETE` are selectable, because R4–R6 would be hollow without
them and a REST console that cannot issue a write is a viewer. The verb is always visible
in a status line before the request goes out, and a write body is only enabled for a verb
that can carry one (`RestMethod.allowsRequestBody()`), so the UI cannot build a request
the transport would reject.

**DD12 — "Get a bearer token" means paste one, *and* fetch one from a token endpoint.**
*Confirmed with the user: both.* The console offers a token field for a pasted token, sent
via `BearerServerAuthentication`, **and** a **Fetch token…** button that runs an OAuth 2.0
`client_credentials` grant against a token endpoint the user types, putting the returned
`access_token` into that field. Phase E is therefore in scope, not optional.

The boundary is still the same one `BearerServerAuthentication`'s own Javadoc draws, and it
is deliberately narrow: **no SMART on FHIR, no authorization-code flow, no browser
hand-off, no token cache and no refresh.** Those need a client registration and a durable
token store — a different piece of work with its own persistence and expiry questions, and
a half-built version of one is worse than none.

`client_credentials` is the right grant here precisely because it needs no user interaction:
there is no login, no browser and nothing to store between runs, which is what makes it fit
inside a debugging window that is already holding a client secret in memory.

### DD13 — The clamp uses the requested size, never the window's current size

After two attempts at "make it wider" that changed nothing on screen, the cause was found
in `fitToScreen()`, not in the numbers at the call site.

The dialog sizes itself in `setOnShowing`. At that point the stage has only its *natural*
size — `getDialogPane()`'s preferred size as JavaFX computes it — and **not** the 1400×820
the constructor asked for. The clamp was therefore reading `window.getWidth()`, which was
around 900–1000, and clamping *that* down to a comfortable value. Raising the requested
width could never take effect, because the requested width was never read back.

`fitToScreen()` now takes `requestedWidth` / `requestedHeight`, which the constructor stores
when it applies the remembered size. So the requested size flows straight through to the
window, and the clamp only ever reduces it when the screen genuinely cannot hold it.

Two consequences worth stating plainly:

- **The screen still wins.** On a display whose logical width is below the request — 150%
  scaling on a 1920 screen gives 1280 — the window manager would clip a 1400 window, which is
  what the screenshot showed. Requesting the screen's width minus a margin is the correct
  answer, and asking for *more* is worse, not better.
- **The divider is applied after the window has a size.** Set during construction it is
  applied to a zero-width `SplitPane`, does not survive the first real layout, and left the
  request side a 157px sliver while the response side took everything. It is now set inside
  `fitToScreen()`. The request side additionally has a 380px floor, because a divider
  position is only a proportion and without a floor the fields behind it become unusable.

The four result tabs were also shortened — `Body` / `Entries` / `Headers` / `Errors`. A
`TabPane` does not shrink its headers to fit; it inserts scroll arrows over the overflow,
which is why `Diagnostics` was rendering as `Diagnosti…` behind a chevron.

## Implementation phases

### Phase A — Non-UI foundations (no JavaFX)

Small classes, each with one job. All live in `server` / `server.rest` / `ui` but **none
touch JavaFX**, so each is unit-testable without a display.

**A1. `server/AdhocAuthentication.java`** — builds a `ServerAuthentication` from what the
user typed.

```java
public static ServerAuthentication of(ServerAuthKind kind, String userName,
                                      String password, String token)
```

- `ANONYMOUS` → `AnonymousServerAuthentication.INSTANCE`.
- `BASIC` → `BasicServerAuthentication`; a blank user name is refused with a message naming
  the field.
- `BEARER` → `BearerServerAuthentication`; a blank token is refused.
- Never logs, and never includes the secret in `toString()`.

**A2. `server/AdhocServer.java`** — an ad-hoc base URL as a `FhirServerConfiguration`.

```java
public final class AdhocServer implements FhirServerConfiguration {
    public static AdhocServer at(String baseUrl);
}
```

This is the piece that lets `JdkHttpRestClient.forSession(ServerSession)` be used for a URL
that was never saved. It delegates `baseUrl()`, returns `null` for
`administrationBaseUrl()`, `R4` for `fhirVersion()`, an empty header map, and a
`toString()` of name + URL with no credential. Its `credentialKey()` returns a synthetic
key, so an ad-hoc URL can never collide with a saved server's credentials.

**A3. `server/rest/RestAnswers.java`** — classifies a `RestResponse` (DD9).

```java
public enum Kind { FHIR_RESOURCE, BUNDLE, OPERATION_OUTCOME, JSON, XML, TEXT, EMPTY }
public record RestAnswer(Kind kind, int statusCode, RestHeaders headers, String body,
                         IBaseResource resource, List<RestOperationOutcome> issues) { … }
public static RestAnswer classify(RestResponse response);
public static Optional<IBaseResource> resourceIn(String body, String contentType);
public static String summary(RestAnswer answer);
public static List<String> diagnostics(RestAnswer answer);
```

The order of the questions mirrors `ServerOperationResults`: a parsed resource wins, then
an `OperationOutcome`, then an empty body, then content type, then the body's first
character. **It never throws** — an unparseable body yields `TEXT`/`JSON` with no
resource, which is what stops a malformed 500 from taking the screen down.

**A4. `ui/RestConsoleForm.java`** — the request side as plain data, exactly the split
`ServerOperationForm` already establishes.

```java
public final class RestConsoleForm {
    public void baseUrl(String);   public String baseUrl();
    public void method(RestMethod); public RestMethod method();
    public void path(String);       public String path();
    public void parameters(RestParameterList); public RestParameterList parameters();
    public void body(String);       public void contentType(String); public void accept(String);
    public void authentication(ServerAuthKind, String user, String password, String token);
    public Optional<String> problem();   // the first thing wrong, in user wording
    public RestRequest toRequest();      // throws when problem() is present
    public String resolvedUrl();         // base + path + query, for display
}
```

`problem()` reports the first of: no base URL; base URL not absolute; no path; a body on a
verb that cannot carry one; a missing user name or token for the chosen auth kind. This is
the same "validate before the request" rule `PluginOperationClient` applies, kept in the
form as well, because only the form can say *which field* is wrong.

**A5. `ui/RestParameterList.java`** — ordered, repeatable parameters (DD7).

```java
public record RestParameter(String name, String value) { }
public final class RestParameterList {
    public RestParameterList add(String name, String value);  // a flag has an empty value
    public RestParameterList remove(int index);
    public RestParameterList replace(int index, RestParameter);
    public List<RestParameter> entries();            // insertion order
    public Map<String, List<String>> toQueryMap();   // ordered, repeatable
    public static RestParameterList parse(String queryString);  // "?a=1&_include=x"
    public String toQueryString();
}
```

`parse` strips a leading `?`, percent-decodes, treats a bare name as a flag with an empty
value, and keeps repeated names as separate entries. It is what makes "paste the query
string out of a browser address bar" work.

**A6. `server/rest/RestConsoleResponseReader.java`** — *not built.* A `RestClient` decorator
that classifies as it returns is a plausible simplification, but it is only worth adding if
Phase B turns out to classify in more than one place. Listed here so it is a recorded
decision rather than an omission.

### Phase B — The dialog

**`ui/RestConsoleDialog.java`**, a `Dialog<RestConsoleDialog.Outcome>` following
`ServerOperationDialog`'s structure: a dynamic content pane, `BackgroundTasks` for the
call, a cancel handle, and an `Outcome(IBaseResource resource, String label)` returned to
the main window.

To keep the file small, the layout is split into two further classes rather than one long
`buildContentPane()`:

- **`ui/RestRequestPane.java`** — everything the user fills in (rows 1–6 below), exposing
  the current `RestConsoleForm` and a `send()` trigger.
- **`ui/RestResponsePane.java`** — the result tabs and the *Open in FHIR Viewer* button,
  with `show(RestAnswer answer, long elapsedMillis)` and `clear()`.

`RestConsoleDialog` owns the two panes, the send/cancel buttons, the background task, and
the `Outcome`. Each class has one reason to change.

Layout of `RestRequestPane`, top to bottom:

1. **Server row** — a `ComboBox` of configured servers, pre-selected with the active one,
   plus a *Custom…* entry that reveals the free base-URL field. A configured server
   resolves its saved credentials through `ServerCredentials`; *Custom…* starts anonymous.
2. **Method** — a `ComboBox<RestMethod>`, defaulting to `GET`.
3. **URL row** — *Base* and *Path* `TextField`s, with the resolved URL shown beneath as it
   is typed. This satisfies R3 literally, and the preview is what makes a mistyped path
   obvious before the request is sent.
4. **Auth row** — a `ComboBox<ServerAuthKind>`; the user-name field is shown and required
   only when `kind.needsUserName()`, the token field only for `BEARER`, and the password is
   always a `PasswordField`. A warning label appears when
   `TransportSecurity.warningFor(baseUrl)` is non-null **and** a credential is selected —
   the same rule the server manager applies at save time.
5. **Parameters grid** — a `TableView<RestParameter>` with add / remove / clear, backed by
   `RestParameterList`, plus a one-line query preview. Editable in place: typing a cell
   rewrites the list entry rather than replacing the list, so a half-typed row is not lost.
6. **Body** — a `TextArea` with content-type and accept fields, enabled only when
   `method.allowsRequestBody()` (DD11).

Layout of `RestResponsePane`:

7. **Result tabs** — *Body* (a read-only `TextArea` with *Copy* and *Save to file…*, which
   uses the existing `util.FileSupport` for the write), *Headers* (a read-only table whose
   values come from `RestHeaders.toString()`, so credentials are redacted), and
   *Diagnostics* (from `RestAnswers.diagnostics`). A summary line reads e.g.
   `json (HTTP 200) — 1,204 characters — 87 ms`.
8. **Open in FHIR Viewer** — enabled only when `RestAnswer.resource() != null`.

No *View* / *Pretty* tab is added: the raw body is always one click away, and a formatted
FHIR rendering is exactly what the main viewer is for.

A response pane is cleared when a new request starts, so a stale answer is never read as
the current one — the rule `ServerOperationDialog.clearResult()` already follows.

### Phase C — Wiring into `MainWindow`

Two changes, both small:

1. A `MenuItem("REST Console…")` in `buildToolsMenu()`, placed after *Run Server
   Operation…* behind a `SeparatorMenuItem`.
2. A `private void openRestConsole()`, modelled on `runServerOperation()`: build the dialog
   with `serverManager` and the active server preselected, `initOwner(stage)`,
   `showAndWait()`, and on a non-null `Outcome` call `confirmUnsavedChanges(...)` and then
   `display(new LoadedResource(outcome.resource(), ResourceFormat.JSON, outcome.label(),
   null))`.

The resource is displayed with a **null `ServerOrigin`**, exactly as the operation dialog
does. That is deliberate: a REST-console answer is something to look at, and attaching it to
a server origin would let a later *Save to FHIR Server* write back to a server the user did
not choose.

That is the whole of the integration — one menu item and one handler. The dialog never
reaches into `MainWindow`, and `MainWindow` never reaches into the dialog.

### Phase D — Documentation

- **`docs/user-guide/FHIR-Server-Functions.md`** — a *REST Console* section walking through
  the six requirements, and a line in **Known gaps** recording what is still missing: OAuth
  and SMART on FHIR, persisted console credentials, and paging or truncation for very large
  response bodies.
- **`docs/plans/REST-Integration-Progress.md`** — remove "a raw REST console" from
  *Deliberately not built* and add a row to the progress table.
- **`docs/architecture/Current-Plugin-Architecture.md`** — add the console to the layering
  diagram (UI → `RestClient`, *below* the plugin layer rather than through it) and add a
  row to the gap table.

The last point matters, but not in the way this plan first assumed. **Confirmed with the user:
the console bypasses the plugin layer by design** — that is what makes it a Postman for FHIR
rather than a second operation screen. It is a *peer* of the plugin path, not a subordinate
of it: UI → `RestClient` sits alongside UI → `FhirServerService` → plugin, and the plugin
layer keeps its rule for everything it does serve. The architecture document should say that
plainly, because "the console is allowed to do this and the rest of the UI is not" is only
a maintainable position if it is written down.

### Phase E — Fetch a bearer token (in scope, per DD12)

Sits after Phase D so the console it plugs into already exists.

**`server/TokenFetchResult.java`** — the outcome, as data.

```java
public record TokenFetchResult(String accessToken, String tokenType, Long expiresInSeconds) { }
public static Optional<TokenFetchResult> from(String json);   // empty when the body is not one
```

Parsing the answer is separated from issuing the request for the same reason
`RestOutcomeParser` is separate from `JdkHttpRestClient`: "the server said no" and "there
was no server" are different failures and need different handling. `from` never throws — an
HTML error page from a misconfigured endpoint yields `Optional.empty()`, not an exception.

An OAuth error answer (`{"error":"invalid_client", …}`) is a `RestResponse`, not a
transport failure, so it arrives through the normal path and is reported with
`RestResponse.diagnostics()`. That is the case where the user mistyped the client secret,
and the server's own words are the useful part.

**`server/TokenEndpointClient.java`** — the grant.

```java
public TokenFetchResult fetch(String tokenEndpoint, String clientId, char[] clientSecret,
                              String scope) throws ServerOperationException;
```

- `POST`, `application/x-www-form-urlencoded`, body `grant_type=client_credentials`,
  `client_id`, and `client_secret` — a `RestRequest` through the existing `RestClient`, so
  there is no new transport and no new JSON stack.
- `Accept: application/json`.
- The secret is a `char[]` so the caller can wipe it; it is never turned into a `String`
  beyond the single form-encoding the transport requires, and `toString()` never prints it.
- **Reuses `TransportSecurity.warningFor(tokenEndpoint)`** — a client secret sent over plain
  `http://` to a non-loopback host is the same risk as a password, and the warning already
  exists and is already worded. Skipping it here would be an inconsistency a reviewer would
  rightly flag.

**`ui/TokenFetchDialog.java`** — collects the token endpoint, client id, secret (a
`PasswordField`) and an optional scope; shows a busy state; on success puts the token in the
console's token field and switches the auth selector to `BEARER`; on failure shows the
server's message through `ServerErrors`.

**Wiring** — a **Fetch token…** button beside the token field in `RestRequestPane`, enabled
only when the auth kind is `BEARER` and the field is empty. It is beside the field rather
than inside the auth row because obtaining a token is a separate act from choosing how a
request authenticates, and hiding it inside a dropdown would make a first-class capability
look like a sub-option.

**Expiry.** When `expires_in` is present the dialog says so — "expires in 3600 seconds" —
because a token that expires mid-debugging produces a 401 that looks like a server fault.
The token is **never persisted** and there is no refresh (DD12): re-fetch when it expires.

## Tests

No TestFX in this project — the UI is tested through logic that needs no display, plus one
smoke test that starts the real toolkit and skips itself when there is none. The plan
follows that split.

**No display needed:**

| Test | What it pins |
|---|---|
| `AdhocAuthenticationTest` | Each kind builds the right authentication; a blank user name or token is refused; no secret appears in `toString()` |
| `AdhocServerTest` | `baseUrl()` is returned, `administrationBaseUrl()` is `null`, `credentialKey()` cannot collide with a saved server |
| `RestAnswersTest` | Patient → `FHIR_RESOURCE` with a resource; Bundle → `BUNDLE`; `OperationOutcome` → `OPERATION_OUTCOME`; 204 → `EMPTY`; malformed JSON never throws and yields no resource; `summary()` names the status on a failure |
| `RestParameterListTest` | Order is preserved; a repeated name keeps both values; `parse` round-trips `toQueryString`; a bare flag parses to an empty value; percent-decoding works |
| `RestConsoleFormTest` | `problem()` names the offending field; a body on `GET` is refused; `toRequest()` produces the expected `RestRequest` with ordered parameters; `resolvedUrl()` matches `RestUrls.join` |
| `RestConsoleIntegrationTest` | End to end against `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0`, the pattern `StandardFhirRestPluginTest` uses: a `RestConsoleForm` → `RestClient` → `RestAnswers.classify` round trip for a JSON resource, an XML resource, a plain-text refusal and a 404 |
| `TokenFetchResultTest` | A well-formed grant yields the token and `expires_in`; `{"error":"invalid_client"}` yields empty rather than a bogus token; an HTML body yields empty; a missing `access_token` yields empty; `toString()` never prints the token |
| `TokenEndpointClientTest` | Against a stub `HttpServer`: the request really is `POST`, form-encoded, carrying `grant_type=client_credentials`; the client secret reaches the stub and the client id does; a 401 surfaces as a `ServerOperationException` carrying the server's message; the `char[]` is not retained after `fetch` returns |

The two token tests are the ones that matter most for Phase E. A token client that silently
accepts an error body as if it were a token would present the user with a blank
`Authorization` header and a 401 that looks like a permissions problem rather than a
credential problem.

That last one is the test that proves the whole feature rather than its parts. The offline
suite stays offline; anything against `hapi.fhir.org` is marked network-dependent, as
`StandardFhirRestPluginTest` already does.

**Needs a display:** extend `ServerUiSmokeTest` to build `RestConsoleDialog` on a real
toolkit and assert construction succeeds and that a button press reaches the stub server.
It cannot see layout, and that limitation is already stated in that test's own Javadoc.

## Security rules

These are constraints on the implementation, not advice.

1. **Never log or print a token, a password, or an `Authorization` header.** Every message
   shown to the user goes through `ServerErrors.redact(String)`, which is already applied
   to everything that class returns; the console adds no path that bypasses it.
2. **Never persist console credentials.** No write path to `PluginSettingsStore`, no
   properties file, no Java preferences (DD3). This covers the **client secret** of the
   Phase E token fetch as well as a password and a token.
3. **Password, token and client-secret fields are `PasswordField`s**, never `TextField`s, so
   they are not rendered in a screenshot or a screen share.
4. **Plain HTTP with a credential is warned about**, not refused, using
   `TransportSecurity.warningFor(baseUrl)` — the existing rule and the existing wording.
   Refusing outright would break the local-development case the rule was written to allow.
5. **No TLS bypass.** Nothing in the console installs an `SSLContext`, so platform
   certificate validation stays exactly as `JdkHttpRestClient` already leaves it.
6. **`RestRequest.toString()` is not extended.** It prints the shape only; the console must
   not add body, parameter-value or header-value output to it.
7. **The response body is not logged.** `RestResponse.toString()` reports a character count,
   not content, and the console keeps it that way — a response body is patient data.

## What this deliberately does not do

- **No OAuth 2.0 authorization-code flow, no SMART on FHIR, no browser hand-off, no token
  cache and no refresh.** Phase E does `client_credentials` and nothing more (DD12).
- **No persisted client secret and no persisted token.** Both live only as long as the
  window that holds them (DD3).
- **No saved requests and no request history.** Ruled out for the first release because it is
  a new persisted format rather than a missing control; see **Postman parity** below, where
  it is the largest outstanding item (P2/P3/P4).
- **No response paging or streaming.** A body is held in memory as text, which is what
  `RestResponse` already does; a multi-hundred-megabyte bundle export is out of scope and is
  stated as a gap in the user guide.
- **No arbitrary request headers beyond content negotiation.** `Authorization` comes from
  the auth mechanism, not from a header field the user types. This keeps a free-text
  `Authorization` box — the classic way credentials end up in a screenshot — out of the
  screen entirely.
- **No multipart file upload**, and no request signing.
- **No replacement for `Tools → Run Server Operation…`**, which stays the curated path
  (DD1).

## Postman parity — what a Postman user will expect that this plan does not have

Confirmed with the user: the console is meant to be **"Postman, but specific to FHIR"**.
That is a larger product than the single-shot debugging dialog described above, and the
framing invalidates or under-specifies several decisions here. Listing it explicitly is
cheaper than discovering it as a bug report.

**Still correct under the new framing:** bypassing the plugin layer by design (DD1), raw body
always shown alongside any parsed view (DD8), arbitrary verbs and bodies (DD11).

**Gaps, most valuable first:**

| # | Feature | Why it matters for FHIR | Cost |
|---|---|---|---|
| P1 | **Copy as cURL / paste a cURL command** | The most-used Postman feature for a developer. The killer case here: a bug report, a Confluence page or a vendor doc contains `curl -H 'Authorization: Bearer …' '…/fhir/Patient?name=Smith'`, and the user wants to run it. Needs a shell-quoting **parser** *and* a generator — parser and generator must agree, or copy/paste round-trips drift. | M |
| P2 | **Saved collections and request history** | Postman's organising idea. Without it every call is typed from scratch. Needs a new persisted format (a JSON file via `FileSupport.writeText`, **not** `PluginSettingsStore`). | L |
| P3 | **Environment variables, `{{baseUrl}}`** | Switching a whole saved set of requests between test and production is the second-most-used feature. FHIR-specific wins: `{{fhirVersion}}`, `{{patientId}}`. Must refuse to persist a secret in a variable (DD3). | M |
| P4 | **Multiple request tabs** | A `Dialog` holds exactly one request; Postman holds many. **This is a structural change to Phase B**, from `Dialog` to a resizable `Stage` with a `TabPane` of requests — not an addition to it. | M |
| P5 | **Arbitrary request headers, except `Authorization`** | A Postman user expects a Headers tab. The rule against a free-text `Authorization` box stands (it is the classic way credentials reach a screenshot), but `If-Match`, `Prefer`, `_format`, `X-Request-ID` and vendor headers are legitimate and currently impossible. | S |
| P6 | **Open a Bundle entry, not just the Bundle** | FHIR-specific. A search returns a Bundle; *Open in FHIR Viewer* today shows the whole Bundle. Clicking entry `[3]` to open that resource is what a FHIR user actually wants, and `MainWindow` already has `displayBundleEntry` for it. | S |
| P7 | **CapabilityStatement-driven suggestions** | The "specific to FHIR" differentiator. Offer the resource types, search parameters and operations the *connected server* advertises, so `Patient?` suggests `name`, `birthdate`, `_include`, `$everything`. `ServerCapabilityReader` and `ServerCapabilitiesCache` already do the reading — only the UI wiring is new. | M |
| P8 | **Response size in bytes, and timing, properly** | The plan shows characters and milliseconds. Postman shows bytes and milliseconds. Trivial to fix, but "1,204 characters" is not the number an HTTP-minded user expects. | S |
| P9 | **Import an OpenAPI/Swagger document** | Would generate a starter collection of FHIR calls. Attractive, large, and goes stale as vendors change their specs. | L |
| P10 | **Postman-style test scripts** | A JavaScript sandbox inside a Java application. | L |

**Suggested split — and what actually happened:** the user chose **V1**, so v1 = P1, P5, P6,
P8 (all small, each removes a concrete frustration) **and that is what was built**; Phases A–E
went with it. **Not built:** P2, P3, P4 (request tabs together with saved collections, and
`{{variables}}`) and P7 (CapabilityStatement-driven suggestions). **Not planned:** P9 and P10.
All of these, built and unbuilt, are stated in the user guide's *Known gaps* so their absence
is a decision rather than an oversight.

## Open questions

Resolved before implementation began:

- ~~*Does "a way to get a bearer token" mean accept a pasted token, or run a flow to obtain
  one?*~~ **Answered: both.** A pasted token field *and* a **Fetch token…** client-credentials
  grant. Recorded as DD12; Phase E promoted from optional to in scope.

Still open, none of which blocks Phases A–D:

1. **Which Postman-parity items are in the first release?** The list above is ordered by
   value, and the suggested v1 (P1, P5, P6, P8) is all small. If collections and tabs are
   wanted up front, **P4 changes Phase B from a `Dialog` to a `Stage`**, and A–D should be
   re-planned around that rather than built first and refactored after.
2. **Should the console be able to send to a configured server using its *saved*
   credentials, or always start anonymous?** This plan adds the *Custom…* entry and, for a
   configured server, resolves saved credentials through `ServerCredentials`. If a user
   would rather the console never touch the credential store, remove that branch and leave
   *Custom…* as the only path.
3. **Should write verbs ship at all in the first version?** DD11 says yes. If the first
   release should be read-only, `RestConsoleForm` simply offers `GET` alone, and nothing else
   in the design changes.

## Files added and changed

**Added — production:**

| File | Package | JavaFX |
|---|---|---|
| `AdhocAuthentication.java` | `server` | no |
| `AdhocServer.java` | `server` | no |
| `RestAnswers.java` (+ `RestAnswer`) | `server.rest` | no |
| `RestParameterList.java` (+ `RestParameter`) | `ui` | no |
| `RestConsoleForm.java` | `ui` | no |
| `RestConsoleDialog.java` | `ui` | yes |
| `RestRequestPane.java` | `ui` | yes |
| `RestResponsePane.java` | `ui` | yes |
| `TokenFetchResult.java`, `TokenEndpointClient.java` | `server` | no |
| `TokenFetchDialog.java` | `ui` | yes |

**Added — tests:** `AdhocAuthenticationTest`, `AdhocServerTest` (`server`);
`RestAnswersTest` (`server.rest`); `RestParameterListTest`, `RestConsoleFormTest`,
`RestConsoleIntegrationTest` (`ui`); `TokenFetchResultTest`, `TokenEndpointClientTest`
(`server`); one addition to `ServerUiSmokeTest`.

**Modified:** `MainWindow.java` — one menu item and one handler, nothing else.

**Documentation:** `docs/user-guide/FHIR-Server-Functions.md`,
`docs/plans/REST-Integration-Progress.md`,
`docs/architecture/Current-Plugin-Architecture.md`.

## Estimated size

Ten new production classes (five of them small and JavaFX-free), eight test classes, and a
two-hunk change to `MainWindow`. The bulk of the effort is Phase B's layout and the tests,
not the logic — the transport, authentication, error wording, redaction and background
threading all already exist and are only being called.

## Completion criteria

The feature is done when all six requirements are demonstrable in one screen, the offline
test suite passes, and:

1. A `GET` to an arbitrary URL with each of the three auth kinds sends the right
   `Authorization` header and nothing else.
2. A returned `Patient`, in JSON or XML, opens in the main viewer with the raw body still
   available in the console.
3. **Fetch token…** obtains a real token from a stub token endpoint and puts it in the token
   field, and a wrong client secret reports the server's own OAuth error rather than
   producing a blank `Authorization` header.
4. A `404`, a `500` with an `OperationOutcome`, and a transport failure each produce a
   message a user can act on, with no credential in it.
5. No token, password or client secret appears in any log line, any `toString()`, or any
   string reachable from a dialog — including after a failed token fetch.
6. `docs/user-guide/FHIR-Server-Functions.md` describes the screen, and the *Known gaps*
   section says what it does not do, including that SMART on FHIR is not supported.