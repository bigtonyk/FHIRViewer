# Phase 01 — SMART Launch Workbench UI

## Objective

Add a native JavaFX workbench for configuring and inspecting SMART launches. This phase creates the UI and configuration model; real discovery and authorization are implemented later.

## Before coding

Read the Phase 00 assessment and inspect the relevant classes. Follow existing FHIRViewer UI and persistence conventions. Do not invent parallel navigation or configuration systems if reusable ones exist.

## UI requirements

Add a navigation entry named **SMART Launch Workbench** or a consistent equivalent.

### A. Launch configuration

Provide fields for:

- Configuration name.
- FHIR server base URL (`iss`).
- Launch mode: Standalone or EHR launch simulation.
- OAuth client ID.
- Registered redirect URI, where applicable.
- Requested scopes.
- Optional EHR launch parameter.
- Optional audience or other launch parameters only when appropriate for the selected SMART flow.

Provide controls to create, edit, delete, select, and validate configurations. Add actions for discovery and authorization, but keep them disabled or clearly unavailable until their respective phases are implemented. Do not fake successful operations.

Do not provide a client-secret field for the native public desktop client.

### B. Server capabilities

Reserve a panel for discovery status, issuer, authorization/token endpoints, advertised capabilities, supported scopes, supported PKCE methods, and validation errors.

### C. Session and patient context

Reserve a panel for authorization state, granted scopes, patient ID, encounter ID, user identity when available and authorized, and token expiry when known.

Never display the raw access token in the normal UI.

### D. Diagnostics

Provide a panel for structured diagnostic events and safe error messages.

### E. Resource access

Provide a panel or tab that can later reuse FHIRViewer's existing resource tree and pretty-view display.

## Implementation requirements

- Use JavaFX and the existing UI conventions.
- Keep networking and authorization logic out of controllers.
- Use existing UI-state patterns or JavaFX properties as appropriate.
- Never block the JavaFX application thread.
- Use background tasks or the existing asynchronous execution pattern for I/O.
- Return UI updates to the JavaFX thread.
- Handle errors and cancellation.
- Keep the interface usable at typical desktop window sizes.
- Persist configuration only through the existing preferences/configuration approach, if appropriate.

## Acceptance criteria

- The workbench opens and closes correctly.
- Navigation and styling fit the existing application.
- Configuration CRUD and input validation work.
- No fake authorization or network-success state is shown.
- Existing application features continue to work.
- Tests and Maven build pass.

## Completion report

Record changed files, tests executed and results, remaining limitations, and manual verification required. Update `IMPLEMENTATION-STATUS.md`. Stop after this phase.
