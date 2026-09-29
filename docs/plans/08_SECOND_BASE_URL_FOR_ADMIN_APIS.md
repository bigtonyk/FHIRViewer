# Phase 8 — A Second Base URL for Vendor Administration APIs

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

## Not in scope

- **The Smile Web Admin Console.** A browser UI, not a REST API; it is not
  reachable from the operation screen at all.
- **Per-invocation port entry** (the rejected option 2). A user-typed host and
  port on every run is a misdelivery hazard and a second credential path. If the
  configured admin URL is wrong, the operation should fail against the configured
  value, visibly, not be redirected by a field on the form.
- **Standard FHIR REST and Smile `$`-operations.** Neither declares any today.
  Worth a separate look; the HAPI test server is a reasonable target for the
  former, but that is its own piece of work.

## Done when

- A server with no admin URL behaves exactly as it does today — no regression
  for Firely, no change for anyone who leaves the field blank.
- A server with an admin URL sends its administration operations there and its
  FHIR reads to the FHIR endpoint, proved by a test that distinguishes the two.
- An existing `server-definitions.properties` still loads.
- The add-server screen works with the field blank.
- Every declared Smile path has been either verified against a running server
  or explicitly marked unverified in a comment.
