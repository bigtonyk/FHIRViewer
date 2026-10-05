# Phase 8 — Testing, Documentation, and Hardening

## Goal

Finish the REST/plugin architecture with tests, documentation, compatibility checks, and security review.

## Prerequisite

Complete Phases 1–7.

## 1. Regression Testing

Run:

- full compile
- existing unit tests
- existing integration tests
- plugin discovery tests
- UI tests available in the project

Verify existing FHIRViewer features still work.

## 2. REST Tests

Verify:

- GET
- POST
- PUT
- PATCH
- DELETE
- query parameters
- headers
- authentication
- timeout
- network failure
- HTTP failure
- OperationOutcome
- pagination

## 3. Plugin Tests

Verify:

- plugin discovery
- server configuration
- standard operations
- custom operations
- capability discovery
- status
- authentication

## 4. UI Tests

Verify:

- operation discovery
- operation execution
- parameter entry
- resource display
- Bundle display
- errors
- loading state
- background execution

Make sure network calls do not block JavaFX.

## 5. Security Review

Check:

- credential handling
- token handling
- TLS validation
- Authorization headers
- logging
- patient data logging
- custom URLs
- query parameters
- request headers
- request body handling

Do not disable TLS validation.

Do not log secrets.

## 6. Backward Compatibility

Verify existing plugins and configuration continue to work.

If interfaces changed, prefer:

- default methods
- adapters
- compatibility layers

over unnecessary breaking changes.

## 7. Developer Documentation

Document:

- Plugin API
- REST client
- authentication
- standard FHIR operations
- custom operations
- capability discovery
- UI operation discovery
- operation parameters
- result handling
- error handling
- example plugin

Include a small plugin example demonstrating:

1. Standard FHIR read/search.
2. A custom REST operation.
3. Exposing the operation to the Viewer UI.

## 8. Final Architecture Review

Confirm:

Viewer UI
→ application/service layer
→ Plugin API
→ standard/custom REST operation
→ authentication
→ HTTP transport
→ FHIR/vendor server

Confirm there is no vendor-specific branching in the generic UI/core.

## Final Deliverable

Provide a concise implementation summary containing:

- files/classes changed
- features implemented
- plugins updated
- tests run
- known limitations
- supported server versions
- future improvements
