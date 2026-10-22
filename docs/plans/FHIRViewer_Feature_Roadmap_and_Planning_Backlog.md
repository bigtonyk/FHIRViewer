# FHIRViewer Feature Roadmap and Planning Backlog

*Candidate features for future planning and phased implementation*

## Purpose

This backlog captures ten candidate features that can be planned individually later. It is not a detailed implementation specification. Before planning any feature, inspect the repository, identify existing capabilities, and preserve working code.

## Existing product considerations

- **REST Workbench exists.** Reuse its underlying request, server configuration, authentication, and response services where appropriate; do not duplicate its manual request UI.
- **SMART on FHIR work exists.** Extend existing launch/authentication behavior rather than building a parallel OAuth implementation.
- **Server plugins exist or are in progress.** Inspect the real plugin API and SmileCDR/Firely implementations before specifying vendor-specific operations.
- **Java 21** is the intended build target.
- **JShell scripting is in progress.** Testing, batch workflows, transformations, and plugin operations should integrate with its API.
- JShell is not a security sandbox; imported scripts should be treated as executable code.

## Suggested priority overview

| Feature | Priority | Main value |
|---|---|---|
| FHIR Resource Relationship Explorer | High | Navigate between resources through their references instead of manually following reference strings. |
| FHIR Test Runner and Regression Suites | High | Use scripts and assertions to run repeatable checks against FHIR servers and integration behavior. |
| FHIR Data Diff and Version Comparison | High | Make differences between resources or versions understandable at the FHIR field level. |
| Terminology and ValueSet Explorer | High | Help users understand codes, code systems, ValueSets, and terminology validation. |
| Validation Workbench and Reports | High | Make IG/profile validation easier to configure, understand, repeat, and report. |
| Server Capability and Interoperability Dashboard | Medium-High | Show what a configured FHIR server advertises and what has actually been tested. |
| Resource Mapping and Transformation Workbench | Medium-High | Preview and validate transformations between resource structures before writing data. |
| Session and Workspace Management | Medium | Save and restore a useful working context between FHIRViewer sessions. |
| Plugin SDK and Vendor Operation Catalog | Medium | Make server plugin capabilities discoverable and extensible without coupling core FHIRViewer to vendor internals. |
| FHIR Sample Library and Scenario Builder | Medium | Provide safe reusable sample data for demonstrations, validation, and repeatable integration tests. |
| SMART on FHIR Launch and Context Inspector | Medium | Make it easier to diagnose SMART launch context and authorization problems while reusing the existing SMART implementation. |

## Feature briefs

### 1. FHIR Resource Relationship Explorer

**Priority:** High

Navigate between resources through their references instead of manually following reference strings.

**Scope ideas**

- Show inbound/outbound references, resource type and ID, display text, and resolved/unresolved status
- Support common Patient, Encounter, Observation, Condition, ServiceRequest, and DiagnosticReport relationships
- Handle contained, relative, absolute, and version-specific references where feasible
- Reuse existing server/resource services.

**MVP and later extensions:** MVP: a resource panel listing references and allowing navigation to related resources. Later: graph view, integrity checks, cycle detection, and reports.

**Acceptance direction:** A user can identify related resources and unresolved references without the UI hanging on large graphs.

### 2. FHIR Test Runner and Regression Suites

**Priority:** High

Use scripts and assertions to run repeatable checks against FHIR servers and integration behavior.

**Scope ideas**

- Build on JShell scripting rather than a disconnected test framework
- Support named checks, assertions, expected HTTP statuses, resource-type checks, OperationOutcome inspection, pass/fail/skip, and readable reports
- Clearly separate read-only checks from tests that write data.

**MVP and later extensions:** MVP: run a saved script in test mode and show assertion results. Later: reusable suites, fixtures, CI-friendly results, and optional scheduled runs.

**Acceptance direction:** The same suite can be rerun against a test server with consistent results and no silent destructive actions.

### 3. FHIR Data Diff and Version Comparison

**Priority:** High

Make differences between resources or versions understandable at the FHIR field level.

**Scope ideas**

- Compare parsed resources side by side; identify added, removed, and changed values, references, arrays, and metadata
- Offer raw JSON/XML comparison as well as resource-aware comparison
- Compare local content with server content or versions where history is supported
- Define handling of ordering, nulls, extensions, narrative, and server-managed metadata.

**MVP and later extensions:** MVP: structured field-level diff for two parsed resources. Later: history comparison, batch comparison, report export, and test assertions.

**Acceptance direction:** Users can distinguish meaningful content changes from formatting-only differences.

### 4. Terminology and ValueSet Explorer

**Priority:** High

Help users understand codes, code systems, ValueSets, and terminology validation.

**Scope ideas**

- Inspect CodeSystem, ValueSet, and ConceptMap resources
- Display system, code, display, and version
- Support lookup, expansion, or membership checks only where the configured source supports them
- Explain the difference between finding a code and proving membership in a specific ValueSet
- Report unavailable terminology services clearly.

**MVP and later extensions:** MVP: inspect terminology resources and perform a supported code lookup or ValueSet membership check. Later: ConceptMap exploration, cached expansions, and script integration.

**Acceptance direction:** The result identifies its terminology source and distinguishes invalid, valid, and unable-to-determine outcomes.

### 5. Validation Workbench and Reports

**Priority:** High

Make IG/profile validation easier to configure, understand, repeat, and report.

**Scope ideas**

- Extend existing validation and IG-loading functionality, not a duplicate engine
- Group findings by severity, resource, profile, and issue location
- Help identify missing dependencies, unresolved profiles, and package/version mismatches
- Support single-resource and batch reports
- Validation failures should not silently discard local work.

**MVP and later extensions:** MVP: clearer validation results and exportable report for a resource validated against loaded IGs. Later: batch validation, configuration profiles, and IG-version comparisons.

**Acceptance direction:** Reports state what was validated, against which profile/IG version, where it failed, and whether dependencies were unavailable.

### 6. Server Capability and Interoperability Dashboard

**Priority:** Medium-High

Show what a configured FHIR server advertises and what has actually been tested.

**Scope ideas**

- Fetch and summarize CapabilityStatement data: FHIR release, resource types, interactions, search parameters, formats, and declared operations
- Optionally run safe probes
- Clearly distinguish advertised capability from tested behavior
- Include vendor/plugin capabilities only when exposed by the installed plugin
- Reuse REST Workbench request services.

**MVP and later extensions:** MVP: fetch and summarize a server CapabilityStatement. Later: compare servers, run safe conformance probes, and generate tests from capabilities.

**Acceptance direction:** Users can tell declared support from observed results and see when metadata is unavailable.

### 7. Resource Mapping and Transformation Workbench

**Priority:** Medium-High

Preview and validate transformations between resource structures before writing data.

**Scope ideas**

- Map selected fields/references between FHIR resources or from external JSON
- Preview transformed output, validate it, and compare it with the source
- Consider FHIRPath or another documented transformation approach, but start small
- Integrate with scripting for repeatable and batch transformations
- Default to dry-run for workflows that could write to a server.

**MVP and later extensions:** MVP: local source-to-target transformation with preview, validation, and diff. Later: reusable mappings, batch processing, terminology mapping, and plugin operations.

**Acceptance direction:** A user can inspect and validate transformed output without modifying remote data unless explicitly confirmed.

### 8. Session and Workspace Management

**Priority:** Medium

Save and restore a useful working context between FHIRViewer sessions.

**Scope ideas**

- Define session contents such as selected server alias, open resources, active panels, search criteria, loaded IG references, and workbench state
- Store references to configured servers rather than credentials
- Version the session format and handle old/invalid files
- Do not restore SMART tokens or imply prior authorization remains valid.

**MVP and later extensions:** MVP: versioned local session file restoring selected workspace state without secrets. Later: multiple workspaces, recent sessions, and portable project bundles.

**Acceptance direction:** Users can restore their workspace without exposing credentials or silently reusing expired authorization.

### 9. Plugin SDK and Vendor Operation Catalog

**Priority:** Medium

Make server plugin capabilities discoverable and extensible without coupling core FHIRViewer to vendor internals.

**Scope ideas**

- Inspect the existing plugin API before designing changes
- Let plugins describe capabilities, supported versions, operations, parameters, and whether actions mutate server state
- Keep vendor code in plugins
- Reuse plugin authentication and error handling; apply centralized write confirmation
- Never invent endpoints not implemented by the plugin.

**MVP and later extensions:** MVP: document current plugin contract and add capability metadata for one representative plugin. Later: script API registration, compatibility checks, and plugin conformance tests.

**Acceptance direction:** A plugin can declare supported operations and FHIRViewer can present them without vendor-specific core code.

### 10. FHIR Sample Library and Scenario Builder

**Priority:** Medium

Provide safe reusable sample data for demonstrations, validation, and repeatable integration tests.

**Scope ideas**

- Include curated synthetic resources and linked scenarios such as patient/encounter/observation and orders/results
- Label FHIR version, profile/IG assumptions, expected references, and validation expectations
- Let users open samples, validate them, and use them as script fixtures
- Require explicit confirmation before uploading a scenario to a server.

**MVP and later extensions:** MVP: a small version-labeled library of synthetic examples. Later: scenario builder, user-managed collections, and generated test fixtures.

**Acceptance direction:** Users can load a known synthetic scenario and reuse it without real patient data.

### 11. SMART on FHIR Launch and Context Inspector

**Priority:** Medium

Make it easier to diagnose SMART launch context and authorization problems while reusing the existing SMART implementation.

**Scope ideas**

- Display available launch context such as patient, encounter, user, and scopes when provided
- Explain missing context, expired authorization, and insufficient scopes
- Show safe metadata, never raw tokens or secrets
- Keep active server and launch context clearly associated; avoid silently reusing context from a previous launch.

**MVP and later extensions:** MVP: read-only context/status panel with actionable authorization diagnostics. Later: scripted SMART test scenarios and deeper launch troubleshooting.

**Acceptance direction:** Users can understand which context and scopes are available without exposing tokens or creating a parallel OAuth stack.

## How to turn a feature into a Cline plan

For each selected feature, create a separate plan with a master considerations file and numbered implementation phases. Ask Cline to:

1. Inspect the repository and identify related functionality already present.
2. Mark requirements implemented, partial, missing, or blocked.
3. Identify dependencies on the REST Workbench, scripting, SMART on FHIR, validation, and plugins.
4. Define a small useful MVP and split later enhancements into phases.
5. Specify UI/API changes, tests, acceptance criteria, documentation, and migration concerns.
6. Preserve working behavior and avoid duplicate services.
7. Stop after each phase and report tests and readiness before proceeding.

## Cross-feature design rules

- Reuse shared services; keep UI as a consumer of those services.
- Make remote writes explicit. Use preview/dry-run and confirmation for destructive or broad operations.
- Never log credentials, OAuth tokens, or unnecessary patient data.
- Handle FHIR release differences, pagination, server-specific capabilities, OperationOutcome, and authorization failures.
- Keep errors actionable and preserve diagnostic detail.
- Document implemented behavior separately from planned behavior.
- Test with synthetic or isolated non-production data.
