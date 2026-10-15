# Phase 03 — OAuth 2.0 / OIDC authorization with PKCE

## Objective

Implement a real SMART standalone authorization flow for a native Java desktop public client.

This phase is security-sensitive. Follow the current SMART App Launch and OAuth/OIDC specifications and keep the implementation limited to the required flows.

## Supported initial flow

Implement Authorization Code with PKCE using `S256`. Use the system's default browser for interactive authorization. Do not use JavaFX WebView as the default authentication browser.

## Tasks

### A. Authorization request

- Generate cryptographically secure, high-entropy `state`.
- Generate a cryptographically secure PKCE code verifier and derive its S256 challenge.
- Generate and validate an OIDC nonce when required by the selected flow.
- Build the authorization URL from validated discovery metadata.
- Include required client ID, redirect URI, response type, requested scopes, state, PKCE parameters, and flow-specific SMART/OIDC parameters such as `aud` where required.
- Use URI-building utilities, not manual query-string concatenation.
- Open the authorization URL through Java desktop browser integration.

### B. Loopback redirect callback

- Implement a small callback listener bound only to loopback, preferably `127.0.0.1`.
- Use an available ephemeral port if supported by the server's registered loopback redirect URI rules; otherwise support the configured fixed loopback URI.
- Ensure the redirect URI exactly matches the URI used in the authorization request and token exchange.
- Validate callback path, state, and expected parameters.
- Handle OAuth error callbacks safely.
- Reject unexpected requests and callbacks.
- Shut down the listener on success, cancellation, timeout, or failure.
- Ensure a callback cannot be processed more than once.
- Never bind the listener to a network-accessible interface.

### C. Token exchange

- Exchange the authorization code using Java `HttpClient`.
- Send the original PKCE verifier and matching redirect URI.
- Parse the token response with Jackson.
- Model access token, token type, expiry, granted scope, refresh token, and ID token when applicable.
- Validate required fields and error responses.
- Keep tokens in memory initially.
- Do not embed or require a client secret for the native public client.

### D. OIDC validation

When OIDC identity scopes are requested:

- Validate ID tokens according to applicable OIDC requirements.
- Validate signatures using trusted metadata/keys when required.
- Validate issuer, audience, expiry, nonce, and other required claims.
- Do not treat merely decoding a JWT as validation.
- Do not trust user identity claims from an unvalidated token.
- Prefer a maintained protocol library for JWT validation and cryptographic protocol work rather than implementing those primitives from scratch.

Evaluate Nimbus OAuth 2.0 SDK if suitable and compatible with the current Java/dependency setup. Document any new dependency and its license/version.

### E. Lifecycle

Handle success, failure, user cancellation, callback timeout, expiry tracking, and logout by clearing local session state. Support refresh tokens only when issued and supported by the server and security model.

Do not assume all SMART servers issue refresh tokens.

## Tests

Cover:

- PKCE S256 generation.
- State generation and mismatch.
- Invalid callback path and repeated callback.
- OAuth error callback.
- Callback timeout and shutdown.
- Malformed token responses.
- Token exchange errors.
- Issuer/audience/nonce validation as applicable.
- No token leakage into logs.

Mock HTTP/token endpoints for automated tests. Do not require real credentials or patient data in the repository.

## Acceptance criteria

- Standalone authorization succeeds against a compatible SMART test environment.
- PKCE S256 is used.
- State and callback validation work.
- Redirect URI handling is correct.
- Tokens are not logged or displayed raw.
- Callback listener cleanup is reliable.
- Errors are understandable.
- Tests and Maven build pass.

If a test server cannot complete the flow, document the blocker. Do not weaken security to make the test pass.

## Completion report

Record changed files, dependencies, test results, security decisions, and manual verification steps. Update `IMPLEMENTATION-STATUS.md`. Stop after this phase.

### What was implemented

- **New package `server/smart/`:**
  - `Pkce.java` — S256 verifier/challenge from the platform CSPRNG
  - `SmartAuthState.java` — opaque CSRF `state` + OIDC `nonce`
  - `SmartAuthorizationRequest.java` — URI-built authorize URL (no query-string concat)
  - `LoopbackCallbackServer.java` — `127.0.0.1` ephemeral port, one callback, path/state/code validation, timeouts, no repeat processing
  - `BrowserLauncher.java` / `DesktopBrowserLauncher.java` — `Desktop.browse` with an unavailable result
  - `SmartTokenResponse.java` — grant model: access token, type, expiry, granted scope, patient, encounter, id_token, refresh_token
  - `SmartTokenExchangeService.java` — authorization-code POST with the original verifier and exact redirect URI; no client secret
  - `SmartIdTokenValidator.java` — Nimbus JOSE+JWT signature verification, then issuer/audience/expiry/nonce claims
  - `SmartAuthorizationSession.java` — in-memory session with expiry, granted scopes, patient/encounter, logout
  - `SmartAuthorizationFlow.java` — blocking end-to-end orchestration
  - `SmartJwksFetcher.java` — JDK `HttpClient`, redirects never followed, capped body
  - `SmartAuthorizationException.java` — typed, user-facing diagnostics
- **Workflow:** `SmartLaunchDialog` now shows the Authorize button after a successful Discover, opens the browser, and renders the new `SmartSessionPane`; a session is cleared on logout.

### Dependencies

- `jackson-databind` 2.21.6 — JSON parsing for discovery and token documents (Apache-2.0)
- `nimbus-jose-jwt` 10.0.2 — ID-token signature verification (Apache-2.0)

Both were previously only transitive via HAPI and are now declared direct so ID-token trust does not depend on a transitive accident.

### Test results

`mvnw.cmd -o test` → BUILD SUCCESS; surefire tally **836 tests, 0 failures, 0 errors, 0 skipped** (the Phase 02 total 798 + 38 in this phase).

### Security decisions

- Public client, no client secret anywhere in the flow.
- Tokens are only in memory; `SmartAuthorizationSession.logout()` clears them.
- No secret, code, verifier, or state reaches any log line — `ServerErrors.redact()` applies to every diagnostic.
- Redirect URI is loopback-only; the token endpoint is not downgraded to plain http.
- The ID token is signature-checked before any claims are trusted; an unsigned token is rejected.

### Manual verification steps

1. Open Tools > SMART Launch Workbench, enter a FHIR base URL and client id, choose Discover; the Capabilities panel fills with the advertised endpoints.
2. Choose Authorize and complete the sign-in in the system browser that opens; the Session panel shows granted scopes, patient context, token expiry, and a user identity when the server issues one.
3. Log out and confirm the Session panel resets and the Client ID is no longer used for anything else.
4. Point the base URL at a plain (non-SMART) FHIR server and confirm the workbench reports a diagnosis rather than launching.

### Stop

The SMART on FHIR feature reaches Phase 03. `IMPLEMENTATION-STATUS.md` records the result; `PHASE-04` is the next phase.
