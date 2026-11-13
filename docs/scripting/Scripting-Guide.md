# FHIRViewer Scripting Guide

This guide explains how to use the embedded JShell scripting workspace in
FHIRViewer, and documents every object and method actually exposed to scripts.
JShell runs ordinary Java; it is **not** a security sandbox. Only run scripts
you trust.

## Getting started

Open **Tools → Scripting Console...**. The console provides an editor, Run / Run Tests /
Stop / Reset Session, Open / Save / Save As / Example, and an output pane with a Test
details tab.

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
| `fhir` | `FhirApi` | Read and search FHIR resources on the selected server (writes are refused in this build) |
| `viewer` | `ViewerApi` | Display resources/results/text in the console |
| `validation` | `ValidationApi` | Validate FHIR resources |
| `server` | `ServerApi` | Inspect configured servers and capabilities |
| `log` | `LogApi` | Print info/warn/error messages |
| `test` | `TestApi` | Assertions for automated tests |
| `smart` | `SmartApi` | Read through the Workbench's authorized SMART session |
| `batch` | `BatchProcessor` | Process a list of resources one at a time |

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

Writes are refused unless the session has an enabled `WritePolicy`. The console builds
`fhir` with `WritePolicy.READ_ONLY`, so `create`, `update`, `patch` and `delete` throw
`WriteDeniedException` before any request is made in this build. Reads and searches are
unaffected.

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
takes a fresh session and could not use it anyway. Case names must be unique: a
repeated name is refused when the suite is parsed, because two results under one
name cannot be told apart in the report. Every case needs source: a marker
followed immediately by another marker or by the end of the file is refused
rather than passing as a test that checked nothing, and a body holding only
comments is refused for the same reason. Comment markers inside a string
literal are source, not comments.

**Run Tests** runs every case in its own JShell session and shows the passed,
failed and stopped counts on a separate line above the output, so a verdict is
never mixed into what a script printed. A case passes only when it finishes with
no compile errors, exceptions or assertion failures; a failure does not stop the
remaining cases, and each failing case is named with its first diagnostic.
Pressing **Stop** interrupts the current case, reports it as stopped rather than
failed, and does not start the remaining cases. If a run ends before every case
ran, the report shows the executed count against the suite's size — for example
`1 of 2 case(s) ran` — so a partial total is not read as the number of cases the
suite holds. The report line
carries the counts and that first diagnostic; the **Test details** tab carries every
case's printed output and all of its diagnostics and exceptions, while the **Output**
tab stays ordinary run output. Opening another file or loading the example clears
the previous suite's report and details, because they described the buffer that was
replaced. Like Run, the first execution asks for the trust acknowledgement.

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

Authorize in **Tools → SMART Launch Workbench...** before opening the console. The
session is shared with the console and stays in application memory after the Workbench
closes. Press **Log out** in the Workbench to revoke it, or exit the application; it is
never written to disk.

## Sample workflows

The console runs read-only in this build (`WritePolicy.READ_ONLY`), so every example below
uses reads and local assertions. Treat any script that would write as destructive even when
a future build enables writes.

### Read one resource and show it

```java
var patient = fhir.read("Patient", "example-id");
if (patient == null) {
    log.warn("Patient example-id was not found.");
} else {
    viewer.showResource(patient);
    log.info("Loaded " + patient.fhirType() + "/" + patient.getIdElement().getIdPart());
}
```

`fhir.read` returns `null` for a 404; other failures throw `ScriptApiException`.

### Page through a search

```java
var page = fhir.search("Patient", new SearchCriterion("name", "Smith"));
int seen = 0;
while (true) {
    seen += page.size();
    if (!page.hasNextPage()) break;
    page = fhir.nextPage(page.nextPageToken());
}
log.info("Saw " + seen + " patient(s) on these pages");
```

`fhir.searchRaw("Patient", "name:exact=Smith&_count=50")` sends the query string exactly as
written, including prefixes, chains, `_sort` and `_count`.

### Validate against a profile

```java
var patient = fhir.read("Patient", "example-id");
if (patient != null) {
    var report = validation.validate(patient,
            "http://hl7.org/fhir/StructureDefinition/Patient");
    log.info(report.getSummary());
}
```

`validation.validate(patient)` uses the base specification. An invalid resource produces a
report with issues; it does not throw.

### Check capabilities before asking

```java
var info = server.activeServer();
if (info == null) {
    log.warn("Select a server in Tools > FHIR Servers first.");
} else if (!server.supports("Patient", ServerInteraction.READ)) {
    log.warn(info.name() + " does not advertise Patient read.");
} else {
    viewer.showResource(fhir.read("Patient", "example-id"));
}
```

### Process a page in order

```java
var page = fhir.search("Patient", new SearchCriterion("_count", "20"));
var result = batch.process(page.resources(), resource -> {
    var report = validation.validate(resource);
    if (!report.isValid()) {
        log.warn(resource.fhirType() + " has validation issues");
    }
    return resource;
});
log.info(result.processed() + " processed, " + result.failed() + " failed, "
        + result.skippedOrCancelled() + " skipped");
```

`batch.process` is sequential. `BatchErrorPolicy.STOP_ON_FIRST_ERROR` stops after the first
failure and marks the rest skipped; the default `CONTINUE` records every failure and keeps
going. `batch.cancel()` is best effort and checked between items.

### Run a suite

A buffer with `// case:` markers is a suite for **Run Tests**:

```java
// case: arithmetic
test.assertEquals(4, 2 + 2);

// case: server-read
var patient = fhir.read("Patient", "example-id");
test.assertTrue(patient != null, "Patient example-id should exist");
test.assertEquals("Patient", patient.fhirType());
```

Each case runs in its own session, so the second case cannot use the first case's variables.
Parsing refuses duplicate case names and a case with no source, including a body holding only
comments. A failed case does not stop later cases; Stop marks the current case stopped and
leaves later cases unrun. The report gives passed/failed/stopped counts, and Test details holds
each case's output and full diagnostics.

### Read the SMART launch patient

After authorizing in the Workbench, `smart` exposes the granted context:

```java
if (!smart.isSessionActive()) {
    log.warn("Authorize in Tools > SMART Launch Workbench first.");
} else {
    String patientId = smart.launchPatientId();
    if (patientId == null || patientId.isBlank()) {
        log.warn("The launch granted no patient context.");
    } else {
        var patient = smart.read("Patient", patientId);
        if (patient != null) {
            viewer.showResource(patient);
        }
    }
}
```

`smart.read` enforces the session's scopes and expiry; it throws `ScriptApiException` when
no session is active. Raw tokens are never exposed.

## Security and troubleshooting

JShell runs Java with the user's permissions and is not a sandbox. The console is
read-only in this build. Common errors: no selected server, auth failure, missing
scope, unsupported operation, compile errors, runtime-module problems, cancellation
limitations. Examples use synthetic identifiers and read-only operations by default.

## Scripting automation

Opening a saved script loads its text into the editor and never runs it. A buffer
with a file behind it runs in a fresh JShell session each time, so a saved script
starts clean and leaves no state behind. The unsaved editor keeps the interactive
session, and **Reset Session** discards that state.
Use the `test` assertions to check what a script found; a failed assertion appears
in the output as an `AssertionError` with its message, like any other exception.
Treat every script that writes or deletes as destructive and point it at synthetic
data. Press **Run** to execute a workflow script as one buffer, or **Run Tests** to
execute its `// case:` suites without leaving the console.

## API reference details

### FhirApi (`fhir`)
- `IBaseResource read(String resourceType, String resourceId)`
- `SearchResults search(String resourceType, SearchCriterion... criteria)`
- `SearchResults searchRaw(String resourceType, String query)`
- `IBaseResource create(IBaseResource resource)` (write policy)
- `ServerWriteResult update(IBaseResource resource)` (write policy)
- `void delete(String resourceType, String resourceId)` (write policy)
- `ServerWriteResult patch(String resourceType, String resourceId, String body, PatchFormat format)` (write policy)
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
- `void assertNotNull(Object actual)` / `assertNotNull(Object, String)`
- `void assertNull(Object actual)` / `assertNull(Object, String)`
- `void assertThrows(Class<? extends Throwable> expectedType, Executable executable)` / with message
- `void assertDoesNotThrow(Executable executable)` / with message
- `void fail()` / `fail(String)`

### BatchProcessor (`batch`)
- `BatchResult process(List<IBaseResource> resources, BatchAction action)`
- `BatchResult process(List<IBaseResource> resources, BatchAction action, BatchErrorPolicy policy)`
- `BatchResult process(List<IBaseResource>, BatchAction, BatchErrorPolicy, ProgressListener)`
- `void cancel()` / `boolean isCancelled()` / `void resetCancellation()`

`BatchResult`: `total()`, `processed()`, `failed()`, `skipped()`, `skippedOrCancelled()`,
`allProcessed()`, `hasFailures()`, `items()`, `duration()`, `cancelled()`.

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
