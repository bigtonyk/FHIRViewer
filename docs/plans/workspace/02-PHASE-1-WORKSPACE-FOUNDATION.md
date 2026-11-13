# Phase 1 — Workspace Persistence Foundation

## Goal
Implement a minimal, end-to-end named workspace capability based on the approved Phase 0 design.

## Dependencies
- Phase 0 assessment completed and reviewed.
- Explicit authorization to implement Phase 1.

## Scope
1. Add versioned plain-data workspace models and metadata.
2. Implement safe ZIP-based `.fhirworkspace` reading/writing.
3. Validate manifest/schema and report malformed or unsupported archives clearly.
4. Implement New, Open, Save, and Save As through a workspace service.
5. Add minimal JavaFX menu/dialog integration following existing UI conventions.
6. Give each workspace a stable UUID independent of name/path.
7. Track dirty state for fields supported in this phase.
8. Write archives atomically using a temporary file and replacement strategy.
9. Document the initial archive schema and version policy.

Use a minimal state set that proves the lifecycle. Do not attempt full feature integration yet.

## Non-goals
Recovery/autosave, full feature integration, cloud sync, server snapshots, serialization of UI/runtime objects.

## Acceptance criteria
- Create, save, close, and reopen a workspace successfully.
- Save As creates a distinct workspace without unexpectedly overwriting the source.
- Failed saves preserve the last valid archive.
- Invalid or unsupported workspaces do not replace active state.
- No credentials/tokens enter archives.
- Opening does not issue remote writes or execute saved operations.
- Existing behavior remains intact when no workspace is open.

## Tests
Model serialization/schema tests; archive round-trip; malformed ZIP/manifest and unsupported-version tests; atomic-write failure test where feasible; Save/Open/Save As service tests with temp directories; existing regression build/tests.

## Cline instructions
Use real classes and conventions from Phase 0. Avoid unnecessary dependencies. Keep I/O off the FX thread where appropriate and marshal UI changes correctly. Report exact commands/results.
