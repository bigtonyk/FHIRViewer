# FHIRViewer Scripting Guide

This guide explains how to use the embedded JShell scripting workspace in
FHIRViewer, and documents every object and method actually exposed to scripts.
JShell runs ordinary Java; it is **not** a security sandbox. Only run scripts
you trust.

## Getting started

Open the scripting workspace from the application menu. The console provides an
editor, Run / Stop / Reset, Open / Save, and a console output pane.

- **Run** evaluates the editor buffer. The unsaved editor keeps the interactive
  session (state persists between runs); a buffer opened from or saved to a file
  runs in a fresh session of its own, so each saved-script run starts clean and
  leaves nothing behind.
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
session per run, so declarations from earlier runs or other scripts are not
visible to them.

### Suites

A buffer can hold several cases. A line whose only content is `// case: name`
starts a case, and everything up to the next marker is that case's source. A
buffer without markers is one case named after the file. Text before the first
marker is refused rather than treated as a shared setup, because every case
takes a fresh session and could not use it anyway.

**Run Tests** runs every case in its own JShell session and shows the passed and
failed counts on a separate line above the output, so a verdict is never mixed
into what a script printed. A case passes only when it finishes with no compile
errors, exceptions or assertion failures; a failure does not stop the remaining
cases, and each failing case is named with its first diagnostic. The report line
carries the counts and that first diagnostic; the **Test details** tab carries every
case's printed output and all of its diagnostics and exceptions, while the **Output**
tab stays ordinary run output. Like Run, the first execution asks for the trust
acknowledgement.

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

## Scripting automation

Opening a saved script loads its text into the editor and never runs it. A buffer
with a file behind it runs in a fresh JShell session each time, so a saved script
starts clean and leaves no state behind. The unsaved editor keeps the interactive
session, and **Reset Session** discards that state.
Use the `test` assertions to check what a script found; a failed assertion appears
in the output as an `AssertionError` with its message, like any other exception.
Treat every script that writes or deletes as destructive and point it at synthetic
data. There is no suite runner in the viewer: run a workflow by opening its script
and pressing Run.

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
