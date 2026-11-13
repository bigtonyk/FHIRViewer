# FHIR Viewer 2.0

The second numbered release. It builds on the 0.1 viewer with workspaces,
scripting automation, SMART on FHIR, a raw REST console, stronger server
integration, and a searchable in-app documentation set.

## Getting it

Download **FHIRViewer-2.0-windows.zip**, unzip it, and double-click
`FHIRViewer.exe`.

**You do not need Java, Maven or anything else installed.** The download
contains the application and its own copy of the Java runtime.

This release attaches a **Windows** build. `jpackage` cannot cross-compile, so
macOS and Linux builds have to be produced on those platforms. The source
checkout also includes `launch-app.sh` and `run-java21.sh` helpers for running
from a local build; they are not part of the Windows zip.

## Highlights in 2.0

### Workspaces

- Save and reopen a workspace containing the configured servers, open resource,
  tree and tab selection, FHIRPath entries, validation packages, and REST
  workbench state.
- Recovery snapshots protect unsaved work across an unexpected exit.
- Workspace archives are portable and can be opened on another machine.

### Scripting console

- Embedded JShell console with Run, Run Tests, Stop, Reset Session, Open, Save,
  Save As, and Example commands.
- Read and search FHIR resources through `fhir`, inspect servers through
  `server`, validate resources through `validation`, display results through
  `viewer`, and read through a SMART session through `smart`.
- Batch processing, paging, assertion helpers, and isolated test suites with
  per-case results.
- Saved scripts run in a fresh session; the unsaved editor keeps the
  interactive session.
- The Scripting Console is read-only in this build: create, update, patch, and
  delete are refused before a request is sent.

### SMART on FHIR

- SMART Launch Workbench with configuration discovery and Authorization Code +
  PKCE through the system browser.
- Session panel showing granted scopes, launch context, and token expiry.
- The authorized session is shared with the Scripting Console.
- **Log out** revokes the session; tokens stay in memory and are never written
  to disk.
- Scripts can read through the session with `smart.read`, subject to granted
  scopes and expiry.

### REST console

- **Tools -> REST Console...** sends any `GET`, `POST`, `PUT`, `PATCH`, or
  `DELETE` request to a configured server or a custom URL.
- Query parameters preserve order and repeated names; headers, request bodies,
  and content types are editable.
- Anonymous, Basic, and Bearer authentication, plus an OAuth client-credentials
  token fetch.
- Paste cURL to fill the form, and Copy as cURL to export it without
  credentials.
- Response Body, Entries, Headers, and Errors tabs; open a returned FHIR
  resource in the main viewer or save the raw body.
- Browser-style remembered request state, but credentials and request bodies
  are never persisted.

### Server integration

- Plugin-based server support for the standard FHIR REST API and vendor
  plugins.
- Read, search, create, update, patch, and delete with validation and conflict
  detection.
- Server status, capabilities, operations, and Implementation Guide package
  management.
- Improved error messages, credential handling, request deadlines, and
  packaged-build logging.

### Guides and interface

- In-app User Guide, SMART on FHIR Launch Guide, and Scripting Guide, with
  contents navigation, search, Back history, and copyable code samples.
- Contextual guide links from the SMART Workbench and Scripting Console.
- Improved themes, dialogs, screen fitting, and a scrollable Pretty View.

## Existing capabilities

The resource viewer still includes:

- Opening FHIR JSON and XML with content-based format detection.
- A resource tree generated from the FHIR model, with element documentation,
  datatypes, cardinalities, and multiplicity.
- In-place value editing with FHIR datatype conversion and undo.
- Resource creation, JSON/XML views, Bundle navigation, validation, FHIRPath,
  StructureDefinition browsing, terminology lookup, and export.
- Drag and drop, resource comparison, and sample resources.

## Known gaps

Stated plainly, so nothing here reads as working when it does not.

- **Windows-only attachment.** The packaged macOS and Linux builds have not
  been produced for this release. The source launchers require a local JDK and
  build.
- **Unsigned.** Windows will warn about an unknown publisher, and antivirus may
  quarantine the executable. There is no code-signing certificate.
- **No installer.** The zip is the deliverable; there is no `.msi`.
- **Scripting is read-only.** The Scripting Console refuses writes in this
  build.
- **No SMART refresh-token use.** When the access token expires, authorize
  again in the Workbench.
- **The REST console keeps one request.** There are no saved collections,
  request history, or environment variables. Writes and deletes are real and
  are not validated or confirmed by the console.
- **Very large responses are held in memory.**
- **Server operation coverage varies.** Vendor endpoints and specification
  operations depend on what the target server implements.

## Feedback

Please report what does not work, with the server, request, and response where
possible. The most useful reports are the ones that show the exact request that
failed and what the viewer did instead.
