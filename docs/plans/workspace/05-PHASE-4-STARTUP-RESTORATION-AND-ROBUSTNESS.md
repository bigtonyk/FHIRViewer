# Phase 4 — Startup Restoration and Robustness

## Goal
Make startup, restoration, switching, and failure handling predictable and safe.

## Dependencies
- Phases 1–3 complete and tested.
- Explicit authorization for Phase 4.

## Scope
1. Persist last active workspace path/identity in app preferences.
2. Automatically reopen the last workspace at startup.
3. Detect newer compatible recovery snapshots and offer Restore / Discard / Inspect where supported.
4. Handle missing/moved files, no prior workspace, corrupt archives, incompatible schemas, and recovery-read failures.
5. Restore local state even if servers are offline.
6. Handle missing plugins and IG/package versions without silent substitution.
7. Validate ZIP paths, traversal, absolute paths, duplicate/conflicting entries, entry types, manifests, and documented size/count limits.
8. Ensure open/import never executes scripts or saved REST requests.
9. Provide clear user messages and diagnostic logging that excludes secrets and patient data.
10. Verify close, crash-recovery, and workspace-switch lifecycle behavior.

## Non-goals
Cloud sync, implicit remote writes, unreviewed new credential-store dependencies.

## Acceptance criteria
- Startup reopens the last valid workspace automatically.
- Newer recovery data is never silently discarded.
- Missing/corrupt files result in a safe fallback and actionable message.
- Malicious/oversized archives are rejected.
- Workspaces open offline and restore available local state.
- UI restoration is on the FX thread; file I/O does not unnecessarily block it.
- Saved operations are not automatically executed.

## Tests
Startup valid/missing/corrupt workspace; newer/no recovery; restore/discard decisions; ZIP traversal and oversized entries; missing dependencies; dirty/clean workspace switching; no execution/server mutation on restore; regression suite.

## Cline instructions
Treat archives as untrusted; do not trust file extension or manifest alone. Preserve originals on failure. Inspect real startup/close lifecycle before editing and document archive limits selected.
