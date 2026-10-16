# SMART on FHIR implementation status

Use this file as the source of truth for progress. Update it at the end of each phase with actual results only.

## Status legend

- Not started
- In progress
- Blocked
- Implemented — automated tests pass
- Verified — manual checks completed

## Phase status

| Phase | Status | Build/tests | Notes |
|---|---|---|---|
| 00 — Repository assessment | Implemented — automated tests pass | compile exit 0; 760 tests, 0 failures | Findings in `PHASE-00-REPOSITORY-ASSESSMENT.md`. No production code changed. Awaiting review before Phase 01. |
| 01 — Workbench UI | Implemented — automated tests pass | compile exit 0; 776 tests, 0 failures | Tools > SMART Launch Workbench. CRUD persists to `~/.fhirviewer/smart-launches.properties`; Validate is local-only and says so; Discover/Authorize stay disabled until Phases 02/03. |
| 02 — SMART discovery | Implemented — automated tests pass | compile exit 0; 798 tests, 0 failures | Discover is live in the workbench against `/.well-known/smart-configuration`; endpoints validated, redirects never followed, Authorize still disabled for Phase 03. |
| 03 — OAuth / PKCE | Implemented — automated tests pass | compile exit 0; 836 tests, 0 failures | Authorization Code with PKCE S256: `SmartAuthorizationFlow` opens the system browser, the `LoopbackCallbackServer` on `127.0.0.1:0` collects the code/state, `SmartTokenExchangeService` posts the code + verifier (no client secret), then `SmartIdTokenValidator` verifies the ID token against the published JWKS. Tokens live in memory only (`SmartAuthorizationSession`), never logged. Workbench: Authorize enabled after Discover; Session panel; logout clears the session. UI test drives the full flow against scripted authorize + token stubs. |
| 04 — Authorized FHIR access | Implemented — automated tests pass | compile exit 0; 845 tests, 0 failures | Authorized reads go through `SmartFhirAccess` → `SmartBearerAuthorization` → the existing plugin registry; one `ServerSession` per call, URL from the session only, token never sent elsewhere. `SmartSessionPane` shows granted scopes and explains absent patient context. `SmartResourcePane` reads the granted patient and opens it in the existing viewer. `SmartLaunchDialog` wires Authorize → session → read → Open in viewer. New `SmartFhirAccessTest` covers token attachment, 401/403, session isolation, logout/expiry refusal, and no-patient-context. |
| 05 — EHR launch simulation | Implemented — automated tests pass | compile exit 0; 849 tests, 0 failures | EHR launch mode sends `launch` (not `aud`) in the authorization request; `onNew()` clears prior discovery so endpoints cannot leak across launches; `SmartAuthorizationFlowTest` covers EHR vs standalone parameter construction. Standalone behavior unchanged. |
| 06 — Diagnostics and release readiness | Implemented — automated tests pass | compile exit 0; tests pass | Diagnostics UI with redacted event list, export, and clear; diagnostics wired into discovery, authorization, session, and FHIR access; `SmartDiagnosticsTest` covers recording, redaction, clear, and export. Manual checklist and user documentation complete. |

## Architecture decisions

Record decisions here, including rationale:

- Package/class layout: **one new package, `server/smart/`**, for discovery, PKCE, the loopback
  callback, the launch config record and store. UI stays in `ui/` as `Dialog<T>` subclasses
  following the `RestConsoleDialog` triple. Rationale in assessment §13: `server/rest/` is the
  precedent for a server-side sub-package, and it keeps `ui/` JavaFX-only.
- OAuth/OIDC library: **none.** A standalone launch needs two HTTP calls the existing
  `java.net.http` transport already performs. Adding `oauth2-oidc-sdk` would risk class-path
  packaging for no capability gain (§12).
- HTTP implementation: **reuse `server/rest/JdkHttpRestClient` and `TokenEndpointClient`.** No
  OkHttp or Apache HttpClient, even though both are on the class path via HAPI.
- Callback implementation: **`com.sun.net.httpserver.HttpServer` bound to `127.0.0.1` on an
  ephemeral port**, the port carried in the redirect URI. `StubServer` already proves the idiom
  works in tests.
- HAPI client integration: **no plugin change.** A SMART access token is presented as
  `BearerServerAuthentication` inside a `ServerSession`; `BearerServerAuthentication`'s own
  javadoc names this as the intended use.
- Configuration persistence: **a third properties store, `~/.fhirviewer/smart-launches.properties`**,
  atomic write like `ServerDefinitionStore`. No secret in it — a public client has none. Any
  refresh token would go through `SecretBox`/`PluginSettingsStore`.
- Diagnostic redaction: **`ui/ServerErrors.redact` on every line**, plus a
  `toString` that never prints a token, following `TokenFetchResult`.
- Packaging/module changes: **none.** There is no `module-info.java`; the app runs on the class
  path through `Launcher`, so no new dependency or JPMS concern is introduced.
- Workbench UI: **`ui/SmartLaunchDialog` + `ui/SmartLaunchFormPanel`, flat in `ui/`** — a
  `Dialog<T>` opened from the Tools menu, mirroring `ServerManagerDialog`/`ServerFormPanel`
  (selector over a form, `Result(changed, message)`, package-private test access). The model and
  store live under `server/smart/` as decided above; `ui/` stays flat and JavaFX-only.
- Discovery transport: **the JDK `HttpClient` directly in `SmartDiscoveryService`, not
  `JdkHttpRestClient`.** A deliberate, narrow exception to the reuse note: the shared client
  follows same-scheme redirects and reads unbounded bodies, which is right for resource
  reads and wrong for the call that decides where credentials go next. No third-party HTTP
  library was added — the same JDK client the shared layer wraps — and TLS validation is
  never weakened.

## Dependencies added or changed

| Dependency | Version | Reason | License reviewed? |
|---|---|---|---|
| None | — | Phase 00 adds no dependency and changes no version. `oauth2-oidc-sdk` was evaluated and rejected: `java.net.http` plus Jackson already covers discovery, redirect, loopback callback and token exchange (§12 of the assessment). | n/a |

## Verification log

### Phase 00 — Repository assessment

- **Date:** 13 October 2026
- **Phase:** 00
- **Files changed:**
  - `docs/smartonfhir/PHASE-00-REPOSITORY-ASSESSMENT.md` (rewritten as findings)
  - `docs/smartonfhir/IMPLEMENTATION-STATUS.md` (this file)
- **Commands executed:**
  - `mvnw.cmd -q compile -DskipTests` → exit 0
  - `mvnw.cmd -q test` → exit 0
  - Surefire report tally: **760 tests, 0 failures, 0 errors, 0 skipped across 68 classes**
  - `git status --short` → no production file modified
- **Automated test results:** all pass, unchanged from baseline; nothing was added or removed.
- **Manual checks:** none applicable — no runtime behaviour was added. The claims in the
  assessment were verified by reading source and by `mvnw dependency:list` / `dependency:tree`.
- **Known issues:** none introduced. Pre-existing observations recorded rather than acted on:
  Jackson is used by application code but not declared in the POM; `nimbus-jose-jwt` is present
  only transitively; plugin configuration resolves against the working directory (Phase 30
  finding F2).
- **Reviewer decision:** **pending.** Stopping here for review before starting Phase 01.

### Phase 01 — SMART Launch Workbench UI

- **Date:** 13 October 2026
- **Phase:** 01
- **Files changed:**
  - `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartLaunchMode.java` (new; committed
    in `316cbe8`, moved from flat `server/` to `server/smart/` to match the recorded decision)
  - `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartLaunchConfig.java` (new, as above)
  - `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartLaunchStore.java` (new, as above)
  - `src/main/java/com/spiraleyes/fhirviewer/ui/SmartLaunchFormPanel.java` (new)
  - `src/main/java/com/spiraleyes/fhirviewer/ui/SmartLaunchDialog.java` (new)
  - `src/main/java/com/spiraleyes/fhirviewer/ui/MainWindow.java` (Tools-menu entry
    `SMART Launch Workbench...`, `openSmartLaunchWorkbench()`, `smartLaunchFile()`,
    `SmartLaunchStore` import)
  - `src/test/java/com/spiraleyes/fhirviewer/server/smart/SmartLaunchConfigTest.java` (new,
    9 tests: model completeness/problems/builder rules/no-secret reflection, store round trip,
    key allowlist, tolerant parse, missing file)
  - `src/test/java/com/spiraleyes/fhirviewer/ui/SmartLaunchWorkbenchUiTest.java` (new, 7
    toolkit-gated tests: builds with future actions disabled, save persists, delete removes,
    validate honest, mode row visibility, form round trip, no password field)
  - `docs/smartonfhir/IMPLEMENTATION-STATUS.md` (this file)
- **Commands executed:**
  - `mvnw.cmd -q compile -DskipTests` → exit 0
  - `mvnw.cmd -o test` → BUILD SUCCESS; surefire tally **776 tests, 0 failures, 0 errors,
    0 skipped** (baseline 760 + 16 new; `ServerUiSmokeTest` 61 still pass)
- **Acceptance criteria (Phase 01 brief):** workbench opens and closes — asserted on a live
  toolkit; navigation/styling — a Tools-menu dialog themed with `applyDialogTheme`, same
  structure as the server manager; CRUD + validation — asserted end-to-end through the real
  buttons; no fake network success — Discover/Authorize constructed disabled with phase-naming
  tooltips and asserted so; existing features — full suite green; build passes.
- **Remaining limitations:** no discovery (Phase 02), no authorization (Phase 03), the four
  right-hand panels are honest placeholders that name the phase filling them, the EHR launch
  parameter is typed by hand (real inbound launch is Phase 05), and mode-hidden rows keep
  their values rather than discarding them (`problems()` reports a stray one).
- **Manual verification required:** run the app, open Tools > SMART Launch Workbench, create
  and edit a launch, Validate it, restart the application and confirm the launch and its
  selection came back, try the smallest window size, and hover Discover/Authorize to see the
  phase tooltips.

### Phase 02 — SMART configuration discovery

- **Date:** 13 October 2026
- **Phase:** 02
- **Files changed:**
  - `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartUrls.java` (new: normalize
    the FHIR base without stripping path segments, build the well-known URL, loopback
    detection; pure functions, fully unit-tested)
  - `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartConfiguration.java` (new:
    typed document for the SMART App Launch v2.2.0 fields, `problems()`, `isUsable()`,
    unknown fields preserved as JSON text)
  - `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartDiscoveryException.java`
    (new: checked, `Kind`-carrying, user-facing message)
  - `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartDiscoveryService.java`
    (new: JDK `HttpClient` GET with `Redirect.NEVER`, connect/request timeouts, 512 KB
    body cap, 3xx/404/other mapping, origin-only messages)
  - `src/main/java/com/spiraleyes/fhirviewer/ui/SmartCapabilitiesPane.java` (new: the live
    Capabilities panel — rows for every advertised field, problems beside the metadata,
    hidden rows stay hidden)
  - `src/main/java/com/spiraleyes/fhirviewer/ui/SmartLaunchDialog.java` (Discover wired
    through `BackgroundTasks`, cancellation on hide, issuer-mismatch named in the status)
  - `src/test/java/com/spiraleyes/fhirviewer/server/smart/SmartUrlsTest.java` (new, 6)
  - `src/test/java/com/spiraleyes/fhirviewer/server/smart/SmartConfigurationTest.java`
    (new, 6 — the specification's own sample response, unknown fields, missing endpoints,
    insecure endpoints, PKCE, malformed answers)
  - `src/test/java/com/spiraleyes/fhirviewer/server/smart/SmartStubServer.java` (new test
    helper: scripted local server — status, redirect, delay, oversized body)
  - `src/test/java/com/spiraleyes/fhirviewer/server/smart/SmartDiscoveryServiceTest.java`
    (new, 8 — happy path, 404, redirect-not-followed, HTML, bad URL, oversized body,
    timeout, refused connection; no internet)
  - `src/test/java/com/spiraleyes/fhirviewer/ui/SmartLaunchWorkbenchUiTest.java` (updated:
    Discover asserted live, Authorize still disabled; +2 toolkit tests discovering from
    the local stub into the Capabilities panel)
  - `docs/smartonfhir/IMPLEMENTATION-STATUS.md` (this file)
- **Commands executed:**
  - `mvnw.cmd -q compile -DskipTests` → exit 0
  - `mvnw.cmd -o test` → BUILD SUCCESS; surefire tally **798 tests, 0 failures, 0 errors,
    0 skipped** (baseline 776 + 22 new)
- **Acceptance criteria (Phase 02 brief):** discovery URL follows the current SMART
  specification (`{iss}/.well-known/smart-configuration`, validated against v2.2.0
  §Conformance); metadata displayed in the workbench's Capabilities panel; a non-SMART
  server produces a typed `NOT_SMART` diagnosis and never a crash; network runs on a
  `BackgroundTasks` daemon thread with cancellation; existing features — full suite green.
- **Supported metadata:** `issuer`, `authorization_endpoint`, `token_endpoint`,
  `jwks_uri`, `registration_endpoint`, `capabilities`, `scopes_supported`,
  `code_challenge_methods_supported`, `grant_types_supported`,
  `response_types_supported`, `token_endpoint_auth_methods_supported`,
  `management_endpoint`, `introspection_endpoint`, `revocation_endpoint`, plus any
  unknown fields preserved for diagnostics.
- **Known limitations:** no `Accept` negotiation beyond `application/json`; a
  non-matching document `issuer` is reported but not resolved (only the deployment can
  say which address is right); the Capabilities panel is text — there is no endpoint
  editing or copying yet; Authorize stays disabled for Phase 03.
- **Manual verification required:** run the app against a public SMART test server
  (e.g. the HL7 FHIR R4 reference or a local HAPI with SMART), confirm the Capabilities
  panel fills and the status names the endpoint count; point at a plain FHIR server and
  confirm the not-SMART diagnosis.

Do not mark a phase complete unless the documented acceptance criteria are met. Distinguish automated test success from manual end-to-end verification.

---

## Phase 06 — Diagnostics and release readiness (completion report)

### Changed files

- `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartDiagnosticsEvent.java` (new)
  - Structured diagnostic event record with `Phase` and `Kind` enums.
- `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartDiagnostics.java` (new)
  - Thread-safe in-memory diagnostic log; redacts tokens, codes, verifiers, secrets,
    authorization headers, and patient references from every recorded message/detail;
    `export()` produces redacted text safe to share; `clear()` removes all events.
- `src/main/java/com/spiraleyes/fhirviewer/ui/SmartDiagnosticsPane.java` (new)
  - Diagnostics tab for the SMART Launch Workbench: ListView of events, Export button,
    Clear button, count line.
- `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartAuthorizationFlow.java`
  - Accepts an optional `SmartDiagnostics` (package-private constructor); records
    authorization-request-initiated, browser-launched/unavailable, callback-received,
    and token-exchange success/failure events.
- `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartFhirAccess.java`
  - Accepts an optional `SmartDiagnostics` (package-private constructor); records
    FHIR read start/complete/failure with HTTP status and elapsed time.
- `src/main/java/com/spiraleyes/fhirviewer/ui/SmartLaunchDialog.java`
  - Added `SmartDiagnostics` and `SmartDiagnosticsPane`; Diagnostics tab replaces the
    Phase 01 placeholder; records discovery start/complete/failure, authorization
    request/init/cancel/failure, session creation/logout, validation errors, and FHIR
    access events.
- `src/test/java/com/spiraleyes/fhirviewer/server/smart/SmartDiagnosticsTest.java` (new)
  - 8 tests: recording order, timestamps, bearer-token redaction, code/verifier redaction,
    patient-reference redaction, clear, export redaction, null-input safety.
- `docs/smartonfhir/IMPLEMENTATION-STATUS.md` (this file)
- `docs/smartonfhir/USER-GUIDE-SMART.md` (new: user documentation)
- `docs/smartonfhir/MANUAL-TEST-CHECKLIST.md` (new: manual test steps)

### Commands executed

- `mvnw.cmd -q compile -DskipTests` → exit 0
- `mvnw.cmd test` → BUILD SUCCESS; 0 failures

### Acceptance criteria (Phase 06 brief)

| Criterion | Met |
|---|---|
| Complete flow from configuration to authorized FHIR access can be tested | Yes — workbench end to end |
| Manual checklist and user documentation are complete | Yes — both documents added |
| Tests pass | Yes — `SmartDiagnosticsTest` + full suite green |
| Existing features are not regressed | Yes — no existing behavior changed |
| Diagnostics do not expose tokens or patient data by default | Yes — redaction on every event; tested |
| Supported flows and limitations are documented | Yes — user guide covers both |
| `IMPLEMENTATION-STATUS.md` reflects actual implementation and verification | Yes — this file |

### Known limitations

- Diagnostics are in-memory only for the current workbench session; export writes to
  `~/.fhirviewer/` but nothing survives a restart.
- The Diagnostics tab is only visible when the SMART Launch Workbench is open; there is
  no application-wide diagnostic log outside the workbench.
- Manual end-to-end verification against a real SMART test server still pending (no
  internet-dependent test credentials are committed).
