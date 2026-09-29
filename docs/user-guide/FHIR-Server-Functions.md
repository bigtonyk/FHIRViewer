# FHIR Server Functions — User Guide

How to point FHIRViewer at a FHIR server, read resources from it, and write
changes back.

This guide describes the behaviour as it is in the `server-rest-integration`
branch. Where something is deliberately not built yet, it says so rather than
describing an ideal.

---

## Contents

1. [What the server functions do](#1-what-the-server-functions-do)
2. [Adding your first server](#2-adding-your-first-server)
3. [Credentials](#3-credentials)
4. [Finding a resource: three ways](#4-finding-a-resource-three-ways)
5. [Editing a resource](#5-editing-a-resource)
6. [Saving back to the server](#6-saving-back-to-the-server)
7. [Refresh, Patch and Delete](#7-refresh-patch-and-delete)
8. [Server Status and Capabilities](#8-server-status-and-capabilities)
9. [Running server operations](#9-running-server-operations)
10. [Server Tools and Server Plugins](#10-server-tools-and-server-plugins)
11. [What each plugin supports](#11-what-each-plugin-supports)
12. [Reading error messages](#12-reading-error-messages)
13. [Where your settings live](#13-where-your-settings-live)
14. [Troubleshooting](#14-troubleshooting)
15. [Known gaps](#15-known-gaps)

---

## 1. What the server functions do

FHIRViewer can talk to a FHIR R4 server over its REST API. Once you have
added a server, you can:

- Read its capabilities, to confirm which FHIR version it speaks and which
  resource types it supports.
- Search it for resources, and open one in the editor.
- Edit the resource in the normal tree, Pretty, JSON, XML and FHIRPath views.
- Write the result back, with a check that nobody else changed it in the
  meantime.
- Apply a partial update, or delete the resource on the server.
- Run operations the server's plugin offers, including vendor-specific ones.

The viewer does not need to know which server it is talking to. All of the
above work through whichever plugin serves the server, and new integrations
appear in the menus without the application changing.

> **This viewer can modify data on a live system.** Every action that writes is
> confirmed before it happens, and a write that would overwrite somebody else's
> change is never silent. Read [Saving back to the server](#6-saving-back-to-the-server)
> before your first write.



---

## 2. Adding your first server

**Tools → FHIR Servers...**

The dialog asks for five things:

| Field | What to enter |
|---|---|
| **Server name** | Any label you like. It is how you tell your servers apart, and it is the identity the viewer matches on, so names must be unique. |
| **FHIR base URL** | The server's FHIR root, e.g. `https://example.com/fhir` or `http://localhost:8080/fhir`. Must start with `http://` or `https://`. Trailing slashes are removed for you. |
| **FHIR version** | `R4` is the only choice currently. |
| **Integration** | Which plugin serves this server — see [What each plugin supports](#11-what-each-plugin-supports). Choose the vendor plugin when one is listed for your product. |
| **Authentication** | Currently shown as "None (anonymous)". See [Credentials](#3-credentials). |

Press **Test connection** to have the viewer fetch the server's
`CapabilityStatement` and report whether it is reachable. The result appears
in the dialog; the window does not freeze while the request is in flight.

Then press **Save**. The server is added and saved immediately, so it is still
there next time you start the application.

If **Save** appears to do nothing, check the message at the bottom of the
dialog: an empty name or a base URL without a scheme is refused there rather
than being stored.

### The server list

**Tools → FHIR Servers...** only adds servers. To see what is configured, open
**Tools → Server Status and Capabilities...**, which lists every configured
server and marks the active one.

---

## 3. Credentials

The add-server dialog cannot store credentials. That is not an oversight to
work around — credentials belong to the *plugin*, and one plugin can serve
several servers. They are set once per plugin:

**Tools → Server Plugins... → Settings**, for the plugin that serves your
server. Enter a user name and password, and a passphrase of your choosing.

- The password is **encrypted** before it is written to disk, using that
  passphrase. It is never stored as plain text.
- The passphrase is **not stored anywhere**. It is held for the session only
  and cleared when the application closes, so you will be asked for it again
  next time.
- The user name and base URL *are* stored in the clear, because they are not
  secret and you may need to read them.

### If you have saved a password but not unlocked it

Requests are sent **anonymously** and the server decides whether that is
enough. This is deliberate: a missing or mistyped passphrase should not stop
you reading a public endpoint, and the application will not guess.

So if a request fails with a `401`, the usual cause is that the credentials
are locked, or that the server requires authentication. Unlock them in
**Tools → Server Plugins...**.

### If you type the wrong passphrase

Nothing is lost and nothing is exposed. The stored password simply fails to
decrypt, requests fall back to anonymous, and the reason is written to the log
without the passphrase or the password. Re-enter the correct passphrase in


---

## 4. Finding a resource: three ways

### A. Read a resource you know the id of

**File → Open from FHIR Server...**

Type the resource type and id — `Patient` and `123`, for example — and press
**Read**. This is the fastest route when you already know what you want.

### B. Search for a resource

The same dialog, **File → Open from FHIR Server...**, has a **Parameter** and
a **Value** field. Enter a resource type, optionally one search parameter, and
press **Search**.

- Leaving both parameter and value blank asks the server for everything of
  that type, which is a legitimate way to browse.
- Supplying one without the other is refused. A bare `name=` matches nothing
  on most servers and everything on some, so it is better to be told than to
  guess.

Results appear in a table showing the **type**, **id** and **version** of
each. Select a row and press **Read** to open it.

The version column matters: it is the version the viewer will check against
when you save. Being able to see it before you open the resource is the
difference between an informed edit and a blind one.

A result you select is opened directly from the search response, without a
second request, so what you see is exactly the version in the table.

### C. Search the whole server

**Tools → Search FHIR Server...** (`Ctrl+K`)

A broader search screen: pick a server, load its capabilities to get the list
of resource types, search, and page through results. The page buttons are
enabled from the server's own paging links, so a button is only offered when
the server actually said there is a previous or next page.

Open a result and it is displayed like any loaded file.

### Loading types

**Load types** in the Open-from-Server dialog asks the server which resource
types it supports and lists them. Clicking one fills the type field. You can
always type a type instead — the list is a convenience, not a limit.


---

## 5. Editing a resource

Once a resource from a server is displayed, it is edited exactly like one from
a file: select an element in the **Resource Tree**, change its value in the
**Details** tab, or edit the raw text in the **JSON** and **XML** tabs. **Edit
→ Add Child Element...** and **Delete Element** work as usual.

Two things are worth knowing.

**The viewer remembers where the resource came from.** While a server resource
is displayed, the viewer holds its type, id and version. That is what a later
save writes back to, and it is why **Save to FHIR Server** knows where to send
the change.

**Opening something else clears it.** If you then open a file, a sample or
pasted JSON, that origin is discarded — so a resource you read from a server
can never be written back by accident after you have moved on to something
else.

**Saving to a file is always available.** **File → Save** and **Save As...**
work on a server resource too, and are unaffected by any of this. Saving a
copy locally is always a safe alternative to writing to the server.

---

## 6. Saving back to the server

**File → Save to FHIR Server...**

### The confirmation screen

Before anything is sent, one dialog states:

- **Which server** — chosen from a drop-down, defaulting to the server the
  resource came from.
- **What will happen** — *Create* a new resource, or *Overwrite* an existing
  one, with the exact `Type/id` affected.
- **Whether a conflicting change would be noticed.** This is the part worth
  reading.

### Create or update is decided for you

- The resource has a server-assigned id → it is **updated**.
- It has no id (a file, a sample, or pasted JSON) → it is **created**, and the
  id the server assigns is adopted.

You are not asked to choose; the screen tells you which is about to happen.

### Validation runs first, and blocks the write

The resource is validated before anything is sent. If it has an **error**, the
write does not happen, the issues are shown in the validation panel at the
bottom of the window, and the status bar says how many there are. Fix them, or
save to a file instead.

Warnings do not block. The write proceeds and the status bar reports that the
resource was sent with warnings, so you still learn about them.

> This is the one behaviour in the whole feature that is not negotiable. The
> viewer will not push a resource it knows to be invalid onto a live server.

### If the server reports no version

Some servers do not return a `meta.versionId`. When that is the case, the
confirmation screen says so explicitly, because **a write cannot be checked
for a conflicting change** and pretending otherwise would be misleading. You
may then opt to send without a version check.

That is a real trade-off: your write would replace whatever is on the server,
including a change somebody else made. The status bar after the write repeats
this, so the next save's limitation is never forgotten.

### If somebody else changed the resource first

The server can refuse a write when the resource has moved on — an HTTP `412`.
The viewer reports this as a **conflict** and asks you to choose. There is
deliberately no default answer, and no timeout that picks for you:

- **Reload from server** — discards your unsaved edit and shows the server's
  version.
- **Overwrite anyway** — sends your version without the version check,
  replacing whatever the other user did.
- **Cancel** — writes nothing at all.

Take the second option only when you are certain your version is the correct
one.

### After a successful write

The viewer re-bases what it knows about the resource on what the server
reported, so the **next** save's conflict check compares against the right
version. You do not need to reopen the resource.

Writes are performed on a background thread, so the window stays responsive.
The status bar reports what happened — including, if the server reported no
version, that changes by others cannot be detected before the next save.

---

## 7. Refresh, Patch and Delete

These three act on a resource that **came from a server and has an id**, and
are greyed out otherwise. A file opened from disk has no server behind it, so
offering these would only produce a guaranteed failure.

### Refresh from FHIR Server

Re-reads the resource and shows the server's current version.

**This discards your unsaved local edits**, so it asks first. "Refresh" that
silently throws away work because a menu item was pressed would be the worst
possible reading of the word.

### Patch on FHIR Server

Applies a partial update instead of replacing the whole resource. You choose a
format and type the body into the same JSON editor the main window uses, so
the patch parses locally before it is sent.

The format is **not** inferred from the body — it is carried entirely by the
`Content-Type`, and a JSON Patch sent as a merge patch will either mis-apply
or be refused. So it is an explicit choice:

| Format | Content type sent |
|---|---|
| **JSON Merge Patch** (default) | `application/merge-patch+json` |
| **JSON Patch** | `application/json-patch+json` |
| **XML Patch** | `application/xml-patch+xml` |

A FHIRPath patch is not offered: it is a `Parameters` resource rather than a
patch document, and sending it as a merge patch would be wrong.

### Delete from FHIR Server

Removes the resource from the server. It asks first and names the exact
`Type/id`, because this one cannot be undone from the viewer.

Like a write, it carries a version check when the server reported one, so a
delete cannot silently remove a resource somebody else has changed.

---

## 8. Server Status and Capabilities

**Tools → Server Status and Capabilities...**

The connection screen, and the place to check what is configured:

- **The server list**, with the active one marked.
- **Connect / Disconnect** — selects which server the other tools act on.
- **Test** — a quick reachability check.
- **Status / Capabilities** — reads the server's `CapabilityStatement`: the
  FHIR version it speaks and the resource types it supports. Nothing is sent
  until you press a button; opening this screen makes no request.

Use this when a read fails, to tell "the server is unreachable" apart from
"the server is fine but refused this particular call".

---

## 9. Running server operations

**Tools → Run Server Operation...**

The generic operation screen. The list comes from the server's plugin, so
vendor endpoints appear here without the application knowing they exist.

1. Pick a server.
2. Pick an operation from the list. The description explains what it does.
3. Fill in the parameters the form asks for. Required ones are marked.
4. Press **Run**.
5. The result appears in the panel below, classified for you — FHIR resource,
   Bundle, `OperationOutcome`, JSON, XML, plain text, or empty.
6. **Open in viewer** displays a result in the normal views.

Operations declared as needing authentication cannot be run anonymously; the
viewer says so rather than sending a request that would be refused.

### If the list is empty

An empty list means **this plugin has not declared any operations** — it is not
a fault in your setup. The screen covers the extra endpoints a vendor adds to
FHIR REST; reading, searching and writing resources are unaffected and work
through the File and Tools menus as normal.

Which plugins declare operations today:

| Plugin | Operations offered |
|---|---|
| **Standard FHIR REST** | Bulk `$export` and `$import` — see below |
| **Smile CDR** | Its reindex family, plus the inherited bulk operations |
| **Firely Server** | Its administration API, its measure operations, plus the inherited bulk operations |

A second, similar-looking case: a plugin *does* declare operations, but all of
them need credentials and none are unlocked this session. That message says so
explicitly and points at **Tools → Server Plugins...**, which is the answer.

### Bulk operations, on every server

`$export` and `$import` come from the FHIR specification, so every plugin
offers them and a vendor plugin keeps them alongside its own.

- **Bulk export** (`$export`) — starts a background export of the whole server,
  or of the types named by `_type`.
- **Bulk import** (`$import`) — starts an import from NDJSON files already held
  on the server as Binary resources. The files must be uploaded first; the
  operation references them rather than carrying the data.

Both are **asynchronous**. Each answers `202 Accepted` immediately with a
`Content-Location` header naming a URL to poll, and the data arrives later as
NDJSON files. The operation screen shows you that acknowledgement, not the
exported data — this screen starts jobs rather than running them to completion.

### Smile CDR operations

Smile serves these from the **FHIR endpoint itself**, so they work with the
address you already configured — no second URL is needed.

| Operation | What it does |
|---|---|
| **Re-index resources (system)** (`$reindex`) | Re-indexes what `url` selects, or everything when omitted. Answers `202`; poll the `Content-Location` it returns. |
| **Re-index one resource** | Re-indexes a single resource so a new SearchParameter takes effect for it. |
| **Preview a re-index (dry run)** | Reports which search parameters *would* change, without altering the server. **Safe to run at any time.** |
| **Mark all resources for re-indexing** | Deprecated by Smile in favour of `$reindex`; listed only for servers still relying on it. |

- **The dry run is the one to reach for first.** It is a read, it changes
  nothing, and it tells you what a full re-index would do before you start one.
- It requires the FHIR Storage (RDBMS) module. A server without it answers
  that the operation is unknown — a server setting, not a fault.
- Re-indexing a large server is slow. Prefer the dry run, or scope `$reindex`
  with a `url` such as `Patient?`.
- `$reindex` also accepts `partitionId` on a multi-tenant server; `_ALL`
  covers every partition.

### Firely Server operations

For a Firely server the list covers three groups.

**The administration API** — a separate branch of the server, at
`/administration`, holding the conformance resources the server validates
against rather than patient data.

| Operation | What it does |
|---|---|
| **Re-index resources** (`$reindex`) | Re-indexes so a new or changed SearchParameter takes effect |
| **Re-index all resources** (`$reindex-all`) | The same for every resource; considerably slower |
| **Preload resources** (`$preload`) | Loads resources into the index ahead of time |
| **Import conformance resources** (`$import-resources`) | Loads conformance resources on demand, so they are available without a restart |
| **Reset the database** (`$reset`) | Erases the administration database and reloads it. **Destroys stored conformance resources.** |
| **List *Type* (admin)** ×11 | Searches one conformance type: SearchParameter, StructureDefinition, ValueSet, CodeSystem, CompartmentDefinition, StructureMap, ConceptMap, Library, Measure, Questionnaire, Subscription |

**Quality measures** — these run on the **main FHIR endpoint**, not under
`/administration`, and need a request body carrying the expression or measure.

| Operation | What it does |
|---|---|
| **Evaluate CQL** (`$cql`) | Evaluates a CQL expression against the server's data |
| **Evaluate a measure** (`$evaluate-measure`) | Evaluates a quality measure, returning a MeasureReport |
| **Extract data requirements** (`$data-requirements`) | Returns the data a measure or library needs |
| **Evaluate an expression** (`$evaluate`) | Evaluates a FHIRPath or CQL expression |

These need Firely's DQM module licensed and deployed. A server without it
answers `501 Not Implemented` — including the public test server at
`server.fire.ly`, which is a server setting rather than a mistake.

Worth knowing before you use the administration operations:

- **All of them need credentials.** Unlock them in **Tools → Server Plugins...**
  or the list will be empty.
- **Some are network-restricted.** Firely can limit `$reindex`, `$reindex-all`,
  `$reset`, `$preload` and `$import-resources` to particular IP networks by
  configuration. A `403` on one of those is the server's setting, not the viewer.
- **The searches are read-only on purpose.** The administration API does allow
  writing these resources, but it is not offered: changing a conformance
  resource changes what a server validates against, which should not sit behind
  a one-click generated form. Use a dedicated conformance client for that.
- **`$reset` destroys data.** It is listed because it is a real operation, but
  read its description before running it.
- A Firely server holds a great many of these — over a thousand SearchParameters
  and CodeSystems — so the searches take a `_count` to size the page.

Results are deliberately **not** given a server origin. An operation's answer
is something to look at, not something to save back, so no server menu item
offers to write it.

---

## 10. Server Tools and Server Plugins

### Tools → Server Tools...

Lists the **vendor-specific screens** a plugin offers — whole extra screens,
distinct from operations.

Expect little here, and read this before reporting it as broken:

- **Standard FHIR REST** and **Smile CDR** declare no vendor tools, so the
  viewer says *"None of the configured servers offers extra tools."* That is
  correct, not a failure.
- **Firely Server** offers *Firely Administration...*. Selecting it reports
  that the screen is not implemented yet. Firely's administration endpoints
  are reachable through **Tools → Run Server Operation...** instead.

### Tools → Server Plugins...

Manages the integrations themselves:

- **Settings** — base URL, user name, password and load-on-start for a plugin.
  This is where credentials are set; see [Credentials](#3-credentials).
- **Discovery** — scan a folder for plugin jars and load them.
- **Configuration** — enable or disable plugins at start-up.

The plugin configuration is a plain text file you can edit by hand.

---

## 11. What each plugin supports

| | **Standard FHIR REST** | **Smile CDR** | **Firely Server** |
|---|---|---|---|
| Choose it for | Any FHIR R4 server | Smile CDR | Firely Server |
| Capabilities, search, read | Yes | Yes | Yes |
| **Create / Update / Delete** | **Yes** | Yes | Yes |
| Conditional write (`If-Match`) | Yes, when the server reports a version | Yes | Yes |
| PATCH | Yes | Yes | Yes |
| `$`-operations | Yes | Yes | Yes |
| Administration operations | No | No | **Yes** — the Firely administration API |
| Vendor screens | None | None | Declared, not yet implemented |

**Standard FHIR REST** is the right choice for anything that speaks ordinary
FHIR REST. The two vendor plugins extend it, so they support everything it
does.

**Smile CDR** and **Firely Server** are for their respective products and add
product-specific handling — a richer capabilities read, and vendor endpoints
exposed through the operation screen.

### Which to pick

Use **Standard FHIR REST** unless you know your product is one of the two and
want its specific handling. All three support reading and writing, so this is
a choice about fidelity, not about capability.

---

## 12. Reading error messages

Every failure is reduced to one line you can act on, plus what the server
itself said when it said anything. The status bar always names the next step.

| Message mentions | It means | Do this |
|---|---|---|
| Could not be reached | The server did not answer at all | Check the base URL, the network, any firewall |
| Did not answer in time | The address may well be right; the server was slow | Try again later |
| `401` / rejected the credentials | Credentials are wrong, or locked for this session | Unlock them in **Tools → Server Plugins...** |
| `403` / not allowed | Credentials are valid but not permitted for this | You need a different account or permission |
| `404` / not found | The resource, type or base URL is wrong | Check the type, the id and the base URL |
| `409` or `412` / changed since it was read | Somebody else changed it | Reload, or overwrite deliberately |
| `422` / not valid | The server understood the request but rejected the content | Fix the resource; the message carries the detail |
| The server copy changed | A conflict, offered as a choice | Reload or overwrite — never decided for you |
| Cannot write / does not support it | The server or plugin cannot do this | Save to a file instead |
| A blue `Basic …` or `Bearer …` | **Never shown** — credentials are redacted | If you see one, report it; it is a defect |

When the server returns an `OperationOutcome`, its own explanation is shown in
brackets after the summary. That text is usually the most useful part, because
it is the only thing that distinguishes "unknown search parameter" from
"patient not found" once the status has been reduced to a number.

---

## 13. Where your settings live

Two files under `~/.fhirviewer/` (your home directory):

| File | Holds | Format |
|---|---|---|
| `server-definitions.properties` | Your servers: name, base URL, FHIR version, plugin, timeout, and which is active | Plain text, hand-editable |
| `plugin-settings.properties` | Per-plugin base URL, user name, **encrypted** password, load-on-start | Plain text except the password |

**No secret is ever written to `server-definitions.properties`.** It is safe to
attach to a support request. Passwords live only in the plugin settings file,
encrypted.

A definition that cannot be read — a hand-edited block with a malformed base
URL, say — is skipped with a line in the log, and your other servers still
load.

Changing either file by hand while the application is running may be undone:
the list is written when you add a server.

---

## 14. Troubleshooting

**Every Tools item opens the add-server dialog, then nothing happens.**
Your server was never saved. Open **Tools → FHIR Servers...** directly, fill
in the name and base URL, and press **Save**. If the dialog shows an error
message, that is why.

**"Save" in the add-server dialog does nothing.**
An empty name, or a base URL not starting with `http://` or `https://`, is
refused with a message at the bottom of the dialog.

**"None of the configured servers offers extra tools."**
Correct for a Standard REST or Smile CDR server. See
[Server Tools](#10-server-tools-and-server-plugins).

**A search returns nothing.**
Check the resource type. A server that supports `Patient` may not support
whatever you asked for, and **Tools → Server Status and Capabilities...** lists
what it does.

**"Unknown search parameter".**
The server rejected the parameter name. It comes from the server's own message.

**A `401` on a server you have credentials for.**
They are probably locked for this session — the passphrase is not remembered
between runs. Unlock them in **Tools → Server Plugins...**.

**My servers disappeared.**
The settings file could not be read. The reason is in the log
(`app.log` / `app.err.log` in the working directory). The viewer starts
with no servers rather than refusing to start, because a damaged
settings file is not a reason to make the application unusable.

**The save was refused.**
The resource has validation **errors**. They are listed in the validation
panel at the bottom of the window. Warnings do not block a save.

**The save reported a conflict.**
Somebody else changed the resource. You were offered reload or overwrite; the

correct.

**A write succeeded but the next save says conflicts cannot be detected.**
The server does not report a `meta.versionId`. Nothing is wrong with the
viewer; the server cannot offer the check.

**The window froze during a server request.**
It should not — requests run on a background thread. If it did, note which
action, because that is a defect worth reporting.

---

## 15. Known gaps

Stated plainly, so nothing here reads as working when it does not.

| Not built yet | What you get instead |
|---|---|
| **Vendor screens** (*Tools → Server Tools...*) | Firely's *Administration* screen is declared but reports "not implemented". Its endpoints are reachable as individual operations under **Run Server Operation...**. |
| **Smile CDR's Admin JSON API** | User, session and partition management sit on a separate port that the viewer cannot address yet. The reindex operations, which are on the FHIR endpoint, do work. See [Phase 8](../plans/08_SECOND_BASE_URL_FOR_ADMIN_APIS.md). |
| **Bulk jobs are started, not finished** | `$export` and `$import` return `202` with a polling URL. The screen shows that acknowledgement; it does not follow the job to completion. |
| **Writing conformance resources** | Firely's administration API allows it; the viewer deliberately offers those searches read-only. |
| **FHIRPath patch** | The three body-shaped patch formats only. A FHIRPath patch is a `Parameters` resource, and sending it as a merge patch would be wrong. |
| **More than one search parameter** | The search screens take one parameter and value. Leave both blank to browse a type. |
| **A raw REST console** | Deliberately excluded. Arbitrary GET/POST/PUT/DELETE would need its own authentication, error mapping and paging. Use the operation screen. |
| **Extra request headers per server** | Not persisted. A header *value* is a secret, and `ServerDefinition` has no way to hold one. |
| **Transaction bundles** | No multi-resource write. Write one resource at a time. |
| **Conditional create / `$everything`** | Out of scope. |
| **Subscriptions and push notifications** | Out of scope. |
| **Smile CDR's connection half has no automated test** | Its test file is disabled. The operation declarations added here are covered separately. |

---

## See also

- [`Current-Plugin-Architecture.md`](../architecture/Current-Plugin-Architecture.md) —
  how the pieces fit together, for anyone extending this.
- [`Phase-7-Open-Save-from-FHIR-Server.md`](../plans/Phase-7-Open-Save-from-FHIR-Server.md) —
  the design decisions behind Open and Save.
- [`REST-Integration-Progress.md`](../plans/REST-Integration-Progress.md) —
  what is done and what is outstanding across the whole integration.

viewer will not pick for you. Reload unless you are certain your version is
