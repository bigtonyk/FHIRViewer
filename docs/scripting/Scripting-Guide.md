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

## Concurrency and cancellation

The scripting console serialises every `run` through one worker thread. A second
`run` while a script is already executing is refused immediately with a
`ScriptRunResult` whose status is `FAILED` and whose message contains
`"already running: "` - the console's **Run** button stays enabled while a
script is in flight.

- **Stop** interrupts the in-flight script at the next JShell boundary. The
  worker thread returns a result with status `STOPPED`; the console then greys
  Run/Reset and the script's result is visible in the output pane.
- **Reset** closes and recreates the JShell session, so variables, methods and
  classes defined by the previous run are gone afterwards.
- A stopped script is **not** a crashed one: `first(ScriptRunResult.Kind.ERROR).text()` may still be consulted, but the status is `STOPPED`, not `FAILED`.

## Automated testing

Assertions throw `AssertionError` on failure:
`assertTrue`, `assertFalse`, `assertEquals`, `fail`. Saved tests run in a fresh
session independent of interactive history.

## SMART on FHIRser's permissions and is not a sandbox. Common
errors: no selected server, auth failure, missing scope, unsupported operation,
compile errors, runtime-module problems, cancellation limitations. Examples use
synthetic identifiers and read-only operations by default.

## API reference details

### FhirApi (`fhir`)
- `IBaseResource read(String resourceType, String resourceId)`
- `SearchResults search(String resourceType, SearchCriterion... criteria)`
- `IBaseResource create(IBaseResource resource)` (write policy)
- `ServerWriteResult update(IBaseResource resource)` (write policy)
- `void delete(String resourceType, String resourceId)` (write policy)
- `SearchResults nextPage(String pageToken)`
- `SearchResults previousPage(String pageToken)`

### ViewerApi (`viewer`)
- `void showResource(IBaseResource resource)`
- `void showSearchResults(SearchResults results)`
- `void showText(String text)`

### ValidationApi (`validation`)
- `ValidationReport validate(IBaseResource resource)`
- `ValidationReport validate(IBaseResource resource, String profileCanonical)`

### ServerApi (`server`)
- `ServerInfo activeServer()`
- `List<ServerInfo> servers()`
- `ServerCapabilities capabilities()`
- `boolean supports(String resourceType, ServerInteraction interaction)`
- `String fhirVersion()`

### LogApi (`log`)
- `void info(String message)`
- `void warn(String message)`
- `void error(String message)`

### TestApi (`test`)
- `void assertTrue(boolean condition)` / `assertTrue(boolean, String)`
- `void assertFalse(boolean condition)` / `assertFalse(boolean, String)`
- `void assertEquals(Object expected, Object actual)` / `assertEquals(Object, Object, String)`
- `void fail()` / `fail(String)`

### SmartApi (`smart`)
- `boolean isSessionActive()`
- `List<String> grantedScopes()`
- `String launchPatientId()`
- `String launchEncounterId()`
- `IBaseResource read(String resourceType, String id)`

### SearchResults
- `List<IBaseResource> resources()`
- `Integer total()`
- `String nextPageToken()`
- `String previousPageToken()`
- `boolean hasNextPage()`
- `boolean hasPreviousPage()`
- `int size()`

### ServerInfo
- `String name()`
- `String baseUrl()`
- `String fhirVersion()`
- `String pluginId()`
## Interoperability Automation

Support for repeatable multi-step FHIR workflows, parameterized server checks, batch processing, transformations, and quality reports.

### Parameters and target selection
- Support named parameters, defaults, and required-value validation.
- Allow explicit target-server selection from configured servers.
- Show target and parameters before execution; never silently redirect a write workflow.

### FHIR workflows and writes
- Support multi-step search/read and processing through existing services.
- Add create/update where supported by the server and authorization.
- Centralize confirmation for DELETE, broad/destructive batch work, write transaction bundles, and mutating plugin operations.
- Show target, operation, resource type/ID, and scope/count where known.
- Respect FHIR version, capabilities, authorization, and SMART scopes.

### Batch processing and transformations
- Process paginated results with progress and cancellation where feasible.
- Transform FHIR resources using existing parsers/models.
- Make failure policy explicit: stop, skip item, or continue and collect errors.
- Avoid unbounded memory use where chunked processing is practical.
- Preserve source resources unless the workflow explicitly writes changes; add dry-run/preview for write workflows where feasible.

### Validation and quality reports
- Validate collections and summarize findings by resource type, profile, and severity.
- Export JSON/CSV reports if consistent with existing export patterns.
- Include resource identifiers and issue locations while avoiding unnecessary patient data.
- Report validation failures without automatically blocking local inspection/saving.

### SMART on FHIR and run management
- Reuse existing SMART launch context and authorization; clearly identify patient/user/encounter context when available.
- Never expose raw access/refresh tokens by default.
- Handle missing/expired authorization and insufficient scopes clearly; do not silently reuse context from another launch/server.
- Track timestamp, script, target server alias, duration, outcome, and summary.
- Support cancellation where safe; defer background scheduling unless separately approved.

## Extensibility and Plugin SDK

Allows core features and supported server plugins to provide documented script capabilities without coupling scripts to vendor internals.

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
- Document API reference, plugin-author guide, compatibility guidance, security assumptions, and migration notes.
