# FHIR Viewer 0.1

The first numbered release. A desktop viewer for FHIR R4 resources, and a
first version of its FHIR server integration.

## Getting it

Download **FHIRViewer-0.1.0-windows.zip**, unzip it, and double-click
`FHIRViewer.exe`.

**You do not need Java, Maven or anything else installed.** The download
contains the application and its own copy of the Java runtime.

This release is **Windows only**. macOS and Linux builds are not attached;
see [Known gaps](#known-gaps) for why.

## What it does

### Working with resources

- Open FHIR files, JSON or XML, with format detection
- Explore them as a tree, with element documentation, datatypes, cardinalities
  and multiplicity
- **Pretty View** — the resource rendered as a person would read it
- Edit values in place, with the value converted to the element's FHIR datatype
  so an invalid date is reported rather than stored
- Create resources from scratch (**File → New**)
- Undo and redo edits
- Compare two resources
- Validate against the specification, with errors and warnings and their
  FHIRPath locations
- Evaluate FHIRPath expressions
- Browse StructureDefinitions and other conformance resources
- Look up codes in terminologies
- Bundle navigation, JSON and XML views, export, and drag and drop

### FHIR servers

Point it at a FHIR R4 server and work against it:

- **Read** a resource by type and id, or **search** for one
- Searching takes several parameters, or a raw search string if you would
  rather type the query yourself
- The last search is kept, so reopening the screen shows it again
- **Create, update, patch and delete**, with validation run before anything is
  written and conflicts detected rather than overwritten
- Per-server credentials: anonymous, user name and password, or a bearer token.
  Passwords are encrypted at rest; the passphrase to decrypt them is never
  stored
- Servers are saved and come back on the next start
- Server status and capabilities, and the FHIR specification's own operations
  plus each vendor's
- Reads and writes record where a resource came from, so the server operations
  stay enabled for it

## Known gaps

Stated plainly, so nothing here reads as working when it does not.

- **Windows only.** `jpackage` cannot cross-compile, so a macOS or Linux build
  has to be made on that platform. Neither has been produced yet.
- **Unsigned.** Windows will warn about an unknown publisher and antivirus may
  quarantine the file. Both are consequences of not having a code-signing
  certificate.
- **No installer.** The zip is the deliverable; there is no `.msi` yet.
- **No raw REST console.** Arbitrary GET/POST/PUT/DELETE was deliberately left
  out; the operation screen covers it.
- **No transaction bundles.** One resource is written at a time.
- **Smile CDR's administration API paths are unverified.** No public Smile
  instance was reachable, so they come from Smile's documentation. The reindex
  operations, which are on the FHIR endpoint, are the ones to rely on.
- **Most specification operations are unverified.** Four were run against a
  public server; the rest come from the specification and a given server may
  not implement all of them.

## Feedback

Please report what does not work. Early software is rough in ways that are not
obvious from the outside, and the ones found so far - a button that did nothing,
a search that lost itself - all came from someone using it rather than testing
it.