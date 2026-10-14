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
| 01 — Workbench UI | Not started | Not run | |
| 02 — SMART discovery | Not started | Not run | |
| 03 — OAuth / PKCE | Not started | Not run | |
| 04 — Authorized FHIR access | Not started | Not run | |
| 05 — EHR launch simulation | Not started | Not run | |
| 06 — Diagnostics and release readiness | Not started | Not run | |

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

Do not mark a phase complete unless the documented acceptance criteria are met. Distinguish automated test success from manual end-to-end verification.
