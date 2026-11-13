# Phase 04 — Authorized FHIR access and SMART context viewer

## Objective

Connect an authorized SMART session to FHIRViewer's existing HAPI FHIR resource browsing and display features.

## Before coding

Inspect the existing HAPI FHIR client factory, interceptors, server configuration, and plugin contracts. Reuse existing code wherever practical. Do not create a second unrelated FHIR client stack.

## Tasks

1. Define a session model containing:
   - FHIR base URL.
   - Authorization/session state.
   - Access token and expiry.
   - Granted scopes.
   - Patient context, when supplied.
   - Encounter context, when supplied.
   - Other validated launch context supported by the selected flow.
2. Create a reusable service that supplies bearer authorization to requests belonging to the authorized session.
3. Integrate with the existing HAPI FHIR client construction mechanism.
4. Prefer a per-session client or request interceptor over global authentication state.
5. Ensure tokens are sent only to the intended FHIR resource server.
6. Do not attach SMART tokens to unrelated requests or administrative/plugin endpoints.
7. Implement a minimal authorized FHIR operation, such as retrieving the authorized patient context when available and permitted.
8. Reuse the existing resource tree and pretty-view display for returned resources.
9. Display actual granted scopes rather than only requested scopes.
10. Explain that patient context may legitimately be absent even when authorization succeeds.

## Scope and authorization behavior

- Do not assume successful login grants unrestricted access.
- Do not treat patient context itself as an authorization grant.
- Respect the scopes and server permissions that actually apply.
- Handle HTTP 401 and 403 clearly.
- Do not automatically retry indefinitely on authorization failures.

## Session isolation and security

- Never store the current user's token in a global singleton shared by all server connections.
- Never send a token to another configured server.
- Clear session state on logout/disconnect.
- Prevent concurrent sessions from overwriting one another's tokens or context.
- Redact authorization headers and token values from logs and diagnostics.

## Tests

Cover:

- Token attachment to the intended HAPI request.
- Token not sent to another server or unrelated endpoint.
- Multiple-session isolation.
- Logout clears local session data.
- HTTP 401/403 behavior.
- Authorized resource response integration.
- Existing unauthenticated connections remain functional.

Use mocks or a controlled test server for automated tests.

## Acceptance criteria

- An authorized FHIR request succeeds against a compatible server.
- Returned resources appear in the existing viewer.
- Expired/invalid token errors are clear.
- Existing unauthenticated connections and server plugins still work.
- Session isolation is verified by tests.
- Tests and Maven build pass.

## Completion report

Record changed files, tests/results, manual verification, and known limitations. Update `IMPLEMENTATION-STATUS.md`. Stop after this phase.
