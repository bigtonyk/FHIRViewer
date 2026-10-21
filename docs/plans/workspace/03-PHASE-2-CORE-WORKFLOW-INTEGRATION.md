# Phase 2 — Core Workflow State Integration

## Goal
Persist and restore core FHIR Viewer workflow configuration without reimplementing feature logic.

## Dependencies
- Phase 1 complete and tested.
- Phase 0 state-ownership inventory available.
- Explicit authorization for Phase 2.

## Scope
Integrate only features confirmed to exist:
1. **Connections:** profile ID/alias, base URL, server type, plugin ID, safe options, and credential reference only. Never serialize secrets/tokens. Workspace opens offline.
2. **REST workbench:** saved requests and supported drafts (method, path, parameters, safe headers, body). Exclude authorization/secrets; never auto-execute on restore.
3. **Tree/tabs:** selected resource reference, open tab IDs, filters, expanded nodes, and supported view state.
4. **FHIRPath:** saved expressions and associated resource context/reference. Avoid automatic evaluation if it requires remote data or expensive work.
5. **Validation:** workspace-scoped IG/package IDs and versions, profiles, and options. Never silently substitute missing package versions.
6. Add small typed state adapters/providers only where they cleanly separate feature-owned state from workspace coordination.

## Non-goals
Recovery/unsaved-buffer snapshots (Phase 3), automatic remote content downloads, invented features, plugin-system rewrites, transient REST response persistence.

## Acceptance criteria
- Save/close/open restores supported connection, REST workbench, tree/tabs, FHIRPath, and validation state.
- Missing servers/plugins/IG packages do not prevent local workspace opening; warnings are actionable.
- Restore does not execute REST calls, scripts, validations, or server writes automatically.
- Providers do not duplicate tabs or create a competing state store.
- Global preferences/plugin installation state remain application-wide.

## Tests
Provider unit tests; core workflow round-trip integration test; missing dependency tests; no-request-on-restore test; secret-exclusion tests; regression tests for existing feature behavior.

## Cline instructions
Use the Phase 0 inventory and current APIs. Document state that cannot safely be restored instead of persisting fragile internals. Do not implement recovery or remote snapshots in this phase.
