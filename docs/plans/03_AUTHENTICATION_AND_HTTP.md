# Phase 3 — Authentication and HTTP Transport

## Goal

Connect the REST API to the project's actual HTTP and authentication infrastructure.

## Prerequisite

Complete Phase 2.

## Tasks

### 1. HTTP Transport

Use the existing HTTP implementation if available.

Otherwise use the standard Java HTTP client supported by the project.

Support:

- HTTP/HTTPS
- headers
- request bodies
- response bodies
- timeouts
- appropriate redirects
- TLS validation
- useful status/error handling

### 2. Authentication Abstraction

Authentication must be separate from REST operation code.

Design an authentication provider/strategy compatible with the current application.

It should be capable of supporting the mechanisms actually needed by the project, potentially:

- no authentication
- Basic authentication
- Bearer token
- OAuth 2.0 / SMART on FHIR
- vendor-specific authentication

Do not implement unnecessary mechanisms just for completeness.

### 3. Credential Security

Never:

- hard-code credentials
- log passwords
- log bearer tokens
- log Authorization headers
- disable TLS validation

Reuse existing secure configuration/storage mechanisms.

### 4. Server Connection

Ensure the REST client can operate against the active server configuration.

Avoid creating a second connection/configuration system.

### 5. Testability

Make the HTTP layer replaceable/mockable so unit tests do not require an external server.

## Verification

Test:

- successful request
- query parameters
- headers
- authentication
- timeout
- HTTP error
- network error
- malformed response

Compile and run the full existing test suite.

## Do Not Do Yet

Do not add Smile or Firely custom APIs.

Do not add substantial UI work.
