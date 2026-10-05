# Phase 6 — Viewer UI Integration

## Goal

Make the REST functionality directly usable from the FHIRViewer JavaFX UI.

## Prerequisite

Complete Phases 1–5.

## Critical Rule

The JavaFX UI must NOT directly construct vendor-specific REST requests.

Use:

Viewer UI
→ application/service layer
→ active FHIR server plugin
→ REST API

## Tasks

### 1. Inspect Existing UI Architecture

Reuse existing:

- controllers
- services
- JavaFX task/background infrastructure
- resource editors
- JSON/XML viewers
- menus/toolbars/context menus

Do not duplicate these systems.

### 2. Server Connection UI

Expose appropriate actions such as:

- Connect
- Disconnect
- Test Connection
- Server Status
- Capabilities

Use the active plugin.

### 3. Resource Actions

Where appropriate, allow the user to:

- Read/refresh
- Create
- Update
- Patch
- Delete

When viewing a resource, integrate actions into the existing resource UI rather than creating a disconnected screen.

### 4. Search

Provide UI access to FHIR server search.

Support:

- resource type
- search parameters
- execute
- result Bundle
- pagination

Use existing resource/tree display functionality for results.

### 5. Custom Operations

The UI should discover operations from:

getAvailableOperations()

Do not hard-code Smile or Firely menu items.

Display custom operations under an appropriate existing Server/Plugin area.

### 6. Operation Parameters

Create a generic UI mechanism for operation parameters.

For request bodies, reuse the existing JSON/XML/FHIR editor where possible.

### 7. Results

Display:

- FHIR resources in the existing resource viewer
- Bundles in the existing resource/tree UI
- OperationOutcome as a useful error/diagnostic display
- JSON/XML using existing viewers
- text responses appropriately
- HTTP status/duration when useful

### 8. Asynchronous Execution

All network operations must run off the JavaFX UI thread.

Provide:

- loading state
- success
- failure
- cancellation where practical

### 9. Error Handling

Display useful information for:

- 401
- 403
- 404
- 409
- 422
- 429
- 500+
- timeout
- network error
- TLS error
- OperationOutcome

Never display credentials or tokens.

## Verification

Manually exercise the UI against a test/mock server.

Verify:

- UI remains responsive
- supported operations appear
- unsupported operations are absent/disabled
- parameters work
- results display correctly
- errors are understandable

Compile and run all tests.

## Important

Keep the UI generic.

A future plugin should be able to expose a new operation and have it become available through the generic operation mechanism without modifying the core UI.
