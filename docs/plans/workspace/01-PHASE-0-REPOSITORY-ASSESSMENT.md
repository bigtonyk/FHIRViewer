# Phase 0 — Repository Assessment and Workspace Design

## Goal
Understand the actual FHIR Viewer repository and produce a repository-specific design before any code changes.

## Dependencies
- None; this phase precedes all implementation.

## Scope
1. Confirm branch, working-tree status, Java/toolchain target, build system, app entry point, JavaFX lifecycle, and test framework.
2. Locate actual implementations for connection profiles; REST workbench requests/drafts; resource tree/tabs/selection/filters; editor buffers/dirty state; FHIRPath expressions/context; IG/package and validation settings; local resources; preferences and shutdown.
3. Map ownership and available capture/restore APIs for each state.
4. Identify credential storage, logging, file/archive utilities, thread scheduling, and test conventions.
5. Propose model fields, archive layout, recovery location, and adapter integration points using repository conventions.
6. Identify unsupported state, risks, gaps, and implementation order.

## Out of scope
No source/build changes, dependencies, refactoring, or speculative adapter implementation.

## Deliverable
Create a repository-specific assessment note (suggested `docs/workspace/REPOSITORY-ASSESSMENT.md`) covering actual files/classes, state ownership, proposed mappings, schema/archive, recovery path, credential handling, risks, and available build/test commands.

## Acceptance criteria
- All findings refer to actual repository files/classes, not guessed names.
- Every core feature has an identified state owner or documented gap.
- Existing services are reused; no duplicate REST/FHIRPath/validation logic is proposed.
- Working tree remains unchanged.

## Tests / verification
Inspect repository status before/after. Use non-mutating discovery commands only. Do not claim tests passed unless run.

## Cline instructions
Inspect first; absent features must be documented as absent, not invented. Produce the assessment and stop. Do not start Phase 1 automatically.
