# SMART on FHIR — User Guide

This guide covers FHIRViewer's SMART Launch Workbench: what it does, how to configure a
test launch, how to run standalone and EHR launch flows, and what to expect at each step.

## What SMART on FHIR is

SMART on FHIR is an OAuth 2.0-based authorization framework that lets a client application
(like FHIRViewer) request access to FHIR resources on a FHIR server on behalf of a user.
Instead of entering a username and password in FHIRViewer, you sign in to the server through
its own authorization page (opened in your system browser), and the server gives FHIRViewer
a time-limited access token with the scopes it granted.

FHIRViewer implements the **SMART App Launch v2.2.0** specification using
**Authorization Code with PKCE S256**, the flow recommended for public desktop clients.

## Standalone versus EHR launch

### Standalone launch

You configure a FHIR server issuer URL and a client ID, press Discover to learn the
server's authorization endpoints, then press Authorize. FHIRViewer opens the server's
authorization page in your system browser; after you sign in and approve, the server
returns an authorization code, FHIRViewer exchanges it for an access token, and the
Workbench shows you what was granted (scopes, patient context if any, token expiry).

Use this when you want FHIRViewer to act as a standalone app against a SMART-enabled FHIR
server.

### EHR launch simulation

In an EHR launch, an EHR system hands FHIRViewer a `launch` token and an issuer URL.
FHIRViewer simulates the client side of that flow: it sends the `launch` parameter (not
the `aud` audience parameter) in the authorization request, and any patient/encounter
context the server returns is shown in the Workbench.

This is a **client-side simulation** — FHIRViewer is not an EHR, and it does not receive an
inbound launch URI from a real EHR. You enter the `iss` and `launch` values yourself, as
documented by your test harness.

## How to register a test application

Before you can authorize, you need a SMART-enabled FHIR server and a registered public
(client-side) client on it:

1. Choose a SMART test server (for example the HL7 FHIR R4 reference server or a local HAPI
   FHIR server configured with SMART support).
2. Register a **public** (confidential=false) OAuth client on that server. Record the
   client ID it issues.
3. Set the redirect URI to a loopback address such as `http://127.0.0.1:54321/callback` —
   the Workbench picks a free local port automatically at callback time, but the registered
   redirect URI must match the pattern the server accepts (loopback http is required).
4. Request scopes your launch needs, for example `launch/patient patient/*.read
   openid fhirUser`.

Do not commit real client IDs or redirect URIs into version control; use placeholders only.

## Client ID and redirect URI configuration

Open **Tools > SMART Launch Workbench...** and fill in the form:

| Field | What to enter |
|---|---|
| Name | A nickname for this launch (for the saved list only) |
| Issuer | The FHIR server base URL / issuer, e.g. `https://fhir.example.com` |
| Client ID | The public client ID from your server registration |
| Scopes | Space-separated scopes, e.g. `launch/patient patient/*.read` |
| Mode | Standalone or EHR launch |

In EHR launch mode, an additional **Launch** field appears for the opaque launch token
supplied by the EHR.

Press **Validate** to check the form locally (no server is contacted). Press **Discover**
to fetch `/.well-known/smart-configuration` from the issuer and learn its endpoints.

## Scope behavior

The scopes you request are sent in the authorization request. The server may grant fewer
scopes than you asked for — the Workbench reports the **granted** scopes (from the token
response), not the requested ones, so you see what you actually received.

- `launch/patient` — needed to receive patient context in an EHR launch.
- `patient/*.read` — needed to read patient resources through an authorized session.
- `openid`, `fhirUser` — identity scopes; requesting them causes an ID token to be issued
  and validated, and the `sub` claim is shown as the "User" in the Session panel.

If a launch grants no patient context, that is a normal outcome: identity-scoped launches
carry no patient, and the Resource access panel says so rather than hiding the row.


## How to configure a test server

FHIRViewer contacts your SMART server only at discovery (`/.well-known/smart-configuration`)
and, after authorization, at the token endpoint and FHIR base URL learned from discovery.
No server is hard-coded.

1. Point the Issuer field at your test server's FHIR base URL.
2. Press **Discover** — the Capabilities panel fills with the advertised endpoints and any
   problems found in the document.
3. Press **Authorize** — FHIRViewer opens the server's authorization page in your system
   browser. Sign in there and approve the requested scopes.
4. When the browser returns, FHIRViewer exchanges the code, validates the ID token (if any),
   and shows the granted session.

## Troubleshooting common failures

| Symptom | Likely cause | Fix |
|---|---|---|
| Discover says "not SMART" | The server does not publish a SMART configuration document | Confirm the base URL and that SMART is enabled on the server |
| Authorization page never opens | System browser unavailable or redirect URI mismatch | Check that a browser is installed; confirm the registered redirect URI is loopback http |
| "Authorization failed: invalid_grant" | The authorization code expired or was already used | Press Authorize again and sign in promptly |
| "The access token has expired" | The token lifetime passed | Press Authorize again for a new session |
| Read returns no patient | The launch granted no patient context | Normal for identity-scoped launches; request `launch/patient` if you need one |
| Read fails with 401/403 | The token was rejected or lacks scope | Re-authorize with the needed scopes |

None of these messages ever contains your access token, authorization code, or patient
data.

## Security precautions for production servers

- FHIRViewer never writes tokens to disk; they live in memory only for the current
  workbench session. Closing the dialog drops them.
- The redirect URI is always loopback (`127.0.0.1`); the authorization code can never be
  sent to a remote host.
- No client secret is used — public clients have none, so nothing is stored or logged.
- Diagnostic events and exports are redacted before display or writing: no bearer token,
  authorization code, PKCE verifier, client secret, authorization header, or patient
  identifier is included.
- Use HTTPS for production servers; loopback http is acceptable for local testing only.

## Supported flows and known limitations

**Supported:** standalone launch, EHR launch simulation, Authorization Code with PKCE S256,
ID token validation against JWKS, authorized patient/resource read through the existing
viewer, session logout, diagnostic export with redaction.

**Known limitations:**

- Diagnostics are in-memory only for the current workbench session; export writes to
  `~/.fhirviewer/` but nothing survives a restart.
- No refresh-token use: if the server issues a refresh token it is recorded but not used
  to obtain a new access token.
- No `Accept` negotiation beyond `application/json` for discovery.
- A non-matching document `issuer` is reported but not resolved — only the deployment can
  say which address is right.
- Manual end-to-end verification against a public SMART test server is recommended before
  production use; no test credentials are committed.