# Phase 2 — FHIRViewer Scripting API

## Goal
Expose a small, documented set of FHIRViewer capabilities to scripts without exposing controllers or internals.

## Scope
1. Inspect/reuse existing FHIR clients/services, resource display, validation, FHIRPath, server configuration, plugin interfaces, and logging.
2. Design script-facing facades/adapters. Suggested APIs:
   - `fhir` — approved resource reads/searches; writes only with explicit policy/confirmation.
   - `viewer` — show a resource, show search results, refresh a supported view.
   - `validation` — validate a resource and return structured results.
   - `server` — inspect selected server identity/capabilities and invoke approved plugin abstractions.
   - `log` — info/warning/error output to the scripting console.
3. Inject these objects into JShell through a deliberate, tested mechanism.
4. Add built-in help and example scripts.
5. Prefer HAPI FHIR types or stable result objects over raw JSON strings.
6. Handle no active server, network/auth failures, invalid types/IDs, pagination, and server errors.
7. Keep network operations off the JavaFX thread and UI operations on the correct thread.
8. Make read-only behavior the default. Side-effecting operations require explicit permission and confirmation.
9. Do not automatically log full resources or sensitive content.
10. Record exact method signatures, return values, errors, side effects, and limitations as APIs are implemented; Phase 6 creates the complete user guide.

## Illustrative example
```java
var patient = fhir.read("Patient", "example-id");
if (patient != null) {
    viewer.showResource(patient);
    log.info("Patient loaded");
} else {
    log.warn("Patient was not found");
}
```
Names/signatures are illustrative; adapt them to the actual architecture.

## Acceptance criteria
- Scripts can call documented APIs.
- A read/search example works against a test server or mock.
- Validation results can be inspected.
- Scripts can request display without manipulating JavaFX controls.
- Errors are actionable; UI remains responsive.
- Write policy is enforced.
- Facade tests and examples exist.

## Out of scope
No full test framework, SMART context, or broad CRUD support unless a safe subset already exists.

## Deliverable
Script API, examples, tests, initial API notes, and updated status.
