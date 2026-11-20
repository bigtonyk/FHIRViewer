# SMART on FHIR — Manual Test Checklist

These steps exercise the complete SMART Launch Workbench flow end to end. They assume a
SMART-enabled test server and a registered public client are available (see the User
Guide for registration details).

## Prerequisites

- FHIRViewer built and running (Java 21).
- A SMART-enabled FHIR test server with a registered public client.
- The client ID and redirect URI (loopback http) from the server registration.
- No real credentials or tokens are committed anywhere.

## Checklist

| # | Step | Expected result |
|---|------|-----------------|
| 1 | Start FHIRViewer. | Application opens; no errors. |
| 2 | Open **Tools > SMART Launch Workbench...**. | Workbench dialog opens; form empty; status says nothing has been discovered or authorized. |
| 3 | Configure a SMART-enabled test server: issuer, client ID, scopes, mode. | Fields accept input; Validate checks the form locally. |
| 4 | Run discovery (press **Discover**). | Capabilities panel fills with advertised endpoints; status names endpoint count; Authorize enables if usable. |
| 5 | Complete standalone authorization (press **Authorize**). | System browser opens to the server's authorization page; sign in and approve; callback returns; Session panel shows granted scopes, patient context, expiry; token never displayed. |
| 6 | Inspect granted scopes. | Session panel shows the scopes the server granted (may differ from requested). |
| 7 | Retrieve a permitted FHIR resource (press **Read patient context**). | If patient context was granted, the patient resource is read and shown; Open in viewer offers to display it. |
| 8 | Inspect patient/encounter context. | Session panel shows patient and encounter when granted; explains absence when not. |
| 9 | Exercise an authorization failure. | E.g. use wrong scopes or revoke consent; a clear failure message appears, no token leaked. |
| 10 | Exercise an expired token without a refresh token. | Authorize without `offline_access`/`online_access`; wait for expiry; Session panel says expired; reads refuse until re-authorized, and the message names the scopes that allow renewal. |
| 10a | Exercise renewal with a refresh token. | Authorize with `offline_access` (Smile CDR: tokens last about a minute); State line says the session renews itself; wait past expiry and read again; the read succeeds and Diagnostics shows "Session renewed", with no token in the text. |
| 10b | Federated login through Smile CDR. | Public client definition with a fixed loopback redirect URI entered in **Redirect URI**; Authorize goes Smile CDR → upstream provider sign-in → back; Session panel fills; ID token accepted (no "unexpected issuer"). |
| 10c | Provider selection and re-login. | With several providers, a `launch=<registrationId>-...` line in **Authorization parameters** skips Smile CDR's chooser when its interceptor reads it; after Log out, **Force re-login** makes the server ask for a sign-in again. Diagnostics name the extra parameters but show no values. |
| 11 | Clear the session (logout). | Session panel resets to "No session exists yet"; token dropped from memory. |
| 12 | Exercise an EHR launch simulation. | Switch mode to EHR launch; enter `iss` and `launch`; run discovery and authorize; context shown when supplied. |
| 13 | Confirm existing FHIRViewer features still work. | Open a normal FHIR server, search, view a resource — unaffected by SMART changes. |

## Diagnostics checks

| # | Step | Expected result |
|---|------|-----------------|
| D1 | Open the Diagnostics tab during a launch. | Events appear for discovery, authorization, callback, token exchange, session, and FHIR access. |
| D2 | Press **Export diagnostics**. | A redacted text file is written to `~/.fhirviewer/`; no token, code, verifier, secret, or patient data appears. |
| D3 | Press **Clear diagnostics**. | The event list empties; count resets to zero. |

## Regression checks

| # | Step | Expected result |
|---|------|-----------------|
| R1 | Build and run the full automated test suite. | All tests pass; 0 failures. |
| R2 | Open a non-SMART FHIR server and run discovery. | A clear "not SMART" diagnosis appears; no crash. |
| R3 | Open an existing saved server and read a resource. | Existing FHIRViewer behavior unchanged. |
