# FHIRViewer Server Plugin REST — Master Instructions

## Purpose

Extend the existing FHIRViewer server-plugin architecture so plugins can communicate with FHIR servers through standard FHIR REST APIs and vendor-specific REST APIs such as Smile CDR and Firely Server.

The Viewer UI must also be able to discover and invoke the functionality exposed by the active server plugin.

## Critical Architecture Rule

The UI must NOT contain vendor-specific REST URLs or vendor-specific branching.

Use this architecture:

Viewer UI
→ Application/service layer
→ FHIR Server Plugin API
→ Standard FHIR API or Custom Plugin Operations
→ Authentication
→ REST transport
→ Server

The plugin owns server-specific behavior. Core FHIRViewer owns reusable transport/infrastructure.

## Implementation Order

Run these plans one at a time:

1. `01_INSPECT_CURRENT_ARCHITECTURE.md`
2. `02_REST_CORE_API.md`
3. `03_AUTHENTICATION_AND_HTTP.md`
4. `04_STANDARD_FHIR_OPERATIONS.md`
5. `05_PLUGIN_CUSTOM_OPERATIONS.md`
6. `06_VIEWER_UI_INTEGRATION.md`
7. `07_SMILE_AND_FIRELY_PLUGINS.md`
8. `08_TESTING_DOCUMENTATION_AND_HARDENING.md`

Do not skip ahead unless the earlier phase is already implemented and verified.

## Rules for Every Phase

- Inspect existing code before creating new classes.
- Reuse existing HTTP, authentication, FHIR parsing, logging, configuration, and JavaFX infrastructure where possible.
- Do not duplicate existing functionality.
- Keep vendor-specific code out of core FHIRViewer.
- Keep networking off the JavaFX UI thread.
- Do not log passwords, bearer tokens, or Authorization headers.
- Do not disable TLS certificate validation as a shortcut.
- Compile after significant changes.
- Run existing tests after significant changes.
- Fix regressions before continuing.
- Keep changes reasonably isolated and reviewable.
- Do not make speculative architectural rewrites.
- If the current code differs from assumptions in a plan, adapt the plan to the actual architecture and document the decision.

## Completion Rule

At the end of each phase, provide:

1. What was inspected/changed.
2. Files/classes added or modified.
3. Tests run and results.
4. Any unresolved issues.
5. Anything the next phase needs to know.

Do not implement later-phase functionality merely because it is convenient during an earlier phase.
