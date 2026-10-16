# Phase 4 — SMART on FHIR Integration

## Goal
Allow scripts to use the existing authorized SMART on FHIR session through a narrow API without exposing OAuth credentials.

## Prerequisites
Phases 1–3 are complete and FHIRViewer's SMART launch/authentication flow exists and is tested. If SMART is not implemented, document the dependency and defer this phase rather than creating a parallel OAuth implementation.

## Scope
1. Inspect actual SMART session handling and granted scopes.
2. Add a `smart` facade only when an active SMART session exists.
3. Expose safe methods: active-session status, granted scopes, available patient/encounter launch context, and approved reads/searches through the existing authorized client.
4. Never expose access/refresh tokens, authorization headers, client secrets, raw token responses, or credential-store objects.
5. Do not request broader scopes or bypass the normal authorization flow.
6. Distinguish SMART sessions from normal server connections.
7. Handle logout, expiry, revocation, and server switching gracefully.
8. Redact sensitive data in logs, exceptions, and reports.
9. Test using mocks, not real patient data.
10. Provide synthetic/test-data examples and document required scopes.

## Illustrative example
```java
if (smart.isSessionActive()) {
    log.info("SMART session is active");
    log.info("Granted scopes: " + smart.grantedScopes());

    var patientId = smart.launchPatientId();
    if (patientId != null) {
        var patient = smart.read("Patient", patientId);
        viewer.showResource(patient);
    }
} else {
    log.warn("No active SMART session");
}
```
Confirm signatures and context semantics against the existing SMART implementation.

## Security requirements
- No raw token access, including via helper objects/debug output.
- Patient launch context does not authorize arbitrary-patient access.
- Effective script permissions must not exceed session authorization.
- Do not auto-run scripts after SMART launch.
- Do not log full resources or patient identifiers by default.

## Acceptance criteria
- Scripts can detect whether a SMART session is available.
- Authorized context/operations use the existing session/client.
- No secrets are exposed.
- Scope/context restrictions and expiry/logout are tested.
- Tests do not depend on real patient data.

## Out of scope
No new OAuth implementation, token-management UI, auto-run behavior, or scope escalation.
