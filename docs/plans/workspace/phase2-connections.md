# Phase 2 — Connections Provider

## Scope
Capture and restore connection state only.

## Capture
- profile id/alias
- base URL
- server type
- plugin id
- safe options
- credential reference only
- Never serialize secrets/tokens

## Restore
- Resolve connections offline.
- Warn on missing server/plugin.
- No auto-connect or writes.

## Tests
- Secret-exclusion test
- Missing dependency test
