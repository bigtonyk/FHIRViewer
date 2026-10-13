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

Document changed files, test results, test harness used, and known vendor-specific limitations. Update `IMPLEMENTATION-STATUS.md`. Stop after this phase.
