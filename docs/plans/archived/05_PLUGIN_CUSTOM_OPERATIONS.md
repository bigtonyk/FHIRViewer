# Phase 5 — Plugin Custom Operations

## Goal

Allow a server plugin to expose vendor-specific REST operations without adding vendor-specific code to FHIRViewer core.

## Prerequisite

Complete Phases 1–4.

## Core Rule

The core application must never need code such as:

if serverType == SMILE

or:

if plugin instanceof SmilePlugin

Vendor-specific behavior belongs inside the plugin.

## Tasks

### 1. Define ServerOperation

If missing, create a model describing an operation, including as appropriate:

- stable operation ID
- display name
- description
- category
- HTTP method
- path
- parameters
- request body requirements
- expected response
- required capability/authentication

### 2. Plugin Discovery

The plugin must be able to expose something equivalent to:

getAvailableOperations()

The exact method should fit the existing Plugin API.

### 3. Operation Execution

Provide a common mechanism to execute a discovered operation.

The core does not need to know the endpoint's vendor meaning.

### 4. Parameters

Support operations that require:

- path parameters
- query parameters
- headers when appropriate
- request body

Do not allow arbitrary unsafe header manipulation without considering security.

### 5. Results

Return structured REST results so the caller can handle:

- FHIR resources
- Bundles
- OperationOutcome
- JSON
- XML
- text
- HTTP metadata

## Verification

Create a small test/mock plugin with at least:

- one standard FHIR operation
- one custom REST operation

Verify the custom operation can be discovered and executed without changing FHIRViewer core.

## Do Not Yet

Do not implement actual Smile/Firely endpoints unless required to prove the abstraction.

Do not build the final UI.
