# Phase 3 — Local Content and Recovery

## Goal
Protect unsaved local work independently of the last explicitly saved workspace archive.

## Dependencies
- Phases 1 and 2 complete and tested.
- Explicit authorization for Phase 3.

## Scope
1. Persist local/imported resources and unsaved editor buffers.
2. For edited remote resources, preserve local working content and separate provenance (server profile/reference, type/ID, optional version ID/ETag).
3. Add a private application-managed recovery store separate from portable workspaces.
4. Track workspace UUID/path, schema version, snapshot timestamp, and saved-baseline relationship.
5. Add periodic snapshots and snapshots after meaningful changes; debounce/coalesce rapid edits.
6. Integrate with existing dirty-state notifications.
7. Add Save / Discard / Cancel prompts for close/switch flows that could lose changes.
8. Make writes atomic and preserve the last valid snapshot on failure.
9. Define bounded retention; never delete the only recoverable copy before validating its replacement.

## Non-goals
Full remote-server snapshots, implicit server writes, cloud sync, multi-user editing, secrets in recovery data.

## Acceptance criteria
- Unsaved edits and local resources recover after simulated abrupt termination.
- Recovery never silently overwrites the portable workspace archive.
- Restored edits remain local and are visibly marked modified/unsaved.
- Server writes remain explicit user actions.
- Snapshot scheduling is throttled/coalesced and storage is bounded.
- Recovery logic is testable without JavaFX.
- Failure does not destroy the last valid snapshot.

## Tests
Recovery round-trip; simulated crash/restart; verify archive remains unchanged; verify no remote writes; debounce/retention; atomic/corrupt snapshot; dirty-prompt decisions.

## Cline instructions
Inspect actual editor dirty-state and lifecycle code before hooking in. Reuse existing scheduler/lifecycle patterns and shut down resources correctly. Keep file I/O off the FX thread. Use synthetic, non-sensitive resources.
