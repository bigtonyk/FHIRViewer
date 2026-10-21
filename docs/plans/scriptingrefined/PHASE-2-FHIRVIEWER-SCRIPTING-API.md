# Phase 2 — FHIRViewer Scripting API

## Goal
Expose a small, documented, stable API for useful interactive troubleshooting without exposing controllers or internal application objects. Lay the API foundation for saved workflows in later phases.

## Repository inspection first
Identify existing FHIR clients/services, resource display, validation, FHIRPath, server configuration, plugin interfaces, logging, SMART session model, and threading conventions. Reuse these services; adapt conceptual names below to the actual codebase.

## Scope
1. Define facade/adapters with explicit types, errors, threading, and side-effect semantics.
2. Expose an initial API surface:
   - `fhir` — read/search and supported create/update/patch operations; destructive operations routed through the central confirmation policy.
   - `server` — selected server identity, safe connection metadata, capabilities, and supported operations.
   - `viewer` — show a resource/result set and access supported selected-resource context without exposing JavaFX controllers.
   - `validation` — call existing validation services and return structured issues.
   - `log` — info/warn/error/progress output with sensitive-data redaction.
3. Prefer existing HAPI FHIR types and existing application service interfaces.
4. Provide useful default imports and a discoverable in-app API help surface if consistent with current UI.
5. Support interactive exploration of the selected server and selected resource.
6. Return structured outcomes for network operations, including status, resource/result, and sanitized errors where possible.
7. Respect pagination, timeouts, server capabilities, auth, and rate limits.
8. Add central write policy checks. Read/search do not require confirmation. Explicit create/update/patch are allowed when authorized. DELETE, bulk destructive actions, and bundles containing destructive operations require confirmation immediately before execution; cancellation must prevent the request from being sent.
9. Ensure script calls never block JavaFX UI thread; dispatch viewer actions safely to the JavaFX Application Thread.
10. Add tests for no selected server, auth errors, unsupported operations, UI-thread behavior, write confirmation, cancellation, and redaction.

## Illustrative example (conceptual only)
```java
var patient = fhir.read("Patient", "example-id");
validation.validate(patient).issues().forEach(log::info);
viewer.showResource(patient);
```
Replace these method names with actual implemented signatures. Do not add APIs solely to make this example compile if they do not fit the existing architecture.

## Acceptance criteria
- Scripts can access selected server metadata and use approved FHIR operations.
- Validation results can be inspected programmatically.
- Viewer operations work without direct controller access.
- Writes go through a single policy service; destructive confirmation is enforced and cancellation sends no request.
- Credentials, tokens, and full patient resources are not logged by default.
- UI remains responsive and existing app behavior is preserved.
- API signatures and tests are recorded for Phase 6 documentation.

## Out of scope
Test-suite framework, SMART context facade, batch orchestration, scheduling, and plugin-specific scripting APIs beyond existing reusable service access.

## Deliverable
Initial scripting API, adapters, focused tests, example script, and updated implementation status.
