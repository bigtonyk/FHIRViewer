# Phase 2 — REST Workbench Provider

## Scope
Saved requests and supported drafts.

## Capture
- method
- path
- parameters
- safe headers
- body

## Exclude
- authorization/secrets

## Restore
- Populate workbench state only.
- Never auto-execute.

## Tests
- No-request-on-restore test
- Secret-exclusion test
