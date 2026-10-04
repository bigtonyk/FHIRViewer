# Phase 11 — REST Console UI

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
| R1 | Get a bearer token | Auth selector + `Bearer` token field, held in memory for the console's lifetime |
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

**DD12 — "Get a bearer token" means paste one, plus an optional token-endpoint fetch.**
The requirement is ambiguous between *accept a token* and *obtain one by running an OAuth
flow*. This plan implements the first (a token field, sent via `BearerServerAuthentication`,
which already exists) and, as a clearly-marked optional step, a "Fetch token…" button
that POSTs client-credentials to a user-supplied token endpoint and puts the result in the
field. **No SMART on FHIR, no authorization-code flow, no browser hand-off, no token
cache** — those need a client registration and a token store, and a half-built version of
one is worse than none. This is the same boundary `BearerServerAuthentication`'s own
Javadoc already draws; see *Open questions* before starting the fetch step.

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

The last point matters for review: the console deliberately bypasses the plugin layer,
because its whole purpose is calling endpoints no plugin declares. That is a real exception
to the architecture's central rule and it should be written down, not left for a reader to
work out.

### Phase E — Optional: fetch a bearer token

**Only after DD12 is confirmed**, and only as:

- **`server/TokenEndpointClient.java`** — POST `grant_type=client_credentials` with a client
  id and secret to a user-entered token endpoint, and read `access_token` out of the JSON
  answer. Reuses `RestClient`; no new transport, no new JSON stack.
- **`ui/TokenFetchDialog.java`** — collects the endpoint, client id and secret; puts the
  returned token into the console's token field.

The client secret is held as a `char[]` for the dialog's lifetime and wiped on close. The
token is **never persisted** by this path (DD3). A non-JSON or error answer is reported
through `RestResponse.diagnostics()` like anything else.

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
   properties file, no Java preferences (DD3).
3. **Password and token fields are `PasswordField`s**, never `TextField`s, so they are not
   rendered in a screenshot or a screen share.
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
  cache or refresh.** Phase E covers client-credentials and nothing more.
- **No persistence of the console's own history**, and no saved "recent requests".
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

## Open questions

1. **Does "a way to get a bearer token" mean accept a pasted token, or run a flow to obtain
   one?** This plan implements paste, plus an optional client-credentials fetch. If SMART on
   FHIR is actually wanted, it needs a client registration and a token store and is a plan
   of its own, not a button.
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
| `TokenEndpointClient.java`, `TokenFetchDialog.java` | `server`, `ui` | no / yes | *(Phase E only)* |

**Added — tests:** `AdhocAuthenticationTest`, `AdhocServerTest` (`server`);
`RestAnswersTest` (`server.rest`); `RestParameterListTest`, `RestConsoleFormTest`,
`RestConsoleIntegrationTest` (`ui`); one addition to `ServerUiSmokeTest`.

**Modified:** `MainWindow.java` — one menu item and one handler, nothing else.

**Documentation:** `docs/user-guide/FHIR-Server-Functions.md`,
`docs/plans/REST-Integration-Progress.md`,
`docs/architecture/Current-Plugin-Architecture.md`.

## Estimated size

Roughly 11 new production classes (5 of them small and JavaFX-free), 6 test classes, and a
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
3. A `404`, a `500` with an `OperationOutcome`, and a transport failure each produce a
   message a user can act on, with no credential in it.
4. No token or password appears in any log line, any `toString()`, or any string reachable
   from a dialog.
5. `docs/user-guide/FHIR-Server-Functions.md` describes the screen, and the *Known gaps*
   section says what it does not do.