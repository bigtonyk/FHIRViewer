# Phase 7 — Smile CDR and Firely Server Plugins

## Goal

Apply the completed REST/plugin/UI architecture to the actual Smile CDR and Firely Server plugins.

## Prerequisite

Complete Phases 1–6.

## Important

Do not guess vendor endpoints.

Verify every vendor-specific endpoint against documentation for the supported server version.

## Smile CDR

Inspect the existing Smile plugin and add:

- standard FHIR REST operations
- Smile-specific REST operations where useful
- server status where supported
- capability information
- custom operation metadata
- UI accessibility through the generic operation system

Keep Smile-specific implementation inside the Smile plugin.

Potential areas to investigate:

- server status
- tenant information
- administration APIs
- Smile-specific operations
- configuration/status APIs

Only implement endpoints that are actually available and appropriate for the supported Smile version.

## Firely Server

Inspect the existing Firely plugin and add:

- standard FHIR REST operations
- Firely-specific REST operations where useful
- server status where supported
- capability information
- custom operation metadata
- UI accessibility through the generic operation system

Do not assume Firely endpoints are equivalent to Smile endpoints.

## Verification

For each plugin verify:

1. Plugin loads.
2. Server connection works.
3. Authentication works.
4. Standard FHIR operations work.
5. Custom operations are discoverable.
6. Custom operations execute.
7. Results reach the Viewer UI.
8. Errors are displayed correctly.
9. No vendor-specific code was added to the generic UI/core.

Use test instances or mock endpoints where available.

Document the supported vendor operations and server-version assumptions.
