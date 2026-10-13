# Phase 06 — Diagnostics, testing, documentation, and release readiness

## Objective

Make the SMART Launch Workbench practical for development and reliable enough to ship as part of FHIRViewer.

## Diagnostics UI

Implement a structured event list for:

- Discovery start/completion.
- Authorization request initiated.
- Browser launch status.
- Callback received.
- Token exchange success/failure.
- Session creation and logout.
- FHIR request method and resource endpoint.
- HTTP status code and elapsed time.
- Patient/encounter context availability.
- Relevant validation errors.

Never log:

- Access tokens or refresh tokens.
- Authorization codes.
- PKCE verifiers.
- Client secrets.
- Raw authorization headers.
- Sensitive patient data by default.

Provide a diagnostic export with redaction applied before writing the file. Include a way to clear diagnostics for the current session. Ensure exported URLs and error bodies are reviewed for possible secrets or patient information.

## Automated test coverage

Use the project's existing test framework. Cover, as appropriate:

- Discovery metadata parsing and URL construction.
- Missing/malformed metadata.
- OAuth request construction.
- PKCE challenge generation.
- State and callback validation.
- Token response parsing and expiry handling.
- Issuer/audience/nonce validation.
- Session isolation.
- Authorization header handling.
- HTTP 400, 401, 403, 404, 429, and 5xx responses.
- Diagnostic redaction.
- UI state transitions where supported by the existing testing approach.
- Regression tests for existing FHIRViewer behavior.

Mock endpoints for deterministic automated tests. Unit tests must not depend on internet access or real patient data.

## Manual test checklist

Document steps to:

1. Start FHIRViewer.
2. Open SMART Launch Workbench.
3. Configure a SMART-enabled test server and registered public client.
4. Run discovery.
5. Complete standalone authorization.
6. Inspect granted scopes.
7. Retrieve a permitted FHIR resource.
8. Inspect patient/encounter context.
9. Exercise an authorization failure.
10. Exercise an expired token.
11. Clear the session.
12. Exercise an EHR launch simulation with a documented test harness.
13. Confirm existing FHIRViewer features still work.

## User documentation

Document:

- What SMART on FHIR is.
- Standalone versus EHR launch.
- How to register a test application.
- Client ID and redirect URI configuration.
- Scope behavior.
- How to configure a test server.
- Troubleshooting common failures.
- Security precautions for production servers.
- Supported flows and known limitations.

Include placeholders in sample configuration only. Do not commit real credentials or tokens.

## Packaging and compatibility

Verify:

- Maven build and automated tests pass.
- Java 21 compatibility.
- Supported Windows, macOS, and Linux packaging targets.
- Java module requirements for loopback callback server and browser integration.
- No dependency on Node.js or a separate web application.
- No new mandatory external service.
- Existing packaging and update mechanisms still work.

## Acceptance criteria

- The complete flow from configuration to authorized FHIR access can be tested.
- Manual checklist and user documentation are complete.
- Tests pass.
- Existing features are not regressed.
- Diagnostics do not expose tokens or patient data by default.
- Supported flows and limitations are documented.
- `IMPLEMENTATION-STATUS.md` reflects actual implementation and verification.

## Completion report

Summarize changed files, test commands/results, packaging checks, manual verification, and remaining issues. Update `IMPLEMENTATION-STATUS.md`. Stop after this phase.
