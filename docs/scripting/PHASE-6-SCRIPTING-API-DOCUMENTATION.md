# Phase 6: Scripting API Reference

This document provides the complete API reference for script authors, documenting every class, interface, and method exposed in the FHIRViewer scripting environment.

## Top-Level Bindings

Scripts receive these final variables via `ScriptBindings`:

| Variable | Type | Description |
|---|---|---|
| `fhir` | `FhirApi` | Read/search/write FHIR resources on the selected server |
### `FhirApi`

**Reading:**
- `IBaseResource read(String resourceType, String resourceId)` — Returns the resource, or `null` if not found
- `SearchResults search(String resourceType, SearchCriterion... criteria)` — Execute a search, returns one page

**SearchResults:**
- `List<IBaseResource> resources()` — Resources on the current page
- `int total()` — Total number of resources across all pages
- `String nextPageToken()` — Token for the next page, `null` if this is the last page
- `String previousPageToken()` — Token for the previous page, `null` if this is the first page
- `boolean hasNextPage()` — True if more pages are available
- `boolean hasPreviousPage()` — True if not on the first page
- `int size()` — Number of resources on the current page

**Writing (requires WritePolicy to be enabled):**
- `IBaseResource create(IBaseResource resource)` — Create a new resource
- `ServerWriteResult update(IBaseResource resource)` — Update an existing resource
- `void delete(String resourceType, String resourceId)` — Delete a resource

**Paging:**
- `SearchResults nextPage(String pageToken)` — Load the next page
- `SearchResults previousPage(String pageToken)` — Load the previous page

**WritePolicy behavior:**
- Writes are refused unless the session was started with an enabled `WritePolicy`
- `WritePolicy.READ_ONLY` — Writes disabled by default
- `WritePolicy.enabled()` — Writes permitted
- `WritePolicy.enabled(Confirmation)` — Writes permitted with a confirmation handler
| `viewer` | `ViewerApi` | Display resources/results/text in the console |
### `ViewerApi`

- `void showResource(IBaseResource resource)` — Display a resource summary and JSON representation
- `void showSearchResults(SearchResults results)` — Display a paginated search result set
- `void showText(String text)` — Display arbitrary text
| `validation` | `ValidationApi` | Validate FHIR resources |
### `ValidationApi`

- `ValidationReport validate(IBaseResource resource)` — Validate against base definition and auto-detected profiles
- `ValidationReport validate(IBaseResource resource, String profileCanonical)` — Validate against a specific profile by canonical URL
| `server` | `ServerApi` | Inspect configured servers and capabilities |
### `ServerApi`

- `ServerInfo activeServer()` — Returns the active server, or `null` if none is selected
- `List<ServerInfo> servers()` — List all configured servers
- `ServerCapabilities capabilities()` — Get the active server's capabilities
- `boolean supports(String resourceType, ServerInteraction interaction)` — Check if a resource type is supported by a given interaction type

**ServerInfo:**
- `String name()` — Server name
- `String baseUrl()` — Base URL of the server
- `String fhirVersion()` — FHIR version reported by the server
- `String pluginId()` — Identifier of the plugin managing this server

**ServerCapabilities:**
- Methods for checking resource support and interactions (details depend on the underlying HAPI FHIR implementation)
| `log` | `LogApi` | Print info/warn/error messages |
### `LogApi`

- `void info(String message)` — Log an informational message
- `void warn(String message)` — Log a warning message
- `void error(String message)` — Log an error message
| `test` | `TestApi` | Assertions for automated tests |
### `TestApi`

**Assertions (throw `AssertionError` on failure):**
- `void assertTrue(boolean condition)` / `assertTrue(boolean, String)`
- `void assertFalse(boolean condition)` / `assertFalse(boolean, String)`
- `void assertEquals(Object expected, Object actual)` / `assertEquals(Object, Object, String)`
- `void assertNotNull(Object actual)` / `assertNotNull(Object, String)`
- `void assertNull(Object actual)` / `assertNull(Object, String)`

**Control:**
- `void assertThrows(Class<? extends Throwable> expectedType, Executable executable)` / `assertThrows(expectedType, executable, String)`
- `void assertDoesNotThrow(Executable executable)` / `assertDoesNotThrow(executable, String)`
- `void fail()` / `fail(String)`

**Executable interface:**
- `void execute() throws Throwable` — Block of code that an assertion can wrap
| `smart` | `SmartApi` | Read through an authorized SMART session |
### `SmartApi`

- `boolean isSessionActive()` — True when a SMART session is active and not expired
- `List<String> grantedScopes()` — Granted OAuth scopes from the SMART launch
- `String launchPatientId()` — Patient ID from the SMART launch
- `String launchEncounterId()` — Encounter ID from the SMART launch
- `IBaseResource read(String resourceType, String id)` — Read a resource through an authorized session
| `batch` | `BatchProcessor` | Process collections of FHIR resources sequentially |
### `BatchProcessor`

**Processing:**
- `process(List<IBaseResource> resources, BatchAction action)` — Process all items with `BatchErrorPolicy.CONTINUE`. Returns a `BatchResult` with per-item outcomes.
- `process(List<IBaseResource> resources, BatchAction action, BatchErrorPolicy policy)` — Process with a configured error policy.
  - `BatchErrorPolicy.STOP_ON_FIRST_ERROR` — Stop after the first failure; remaining items recorded as skipped.
  - `BatchErrorPolicy.CONTINUE` — Process all items regardless of failures.
- `process(List<IBaseResource> resources, BatchAction action, BatchErrorPolicy policy, ProgressListener listener)` — With a progress listener that receives `onItem(result)` after each item.
- `process(List<IBaseResource> resources, BatchAction action, ProgressListener listener)` — Same as above with `BatchErrorPolicy.CONTINUE`.

**Cancellation:**
- `batch.cancel()` — Best-effort cancellation. The flag is checked between items; in-flight items always complete.

**Per-Item Outcomes (`BatchItemResult`):**

| Field | Type | Description |
|---|---|---|
| `index()` | `int` | Zero-based position in the input list |
| `input()` | `IBaseResource` | The original input resource |
| `output()` | `IBaseResource` | The transformed output (or `null` on failure) |
| `processed()` | `boolean` | True if processing succeeded |
| `failed()` | `boolean` | True if an exception was thrown |
| `skipped()` | `boolean` | True if item was skipped (cancellation) |
| `message()` | `String` | Error message if failed, otherwise empty |
| `duration()` | `Duration` | Time elapsed for this item |
| `startedAt()` | `Instant` | When processing started for this item |

**Summary Results (`BatchResult`):**

| Method | Description |
|---|---|
| `int total()` | Total items |
| `int processed()` | Items that succeeded |
| `int failed()` | Items that failed |
| `int skipped()` | Items skipped (cancellation) |
| `boolean cancelled()` | True if cancellation was requested |
| `Duration elapsed()` | Total processing time |
| `Instant startedAt()` | When processing started |
| `List<BatchItemResult> items()` | Per-item outcomes |
| `boolean allProcessed()` | True when no failures/skips (vacuously true for empty list) |
| `boolean hasFailures()` | True when any item failed |

**Error Policies (`BatchErrorPolicy`):**

- **`STOP_ON_FIRST_ERROR`**: Processing halts at the first failure. Remaining items are recorded as `skipped()`.
- **`CONTINUE`**: All items are processed. Failures are recorded per-item but do not stop the batch.

**Example Workflow:**

```java
var patients = List.of(
    fhir.read("Patient", "123"),
    fhir.read("Patient", "456")
);

var result = batch.process(patients, r -> {
    // Transform each resource
    return r;
}, BatchErrorPolicy.CONTINUE);

if (result.hasFailures()) {
    for (var item : result.items()) {
        if (item.failed()) {
            log.error("Failed item " + item.index() + ": " + item.message());
        }
    }
}

log.info("Processed: " + result.processed() + "/" + result.total());
for (var item : result.items()) {
    if (item.processed()) {
        log.info("Item " + item.index() + " OK, output: " + item.output());
    }
}
```