# Phase 4 — SMART on FHIR Integration

## Goal
Expose the existing authorized SMART session and available launch context to scripts without implementing a second OAuth flow or exposing tokens.

## Scope
1. Inspect and reuse the existing SMART launch/session implementation.
2. Add a `smart` facade that reports session availability, safe context metadata, granted-scope metadata, and operations through the existing authorized client.
3. Expose patient/encounter context only when actually present and permitted by the current launch/session.
4. Route resource access through the existing authorized session/client and enforce the same central write policy as other FHIR APIs.
5. Do not expose access/refresh tokens, client secrets, authorization headers, raw token responses, or credential-store objects.
6. Do not request broader scopes or bypass normal authorization.
7. Distinguish SMART sessions from ordinary server connections.
8. Handle logout, expiry, revocation, server switching, and missing scopes gracefully.
9. Redact sensitive data in logs, exceptions, and reports.
10. Test using mocks and synthetic data, not real patient data.
11. Never auto-run scripts when SMART launch completes. Require explicit user action to start a script.

## Illustrative example (conceptual only)
```java
if (smart.isSessionActive()) {
    log.info("SMART session is active");
    var patientId = smart.launchPatientId();
    if (patientId != null) {
        var patient = smart.read("Patient", patientId);
        viewer.showResource(patient);
    }
}
```
Confirm actual signatures and context semantics against the existing implementation.

## Acceptance criteria
- Scripts can detect whether a SMART session is available.
- Authorized context/operations use the existing session and respect granted scopes.
- No secrets are exposed, including through debugging or `toString()` output.
- Patient context does not authorize arbitrary-patient access.
- Expiry/logout/scope restrictions are handled and tested.
- Write/destructive-operation policy applies equally to SMART-backed operations.
- Tests do not depend on real patient data.

## Out of scope
New OAuth implementation, token-management UI, auto-run behavior, and scope escalation.

## Deliverable
SMART facade, mock-based tests, safe examples, and updated status.
