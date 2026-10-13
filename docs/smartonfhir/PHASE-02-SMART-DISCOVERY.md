# Phase 02 — SMART configuration discovery

## Objective

Implement SMART server discovery and display the server's advertised capabilities in the workbench.

## Before coding

Read Phase 00 and Phase 01 completion notes. Inspect the UI state model and existing HTTP/JSON utilities. Implement only discovery; do not add OAuth authorization yet.

## Tasks

1. Accept and safely normalize a FHIR base URL without stripping legitimate base-path segments.
2. Construct the SMART discovery URL according to the selected, current SMART App Launch specification.
3. Retrieve SMART configuration with Java `HttpClient`.
4. Parse JSON using Jackson.
5. Define typed Java models for relevant fields, including:
   - `issuer`
   - `authorization_endpoint`
   - `token_endpoint`
   - `jwks_uri`, when provided
   - `capabilities`
   - `scopes_supported`
   - `code_challenge_methods_supported`
6. Preserve unknown fields where useful for forward compatibility and diagnostics.
7. Display discovered metadata and capabilities in the UI.
8. Validate required metadata and report missing or malformed values.
9. Apply connection and request timeouts and reasonable response-size limits.
10. Handle connection errors, timeouts, non-success status codes, malformed JSON, and servers without SMART support.
11. Validate discovered endpoint URLs. Prevent redirects or endpoint changes from silently moving sensitive requests to untrusted hosts.
12. Do not assume a FHIR base URL is automatically the authorization issuer.

Consult the current SMART specification for exact discovery URL construction and metadata requirements. Do not hard-code assumptions that only work for one server.

## Security requirements

- No tokens are sent in this phase.
- Never disable TLS certificate validation.
- Do not silently downgrade HTTPS to HTTP.
- If local insecure testing is supported, make the exception explicit and restrict it to loopback development endpoints.
- Avoid leaking URL query parameters or other sensitive values into logs.

## Tests

Add deterministic tests for:

- URL normalization and discovery URL construction.
- Valid metadata parsing.
- Missing required fields.
- Malformed JSON.
- HTTP error responses.
- Timeouts and connection failures.
- Endpoint URL validation.

Use mocked HTTP responses; unit tests must not require internet access.

## Acceptance criteria

- Discovery works against a compatible SMART-enabled test server.
- The UI displays metadata accurately.
- A non-SMART server produces a useful diagnostic without crashing.
- Network operations run off the JavaFX application thread.
- Existing features remain functional.
- Tests and Maven build pass.

## Completion report

Document changed files, tests and results, supported metadata, and known limitations. Update `IMPLEMENTATION-STATUS.md`. Stop after this phase.
