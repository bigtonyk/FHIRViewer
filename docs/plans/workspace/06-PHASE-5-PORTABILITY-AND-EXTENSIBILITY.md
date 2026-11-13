# Phase 5 — Portability and Extensibility

## Goal
Finalize `.fhirworkspace` for transfer, backup, compatibility, and future state-provider integration.

## Dependencies
- Phases 1–4 complete and tested.
- Explicit authorization for Phase 5.

## Scope
1. Finalize documented archive schema, manifest fields, required/optional entries, and content conventions.
2. Complete import/export UX if not already covered by Open/Save As.
3. Support transfer to another machine, connection remapping, and credential re-entry through supported secure references.
4. Add deterministic schema migrations and fixtures for each supported old version.
5. Handle missing plugins, server profiles, IG packages, and script references gracefully.
6. Add optional providers for additional existing features only when they have stable APIs and clear ownership.
7. Document included, excluded, and machine-specific content.
8. Add portability, compatibility, and round-trip tests.

## Non-goals
Cloud sync/collaboration, plugin binary bundling, embedded credentials, automatic remote downloads, automatic execution of scripts/REST operations.

## Acceptance criteria
- Workspace can be transferred and opened elsewhere with clear prompts for machine-specific settings.
- Older supported schemas migrate deterministically.
- Unsupported future schemas are rejected without modifying the source archive.
- Missing optional dependencies do not destroy local content.
- Archive format is documented and inspectable.
- No credentials/tokens are included.
- Providers do not duplicate business logic or create a competing state store.

## Tests
Export/import round-trip; path normalization across platforms; migration fixtures; future-version rejection; missing dependencies/remapping; secret-exclusion scans; full regression suite.

## Cline instructions
Only add providers for features confirmed in the repository. Keep a clear compatibility policy and test every migration. Do not expand into cloud sync or server snapshots without separate design approval.
