# Phase 30 — Security Review

Branch `server-rest-integration`. Reviewed at `b7f95e7`; the findings below were fixed in
the following commit.

A review activity rather than an implementation. The plan's checklist is:

> credential storage · token handling · HTTPS validation · certificate validation ·
> authorization headers · logging · sensitive resource logging · URL construction ·
> user-controlled query parameters · custom endpoint handling
>
> Do not disable TLS certificate validation as a shortcut. Do not log authentication
> credentials or bearer tokens.

Both prohibitions are satisfied — see "Clean" below.

---

## Summary

| | |
|---|---|
| Items inspected | 10 |
| **Clean** | 8 |
| **Findings** | 3 — one medium (fixed), two low (recorded, not fixed) |
| Errors found **in this review's own fix** | 2, both caught by its tests before commit |

The medium finding — credentials silently sent over plain HTTP — is now warned about in the
UI and recorded in the log. It is **not refused**, and the reasoning is given below.

## Clean

These were checked and are correct. They are recorded because a security review that only
lists problems gives no evidence about the parts that are right.

| Area | What was checked | Finding |
|---|---|---|
| **Credential storage** | `SecretBox` | AES-256-**GCM** (authenticated), PBKDF2-HMAC-SHA256 at **210,000** rounds, 256-bit key, per-encryption random 16-byte salt and random 96-bit IV, 128-bit tag. No ECB, no static IV, no reused nonce. `PBEKeySpec.clearPassword()` is called in a `finally`. |
| **Token handling** | `ServerPassphrase` | Held as `char[]`, not `String`. `copy()` hands out a copy so the caller cannot retain the live array. `clear()` overwrites before nulling. `toString()` says only `<set>`/`<none>`; **no getter returns a `String`**, so it cannot reach a log by accident. |
| **Certificate validation** | whole of `src/main` | No `TrustManager`, no `X509TrustManager`, no `SSLContext`, no `NoopHostnameVerifier`, no "trust all" of any kind. The JVM default applies, so certificates **are** verified. The plan's prohibition is met. |
| **Authorization headers** | `PluginOperationClient`, `RequestHeaders` | `RequestHeaders.isSecret(name)` refuses any operation parameter named `Authorization`, so a plugin cannot open a second, unredacted credential path around `ServerAuthentication`. The client never builds an `Authorization` value of its own. |
| **Logging** | every `log.` call in the server package | 7 log statements in total. **None** reference a password, passphrase, token or credential. Names of servers and failure kinds only. |
| **Sensitive resource logging** | whole of `src/main` | No log statement contains a resource body, a patient, or JSON. `ServerErrors.redact()` strips `Bearer <tok>` and `Basic <base64>` from every string it returns — applied to all of them rather than only the cases currently known, because a message is what a user pastes into a bug report. |
| **URL construction** | `OperationPathTemplate` | Every substituted path value is percent-encoded to the RFC 3986 **unreserved** set only. Critically, `.` is excluded, so `..` cannot survive encoding and traverse — the one traversal a plain percent-encoder does not stop. |
| **User-controlled parameters** | `PluginOperationClient`, `JdkHttpRestClient` | Query names and values are percent-encoded on both halves. Path values as above. Missing required values are an error rather than an empty segment, because `admin/export/` would be a different, valid-looking URL answering about something else. |

## Findings

### F1 — Credentials sent over plain HTTP with no warning (**medium**, fixed)

`ServerDefinition` accepts `http://`, and nothing anywhere checked the scheme before an
`Authorization` header was attached. A user configuring `http://prod.example.com/fhir` with
a password had it sent base64-encoded — encoding, not encryption — in the clear, with no
message on screen and nothing in the log.

A blanket refusal would be wrong: `http://localhost:8080` is how most local testing is done,
and loopback traffic never leaves the machine.

**Fixed.** `TransportSecurity` distinguishes loopback from remote. When credentials are set
on a non-loopback `http://` URL the server manager says so in the status line, and the log
records it at both the save and the use. **Not refused** — it is the user's decision, and a
check they cannot see past gets worked around rather than obeyed.

### F2 — Plugin enable/disable config resolves against the working directory (**low**, recorded)

`PluginConfig` uses `Path.of("fhirviewer-plugins.properties")`, a relative path. Anyone able
to write to the directory the application was launched from can change which plugins are
enabled. This is a local integrity issue, not a remote one, and it matters only where that
directory is shared or writable by another user.

**Not fixed.** Moving the file would relocate settings users already have, which is a
behaviour change beyond this review. The two settings files that *do* carry anything
sensitive — `server-definitions.properties` and `plugin-settings.properties` — are already
in `~/.fhirviewer`. Worth deciding separately.

### F3 — No TLS truststore support (**low**, recorded, pre-existing)

A server behind a corporate CA fails to connect, and the user's only recourse is a system
truststore change. Already recorded as gap 5 in `Current-Plugin-Architecture.md`.

## Two bugs in this review's own fix

Both were introduced by `TransportSecurity` and caught by `TransportSecurityTest` before
anything was committed. They are recorded because the second is exactly the class of
mistake the class exists to prevent, and finding it in my own code is the clearest evidence
the tests earn their place.

1. **`127.0.0.1.example.com` was treated as loopback.** The check was
   `host.startsWith("127.")`, which exempts a remote host whose domain happens to begin that
   way — a password sent across the internet with the warning suppressed. Replaced with a
   proper dotted-quad parse: exactly four octets, all numeric, all ≤ 255, first equal to
   `127`. Now pinned by tests for `127.0.0.1.5`, `127.0.1`, `127.0.0.999` and `127.0.0.1x`.

2. **`http://[::1]:8080/fhir` was treated as remote.** The host was extracted by scanning
   for `:` as the port separator, which stopped at the first colon *inside* the brackets and
   yielded `[`. That matches neither loopback form, so a local address produced a spurious
   warning. Fixed by handling bracketed literals, and pinned for both `[::1]` (local) and
   `[2001:db8::1]` (remote).

## What this review does not cover

Stated plainly, because a review that implies more coverage than it has is worse than none.

- **The dependencies were not audited.** HAPI FHIR, JavaFX and SLF4J are on the class path;
  none were checked for known CVEs here.
- **No dynamic testing.** No request was captured and inspected on the wire, no proxy was
  used, and the server-side behaviour of any real FHIR server was not assessed.
- **Plugins are trusted.** A plugin jar on the class path runs with full application
  privileges. `PluginJarScanner` reads jars without executing them, but an *enabled* plugin
  is arbitrary code by construction. This is inherent to the plugin model, not a defect.
- **No threat model was agreed first.** Findings are ranked by judgement, not against a
  documented attacker profile. For a viewer used against clinical systems that is worth
  writing down before acting on the ordering here.
- **The dialogs have still never been seen in a running window.** Tests press real buttons;
  they cannot see layout, and the plaintext warning is a layout-visible message.

## Recommended before merge

Phase 30 is now complete as a review. Two things it cannot do for itself:

1. **Someone other than the author should read this**, ideally against a stated threat
   model. It was written by the same person who wrote the code it reviews.
2. **Phase 29, backward compatibility**, is the remaining review activity, and is not
   started.
