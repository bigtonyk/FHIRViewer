# Phase 1 — Inspect Current Plugin Architecture

## Goal

Understand the current FHIRViewer plugin and UI architecture before changing it.

## Tasks

### 1. Find the Plugin API

Locate:

- FHIR server plugin interface(s)
- Abstract/base plugin classes
- Plugin discovery/registration
- Plugin lifecycle
- Existing server configuration
- Existing connection objects
- Existing server/client abstractions

### 2. Find Existing HTTP Code

Search for:

- HTTP clients
- REST calls
- URL/URI handling
- GET/POST/PUT/PATCH/DELETE
- Authorization
- Bearer tokens
- OAuth/SMART
- Basic authentication
- TLS/SSL
- timeouts

Do not create a new HTTP client if one already exists.

### 3. Find Existing FHIR Operations

Locate existing code for:

- reading resources
- writing resources
- searching
- CapabilityStatement/metadata
- Bundles
- pagination
- OperationOutcome
- JSON/XML parsing
- HAPI FHIR

### 4. Inspect the Viewer UI

Determine how JavaFX currently:

- connects to servers
- selects plugins
- displays server information
- displays resources
- performs background operations
- handles errors
- shows menus/buttons/context actions

Identify the current UI → service → plugin path.

### 5. Inspect Existing Plugins

Review every current FHIR server plugin.

Determine:

- What each plugin currently exposes
- How configuration works
- Whether any plugin already performs REST operations
- What common functionality can be moved/shared
- What is genuinely server-specific

## Deliverable

Create a short architecture assessment in the project documentation or an appropriate developer-notes location.

Include:

- Current Plugin API
- Current UI/plugin interaction
- Existing HTTP infrastructure
- Existing authentication infrastructure
- Existing FHIR infrastructure
- Existing reusable code
- Gaps that need to be filled
- Recommended extension points

## Important

Do NOT implement the REST architecture in this phase.

Only make minimal code changes if required to document or safely expose findings.

Compile and run existing tests before finishing.
