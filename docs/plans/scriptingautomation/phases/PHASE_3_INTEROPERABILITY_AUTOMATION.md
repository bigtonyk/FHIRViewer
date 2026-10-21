# Phase 3 — Interoperability Automation

## Goal
Support repeatable multi-step FHIR workflows, parameterized server checks, data-quality scans, controlled writes, and transformations.

## Before coding
Confirm Phase 2 acceptance criteria. Review the existing API and tests. Keep the REST Workbench as the manual REST tool and reuse its underlying services.

## Scope
### Parameters and target selection
- Support named parameters, defaults, and required-value validation.
- Allow explicit target-server selection from configured servers.
- Show target and parameters before execution; never silently redirect a write workflow.
- Do not store credentials or tokens in workflow configuration.

### FHIR workflows and writes
- Support multi-step search/read and processing through existing services.
- Add create/update where supported by the server and authorization.
- Centralize confirmation for DELETE, broad/destructive batch work, write transaction bundles, and mutating plugin operations.
- Show target, operation, resource type/ID, and scope/count where known.
- Respect FHIR version, capabilities, authorization, and SMART scopes; do not assume every server supports transactions or conditional operations.

### Batch processing and transformations
- Process paginated results with progress and cancellation where feasible.
- Transform FHIR resources using existing parsers/models.
- Make failure policy explicit: stop, skip item, or continue and collect errors.
- Avoid unbounded memory use where chunked processing is practical.
- Preserve source resources unless the workflow explicitly writes changes; add dry-run/preview for write workflows where feasible.

### Validation and quality reports
- Validate collections and summarize findings by resource type, profile, and severity.
- Export JSON/CSV reports if consistent with existing export patterns.
- Include resource identifiers and issue locations while avoiding unnecessary patient data.
- Report validation failures without automatically blocking local inspection/saving.

### SMART on FHIR and run management
- Reuse existing SMART launch context and authorization; clearly identify patient/user/encounter context when available.
- Never expose raw access/refresh tokens by default.
- Handle missing/expired authorization and insufficient scopes clearly; do not silently reuse context from another launch/server.
- Track timestamp, script, target server alias, duration, outcome, and summary.
- Support cancellation where safe; defer background scheduling unless separately approved.

## Tests and acceptance criteria
- Parameters and invalid values are handled clearly; target selection remains stable during a run.
- Pagination and large result sets are handled correctly.
- Dry-run does not apply writes and accurately describes intended operations.
- Destructive operations cannot bypass centralized confirmation through normal script APIs.
- SMART context respects scopes and does not reveal tokens.
- Batch failure policy is honored and failures appear in reports.
- Destructive integration tests use isolated non-production data only.
- Record tests and status before Phase 4.

## Handoff to Phase 4
Document stable API seams and identify plugin operations that need a standardized script interface.
