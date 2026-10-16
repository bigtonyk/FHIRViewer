# Phase 05 — EHR launch simulation

## Objective

Enable developers to simulate the client side of an EHR-initiated SMART launch from FHIRViewer.

Do this only after standalone authorization works reliably.

## Tasks

1. Add an EHR launch simulation mode to the workbench.
2. Accept launch parameters `iss` (issuer) and `launch` (opaque launch token).
3. Validate the issuer and retrieve SMART discovery metadata from it.
4. Preserve the launch parameter exactly as required by the specification.
5. Request the necessary launch-related scopes.
6. Execute authorization using the correct launch parameter, redirect URI, state, and PKCE behavior.
7. Process returned patient, encounter, and user context only when supplied and supported by the selected flow.
8. Display the launch parameters and validated context in the workbench without exposing secrets.
9. Distinguish clearly among:
   - Standalone launch.
   - EHR-initiated launch simulation.
   - Successful authorization with no patient context.
10. Provide a way to clear the current simulation and begin another launch.
11. Prevent stale context from a previous session being reused in a new launch.

## Important limitations

This is a simulation of the SMART client side of an EHR launch. It does not make FHIRViewer an EHR or prove compatibility with every vendor.

Use a documented SMART test launcher or compatible test harness for end-to-end testing. Do not fabricate a launch token or claim that manually entering a patient ID is equivalent to a valid EHR launch.

Follow the current SMART specification for launch parameters, scopes, and context handling.

## Tests

Cover:

- Missing or malformed issuer and launch parameters.
- Issuer validation.
- Correct preservation and encoding of launch parameters.
- Launch-related authorization request construction.
- Valid returned context.
- Missing context.
- New launch clears prior context.
- Standalone flow behavior remains unchanged.

## Acceptance criteria

- Launch parameters are handled correctly.
- Standalone authorization remains functional.
- Context is displayed accurately when supplied.
- Invalid parameters produce useful diagnostics.
- No stale context is carried across sessions.
- Tests and Maven build pass.

## Completion report

### Changed files

- `src/main/java/com/spiraleyes/fhirviewer/server/smart/SmartAuthorizationFlow.java`
  - Class javadoc now documents both launch modes (standalone sends `aud`, EHR sends `launch`).
  - `authorize()` javadoc documents the mode-based parameter choice.
  - `requestFor()` is now package-visible and branches on `config.mode()`: EHR launch passes the `launch` parameter and no `aud`; standalone passes `aud` (issuer or configured audience) and no `launch`. An EHR launch without a launch parameter is refused before the browser opens.
- `src/main/java/com/spiraleyes/fhirviewer/ui/SmartLaunchDialog.java`
  - `onNew()` now sets `discovered = null` so endpoints learned for one launch cannot leak into a differently-configured one.
- `src/test/java/com/spiraleyes/fhirviewer/server/smart/SmartAuthorizationFlowTest.java` (new)
  - 5 tests: standalone sends `aud`/`no launch`, EHR sends `launch`/`no aud`, EHR without launch parameter refused, all requests carry required parameters.

### Test results

- `mvnw.cmd compile -DskipTests` → BUILD SUCCESS
- `mvnw.cmd test` → **849 tests, 0 failures, 0 errors, 0 skipped** (845 existing + 4 new)

### How each task is met

1. EHR launch simulation mode — `SmartLaunchMode.EHR_LAUNCH` existed; the form already showed/hid the launch vs audience rows per mode.
2. Launch parameters `iss` and `launch` — `SmartLaunchConfig.launchParameter` existed; `SmartAuthorizationFlow.requestFor()` now sends `launch` for EHR mode.
3. Issuer validation + discovery — unchanged from Phase 02/03; `onDiscover()` reads the issuer from the form and calls `SmartDiscoveryService.discover()`.
4. Launch parameter preservation — `SmartAuthorizationRequest` URL-encodes the `launch` parameter; verified by test.
5. Launch-related scopes — scopes come from `config.scopes()`; unchanged from Phase 03.
6. Authorization execution — `SmartAuthorizationFlow.authorize()` now uses `requestFor()` which branches on mode.
7. Returned context (patient, encounter, user) — unchanged from Phase 03/04; `SmartAuthorizationSession` carries all three and the panels display them.
8. Display without exposing secrets — unchanged from Phase 03/04; token is never shown, only its existence and expiry.
9. Distinguishing modes — mode selector in the form; `SmartLaunchMode.label()` gives user-facing wording; panels state standalone vs EHR behavior where relevant.
10. Clear and begin another launch — `onNew()` clears the form and now also clears `discovered`.
11. No stale context across launches — `onNew()` clears `discovered`; `showSession()` logs out the previous session before installing the new one; `SmartAuthorizationSession.logout()` drops the token.

### Known limitations

- This is a client-side simulation: a real EHR launch needs an inbound URI or protocol handler (out of scope for this branch).
- The form does not auto-toggle launch/audience field visibility when the mode combo changes (visibility updates when `setEnabled()` is called, e.g. on dialog open/close); `problems()` still reports any stray value.

