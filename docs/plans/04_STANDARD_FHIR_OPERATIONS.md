# Phase 4 — Standard FHIR Operations

## Goal

Expose common FHIR REST operations through the plugin API using the REST infrastructure created in earlier phases.

## Prerequisite

Complete Phases 1–3.

## Tasks

Implement only operations appropriate to the current architecture.

Core operations should include, where supported:

### Server

- GET /metadata
- CapabilityStatement retrieval
- server status/basic connectivity

### Resource

- Read
- Create
- Update
- Patch
- Delete

### Search

- resource search
- query parameters
- search result Bundle handling

### Pagination

Understand FHIR Bundle links, especially:

- next
- previous
- first
- last where provided

Use server-provided URLs rather than constructing pagination URLs manually.

### Operations

Support standard FHIR operations if the existing plugin architecture has a suitable abstraction.

### Capability Awareness

The plugin/client should be able to indicate which operations the connected server supports.

Do not assume every server supports PATCH, history, batch, transactions, or conditional operations.

## API Design

Prefer high-level FHIR methods where appropriate, such as:

- readResource
- createResource
- updateResource
- deleteResource
- search
- getCapabilityStatement

Use existing HAPI FHIR types/parsers if already present.

Do not introduce a second FHIR model/parser.

## Error Handling

Handle and preserve:

- HTTP status
- OperationOutcome
- server diagnostics
- validation errors
- conflicts
- authentication failures

## Verification

Add tests for:

- read
- create
- update
- delete
- search
- pagination
- metadata
- OperationOutcome

Use a mock/local server for deterministic tests.

Compile and run all existing tests.

## Do Not Do Yet

Do not implement Smile-specific or Firely-specific endpoints.

Do not build the UI integration yet.
