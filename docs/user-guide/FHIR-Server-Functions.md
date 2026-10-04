# FHIR Server Functions — User Guide

How to point FHIRViewer at a FHIR server, read resources from it, and write
changes back.

This guide describes the behaviour as it is in the `server-rest-integration`
branch. Where something is deliberately not built yet, it says so rather than
describing an ideal.

---

## Contents

1. [What the server functions do](#1-what-the-server-functions-do)
2. [Adding and editing servers](#2-adding-and-editing-servers)
3. [Credentials](#3-credentials)
4. [Finding a resource: three ways](#4-finding-a-resource-three-ways)
5. [Editing a resource](#5-editing-a-resource)
6. [Saving back to the server](#6-saving-back-to-the-server)
7. [Refresh, Patch and Delete](#7-refresh-patch-and-delete)
8. [Server Status and Capabilities](#8-server-status-and-capabilities)
9. [Running server operations](#9-running-server-operations)
10. [Server Plugins](#10-server-plugins)
11. [What each plugin supports](#11-what-each-plugin-supports)
12. [Reading error messages](#12-reading-error-messages)
13. [Where your settings live](#13-where-your-settings-live)
14. [Troubleshooting](#14-troubleshooting)
15. [The REST console](#15-the-rest-console)
16. [Known gaps](#16-known-gaps)

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

## 2. Adding and editing servers

**Tools → FHIR Servers...**

The dialog opens with a **Server** drop-down listing every configured server,
so you can switch between them without leaving the form. Choosing one loads
its details; typing a name that is not in the list starts a new one. The
first entry is blank, which means "a new server" — that is how the first
server gets added.

Four buttons sit under the form:

| Button | What it does |
|---|---|
| **Add** | Clears the form and returns the selector to the blank entry, ready to describe a new server. |
| **Save** | Adds the server, or updates the one you picked. Takes effect immediately — there is no separate OK. |
| **Delete** | Removes the server you picked from the list. Disabled until you pick one. |
| **Test connection** | Fetches the server's `CapabilityStatement` and reports whether it is reachable. Works on whatever the form currently holds, so you can test before saving. |

To correct a server that is already there: pick it from the drop-down, change
what needs changing, press **Save**. Editing replaces the server rather than
adding a second one, so you do not end up with a stale copy to clean up.

The form asks for:

| Field | What to enter |
|---|---|
| **Server** | Pick an existing one, or leave blank and type a **Name** for a new one |
| **Name** | Any label you like. It is how you tell your servers apart, and it is the identity the viewer matches on, so names must be unique. |
| **Base URL** | The server's FHIR root, e.g. `https://example.com/fhir` or `http://localhost:8080/fhir`. Must start with `http://` or `https://`. Trailing slashes are removed for you. |
| **FHIR version** | `R4` is the only choice currently. |
| **Server type** | Which plugin serves this server — see [What each plugin supports](#11-what-each-plugin-supports). Choose the vendor plugin when one is listed for your product. |
| **Authentication** | Anonymous, user name and password, or a bearer token. See [Credentials](#3-credentials). |
| **Admin URL** | **Usually leave blank.** Only for a server whose administration API lives on a *different address* than its FHIR root — Smile CDR serves FHIR on port 8000 and its JSON Admin API on 9000. Give the origin, e.g. `https://host:9000`. Leave it blank and every operation goes to the **Base URL** above, which is right for almost every server including Firely. |

Servers are **saved as soon as you change them**, so they are still there next
time you start the application. You do not need to press Save on the main
window as well.

If **Save** appears to do nothing, read the message under the form: an empty
name or a base URL without a scheme is refused there rather than being stored.

### Which server is active?

The dialog shows every configured server but does not choose between them. To
see which one is active, open **Tools → Server Status and Capabilities...**.

---

## 3. Credentials

Choose an authentication kind in the server dialog's form:

| Kind | What it asks for | What is sent |
|---|---|---|
| **None (anonymous)** | Nothing | No `Authorization` header |
| **User name and password** | A user name and a password | An HTTP Basic `Authorization` header |
| **Bearer token** | A token | `Authorization: Bearer <token>` |

The form only shows the fields a kind actually needs, so a bearer token does
not ask for a user name and anonymous hides both.

**Each server keeps its own credentials.** Two servers of the same type — two
Firely servers, or two standard FHIR servers — can each have a different user
name and password, and neither overwrites the other.

**A password is encrypted before it is written to disk**, using a passphrase you
choose. The passphrase is **not stored anywhere** — it is held for the session
and cleared when the application closes. The user name and base URL *are* stored
in the clear, because they are not secret and you may need to read them.

### Set a passphrase first

The passphrase is set once, in **Tools → Server Plugins... → Settings**, and is
shared with the plugin settings. Enter a server password before any passphrase
exists and the viewer **refuses to store it** rather than writing something that
could never be decrypted:

> *Set a passphrase in Tools > Server Plugins... first, then enter the password
> again. It cannot be saved without one.*

A password that is stored but unreadable would look saved and never work, which
is the hardest kind of failure to diagnose — so the viewer tells you instead.

### Leaving a password blank when editing

The password field is never echoed back, so it is always blank when you open a
server that has one. **A blank field means "leave the stored password alone"**,
not "clear it" — otherwise correcting a URL would quietly sign you out of your
own server.

To actually remove a stored password, set the kind to **None (anonymous)** and
press **Save**.

Credentials follow the **server**, not the URL or the name. Renaming a server or
correcting its address keeps the password you already saved, because those are
the edits people make when something is not working.

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
without the passphrase or the password.

---

## 4. Finding a resource: three ways

### A. Read a resource you know the id of

**File → Open from FHIR Server...**

Type the resource type and id — `Patient` and `123`, for example — and press
**Open in viewer**. This is the fastest route when you already know what you
want.

### B. Search for a resource

The same dialog, **File → Open from FHIR Server...**, searches in two ways. Choose
**Parameters** or **Search string** at the top.

**Parameters** takes any number of name and value rows. Press **Add parameter**
for another, **Remove last** to drop one. Enter a resource type and press
**Search**.

- Leaving a row blank asks the server for everything of that type, which is a
  legitimate way to browse.
- A value with no name is refused. A bare `name=` matches nothing on most
  servers and everything on some, so it is better to be told than to guess.

**Search string** takes one line, sent to the server exactly as typed. Use this
when you already have the search written down — copied from a browser address
bar, a specification example or a colleague.

- Prefixes (`name:exact`), modifiers (`name:contains`), chained parameters
  (`subject.name`), `_sort`, `_count` and anything the server adds all work,
  because the viewer does not try to interpret it.
- You may paste `Patient?name=Smith`, `?name=Smith` or just `name=Smith`. The
  resource type comes from the **Type** field; everything after the question
  mark is sent as typed.
- Nothing is escaped twice, so a value you have already encoded stays as it
  is.
- An empty search string is refused rather than run, because a search with
  nothing applied returns *every* resource of the type.

Switching between the two clears the other, so a half-typed parameter cannot
be sent as a search you did not write.

Results appear in a table showing the **type**, **id** and **version** of
each. Select a row and press **Open in viewer** to open it.

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

### Coming back to a search

Reopening the search screen brings back your last search: the server, the
resource type, every parameter, and the results. It re-reads the server
rather than showing an empty form, so the type list and the results are
there too — you do not have to press Load capabilities or Search again.

The first time you open the screen, or after restarting the viewer, there
is nothing to bring back and it does not contact the server at all.

If the server has gone away, the search you had is still filled in and
ready to edit — only the results are missing.

### Loading types

**Load capabilities** asks the server which resource types it supports and
fills the type box's drop-down. Pick one from the list, or always type a type
instead — the list is a convenience, not a limit.

The answer is remembered for as long as the viewer is open, so the button is
only needed the first time you use a server. Press it again after the server
has been upgraded and you want its new resource types.


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
- **Served by** — the plugin handling this server, named in full ("Standard FHIR
  REST", "Firely Server"). **Check this first** if an operation looks missing:
  a Firely server added without changing **Server type** is served by the plain
  FHIR plugin, which offers the specification's operations but none of the
  vendor ones. `standard-rest` on a vendor's endpoint is the usual cause.
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

1. Pick a server from the **Server** drop-down at the top. It lists every
   configured server, and defaults to whichever one the current resource
   came from, or the active one. Changing it reloads the operation list for
   that server, because each server's plugin offers different operations.
2. Pick an operation from the list. The description explains what it does.
3. Fill in the parameters the form asks for. Required ones are marked.
4. If the operation takes a body, two more choices appear above it:
   - **Body is** — what that body *is*, so the box is labelled rather than
     anonymous. `$validate` wants a resource; `$graphql` wants a query.
   - **Sent as** — the content type. Pick one the operation actually accepts;
     anything else is refused here rather than by the server. If the resource
     you have open in the editor fits the operation, the body is **filled in
     for you** — edit it, or clear it.

   That last point is what makes `$validate` practical: open the resource you
   suspect, run `$validate`, and the body is already the thing you were looking
   at.
5. Press **Run**.
6. The result appears in the panel below, classified for you — FHIR resource,
   Bundle, `OperationOutcome`, JSON, XML, plain text, or empty.
7. **Open in viewer** displays a result in the normal views.

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
| **Standard FHIR REST** | The specification's own — `$validate`, `$expand`, `$lookup`, `$everything`, `$patient`, `$compartment`, `$convert`, `$vread`, `$graph`, `$history`, the export/import poll-status pair, `$graphql`, `$snapshots` — plus bulk `$export` and `$import`. See below |
| **Smile CDR** | Its reindex family and its JSON Admin API, plus everything inherited from the standard layer |
| **Firely Server** | Its administration API, its measure operations, plus everything inherited from the standard layer |

### The specification's operations, on every server

These come from the FHIR specification rather than any vendor, so all three plugins
offer them and a vendor plugin adds to them rather than replacing them. They are
the ones worth knowing:

| Operation | What it does |
|---|---|
| **Validate a resource** (`$validate`) | Checks a resource against the profiles it claims, and returns the errors and warnings. The body is filled in from whatever you have open. |
| **Everything about a patient** (`$everything`) | Every resource related to a patient, across types. Narrow it with `start` and `end` — it is large otherwise. |
| **Convert to another FHIR version** (`$convert`) | Converts older data into the version this viewer reads. |
| **Version read** (`$vread`) | One specific version of a resource, so you can see what changed. |
| **Expand a ValueSet** (`$expand`) | The list of codes a ValueSet contains. |
| **Look up a code** (`$lookup`) | The resources a code identifies — the reverse of a terminology search. |
| **Bulk export progress** (`$export-poll-status`) | How far a `$export` you started has got. |

The type-level and instance-level forms are listed separately, because the
specification defines them as separate interactions.

**Not offered here: `$search`.** It needs a search expressed as several
parameters, which this screen cannot do well — and
[the two search screens](#4-finding-a-resource-three-ways) do it properly,
with an editor for several parameters and a raw string. Use those.

**How far this has been checked.** `$everything`, `$patient`, `$graphql` and
`$export-poll-status` were each run against `hapi.fhir.org/baseR4`. The rest are
taken from the specification and have not been sent to a real server, so a
server may answer "unknown operation" for some of them — that is the server
declaring it does not implement that one, not a fault in the viewer.

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

#### Smile's JSON Admin API

Smile also serves an administration API on its own port — version, configuration,
runtime status, metrics, OpenID Connect clients and sessions, the user list, and
session invalidation. **These need the Admin URL field on the server** (see
[The form asks for](#2-adding-and-editing-servers)); with it blank they would be
sent to the FHIR endpoint and fail there.

**They are unverified.** No public Smile CDR was reachable while this was
written, so every path comes from Smile's documentation and none has been run
against a real instance. Expect to correct some of them. If **Version** works,
the rest are likely right in shape.
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

## 10. Server Plugins

### Tools → Server Plugins...

Manages the integrations themselves:

- **Settings** — base URL, user name, password, load-on-start, and the
  **passphrase** that encrypts stored passwords. This is where the passphrase
  is set; see [Credentials](#3-credentials).
- **Discovery** — scan a folder for plugin jars and load them.
- **Configuration** — enable or disable plugins at start-up.

The plugin configuration is a plain text file you can edit by hand.

### There is no "Server Tools" menu any more

An earlier version had **Tools → Server Tools...**, which listed the
vendor-specific *screens* a plugin offers. It was removed because it could not
do anything: the only screen it could offer was not implemented, and every
other plugin declared none. A menu item that opens a picker and then reports
"not implemented" is worse than no menu item at all.

The operations those vendor endpoints expose are still reachable, individually,
under **Tools → Run Server Operation...** — see
[Firely Server operations](#firely-server-operations).

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
Server Tools, which has been removed (see [section 10](#10-server-plugins)).

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

## 15. The REST console

**Tools → REST Console...**

The screens elsewhere are curated: they offer the operations a plugin declared. The console
is the opposite — it lets you type *any* REST call and see exactly what came back. Use it
for a vendor endpoint nobody declared, an operation with an argument the forms do not offer,
or just to find out what a server actually does when you ask it something.

It deliberately does not go through the plugin layer, because calling an endpoint no plugin
declares is the whole point. Everything else is shared: the same HTTP transport, the same
authentication, the same error messages, the same TLS behaviour.

### Filling in the request

| Field | What it is |
|---|---|
| **Server** | A server you have already configured, or **Custom URL…** to type any address. A server you have not configured can still be called. |
| **Method** | `GET`, `POST`, `PUT`, `PATCH` or `DELETE`. `GET` is the default. |
| **Base URL** | The server root, e.g. `https://example.com/fhir`. Shown and used when **Custom URL…** is selected. |
| **Path** | The rest, e.g. `Patient/123` or `/Patient?name=Smith`. The full address is previewed underneath as you type. |
| **Query parameters** | A grid of name and value. A name may repeat — `_include` often does — and the order you type is the order that is sent. |
| **Headers** | Any header you need: `If-Match`, `Prefer`, `_format`, a vendor's own. |
| **Request body** | Enabled only for a method that can carry one. |

The **Send** button refuses to send an incomplete request and tells you which field is
missing, rather than opening a connection first.

### Authentication

Choose from **None**, **User name and password**, or **Bearer token**. Only the fields that
kind needs are shown.

- **Fetch token...** runs an OAuth 2.0 **client-credentials** grant against a token endpoint
  you type, and puts the returned token in the token field. It reports when the token
  expires, because one that expires mid-debugging looks like a server fault.
- You may also paste a token you already have.

There is deliberately **no `Authorization` header you can type yourself** — the console
points you at the authentication selector instead, so a credential cannot end up sitting in
a text field that gets screenshotted.

Credentials typed here are **not saved**. They are forgotten when the window closes.

### Reading the answer

Four tabs:

- **Body** — the raw response, exactly as the server sent it, whatever it turned out to be.
  Use **Copy body** or **Save body to file...** when you need it elsewhere.
- **Bundle entries** — when the answer is a Bundle, its entries are listed here. Double-click
  one to open that resource in the main viewer; you rarely want the wrapper when you wanted
  a search result.
- **Headers** — the response headers, with any credential-bearing value replaced.
- **Diagnostics** — what the server said when it refused.

The line above the tabs gives the kind, the HTTP status, the size in bytes and how long it
took, and repeats the server's own explanation on a failure.

### Opening a resource in the viewer

**Open in FHIR Viewer** is enabled when the body turned out to be a FHIR resource, and works
for any verb — not just a search. The resource is opened with no server attached, so a later
**Save to FHIR Server** will not write it back somewhere you did not choose.

### Pasting and copying cURL

- **Paste cURL...** fills the form from a `curl` command. This is the quickest way to run
  something from a bug report, a wiki page or a vendor's documentation.
- **Copy as cURL** puts the current request on the clipboard as a command you can paste into
  a ticket. **Credentials are left out** of what it copies.

`--insecure` is refused with an explanation rather than quietly ignored, because ignoring it
would produce a request that behaves differently from the one you are looking at. Fix the
certificate instead.


---

## 16. Known gaps

Stated plainly, so nothing here reads as working when it does not.

| Not built yet | What you get instead |
|---|---|
| **Vendor screens** (*Tools → Server Tools...*) | Removed. The only screen it could offer was never implemented, so the menu item was removed rather than left doing nothing. Those endpoints are reachable as individual operations under **Run Server Operation...**. |
| **Smile CDR's Admin JSON API** | **Reachable now**, through the **Admin URL** field, but the paths are documentation-derived and unverified — no public Smile instance was available to test them. The reindex operations, on the FHIR endpoint, are the ones to rely on. See [Smile's JSON Admin API](#smiles-json-admin-api). |
| **Bulk jobs are started, not finished** | `$export` and `$import` return `202` with a polling URL. The screen shows that acknowledgement; it does not follow the job to completion. Use `$export-poll-status` by hand to check on one. |
| **Writing conformance resources** | Firely's administration API allows it; the viewer deliberately offers those searches read-only. |
| **FHIRPath patch** | The three body-shaped patch formats only. A FHIRPath patch is a `Parameters` resource, and sending it as a merge patch would be wrong. |
| ~~**A raw REST console**~~ | **Built** — **Tools → REST Console...** See [The REST console](#15-the-rest-console). It reuses the existing transport, authentication and error mapping rather than having its own. |
| **Extra request headers per server** | Not persisted. A header *value* is a secret, and `ServerDefinition` has no way to hold one. |
| **Transaction bundles** | No multi-resource write. Write one resource at a time. |
| **Subscriptions and push notifications** | Out of scope. |
| **Smile CDR's connection half has no automated test** | Its test file is disabled. The operation declarations added here are covered separately. |
| **Most specification operations are unverified** | Four were run against a public server. The rest come from the specification and may not be implemented by your server. See [How far this has been checked](#the-specifications-operations-on-every-server). |
| **Console requests are not saved** | The console holds one request at a time and keeps nothing between sessions — no saved collections, no request history, no `{{variable}}` environment. You can copy a request out as cURL and paste it back. |
| **SMART on FHIR is not supported** | The console can *use* a bearer token and can *fetch* one with an OAuth client-credentials grant. It does not run an authorization-code flow, open a browser, or refresh an expired token. Re-fetch when one expires. |
| **Console credentials are never stored** | A password or token typed into the console is forgotten when the window closes. Use the server manager to store one that should persist. |
| **Very large responses are held in memory** | The whole body is loaded as text. A multi-hundred-megabyte bulk export is not something this handles well. |

---
## See also

- [`Current-Plugin-Architecture.md`](../architecture/Current-Plugin-Architecture.md) —
  how the pieces fit together, for anyone extending this.
- [`Phase-7-Open-Save-from-FHIR-Server.md`](../plans/Phase-7-Open-Save-from-FHIR-Server.md) —
  the design decisions behind Open and Save.
- [`REST-Integration-Progress.md`](../plans/REST-Integration-Progress.md) —
  what is done and what is outstanding across the whole integration.

viewer will not pick for you. Reload unless you are certain your version is
