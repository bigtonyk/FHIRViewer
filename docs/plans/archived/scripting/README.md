# FHIRViewer JShell Scripting Implementation Plan

This package contains a phased plan for embedded Java scripting in FHIRViewer using the Java 21 JShell API.

## Files
- `MASTER-CONSIDERATIONS.md` — architecture, constraints, security, packaging, and rules for every phase.
- `PHASE-1-BASIC-SCRIPTING-CONSOLE.md` — JavaFX editor, embedded JShell, execution, diagnostics, open/save.
- `PHASE-2-FHIRVIEWER-SCRIPTING-API.md` — controlled APIs for FHIRViewer features.
- `PHASE-3-AUTOMATED-TESTING.md` — assertions, repeatable test runs, outcomes, and reports.
- `PHASE-4-SMART-ON-FHIR-INTEGRATION.md` — authorized access to an existing SMART session.
- `PHASE-5-ADVANCED-SCRIPTING.md` — reusable scripts, parameters, authoring improvements, and plugin workflows.
- `PHASE-6-SCRIPTING-API-DOCUMENTATION.md` — user-facing documentation explaining how scripts access FHIRViewer features.
- `IMPLEMENTATION-STATUS-TEMPLATE.md` — template for tracking progress.

## Instructions for Cline
1. Copy this folder into the repository at `docs/implementation/jshell-scripting/`.
2. Ask Cline to read `MASTER-CONSIDERATIONS.md` and `PHASE-1-BASIC-SCRIPTING-CONSOLE.md`.
3. Inspect the repository, then implement Phase 1 only.
4. Review changes and tests before proceeding to the next phase.
5. Keep `IMPLEMENTATION-STATUS.md` updated using the supplied template.

Do not implement all phases in one pass. Validate JShell first, then add FHIR APIs, automated testing, SMART integration, advanced features, and user documentation.
