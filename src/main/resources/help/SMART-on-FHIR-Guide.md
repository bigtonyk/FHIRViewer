# SMART on FHIR — Launch Guide

How to add a SMART-enabled FHIR server to FHIRViewer, discover what it advertises, sign in
through the system browser, and read resources you are entitled to. The SMART Launch Workbench
lives in **Tools → SMART Launch Workbench...** and is described in the User Guide's Part B
for the server-features side; this guide is the on-ramp.

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
10. [Log out and start again](#log-out-and-start-again)
11. [Troubleshooting](#troubleshooting)
12. [Security notes](#security-notes)
13. [Known limitations](#known-limitations)

---

## What SMART on FHIR adds to FHIRViewer

The SMART Launch Workbench is a separate screen from the normal server workflow. It does not
replace **File → Open from FHIR Server...** or the server plugins. It adds:

- **Discovery** of a server's SMART metadata from `/.well-known/smart-configuration`.
- **Authorization Code with PKCE S256** against a public (client-side) client, with no client
  secret and no saved credentials.
- **Standalone launch** and **EHR launch simulation** modes.
- **Authorized reads** of the patient context the token grants, through the existing viewer.
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
3. **Set the redirect URI.** The Workbench communicates over a loopback address chosen at
   runtime, but the **registered redirect URI must be loopback http** — for example
   `http://127.0.0.1:54321/callback`. The exact port does not matter: the Workbench picks a
   free local port and the server must accept the pattern.
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
| Scopes | Space-separated, e.g. `launch/patient patient/*.read` |
| Mode | **Standalone** or **EHR launch** |

3. Fill the **Launch** field only in EHR mode — paste the opaque launch token.
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


## Inspect what you were granted

The **Session** panel shows what the server actually gave you. It never shows the token
itself:

| Field | Meaning |
|---|---|
| State | Whether the session is active or has been logged out |
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

## Log out and start again

1. Press **Clear session** (logout) in the workbench. The Session panel resets to
   *"No session exists yet."* and the Resource access panel disables its read button.
2. The token is dropped from memory and is never written to disk, so there is nothing to
   find in the filesystem. Closing the dialog also drops the session.
3. To start again, press **New** or **Open** to load a saved configuration, then go through
   Discover and Authorize again. The previous session cannot leak into a new one: logout
   clears the old token, and the session field is set to the new session.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Discover says "not SMART" | The server does not publish a SMART configuration document | Check the base URL; confirm SMART is enabled on the server |
| Authorization page never opens | Browser unavailable or redirect URI mismatch | Confirm a browser is installed; the registered redirect URI must be loopback http |
| Authorization fails with `invalid_grant` | The authorization code expired or was already used | Press Authorize again and sign in promptly |
| "The access token has expired" | The token's stated lifetime passed | Press Authorize again for a new session |
| Read fails with `401` or `403` | The token was rejected or lacks the needed scope | Re-authorize with the scopes the resource requires |
| Read returns no patient | The launch granted no patient context | Normal for identity-scoped launches; request `launch/patient` if you need one |
| Session panel is always empty | Authorization succeeded but granted no scopes | Check that the server actually granted what you requested |
| Discovery works but Authorize stays disabled | The discovered document had problems | Open the Capabilities panel and read the problem list |

None of these messages ever contains your access token, authorization code, PKCE verifier,
client secret, authorization header, or patient data.

---

## Security notes

- **Tokens are memory-only.** The access token is carried in the session and dropped on
  logout or dialog close. It is never written to disk and never logged.
- **No client secret.** Public clients have none, and none is used anywhere in the flow. The
  Workbench posts the authorization code plus the PKCE verifier and the exact redirect URI —
  that is all the token endpoint receives.
- **Loopback only.** The authorization redirect is always `127.0.0.1`. A code can never be
  sent to a remote host.
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
- **No refresh-token use.** If the server issues a refresh token, the grant records it but the
  Workbench does not use it to obtain a new access token.
- **No `Accept` negotiation** beyond `application/json` for discovery.
- **A non-matching document issuer is reported but not resolved.** The Workbench does not
  guess which address is right; that is a deployment decision.
- **EHR launch is a client-side simulation only.** It does not integrate with a real EHR.
- **Manual end-to-end verification** against a public SMART test server is recommended before
  production use; this guide contains no committed test credentials.
