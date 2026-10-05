# Phase 29 — Backward Compatibility

## Verdict

**Nothing broke.** Everything that worked before the REST work still works, and the evidence
is a compile rather than a claim.

## The five checks

| Check | Result | Evidence |
|---|---|---|
| Existing plugins still load | Pass | `LegacyPlugin` is discovered by the registry |
| Plugin discovery still works | Pass | `legacyPluginIsDiscovered` |
| Existing functionality still works | Pass | `legacyPluginStillReadsAndSearches` — read and search |
| Existing configuration files remain compatible | Pass | `pluginKeyedCredentialFileStillLoads`, `missingConfigurationFileIsNotAnError` |
| Existing UI functionality remains intact | Pass | Full suite: 593 tests, 0 failures |

## How the source-compatibility claim is proved

`LegacyPlugin` implements **exactly the eleven methods that were abstract in `295c97f`** — the
last commit before the plugin interface grew — and overrides nothing added since: no
`availableOperations`, no `create`, `update`, `delete`, `patch`, no `pageAt`, no
`vendorActions`, no `execute`.

That is the whole test, and it is a **compile-time** one. If a method added since loses its
default, `LegacyPlugin` stops compiling and the build fails here rather than in somebody's
plugin. A comment asserting "they all have defaults" would have been worth much less.

What the interface gained, and how:

| Member | Kind | Backward compatibility |
|---|---|---|
| `credentialKey()` | new, `default` | Inherited default keys on the base URL |
| everything else added | `default` | Falls back or raises `ServerOperationException` |

`operationsAddedLaterFailCleanly` pins the *shape* of a default as well as its existence: a
plugin that predates `create` must raise `ServerOperationException` — something the UI can
show — not `AbstractMethodError`, which would escape as a crash.

## The finding worth keeping

The plugin-keyed credential fallback **existed but had no test**. It is the single thing
standing between an existing user's saved password and a silent loss, and it could have rotted
without anything noticing. It is now covered from both directions:

- `pluginKeyedCredentialFileStillLoads` — a hand-written, pre-per-server-key file still loads
- `pluginKeyedCredentialIsNotGivenElsewhere` — **and is not handed to a different server**

That second one matters more than the first. Relaxing the fallback to "any plugin-keyed entry
will do" would send one server's password to a different host. The base URL check is the only
thing preventing that, and it is now a test rather than a comment.

## Not covered

- **No third-party plugin was run.** `LegacyPlugin` reproduces the interface contract, not
  anyone's actual code. A plugin that reaches into internals rather than the interface could
  still break.
- **No migration test from a real old install.** The legacy file is hand-written in the old
  format, which is what the code actually has to read, but it was not taken from a backup.
- **Binary compatibility is not claimed.** A plugin jar compiled against an older interface
  is fine as long as it was compiled against *this* interface's shape; it is not guaranteed
  to run against a future one that changes a signature.

## Next phase

Nothing here constrains Phases 9 or 10, with one exception worth stating: Phase 9 adds
`administrationBaseUrl()` to `FhirServerConfiguration`. It must be a **default method
returning null**, for the same reason `credentialKey()` was — otherwise every existing
configuration implementation breaks, which is precisely what this phase exists to prevent.