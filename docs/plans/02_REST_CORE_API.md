# Phase 2 — REST Core API

## Goal

Create the reusable REST abstraction that server plugins can use to communicate with FHIR servers.

## Prerequisite

Complete Phase 1 first.

Use the actual architecture discovered there. Do not blindly create the example classes below if equivalent classes already exist.

## Tasks

### 1. Define REST Request/Response Models

If missing, create suitable models for:

Request:

- HTTP method
- relative/absolute path as appropriate
- query parameters
- headers
- body
- content type
- accept type

Response:

- status code
- headers
- body
- success/failure
- parsed OperationOutcome when applicable

### 2. Define REST Client Abstraction

Provide an abstraction that can support:

- GET
- POST
- PUT
- PATCH
- DELETE

It must support query parameters, headers, request bodies, response status, and response data.

### 3. Keep It UI-Neutral

The REST layer must not depend on JavaFX.

It should be usable by:

- plugins
- application services
- UI-facing services
- tests

### 4. Preserve Existing Architecture

If the project already has a server client or HTTP abstraction, extend it instead of introducing a competing API.

### 5. Error Handling

Create a consistent representation for:

- HTTP errors
- network failures
- timeouts
- malformed responses
- FHIR OperationOutcome

Do not lose useful server diagnostic information.

## Verification

- Compile.
- Run existing tests.
- Add focused unit tests for request/response behavior.
- Verify no UI thread is used by the transport itself.

## Do Not Do Yet

Do not implement Smile-specific endpoints.

Do not implement Firely-specific endpoints.

Do not build the new UI.

Do not redesign authentication unless the current architecture requires a small extension point.
