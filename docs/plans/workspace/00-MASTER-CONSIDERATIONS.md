# FHIR Viewer Workspace System — Master Considerations

## Purpose
Plan a named-workspace system for FHIR Viewer (Java 21, JavaFX, HAPI FHIR). Portable workspaces preserve intentionally saved state; a separate local recovery store protects unsaved work.

**Planning only:** These files are implementation plans, not application code. Execute phases only when explicitly authorized.

## Required Cline workflow
1. Inspect the repository, branch, working tree, build files, Java target, JavaFX lifecycle, package structure, and tests before proposing edits.
2. Inventory the actual state owners and APIs for server profiles, REST workbench, resource tree/tabs, editors and dirty buffers, FHIRPath, IG/package selection and validation, local resources, app preferences, and shutdown handling.
3. Identify existing credential storage, logging, concurrency, archive/file utilities, and test conventions.
4. Map the plan's conceptual components to actual classes. Suggested names below are illustrative, not assumptions.
5. Present repository-specific findings and risks before coding. Phase 0 is analysis only.
6. Implement only the currently authorized phase. Do not automatically proceed to later phases.
7. Preserve existing behavior and reuse existing services. Do not invent features or replace the REST workbench, validation engine, FHIRPath implementation, or plugin API.
8. Run relevant tests/builds and report exact commands and actual results. Never claim tests passed if not run.

## Product decisions
- Named workspaces; automatically reopen the last active workspace at startup.
- Storage: portable user-managed `.fhirworkspace` files plus a separate app-managed local recovery store.
- Workspace format: ZIP archive with versioned JSON metadata/configuration and optional local content.
- Recovery: periodic snapshots plus snapshots after meaningful changes, with throttling/debounce.
- Initial integration: connection profiles, REST workbench, resource tree/tabs, FHIRPath, validation configuration, and local editor/resource state.
- Preserve unsaved edits and local resources. Do not automatically download or snapshot whole remote servers.
- Restoring a workspace must never implicitly create/update/delete resources on a remote FHIR server.
- Never store passwords, client secrets, OAuth access tokens, or refresh tokens in workspace/recovery files.
- Never serialize JavaFX controls, live HAPI clients, plugin instances, or other runtime objects.

## Conceptual architecture
Adapt these roles to the existing repository rather than creating unnecessary layers:
1. **Workspace model:** versioned plain-data objects describing metadata, configuration, UI state, and local-content references.
2. **Workspace repository:** reads/writes portable archive files and validates contents.
3. **Workspace service:** coordinates New/Open/Save/Save As/switching, dirty prompts, capture and restore.
4. **Recovery service/store:** local snapshots, recovery metadata, last-workspace tracking.
5. **Feature state adapters:** typed capture/restore boundaries for state owned by existing features. Avoid building a second plugin framework.

Possible conceptual names include `WorkspaceDefinition`, `WorkspaceMetadata`, `WorkspaceSnapshot`, `WorkspaceResource`, `WorkspaceService`, `WorkspaceRepository`, `WorkspaceArchiveReader`, `WorkspaceArchiveWriter`, `WorkspaceRecoveryService`, `RecoveryStore`, and `WorkspaceStateProvider`. Use repository naming conventions instead.

## Archive layout proposal
A `.fhirworkspace` ZIP could contain:
```text
manifest.json
workspace.json
rest-workbench.json
fhirpath.json
validation.json
resources/<stable-local-id>.json
scripts/<safe-relative-path>  # only if existing script support is in scope
```
Prefer a simple schema over excessive file fragmentation. The manifest should declare schema version and required/optional entries. Keep paths relative and normalized. Store content only for local resources and explicit unsaved working copies; never crawl or download remote resources.

## Data and security rules
- Stable workspace UUID independent of display name and path.
- Metadata: schema version, display name, created/modified timestamps, and app format/version when useful.
- Remote references store server-profile identity/alias, resource type, logical ID, and optional version ID/ETag. Retrieve remote data only when explicitly requested by a feature/user.
- Edited remote resources need a local working copy plus separate provenance.
- Separate app-global settings (theme, installed plugins, global credential references) from workspace-specific selections.
- Persist base URLs and safe non-secret settings only. Reuse secure credential handling if present; do not add a new credential-store dependency without review.
- Opening a workspace tolerates offline servers, missing plugins, and missing IG packages; warn clearly and do not silently substitute IG versions.
- Do not persist arbitrary REST response payloads unless explicitly saved as local content.
- Treat imported archives as untrusted: defend against ZIP Slip/path traversal, absolute paths, duplicate/conflicting entries, unexpected entry types, malformed manifests, oversized entries, excessive expanded size/count, and decompression bombs.
- Do not execute scripts or saved REST requests during open/import.
- Preserve source archives on failed open/import; never overwrite active state until validation succeeds.
- Logs and errors must not expose credentials or patient data.

## Recovery and save semantics
- The portable archive represents the last explicit save. Recovery snapshots must not silently overwrite it.
- The recovery store is private application data, separate from the portable archive.
- Track workspace UUID/path, snapshot time, schema version, and relation to the last saved baseline.
- Snapshot periodically and after meaningful changes; debounce/coalesce rapid edits.
- Use atomic writes (temporary file then replace); preserve last known-good snapshots on failure.
- At startup, reopen the last workspace. If a newer compatible recovery snapshot exists, offer Restore / Discard / Inspect (if inspection is supported); never silently discard newer recovery data.
- If no workspace was previously opened, use existing startup behavior or a clear empty-workspace state.
- Bound recovery storage. Do not delete the only recoverable copy until a replacement has been validated.
- Distinguish workspace-configuration dirty state from local resource/editor dirty state. Support Save / Discard / Cancel for operations that could lose work.
- A recovered local edit stays local until the user explicitly performs a server operation.

## JavaFX and lifecycle
- Scene graph mutations must occur on the JavaFX Application Thread.
- Perform archive I/O and large serialization off the FX thread where appropriate; marshal UI restoration back to the FX thread.
- Integrate with existing startup/close lifecycle; do not add competing shutdown hooks.
- Workspace open must not depend on network availability or block on network calls.
- Use existing scheduling/lifecycle conventions and shut down any scheduler with the application.

## Compatibility
- Version the schema from the first release.
- Reject unsupported future versions without overwriting the source.
- Add deterministic migrations and fixtures for each supported older version.
- Document the archive format and required/optional entries.

## Test strategy
Use synthetic, non-sensitive FHIR fixtures and temporary directories. Test serialization/round trips, archive safety, migrations, atomic writes, recovery behavior, dirty prompts, state adapters, offline/missing dependencies, startup restore, and no-network-side-effect behavior. No real server should be required for routine tests.

## Out of scope
Full server snapshots, implicit remote writes, cloud sync/collaboration, plugin-binary bundling, speculative adapters for nonexistent features, and replacing existing REST/validation/FHIRPath/plugin implementations.

## Phase order
1. `01-PHASE-0-REPOSITORY-ASSESSMENT.md`
2. `02-PHASE-1-WORKSPACE-FOUNDATION.md`
3. `03-PHASE-2-CORE-WORKFLOW-INTEGRATION.md`
4. `04-PHASE-3-LOCAL-CONTENT-AND-RECOVERY.md`
5. `05-PHASE-4-STARTUP-RESTORATION-AND-ROBUSTNESS.md`
6. `06-PHASE-5-PORTABILITY-AND-EXTENSIBILITY.md`

Execute in order. Phase 0 is assessment only; later phases require explicit authorization.
