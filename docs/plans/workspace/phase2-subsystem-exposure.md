# Phase 2 — Subsystem API/Service Exposure Plan

## Connections
- Expose profile registry read/capture/restore methods.
- Credential reference handles only.

## REST Workbench
- Expose request/draft model capture/restore.
- No execution on restore.

## Tree/Tabs
- Expose tab ids, filters, expanded nodes, selected ref.

## FHIRPath
- Expose saved expressions + context.

## Validation
- Expose IG/package ids+versions, profiles, options.

## General
- Each subsystem provides typed state adapter implementing WorkspaceStateProvider.
- No secrets/tokens.
- Offline restore + warnings.
