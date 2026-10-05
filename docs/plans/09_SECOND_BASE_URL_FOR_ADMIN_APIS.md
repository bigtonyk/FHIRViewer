# Phase 9 — A Second Base URL for Vendor Administration APIs

> **Corrected after reading Smile's documentation.** This plan originally said Smile had
> **no** addressable operations. That was wrong, and checking the documentation rather than
> the plan is what caught it. Smile exposes a reindex family — `$reindex`,
> `$reindex-dryrun` and `$mark-all-resources-for-reindexing` — as **ordinary operations on
> the FHIR endpoint**, not on the JSON Admin API. Those are now declared and work with the
> single base URL already configured.
>
> What remains true is narrower: Smile's **JSON Admin API** (`/version/`, user and session
> management, partition configuration) really is on a separate port and really is
> unreachable today. That is still worth doing, and this plan still covers it — it just no
> longer has a whole plugin's usability resting on it.

## Goal

Let a plugin address a vendor's administration API when it is served from a
**different origin** than the FHIR endpoint, so Smile CDR's JSON Admin API
becomes reachable from the generic operation screen.

Not an emergency, and not a bug. It is the reason Smile's **Admin JSON API**
endpoints are absent from the operation screen, while its reindex operations —
which live on the FHIR endpoint — are present.

## Why this exists

A server definition carries one base URL, and that is the FHIR endpoint:

```java
public interface FhirServerConfiguration {
    String baseUrl();   // the FHIR root, e.g. https://example.com/fhir
}
```

`JdkHttpRestClient.forSession` builds every request from
`session.server().baseUrl()`, and `PluginOperationClient` resolves an
operation's path template against it. A plugin therefore **cannot address a
different origin**, by construction.

That is fine when the admin API shares the FHIR origin, and fine when the
operations live on the FHIR endpoint itself. It is a problem only for a
genuinely separate origin:

| | Firely | Smile CDR |
|---|---|---|
| FHIR endpoint | `https://server.fire.ly` | `https://host:8000/fhir` |
| Administration API | `https://server.fire.ly/administration` | `https://host:9000` |

Firely's is a **branch of the same origin**, so the existing Firely
declarations work unchanged. Smile's JSON Admin API is a **separate port**, so
declaring `version/` today would send the request to
`https://host:8000/fhir/version/` and fail against every real server.

Smile's *reindex* operations are unaffected: they are served from the FHIR
endpoint, so the single base URL reaches them, and they are already declared.

## Important

**Verify every path against a running server before declaring it.** This has
already gone wrong twice on this branch, in ways a review would not have caught:

1. The Firely administration branch is `/administration`, not the plausible
   `/admin`. Guessing would have shipped fifteen broken operations that looked
   entirely correct. It was caught only by querying a live server.
2. `hapi.fhir.org/baseR4` was supplied as "the Smile CDR test server". Its
   CapabilityStatement reports `HAPI FHIR Server`. A different product entirely.

There is currently **no reachable public Smile CDR endpoint** — the
`cds-smile-dev...startup.fhir.cloud` host does not respond — so the
declarations this phase enables cannot be verified the way Firely's were. Either
stand up a local Smile CDR (a Docker image is the cheapest route) or accept
that the Smile paths are documentation-derived and untested. Say which, in the
commit that adds them.

## What to build

### 1. An optional second base URL

Add to `FhirServerConfiguration`, and to `ServerDefinition.Builder`:

```java
/** The administration base URL, or {@code null} to share the FHIR origin. */
String administrationBaseUrl();
```

Semantics, chosen to be boring:

- **`null` means "same origin as the FHIR endpoint."** The overwhelmingly
  common case, and the default. Firely needs no configuration at all.
- An explicit value is an **origin** (`https://host:9000`), not a full path.
  The plugin's path template is appended to it.
- Never persisted with a credential in it, and never sent to a log line — the
  same rules `server-definitions.properties` already follows.

### 2. Let the transport use it

`JdkHttpRestClient.forSession(session)` should prefer
`administrationBaseUrl()` when the request is an administration one, and fall
back to `baseUrl()` otherwise. Two viable shapes:

- **(a) A flag on the request.** Explicit, and the caller decides. Preferred:
  it makes "this is an admin call" visible at the call site rather than inferred
  from the path.
- **(b) A second client.** `PluginOperationClient` takes the base URL it should
  use. Smaller diff, but the decision then lives in the plugin, which is where
  it was trying not to be.

Record which was chosen, and why, in the code.

### 3. Persistence and UI

- `ServerDefinitionStore`: add the field to the block, omitting it when null so
  existing files keep loading unchanged.
- `ServerDialog`: one optional field, clearly labelled as usually left blank.
  It must not become required — that would break every existing user.
- The existing `ServerDefinitionPersistenceTest` covers round-tripping; extend it
  rather than writing a new class.

### 4. Declare the Smile operations

Only once the path can be resolved. From the Smile CDR documentation:

| Operation | Path | Verb |
|---|---|---|
| Version | `version/` | GET |
| System config | `config/` | GET |
| Runtime status | `runtime-status/` | GET |
| Metrics | `metrics/` | GET |
| OpenID Connect clients | `openid-clients/` | GET |
| OpenID Connect sessions | `openid-sessions/` | GET |
| Privacy notice | `privacy-notice/` | GET |
| User list | `user-management/{node_id}/{module_id}/users` | GET |
| Invalidate all sessions | `user-management/{node_id}/{module_id}/invalidate-all-sessions?username={username}` | POST |

All require the `ACCESS_ADMIN_JSON` permission, so each is marked
`requiresAuthentication()` — which also means a locked session shows the
"declares N, none can run" message added in Phase 7 rather than an empty list.

**Read-only where read-only suffices.** `invalidate-all-sessions` revokes live
tokens and is deliberately the only mutating entry here. Add the rest only when
they are wanted and verified.

## Done

Implemented, in four commits. 603 tests at the end of this phase, all passing;
614 once the UI work that followed was added.

### 1. The optional URL

`FhirServerConfiguration.administrationBaseUrl()` — a **default** method returning `null`.
Phase 29 ran immediately before this and found that a new *abstract* member would break every
existing configuration implementation, so the default is not a convenience here, it is the
requirement. `LegacyPlugin` keeps compiling because of it.

`ServerDefinition` carries it, and `ServerDefinitionStore` writes the key **only when set**,
so the settings file of the ordinary server is byte-identical to before.

### 2. The transport

Option **(a) from the plan** — a flag on the request — and the plan's reason for preferring
it holds: "this is an administration call" is a fact at the call site, because the plugin
declared it. Inferring it from the path would be a guess, and a wrong guess sends a
credential to an address the user did not choose.

- `RestRequest.administration()` / `isAdministration()`
- `ServerOperation.administration()` — what the plugin states
- `PluginOperationClient` copies it onto the request
- `JdkHttpRestClient` holds both origins and picks per request

**A flagged request with no configured administration URL falls back to the base URL.**
Firely's administration API is a branch of the same origin, so its operations are flagged;
failing there would break every existing Firely server.

### 3. Persistence and UI

Three tests in `ServerDefinitionPersistenceTest`, extended rather than duplicated: the URL
survives a restart, a server without one writes no key, and a hand-written file predating
the field still loads.

One optional field in the add-server form, below the fields that matter, with a tooltip
saying it is usually blank.

### 4. The Smile operations

Nine, all flagged and all requiring credentials: `version/`, `config/`, `runtime-status/`,
`metrics/`, `openid-clients/`, `openid-sessions/`, `privacy-notice/`,
`user-management/{node_id}/{module_id}/users`, and the one deliberately mutating entry,
`.../invalidate-all-sessions?username=`.

**These are the unverified option the plan asked to be stated explicitly.** There is no
reachable public Smile CDR, so every path is taken from Smile's documentation and none has
been sent anywhere. That is recorded in the code beside the declarations, not only here,
because a wrong path fails on every real server while looking entirely correct — which is
how Firely's `/administration` branch went wrong on this branch once already. `version` is
the first thing to try against a real instance, and it is the cheapest to correct if the
shape is wrong.

## Also changed

`SmileCdrPluginOperationsTest.operationsAreOnTheFhirEndpoint` asserted that **no** Smile
operation used the admin API. That premise was correct when written and is now false, so it
was narrowed rather than deleted: the re-index operations must still not be flagged, and the
admin ones must be. The old test also referenced this file under its pre-rename name.

## Not done

- **No live verification** of any Smile path, for the reason above.
- **The Smile Web Admin Console** is a browser UI, not a REST API, and is unreachable from
  the operation screen entirely.
- **No per-invocation URL entry.** A user-typed host and port on every run is a misdelivery
  hazard and a second credential path; if the configured URL is wrong the operation should
  fail visibly against it.

## Done when

- A server with no admin URL behaves exactly as it does today — no regression
  for Firely, no change for anyone who leaves the field blank.
- A server with an admin URL sends its administration operations there and its
  FHIR reads to the FHIR endpoint, proved by a test that distinguishes the two.
- An existing `server-definitions.properties` still loads.
- The add-server screen works with the field blank.
- Every declared Smile path has been either verified against a running server
  or explicitly marked unverified in a comment.
