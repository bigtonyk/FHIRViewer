# Phase 5: Advanced Scripting — Batch Processing and Transformations

This section documents the batch processing infrastructure added in Phase 5.
It enables scripts to process collections of FHIR resources sequentially with
configurable error handling, cancellation, and progress reporting.

## `batch` Binding

Scripts receive a `final BatchProcessor batch` variable via `ScriptBindings`.
Example usage:

```java
// Process a list of patients, continuing on failures
var results = batch.process(patients, r -> {
    // Transform each resource
    return transform(r);
}, BatchErrorPolicy.CONTINUE);

// Stop on first error
var results2 = batch.process(patients, r -> {
    return transform(r);
}, BatchErrorPolicy.STOP_ON_FIRST_ERROR);
```

## BatchProcessor API

### `process(resources, action)` — Continue on failure

```java
BatchResult process(List<IBaseResource> resources, BatchAction action)
```

Processes all items with `BatchErrorPolicy.CONTINUE`. Returns a `BatchResult`
with per-item outcomes.

### `process(resources, action, policy)` — Configured error policy

```java
BatchResult process(List<IBaseResource> resources, BatchAction action, BatchErrorPolicy policy)
```

- `BatchErrorPolicy.STOP_ON_FIRST_ERROR` — stops processing after the first
  failure; remaining items are recorded as skipped.
- `BatchErrorPolicy.CONTINUE` — processes all items regardless of failures.

### `process(resources, action, policy, listener)` — With progress listener

```java
BatchResult process(List<IBaseResource> resources,
                    BatchAction action,
                    BatchErrorPolicy policy,
                    ProgressListener listener)
```

Notifies `listener.onItem(result)` after each item completes. Useful for UI
progress bars.

### `process(resources, action, listener)` — Continue with listener

```java
BatchResult process(List<IBaseResource> resources, BatchAction action, ProgressListener listener)
```

Same as `process(resources, action, BatchErrorPolicy.CONTINUE, listener)`.

## Cancellation

Best-effort cancellation via `batch.cancel()`:

```java
// Cancel after starting; remaining items will be skipped
batch.cancel();
```

The flag is checked between items. An in-flight item always runs to completion.

## Per-Item Outcomes (`BatchItemResult`)

Each item result contains:

| Field | Type | Description |
|-------|------|-------------|
| `index()` | `int` | Zero-based position in the input list |
| `input()` | `IBaseResource` | The original input resource |
| `output()` | `IBaseResource` | The transformed output (or `null` on failure) |
| `processed()` | `boolean` | `true` if processing succeeded |
| `failed()` | `boolean` | `true` if an exception was thrown |
| `skipped()` | `boolean` | `true` if item was skipped (cancellation) |
| `message()` | `String` | Error message if failed, otherwise empty |
| `duration()` | `Duration` | Time elapsed for this item |
| `startedAt()` | `Instant` | When processing started for this item |

## Summary Results (`BatchResult`)

```java
int total()        // total items
int processed()    // items that succeeded
int failed()       // items that failed
int skipped()      // items skipped (cancellation)
boolean cancelled()// true if cancellation was requested
Duration elapsed() // total processing time
Instant startedAt()// when processing started
List<BatchItemResult> items() // per-item outcomes
boolean allProcessed() // true when no failures/skips
boolean hasFailures() // true when any item failed
```

## Error Policies

- **`BatchErrorPolicy.STOP_ON_FIRST_ERROR`**: Processing halts at the first
  failure. Remaining items are recorded as `skipped()`.
- **`BatchErrorPolicy.CONTINUE`**: All items are processed. Failures are
  recorded per-item but do not stop the batch.

## Example: Full Batch Workflow

```java
import com.spiraleyes.fhirviewer.service.scripting.*;
import com.spiraleyes.fhirviewer.service.scripting.api.BatchErrorPolicy;

// Read patients from the server (example)
var patients = List.of(
    fhir.read("Patient", "123"),
    fhir.read("Patient", "456")
);

// Process all, continuing on failures
var result = batch.process(patients, r -> {
    // Mutate or validate each resource
    return r;
}, BatchErrorPolicy.CONTINUE);

if (result.hasFailures()) {
    for (var item : result.items()) {
        if (item.failed()) {
            log.error("Failed item " + item.index() + ": " + item.message());
        }
    }
}

// Check results
log.info("Processed: " + result.processed() + "/" + result.total());
for (var item : result.items()) {
    if (item.processed()) {
        log.info("Item " + item.index() + " OK, output: " + item.output());
    }
}
```

## Testing

The test class `BatchProcessorTest` validates:

- All items processed successfully
- Continue policy records all outcomes (some fail, some succeed)
- Stop policy halts on first failure
- Empty list produces empty result
- Cancellation skips remaining items
- Reset cancellation allows reuse
- Default policy is CONTINUE
- Item duration is recorded

Run: `.\mvnw.cmd test -Dtest=BatchProcessorTest`