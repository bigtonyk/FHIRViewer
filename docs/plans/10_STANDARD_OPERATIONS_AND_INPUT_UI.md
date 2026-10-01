# Phase 10 — Standard FHIR Operations, and an Input UI Worth Using

## Goal

> **Partly done.** The search screens now take several parameters and offer a raw search
> string; see *Done so far* below. Declaring the specification's operations is **not** started.

Declare the operations the FHIR specification defines, so a plain FHIR server offers
more than bulk export and import — and make the screen that runs them usable for the
operations that need input.

Triggered by two observations while using the operation screen:

- **A standard server shows only 2 operations.** `StandardFhirRestPlugin` declares
  `$export` and `$import` and nothing else. The specification's operations are absent
  from the codebase entirely — `$validate`, `$everything`, `$expand`, `$lookup`,
  `$convert`, `$patient`, `$content`, `$graphql` and `$subscription` are not declared
  anywhere.
- **The screen cannot take real input.** There is a free-text request body and
  auto-generated fields for declared parameters, but nothing that makes composing an
  operation call practical.

## Done so far

### Search: several parameters, and a raw search string

Both search screens (`ServerSearchDialog` and `OpenFromServerDialog`) previously took one
name/value pair. `SearchCriteriaEditor` replaces that pair with two ways to describe a
search:

- **Parameters** — any number of name/value rows, added and removed with buttons. A blank
  row means "no filter", which is how you browse a whole resource type.
- **Search string** — one field, sent to the server exactly as typed.

The raw form exists because a search is often *written down* — copied from a browser address
bar, a specification example or a colleague — and prefixes (`name:exact`), modifiers
(`name:contains`), chains (`subject.name`), `_sort`, `_count` and vendor extensions all
work, because nothing parses it. You may paste `Patient?name=Smith`, `?name=Smith` or just
`name=Smith`; the type and the question mark are stripped and the rest kept as typed.

Three decisions worth recording:

1. **Raw searches take a different route**, not a normal criterion. They are assembled into
   the path and sent through `JdkHttpRestClient`, because HAPI's query builder cannot put
   text on a query string untouched: splitting on `&` would double-encode any value the user
   had already escaped, and `withAdditionalParameter` would wrap the whole thing in a
   parameter of its own. `RawSearchTest` asserts on the request line the server actually
   received, including that `%20` is not turned into `%2520`.
2. **The two kinds cannot be mixed.** `SearchRequest` refuses a request holding both, because
   they travel by different routes and have no single correct answer. This matters: with raw
   criteria skipped by the normal path, a mixed request would have been sent with *nothing
   applied* — returning every resource of the type. An empty raw search is refused for the
   same reason.
3. **Switching mode clears the other one**, so a half-typed parameter cannot be carried into a
   raw search.

Two behaviours the tests pinned that were not obvious:

- The structured path renders `matchesExactly()` as `name%3Aexact=value`. That is HAPI being
  explicit about the modifier, and is correct — the raw path is what sends a search *without*
  that normalisation.
- The editor's `applyMode` runs last and was re-enabling Remove on a single row, where there
  is nothing left to remove.

### The Firely operation-count question

Answered while writing this plan and kept for the record: it was **not** an authentication
limitation — `supportedOperations` applies no auth filter at all — and the real cause was the
*Server type* list defaulting to the standard plugin. See *Step 3*.

---

## Why it is not an authentication limitation

Worth stating because it was the first guess, and it is wrong:

```java
default List<ServerOperation> supportedOperations(ServerSession session) {
    return availableOperations();   // no auth check; no plugin overrides this
}
```

Discovery applies **no** authentication filter. Operations marked as needing credentials
still appear in the list; only running one is refused. So nothing is being hidden from
the user — the list is short because the declarations are short.

## Step 1 — Declare the standard operations

On `StandardFhirRestPlugin`, so both vendor plugins inherit them.

**System level** (path `$op`, all against the base URL):

| Operation | Method | Input | Notes |
|---|---|---|---|
| `$validate` | POST | Resource body | Validate a resource against a profile. The most obviously missing one for a viewer. |
| `$expand` | POST | ValueSet body; query `url`, `valueSetVersion`, `timestamp`, `count`, `includeDesignations` | Terminology expansion. |
| `$lookup` | POST | `Parameters` body | Code-to-resource lookup. |
| `$convert` | POST | Resource body; query `from`, `to` | Version conversion, which this viewer in particular has use for. |
| `$validate-code` | POST | `Parameters` | |
| `$validate-value` | POST | `Parameters` | |
| `$content` | GET | query `_format`, `_mimeType` | XHTML rendering. |
| `$graphql` | POST | `{"query": ...}` | |
| `$data-absent-reason` | POST | Resource | Rare, but declared by several servers. |
| `$export-poll-status` | GET | path `{id}` | Without this a bulk job cannot be followed. |
| `$import-poll-status` | GET | path `{id}` | Without this a bulk import cannot be followed. |

**Instance level** (path `{resourceType}/{id}/$op`):

| Operation | Notes |
|---|---|
| `$everything` | Patient compartment contents. |
| `$patient` | Everything about one patient. |
| `$compartment` | |
| `$graph` | Version-aware; takes `since` / `at`. |
| `$vread` | Versioned read. |

**Type level** (path `{resourceType}/$op`):

| Operation | Notes |
|---|---|
| `$search` | POST form of a search. Needs several parameters at once, which the one-parameter search screens cannot express — see below. |
| `$history` | Also system- and instance-level. |
| `$validate` | Type-level variant. |

### Deliberately excluded

- **Anything needing a parameter the UI still cannot express.** `$search` in particular now that
  the search screens take several parameters, it is a POST body carrying a search, which
  the operation body area can express but the generated form cannot prefill.
- **Operations behind extensions** — too variable across servers to be useful.

### Verification

Paths are specification-defined, so they can be written without a server. But "defined"
is not "implemented". **Every declared path should be checked against
`hapi.fhir.org/baseR4`**, and the result recorded per operation, so the screen does not
promise fifteen operations that silently fail. An operation answering `404` or `501` is a
genuine finding about that server and should be reported as such, not hidden.

This is the part most likely to be got wrong from memory: the `/administration` versus
`/admin` mistake in the Firely work, and the wrong assumption that Smile had no
addressable operations at all, both came from exactly that.

## Step 2 — An input UI that can carry real data

The screen currently offers a free-text body area and fields for declared parameters.
That is enough for `$reindex-dryrun` and not enough for `$validate`. What is missing:

1. **Load the body from what the user already has.** An empty body area is a wall of
   typing. Seed it from the displayed resource, the selected element, or nothing — chosen
   by a picker rather than guessed.
2. **A "use the open resource" button** where the operation takes a resource. Most
   standard operations do, and the user nearly always has one open.
3. **Parameter fields that help.** Parameters may be optional, repeated, or from a fixed
   vocabulary. A free-text field each is fine for one string and poor for the rest. At
   minimum: an optional checkbox, and a hint about the expected format.
4. **Validate before sending.** A malformed body fails at the server with an opaque
   message. Parsing it locally and naming the offending line is cheap and much kinder.
5. **Repeatable parameters.** `$export` takes `_type` more than once. A field holding one
   value silently sends one.

Each is small alone. Together they are what turns "a form that can run an operation" into
"a screen a user can actually run `$validate` on".

## Step 3 — Make the server's operations visible and honest

**Confirmed root cause of "the Firely list only shows 2 operations",** found while
writing this plan:

- The **Server type** list defaults to its first entry, and the first entry is the
  standard plugin.
- A server added for a Firely endpoint without changing that is therefore saved with
  `pluginId = standard-rest`, and is served by `StandardFhirRestPlugin` — which
  declares exactly two operations.
- Nothing on screen says the plugin had not been chosen. The Firely plugin itself is
  fine and does reach the application.

**Not the cause, and worth stating so it is not "fixed" later by mistake:**

- `FirelyPlugin` is *absent from `META-INF/services` on purpose*. It is loaded from
  `fhirviewer-plugins.properties` so a user can disable it without changing the class
  path. Adding it to the service file would defeat that deny list. `PluginLoaderTest`
  already asserts this.
- `MainWindow` builds its registry with `PluginLoader.load()`, which combines service
  discovery with the configuration list, so all three plugins are available.

Two tests now pin this, so the service file cannot be "fixed" by someone who has not
found this note, and so the loader keeps reaching Firely.

The fix itself:

1. **Detect, or at least say so.** `FirelyPlugin` and `SmileCdrPlugin` can both identify
   their server from its `CapabilityStatement`. Offering the detected type when a server
   is added would prevent the mistake outright; it costs one metadata request, which
   *Test connection* already makes.
2. **Name the serving plugin.** The status screen should say which plugin is serving
   each server, so "Firely has only two operations" is distinguishable from "this is
   being served as a plain FHIR server" without the user having to know the mechanism.
3. **Mark unsupported operations.** The `requiredCapability` seam exists for this and is
   unused. An operation the server does not advertise should show as unavailable rather
   than fail on press.

The immediate workaround, with no code change: open **Tools → FHIR Servers...**, pick
the Firely server, and set **Server type** to **Firely Server**.

## Already fixed alongside this

`FirelyPlugin` was missing from `META-INF/services/...FhirServerPlugin`, so
`ServiceLoader` never discovered it and the *Server type* list never offered it. Any
server added for a Firely endpoint was saved as `standard-rest` and showed 2 operations.
Fixed, with a test that asks the registry for its plugins the way the application does —
every other test constructed plugins directly, which is why nothing noticed.

## Tests

- One per declared operation asserting its path and method, so neither can drift.
- One asserting each body requirement matches the spec's semantics.
- Round-trip checks against `hapi.fhir.org`, marked as network-dependent so the offline
  suite stays offline.
- One that a declared operation can actually be filled in and invoked through
  `ServerOperationForm` — the operation existing is not the same as it being runnable.

## What this does not do

- **No raw REST console.** It would need its own authentication, error mapping and
  paging, and is a different decision from a curated operation list.
- **No OAuth or SMART.** Still absent; `BearerServerAuthentication` is a static token.
- **No capability-driven gating** until step 3 lands, so the list is static meanwhile.