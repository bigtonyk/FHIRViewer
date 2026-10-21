# Phase 4 — Extensibility and Ecosystem

## Goal
Allow core features and supported server plugins to provide documented script capabilities without coupling scripts to vendor internals.

## Before coding
Confirm Phase 3 acceptance criteria. Inspect the actual plugin architecture and SmileCDR/Firely integrations. Design the smallest useful extension contract based on current code, not speculative abstractions.

## Scope
### Plugin script API
- Define how plugins advertise capabilities and register typed API objects/operations.
- Include descriptions, argument/return types, mutability, compatibility, and prerequisites.
- Keep core APIs independent of vendor-specific implementation classes.
- Make capabilities discoverable through console help/UI.
- Handle absent, disabled, incompatible, or failed plugins cleanly.

### Vendor-specific operations
- Expose operations only when provided by the installed plugin.
- Reuse plugin authentication/configuration/server selection/error handling.
- For custom REST operations, declare method, parameters, supported versions, and read-only versus mutating behavior.
- Apply centralized write confirmation and existing authorization rules.
- Never claim an operation exists unless the plugin explicitly advertises it.

### Script library and sharing
- Provide versioned examples for search/read, validation, repeatable tests, paginated quality checks, dry-run transformations, safe updates, authorized SMART context, and capability-guarded vendor features.
- Support import/export of `.jsh` scripts and non-secret workflow metadata, with validation and overwrite warnings.
- Treat imported scripts as executable trusted code requiring review; do not imply sandboxing.
- Keep credentials/tokens out of exported files. Prefer a local library before considering online marketplaces or remote repositories.

### API compatibility and docs
- Version the public scripting API or define a compatibility/deprecation policy.
- Test supported Java 21 and plugin combinations.
- Document behavior when a script uses an API absent from the installed version.
- Publish API reference, plugin-author guide, compatibility guidance, security assumptions, and migration notes.

## Out of scope unless separately approved
Online marketplace, remote script execution, multi-user scheduling/orchestration, or claims of sandboxing arbitrary in-process Java code.

## Tests and acceptance criteria
- Plugins advertise capabilities without requiring core scripting changes.
- At least one available plugin demonstrates the extension path, chosen based on repository readiness.
- Missing/incompatible plugins fail clearly without breaking core scripting.
- Plugin writes obey authorization and central confirmation policy.
- Import/export preserves non-secret metadata and warns that scripts execute code.
- API compatibility tests and examples pass against test/mock servers and contain no secrets or real patient data.
- Documentation and implementation status are updated.
