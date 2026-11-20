# SMART on FHIR — Launch Guide

How to add a SMART-enabled FHIR server to FHIRViewer, discover what it advertises, sign in
through the system browser, and read resources you are entitled to — and, from the Scripting
Console, create, update, patch and delete them under the same token's scopes. The SMART Launch
Workbench lives in **Tools → SMART Launch Workbench...**; the User Guide's Part B covers the
ordinary server features, and this guide is the SMART on-ramp.

> **This feature needs a SMART-enabled FHIR server.** A plain FHIR server will be diagnosed as
> `not SMART` at Discover and will not launch. Use a test server (the HL7 FHIR R4 reference
> server, a local HAPI server configured with SMART, or an official SMART sandbox) until you
> are running in production.

---

## What this guide covers

1. [What SMART on FHIR adds to FHIRViewer](#what-smart-on-fhir-adds-to-fhirviewer)
2. [Standalone versus EHR launch](#standalone-versus-ehr-launch)
3. [Before you start — register a test application](#before-you-start--register-a-test-application)
4. [Add a SMART launch in the workbench](#add-a-smart-launch-in-the-workbench)
5. [Discover what the server advertises](#discover-what-the-server-advertises)
6. [Authorize in the system browser](#authorize-in-the-system-browser)
7. [Inspect what you were granted](#inspect-what-you-were-granted)
8. [Read a resource through the authorized session](#read-a-resource-through-the-authorized-session)
9. [EHR launch simulation](#ehr-launch-simulation)
10. [Federated login with Smile CDR](#federated-login-with-smile-cdr)
11. [Log out and start again](#log-out-and-start-again)
12. [Sample workflows](#sample-workflows)
13. [Write through the authorized session](#write-through-the-authorized-session)
14. [Coding a SMART workflow](#coding-a-smart-workflow)
15. [Troubleshooting](#troubleshooting)
16. [Security notes](#security-notes)
17. [Known limitations](#known-limitations)

---

## What SMART on FHIR adds to FHIRViewer

The SMART Launch Workbench is a separate screen from the normal server workflow. It does not
replace **File → Open from FHIR Server...** or the server plugins. It adds:

- **Discovery** of a server's SMART metadata from `/.well-known/smart-configuration`.
- **Authorization Code with PKCE S256** against a public (client-side) client, with no client
  secret and no saved credentials.
- **Standalone launch** and **EHR launch simulation** modes.
- **Authorized reads** of the patient context the token grants, through the existing viewer.
- **Scripted reads and writes** through the same session: the Scripting Console's `smart`
  binding creates, updates, patches and deletes under the token's scopes, with its own
  opt-in (see [Write through the authorized session](#write-through-the-authorized-session)).
- **Diagnostics** — a structured, redacted event list with export and clear.

The rest of the application — tree, views, editing, validation, server connections — is
unchanged.



## Standalone versus EHR launch

| Mode | What you supply | What it sends | What it is for |
|---|---|---|---|
| **Standalone** | An issuer URL and a client ID | `aud` (the FHIR base URL), `response_type=code`, `client_id`, `redirect_uri`, `scope`, `state`, PKCE S256 | You sign in as yourself to a server that already knows you. |
| **EHR launch** | An issuer URL, a client ID, and a `launch` token | `launch` (the opaque context token), no `aud` | You simulate what an EHR does when it hands FQ a launch context and expects the patient it is visiting. |

Standalone is the normal case for a desktop application. EHR launch is a client-side
simulation only — FHIRViewer is not an EHR and does not receive an inbound launch URI from a
real EHR. Use a documented SMART test launcher or compatible test harness for end-to-end
testing, and never claim a manually-entered launch token is equivalent to a valid EHR launch.

---

## Before you start — register a test application

You need a SMART-enabled FHIR server and a registered **public** OAuth client on it.

### Steps

1. **Choose a test server.** A local HAPI FHIR server with SMART enabled, or an official
   SMART sandbox. Do not use a production server with real patient data.
2. **Register a public client.** In the server's administration console, register a client
   with `confidentiality = public`. The server will issue a **client ID**; record it.
3. **Set the redirect URI.** The redirect URI must be **loopback http**, for example
   `http://127.0.0.1:54321/callback`. There are two ways to use it:
   - **Any port.** Leave the Workbench's **Redirect URI** field blank. The Workbench picks a
     free local port each time, so the server must accept any port on `127.0.0.1`.
   - **A fixed port.** If the server matches the redirect URI exactly (Smile CDR does),
     register one URI with an explicit port and path, then enter the same URI in the
     **Redirect URI** field. The Workbench listens on that port and path and sends the URI
     exactly as entered. `localhost` is accepted and is listened on at `127.0.0.1`. If
     another program holds the port, Authorize says so; pick and register another port.
4. **Request scopes.** At minimum request `launch/patient` if you want patient context, and
   `patient/*.read` if you want to read resources. Identity scopes (`openid`, `fhirUser`) are
   optional and produce an ID token.

> Do not commit client IDs, redirect URIs, or tokens into version control. Use placeholders
> in the workbench for any shared configuration.

---

## Add a SMART launch in the workbench

1. Open **Tools → SMART Launch Workbench...**.
2. Fill in the form:

| Field | What to enter |
|---|---|
| Name | A nickname for this launch, only used in the saved list |
| Issuer | The FHIR server base URL, e.g. `https://fhir.example.com` |
| Client ID | The public client ID you registered |
| Redirect URI | Blank for any free port, or the exact loopback URI you registered |
| Scopes | Space-separated, e.g. `launch/patient patient/*.read` |
| Mode | **Standalone** or **EHR launch** |
| Authorization parameters | Optional. Extra request parameters, one `name=value` per line; see below |
| Force re-login | Optional. Sends `prompt=login` so the server asks you to sign in again |

3. Fill the **Launch** field only in EHR mode — paste the opaque launch token.
   - **Authorization parameters** are for servers that need more than SMART defines: an
     identity-provider hint (`kc_idp_hint` for Keycloak, `idp` on some others),
     `login_hint`, `acr_values`, or a `launch` value in a standalone launch. Lines starting
     with `#` are ignored. The parameters the launch sets itself (`client_id`,
     `redirect_uri`, `scope`, `state`, `nonce`, the PKCE pair, `aud`, `response_type`,
     `response_mode`, `request`, `request_uri`) cannot be set here, and Validate says so.
     They are saved with the launch, so do not put anything secret in them.
4. Press **Validate** to check the form without contacting any server. It reports problems
   such as a blank client ID or a malformed scope. The status line is explicit about what it
   did: "no server has been contacted".

---

## Discover what the server advertises

1. Press **Discover**. The dialog shows "Discovering from http://... — ..." and runs on a
   background daemon thread. Click the **Diagnostics** tab to watch it, or open `~
   /.fhirviewer/smart-launches.properties` to inspect the saved configuration.
2. When it finishes, the **Capabilities** panel fills with the endpoints the server advertises:

- Issuer
- Authorization endpoint
- Token endpoint
- JWKS URI
- Capabilities (e.g. `launch-standalone`, `client-public`)
- Scopes supported
- PKCE methods supported (usually `S256`)
- Grant types supported
- Unknown fields preserved for diagnostics

3. The status line says how many capabilities were found and whether there were problems.
   Problems (a missing authorization endpoint, an insecure token endpoint, a malformed
   document) are listed beside the metadata so you can see exactly what was wrong — and the
   panel never reports a problem the document did not actually contain.

### What a successful discovery looks like

- The Capabilities panel shows the full document.
- The status line says: *"Discovered N capability(ies). The server can be launched against."*
- The **Authorize** button becomes pressable.
- Unknown fields (extensions a server adds beyond the SMART spec) are shown as "Other"
  rows, so nothing is silently dropped.

### What happens with a plain FHIR server

- Discovery answers with a clear `not SMART` diagnosis: the server does not publish
  `/.well-known/smart-configuration`, or the document does not contain the endpoints
  SMART needs.
- The Workbench does not half-launch. The status line and the Capabilities panel both say
  exactly what was wrong, and the user is told to check the base URL and that SMART is
  enabled on the server.

## Authorize in the system browser

1. Press **Authorize**. The Workbench starts a loopback listener and opens the issuer's
   authorization page in the system browser. The redirect URI is the loopback address that
   listener chose; it is not a hosted service.
2. Sign in and approve the scopes in the browser, then return to the Workbench. Keep the
   Workbench open while you do this: closing it cancels the attempt, and the listener waits
   at most five minutes for the callback.
3. The browser returns to the loopback address, and the Workbench exchanges the code using
   PKCE and validates the returned ID token. The status line reports a failure or a
   cancellation rather than leaving the panel looking authorized.
4. On success the Session panel fills with the granted scopes, patient, encounter, user and
   expiry. Authorizing again logs the previous session out first, so two tokens never
   coexist.

## Inspect what you were granted

The **Session** panel shows what the server actually gave you. It never shows the token
itself:

| Field | Meaning |
|---|---|
| State | Whether the session is active or has been logged out, and whether it can renew itself |
| Granted scopes | The scopes the server granted (which may differ from what you asked for) |
| Patient | The patient context, when the server supplied one |
| Encounter | The encounter context, when supplied |
| User | The `sub` claim from the ID token, when identity scopes were requested |
| Token expiry | When the access token stops working, or a note that the server did not state a lifetime |

- A launch can succeed with **no patient context**. That is a normal outcome, not a bug: it
  happens for identity-scoped launches that did not request `launch/patient`. The Session
  panel says so rather than hiding the row.
- A launch that granted **no patient** disables the **Read patient context** button in the
  Resource access panel — there is nothing to read.
- The granted scopes may be fewer than the ones you requested. That is the server's
  decision, and the Session panel reports the granted set, not the requested set.

### Session renewal

Some servers issue very short-lived access tokens; Smile CDR's default is about a minute.
If the server also issued a **refresh token**, the session renews itself:

- Before a read or write, a token that has expired, or will within 15 seconds, is renewed
  first with `grant_type=refresh_token` and the client id. No secret is sent.
- If the server answers **401** to a token that has not expired (it was revoked early), the
  session renews once and retries the request once. A second 401 is reported as it is.
- A new refresh token from the server replaces the old one. The patient, encounter and user
  stay those of the original launch.
- If the server refuses the renewal (for example `invalid_grant`), the refresh token is
  dropped and you are asked to authorize again.

Servers only issue a refresh token when you ask for one: add **`offline_access`** or
**`online_access`** to the scopes. The Session panel's State line says whether the current
session can renew itself. Renewals appear in the Diagnostics tab, with tokens redacted.

---

## Read a resource through the authorized session

1. With an authorized session that has a patient context, press **Read patient context**.
   The button is enabled only when the session granted a patient, so pressing it with
   nothing to read cannot happen through the UI — the guard stays in code too.
2. A status line reports the read. The Resource access panel shows the result:

- *"Read Patient 12345 from the authorized session."* — success.
- *"The server says Patient 12345 does not exist."* — a 404, not a scope problem.
- A failure message — the redacted reason, with no resource offered, and the status line
  says what to do next (e.g. re-authorize).

3. Press **Open in viewer** to display the fetched resource in the existing tree and Pretty
   View exactly as any other read would appear.

---


## EHR launch simulation

1. Switch the Mode to **EHR launch**.
2. Enter the **Issuer** and **Client ID** as usual.
3. Paste the **Launch** token the EHR supplied. It is opaque and must be preserved exactly.
4. Discover and authorize as normal. The Workbench sends the `launch` parameter and no `aud`,
   exactly as a real EHR would.
5. Any patient, encounter, or user context the server returns is shown in the Session
   panel. The Workbench distinguishes clearly between:

- a standalone launch (sends `aud`, no `launch`),
- an EHR launch simulation (sends `launch`, no `aud`),
- a successful authorization that happens to have no patient context.

### What EHR simulation does not do

- It does not receive an inbound launch URI, so it is not a real EHR integration.
- It does not prove compatibility with any vendor that is not a documented test harness.
- It does not convert a manually-entered opaque token into a valid launch context — that is
  the server's job, and the Workbench cannot.

---

## Federated login with Smile CDR

Smile CDR can send the sign-in on to an upstream OpenID Connect provider (Keycloak, Okta,
Entra ID and others), which its documentation calls *federated OAuth2/OIDC login*. To
FHIRViewer this is an ordinary SMART launch. The Workbench talks only to Smile CDR's
`/oauth/authorize` and `/oauth/token`; Smile CDR sends the browser to the upstream provider
and back, and then issues its own access token and ID token. Nothing about the upstream
provider is configured in FHIRViewer.

**On the Smile CDR side** (your administrator's job): federated login enabled in the SMART
Outbound Security module, one OIDC server definition per upstream provider, and an OIDC
client definition for FHIRViewer with:

- **Client Required to Authenticate** turned off, since FHIRViewer is a public client with no
  secret.
- A redirect URI with a fixed port, such as `http://127.0.0.1:8765/callback`. Smile CDR
  compares redirect URIs exactly, so a random port will not match.
- The SMART scopes you want, plus `offline_access` or `online_access` if sessions should
  renew themselves. Smile CDR's access tokens are short-lived (about a minute by default).

**In the Workbench:**

1. Set **FHIR server (iss)** to the Smile CDR FHIR endpoint and **Client ID** to the
   client definition's id.
2. Enter the registered URI in **Redirect URI**.
3. Request scopes such as `openid fhirUser patient/*.read offline_access`.
4. **Choosing the provider.** With one upstream provider, Smile CDR goes straight to it.
   With several, it shows its own selection page. A Smile CDR interceptor on the
   `SMART_FEDERATED_OIDC_PRE_PROVIDER_SELECTION` pointcut can choose for you; Smile CDR's
   example reads a `launch` value starting with the provider's registration id. To use one
   like that, put a line such as `launch=<registrationId>-standalone` in **Authorization
   parameters**. What the value must look like is decided by your interceptor, not by
   FHIRViewer.
5. Discover and Authorize as usual. The browser goes to Smile CDR, on to the upstream
   provider's sign-in page, and back to the Workbench.

**Signing in as someone else.** Log out in the Workbench clears FHIRViewer's session only.
The browser stays signed in to Smile CDR and to the upstream provider, so the next Authorize
may finish without asking for a password. Tick **Force re-login** to send `prompt=login`,
the only `prompt` value Smile CDR supports. To also end the upstream provider's session, sign
out of it in the browser.

**If the ID token is refused** with "issued by an unexpected issuer", the token's `iss` does
not match the issuer in Smile CDR's discovery document. That is a server configuration
mismatch; request no identity scopes (`openid`, `fhirUser`) to work without an ID token
until it is fixed.

---

## Log out and start again

1. Press **Log out** in the Workbench. The Session panel resets to *"No session exists yet."*,
   the Resource access panel disables its read button, and the shared token is dropped, so
   the Scripting Console no longer has a SMART session either.
2. Closing the Workbench **without** pressing Log out keeps the authorized session in
   application memory for the Scripting Console. It is never written to disk; exiting the
   application drops it.
3. Reopen **Tools → SMART Launch Workbench...** and the Session panel shows the current
   session, if there is one. Press **Open** (or **New**) to load a configuration, then
   Discover and Authorize again when you want a different launch. Authorizing again replaces
   any previous session and logs the old one out.
4. Logging out does not sign the browser out of the server. If the next Authorize signs you
   straight back in as the same user, tick **Force re-login** on the launch (see
   [Federated login with Smile CDR](#federated-login-with-smile-cdr)).

---

## Sample workflows

Both workflows below are read-only. The Workbench itself only reads through the authorized
session; writes through that token are possible from the Scripting Console alone, as
[Write through the authorized session](#write-through-the-authorized-session) describes.

### Workflow 1 — standalone review of the launch patient

Use this when you are acting as yourself and want to inspect the patient the server puts in
context.

1. Register a public client and a loopback redirect URI as described in
   [Before you start](#before-you-start--register-a-test-application).
2. Open **Tools → SMART Launch Workbench...**.
3. Enter a name, the issuer, the client ID, and
   `launch/patient patient/*.read openid fhirUser`. Leave the mode on **Standalone**.
4. Press **Validate** to catch typos locally, then **Discover**.
5. Check that the Capabilities panel names the authorization and token endpoints, and that
   `S256` is listed under PKCE methods.
6. Press **Authorize**, sign in in the system browser, and approve the scopes.
7. In the Session panel, confirm the granted scopes, the patient, and the token expiry. If
   the patient row says none, the server chose not to supply patient context; re-check that
   `launch/patient` was requested and granted.
8. Press **Read patient context**. A successful read offers **Open in viewer**.
9. Press **Open in viewer** to inspect the resource. Press **Log out** to revoke the token,
   or close the Workbench to leave the session available to the Scripting Console.

What a good run looks like:

- Discover reports a capability count and enables Authorize.
- The Session panel shows the scopes the server granted, which may be fewer than the ones
  requested. That is the server's grant, not an error.
- Read names the patient and offers the viewer; opening it shows the same tree and Pretty
  View as a file.

### Workflow 2 — EHR launch simulation

Use this when a test harness has given you an `iss` and an opaque `launch` token and you want
to check what the server returns for that context.

1. Open the Workbench and choose **EHR launch**.
2. Enter the issuer and client ID, then paste the `launch` token exactly as supplied.
3. Press **Validate** and **Discover** as before.
4. Press **Authorize**. The request carries `launch` and no `aud`.
5. Inspect the Session panel for patient, encounter, and user context. Any of them may be
   absent; the panel names what the server returned.
6. Read the patient context and open it in the viewer if one was granted.
7. Clear the session. Do not reuse the launch token: a real EHR launch token is single-use.

This simulates the client half only. It cannot receive an inbound launch from a real EHR, and
it does not prove compatibility with a vendor that is not a documented test harness.

---

## Write through the authorized session

The Workbench does not write. Writes go through the Scripting Console's `smart` binding, under
the same token the session was issued.

1. Authorize in **Tools → SMART Launch Workbench...**, then open
   **Tools → Scripting Console...**. The session stays available to the console after the
   Workbench closes.
2. Select a writable server in **Tools → FHIR Servers** and tick **Allow server writes**. The
   console binds its grant to that configured server, so a SMART session on its own cannot turn
   writes on; the warning names both the configured server and the URL the SMART session is
   authorized for. Approve it.
3. Run the script. Every SMART write needs that grant: turning writes off, changing the active
   server, an unusable server, or 15 idle minutes revokes it for `smart` as well.

```java
if (!smart.isWriteEnabled()) {
    log.warn("Turn on Allow server writes in this console first.");
} else {
    var patient = new org.hl7.fhir.r4.model.Patient();
    patient.addName().setFamily("Scripted");
    var created = smart.create(patient);
    log.info("Created " + created.resourceId() + " version " + created.versionId());
}
```

What a SMART write does and does not have:

- **The session's bearer and nothing else.** Every request carries only the session's token,
  so it can reach only the URL the session was authorized for. `fhir` writes use the
  configured server's saved credentials; `smart` writes never do.
- **The server's scopes decide.** A write the token is not entitled to fails with a `403` as
  a `ScriptApiException` and is journalled as a failed attempt, never retried. The console's
  grant permits a write; it cannot widen the token.
- **The console's rules apply unchanged.** The write scope narrows operations and resource
  types, the 50-write budget pauses long runs, `smart.delete` asks for confirmation naming the
  session's server, and the **Writes** tab records the attempt as `SMART session` because no
  configured server owns it.
- **A timeout is an unknown outcome.** A write that timed out after it may have been sent
  raises `WriteOutcomeUnknownException` instead of an ordinary failure, so read the resource
  through the session before retrying. **Review Unknown** cannot settle a SMART entry — it
  looks the entry up among configured servers — so reconcile that one by hand.

**Undo Last Write** reverses a SMART write through the same session, and only while that
session is still active and still authorized for the URL the write names: a session since
re-authorized for another server is refused rather than sent the before-image.

- An update or patch is reverted by writing the captured resource back with `If-Match` naming
  the version the write produced, because the before-image's own version predates that write.
- A delete is reverted by a restore carrying `If-None-Match: *`, so an id something has taken
  since is refused instead of overwritten; the console reads the id first so that refusal is
  reported plainly, and a read the token is not allowed to make does not block the conditional
  restore.
- A create is reverted by deleting the id the create returned, conditional on the version the
  create produced, so a resource replaced at that id since is left in place and the status
  line says nothing was deleted.

Undo is a separate, confirmed recovery write outside the grant's operation and resource
scope, but it still needs writes on. It is best effort and not a server rollback. A
before-image that could not be read is never offered, and neither is a create whose id the
server never returned.

**Run Tests** uses the separate **Allow writes in test runs** opt-in for SMART writes too:
without it the `smart` binding a suite runs with is read-only, and with it a `// cleanup:`
block can remove the fixtures a case created through the session.

---

## Coding a SMART workflow

The Scripting Console exposes a `smart` binding with `isSessionActive()`, `grantedScopes()`,
`launchPatientId()`, `launchEncounterId()`, `read(resourceType, id)`, `isWriteEnabled()`,
`sessionBaseUrl()`, and the write calls `create(...)`, `update(...)`, `patch(...)` and
`delete(...)` described in [Write through the authorized session](#write-through-the-authorized-session).
It never exposes the access token.

Authorize in **Tools → SMART Launch Workbench...**, then close the Workbench and open
**Tools → Scripting Console...**. The authorized session stays in application memory for
the console, so `smart.isSessionActive()` is true and `smart.read` uses the granted scopes
and patient context. Press **Log out** in the Workbench, authorize again, or exit the
application to drop it; the token is never written to disk. If no session has been
authorized, `smart.read` throws `ScriptApiException("No active SMART session")`.

A scripted SMART read:

```java
if (!smart.isSessionActive()) {
    log.warn("No SMART session is available to this console.");
} else {
    String patientId = smart.launchPatientId();
    if (patientId == null || patientId.isBlank()) {
        log.warn("The launch granted no patient context.");
    } else {
        var patient = smart.read("Patient", patientId);
        if (patient == null) {
            log.warn("Patient " + patientId + " was not found.");
        } else {
            viewer.showResource(patient);
            log.info("Read Patient " + patientId + " with scopes " + smart.grantedScopes());
        }
    }
}
```

A launch with no patient is a normal outcome, not a failure: identity-only scopes do not
guarantee patient context. Check `launchPatientId()` before calling `read`.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Discover says "not SMART" | The server does not publish a SMART configuration document | Check the base URL; confirm SMART is enabled on the server |
| Authorization page never opens | Browser unavailable or redirect URI mismatch | Confirm a browser is installed; the registered redirect URI must be loopback http |
| Authorization fails with `invalid_grant` | The authorization code expired or was already used | Press Authorize again and sign in promptly |
| "The access token has expired" | The token's stated lifetime passed | Press Authorize again for a new session |
| Read fails with `401` or `403` | The token was rejected or lacks the needed scope | Re-authorize with the scopes the resource requires |
| `smart.create` throws `WriteDeniedException` | Writes are off for the console, or the grant expired or was revoked | Tick **Allow server writes** again and approve the warning |
| A SMART write fails with `403` | The session's scopes do not cover that resource or interaction | Re-authorize asking for scope the server will grant; the console's grant cannot widen the token |
| Undo says the session is not the one that made this write | A later authorization replaced the session, possibly for another server | Authorize again for the original server while the console is still open |
| Review Unknown says the server `SMART session` is no longer configured | The review resolves entries against configured servers, and a SMART entry names none | Read the resource through the session and reconcile it by hand |
| Read returns no patient | The launch granted no patient context | Normal for identity-scoped launches; request `launch/patient` if you need one |
| Session panel is always empty | Authorization succeeded but granted no scopes | Check that the server actually granted what you requested |
| Discovery works but Authorize stays disabled | The discovered document had problems | Open the Capabilities panel and read the problem list |

None of these messages ever contains your access token, authorization code, PKCE verifier,
client secret, authorization header, or patient data.

---

## Security notes

- **Tokens are memory-only.** The access token is carried in the session, is never written
  to disk and is never logged. It stays in application memory after the Workbench closes so
  the Scripting Console can use it. **Log out** drops it immediately; exiting the application
  drops it otherwise.
- **No client secret.** Public clients have none, and none is used anywhere in the flow. The
  Workbench posts the authorization code plus the PKCE verifier and the exact redirect URI —
  that is all the token endpoint receives.
- **Loopback only.** The callback listener only ever binds `127.0.0.1`, including for a
  registered `localhost` redirect URI. A code can never be sent to a remote host.
- **Diagnostics are redacted before they leave the process.** The Diagnostics tab and every
  exported log file are run through redaction for bearer tokens, authorization codes,
  PKCE verifiers, client secrets, authorization headers, and patient identifiers. You can
  attach an export to a bug report without leaking anything.
- **Use HTTPS for production servers.** Loopback http is acceptable for local testing only.
- **No real credentials in the guide.** This document and the bundled user guide deliberately
  contain no client IDs, redirect URIs, or tokens. Fill in your own test values in the
  Workbench.

---

## Known limitations

- **Diagnostics are in-memory only.** The Diagnostics tab, export, and clear all apply to the
  current workbench session. Nothing survives a restart.
- **No `Accept` negotiation** beyond `application/json` for discovery.
- **A non-matching document issuer is reported but not resolved.** The Workbench does not
  guess which address is right; that is a deployment decision.
- **EHR launch is a client-side simulation only.** It does not integrate with a real EHR.
- **SMART writes need a writable configured server.** The console binds its write grant to the
  server selected in Tools > FHIR Servers, so an authorized SMART session alone cannot turn
  writes on; the write itself still goes only to the session's server.
- **Review Unknown does not reconcile a SMART write.** It resolves an entry against configured
  servers and a SMART entry names none, so a timed-out SMART write is reconciled by reading
  through the session.
- **Undo covers only the current console and session.** A SMART reversal needs the session that
  made the write, still active and still authorized for the same URL; logging out,
  re-authorizing for another server, or closing the console ends it.
- **Manual end-to-end verification** against a public SMART test server is recommended before
  production use; this guide contains no committed test credentials.
