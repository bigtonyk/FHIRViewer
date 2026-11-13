# Phase 2 — Remaining Implementation Work

## Feature provider implementations (src/main/java/.../workspace/*WorkspaceStateProvider.java)

### ConnectionsWorkspaceStateProvider
- Capture: profile id/alias, base URL, server type, plugin id, safe options, credential reference only.
- Exclude secrets/tokens.
- Restore: resolve connections offline; warn on missing server/plugin.
- No auto-connect/write.

### RestWorkbenchWorkspaceStateProvider
- Capture: saved requests + drafts (method, path, parameters, safe headers, body).
- Exclude authorization/secrets.
- Restore: populate workbench state only; never auto-execute.

### TreeTabsWorkspaceStateProvider
- Capture: selected resource reference, open tab ids, filters, expanded nodes, supported view state.
- Restore: reopen tabs by id/reference; warn if missing.

### FhirPathWorkspaceStateProvider
- Capture: saved expressions + associated resource context/reference.
- Restore: do not auto-evaluate if remote/expensive.

### ValidationWorkspaceStateProvider
- Capture: workspace-scoped IG/package ids+versions, profiles, options.
- Restore: never silently substitute missing package versions; warn.

## Wiring & UX
- MainWindow capture/restore hooks call providers; restore no remote calls.
- Missing dependency warnings actionable.
- Global prefs/plugin install remain app-wide.

## Tests
- Provider unit tests
- Core workflow round-trip integration test
- Missing dependency tests
- No-request-on-restore test
- Secret-exclusion tests
- Regression tests
