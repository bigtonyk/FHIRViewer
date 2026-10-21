# FHIRViewer Scripting Guide

This guide explains how to use the embedded JShell scripting workspace in
FHIRViewer, and documents every object and method actually exposed to scripts.
JShell runs ordinary Java; it is **not** a security sandbox. Only run scripts
you trust.

## Getting started

Open the scripting workspace from the application menu. The console provides an
editor, Run / Stop / Reset, Open / Save, and a console output pane.

- **Run** evaluates the editor buffer in the interactive session (state
  persists between runs).
- **Reset Session** discards all interactive state and starts fresh.
- **Open / Save** load and store script files.
- Compile errors, runtime exceptions, and output appear in the console pane.

A missing resource returns `null` rather than throwing, so scripts can use
`if (patient != null)`.

## Exposed objects

Scripts receive these top-level final variables:

| Name | Type | Purpose |
|------|------|---------|
| `fhir` | `FhirApi` | Read/search/write FHIR resources on the selected server |
| `viewer` | `ViewerApi` | Display resources/results/text in the console |
| `validation` | `ValidationApi` | Validate FHIR resources |
| `server` | `ServerApi` | Inspect configured servers and capabilities |
| `log` | `LogApi` | Print info/warn/error messages |
| `test` | `TestApi` | Assertions for automated tests |
| `smart` | `SmartApi` | Read through an authorized SMART session |

## FHIR resource operations

```java
IBaseResource p = fhir.read("Patient", "example-id");
if (p != null) {
    viewer.showResource(p);
}
```

Signatures:
- `IBaseResource read(String resourceType, String resourceId)` — null if not found.
- `SearchResults search(String resourceType, SearchCriterion... criteria)` — one page.
- `IBaseResource create(IBaseResource resource)` — requires write policy.
- `ServerWriteResult update(IBaseResource resource)` — requires write policy and server id.
- `void delete(String resourceType, String resourceId)` — requires write policy.

SearchResults: `resources()`, `total()`, `nextPageToken()`, `previousPageToken()`,
`hasNextPage()`, `hasPreviousPage()`, `size()`. Paging: `fhir.nextPage(token)`,
`fhir.previousPage(token)`.

Writes are refused unless the session was started with an enabled WritePolicy.

## Validation and FHIRPath

```java
ValidationReport r = validation.validate(patient);
ValidationReport r2 = validation.validate(patient, "http://example.org/StructureDefinition/MyProfile");
```

Validation never throws; issues appear on the report.

## Server and plugin operations

```java
ServerInfo s = server.activeServer();
var caps = server.capabilities();
boolean ok = server.supports("Patient", ServerInteraction.READ);
String v = server.fhirVersion();
```

`ServerInfo`: `name()`, `baseUrl()`, `fhirVersion()`, `pluginId()`.

## Automated testing

Assertions throw `AssertionError` on failure:
`assertTrue`, `assertFalse`, `assertEquals`, `fail`. Saved tests run in a fresh
session independent of interactive history.

## SMART on FHIR

```java
if (smart.isSessionActive()) {
    var scopes = smart.grantedScopes();
    var pid = smart.launchPatientId();
    IBaseResource p = smart.read("Patient", pid);
}
```

Raw tokens are never exposed. Scope restrictions and expiry are enforced;
`read` throws `ScriptApiException` when no session is active.

## Security and troubleshooting

JShell runs Java with the user's permissions and is not a sandbox. Common
errors: no selected server, auth failure, missing scope, unsupported operation,
compile errors, runtime-module problems, cancellation limitations. Examples use
synthetic identifiers and read-only operations by default.
