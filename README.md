# FHIR Resource Viewer

A cross-platform desktop application for loading, viewing and inspecting **FHIR R4**
resources. It runs on Windows, macOS and Linux and is built with Java, JavaFX and
HAPI FHIR. No FHIR server is required.

The application is a developer/inspection tool: it answers questions such as
*what is this element called, what datatype is it, what cardinality does it have, and
what does the specification say about it?*

## Status against the project plan

| Phase | Scope | State |
|-------|-------|-------|
| 1 | Maven project, JavaFX, HAPI FHIR, entry point | Done |
| 2 | File open, JSON/XML detection, parsing, type detection | Done |
| 3 | Dynamic resource tree (primitives, complex types, lists, extensions, references) | Done |
| 4 | JSON/XML views | Done |
| 5 | Validation with HAPI FHIR, issues shown in the UI | Done |
| 6 | Bundles: entry navigation, contained resources | Done |
| 7 | Packaging for Windows/macOS/Linux | Documented, see [Packaging](#packaging-phase-7) |
| 8 | Pretty View: human friendly resource rendering (Pretty View plan) | Done |

## Requirements

| Tool | Version used | Notes |
|------|--------------|-------|
| JDK | 21 or newer (verified on JDK 25) | `maven.compiler.release=21` |
| Maven | 3.9+ | or use the bundled `mvnw` / `mvnw.cmd` wrapper |
| JavaFX | 25.0.4 | resolved by Maven, no manual SDK install needed |
| HAPI FHIR | 8.12.0 | R4 model, parsers, validation |

The first build downloads HAPI FHIR and the R4 validation resources, which takes a
few minutes and roughly 100 MB of local Maven repository space.

## Build, test and run

```bash
# compile and run the unit tests
mvn clean test

# launch the application (Windows: use mvnw.cmd, or mvn if Maven is on the PATH)
mvn javafx:run

# launch and open a resource straight away (also works from a file association)
mvn javafx:run "-Djavafx.args=C:\path\to\patient.json"

# run from the packaged output instead (after mvn package; uses target/lib)
java -cp "target/classes;target/lib/*" com.example.fhirviewer.Launcher   # Windows
java -cp "target/classes:target/lib/*" com.example.fhirviewer.Launcher   # macOS / Linux
```

Use `com.example.fhirviewer.Launcher`, not `Main`: `Main` extends
`javafx.application.Application`, and the JVM launcher refuses such a main class when
JavaFX is on the class path instead of the module path (*"JavaFX runtime components are
missing"*). `mvn javafx:run` uses the module path and therefore `Main`.

## What the application does

- **Opens FHIR JSON and XML files.** The format is detected from the content first and
  from the file extension second, so a `.json` file that actually contains XML still
  opens.
- **Detects the resource type automatically** through HAPI FHIR (no type picker).
- **Shows a resource tree** generated from the FHIR model:
  - nested complex datatypes, expanding to the full element path
  - repeating elements grouped with `[0]`, `[1]`, ... entries
  - primitive values rendered inline, for example `family: Smith`
  - extensions labelled with their canonical URL
  - references rendered as `Patient/123` (with the display name when present)
  - contained resources and Bundle entry resources as nested resources
- **Details panel** for the selected element: element name, full path, datatype,
  cardinality (for example `0..*`), kind, value and the specification definition.
- **Pretty View tab** (the default view) renders the resource the way a human would
  read it — see [The Pretty View](#the-pretty-view).
- **JSON and XML tabs** render the parsed resource pretty printed.
- **Bundle navigator**: inspect the Bundle itself or jump into any entry.
- **Validation** (Tools > Validate, `Ctrl+T`) reports errors and warnings with their
  FHIRPath location; the status bar shows a summary.
- **Export** the currently displayed resource as JSON or XML (File menu).
- **Sample resources** under *File > Open Sample*.

Keyboard shortcuts: `Ctrl+O` open, `Ctrl+T` validate.

## Architecture

The JavaFX UI is fully separated from FHIR processing; the UI never talks to HAPI FHIR
directly.

```
com.example.fhirviewer
|
+-- Main.java ................. JavaFX entry point
|
+-- ui/ ....................... JavaFX only: no FHIR logic
|   +-- MainWindow ............ menus, toolbar, layout, wiring
|   +-- ResourceTreeView ...... TreeView + cells + selection
|   +-- DetailsView ........... element metadata panel
|   +-- JsonView / XmlView .... read-only text views
|   +-- BundleView ............ Bundle entry navigation
|   +-- StatusView ............ status bar + validation messages
|
+-- service/ .................. application/service layer
|   +-- FhirService ........... single entry point used by the UI
|   +-- ResourceLoader ........ file / classpath / text loading, format fallback
|   +-- ValidationService ..... HAPI validation, lazily built and cached
|
+-- fhir/ ..................... FHIR layer
|   +-- FhirContextFactory .... the one place the FHIR version is chosen
|   +-- FhirModelAdapter ...... version independence boundary
|   +-- R4ModelAdapter ........ the ONLY class that imports org.hl7.fhir.r4.model
|   +-- ResourceParser ........ JSON/XML parsing + friendly errors
|   +-- ResourceSerializer .... JSON/XML rendering
|   +-- ResourceTreeBuilder ... builds the tree from model metadata
|
+-- pretty/ ................... Pretty View presentation model + formatting
|   +-- PrettyModelBuilder .... resource traversal -> PrettyDocument (generic)
|   +-- DataTypeFormatter ..... human friendly datatype renderings
|   +-- PrettyDocument / PrettyBlock / PrettyRow
|
+-- model/ .................... UI friendly view model
|   +-- ResourceNode / ElementInfo
|   +-- LoadedResource / ResourceFormat
|   +-- BundleEntryInfo
|   +-- ValidationIssue / ValidationReport
|
+-- util/ ..................... FileSupport (UTF-8 reading, BOM handling, writing)
```

### The viewer is generic

The rule from the plan is enforced by construction: **no FHIR resource or element is
hard-coded**.

- `ResourceTreeBuilder` walks the model through `FhirModelAdapter.propertiesOf(...)`.
  Element names, datatypes, cardinalities and definitions come from HAPI's model
  metadata (`org.hl7.fhir.r4.model.Base#children()` and the `Property` objects it
  returns) - never from a switch on the resource type.
- Choice elements such as `value[x]` are renamed to their actual type, so the tree shows
  spec-accurate paths like `Observation.valueQuantity` or `Patient.deceasedBoolean`.
- Everything else uses HAPI's version neutral interfaces (`IBaseResource`,
  `IPrimitiveType`, `IBaseReference`, `IBaseExtension`, `IIdType`).
- The single version specific class is `R4ModelAdapter`, which reads element metadata
  and Bundle entries. Supporting another FHIR version means adding one more adapter and
  pointing `FhirContextFactory` at it; the UI, services and tree builder stay untouched.

## Tests

`mvn test` runs the JUnit 5 suite:

| Test class | Covers |
|------------|--------|
| `ResourceFormatTest` | JSON/XML detection from content, extension, BOM; misleading extensions |
| `ResourceParserTest` | JSON/XML parsing, type detection, choice types, Bundles, contained resources, malformed and non-FHIR input |
| `ResourceTreeBuilderTest` | nested elements, repeating elements and indices, primitive values, cardinality/datatype metadata, extensions, references, contained resources, Bundle entries, unpopulated-element toggle |
| `ResourceSerializerTest` | pretty printed JSON/XML, JSON to XML round trips, Bundle round trip |
| `DataTypeFormatterTest` | Pretty View datatype renderings: Quantity, Coding, CodeableConcept, HumanName, Address, ContactPoint, Reference, Period, dateTime, extensions |
| `PrettyModelBuilderTest` | Pretty View document structure: header rows, sections, repeating groups, backbone elements, contained resources, Bundle entries, minimal resources |
| `ResourceLoaderTest` | classpath/file/text loading, format fallback, BOM, missing files, malformed content |
| `ValidationServiceTest` | valid resource, missing required elements, never throwing |
| `FhirServiceTest` | end to end: tree + JSON + XML for a loaded resource, Bundle entry listing, entry trees, samples |

Representative test resources live in `src/test/resources/fhir/` (Patient JSON and XML,
Observation with choice types, Observation with missing required elements, Bundle,
Patient with a contained resource, malformed JSON, JSON without a resource type,
plus Encounter, Organization and a minimal Patient for the Pretty View).

## The Pretty View

The **Pretty** tab is the default document view. It renders the resource as labelled
sections instead of raw JSON/XML, the way a clinical application would present it.
Like the tree it is completely generic — the same rules work for Patient, Observation,
Encounter, Organization, Bundles, contained resources and extensions:

- **Document header**: resource type, logical id, and resource level metadata
  (`Language`, profile declarations from `Meta`).
- **Summary rows**: top level primitives appear as labelled rows, for example
  `Gender: male`, `Birth Date: 1974-12-25`, `Status: final`.
- **Sections**: one titled block per top level complex value. Repeating elements get
  numbered sections (`Name 1`, `Name 2`, `Address 1`), and extensions are titled with a
  readable name derived from their canonical URL (`Patient Race` for
  `.../patient-race`).
- **Friendly datatype renderings** inside sections:
  - `HumanName` → `John Jacob Smith` (or the `text` value when present)
  - `Address` → `123 Main St, Springfield, IL 62701`
  - `ContactPoint` → `+1-555-0100 (phone work)`
  - `Quantity` → `72 beats/minute (UCUM /min)`
  - `Coding`/`CodeableConcept` → `Heart rate (LOINC 8867-4)`
  - `Reference` → `Dr. Alice Grey (Practitioner/example-gp)`; contained targets show as
    `(contained resource)`
  - `Period` → `2024-05-01 → (ongoing)`
  - `dateTime`/`instant` → `2024-05-01 10:15:00 UTC`
  - `Narrative` (xhtml) is reduced to plain text, `base64Binary` is summarised by size
- **Backbone elements** (`Observation.component`), **contained resources** and
  **Bundle entries** become nested sections; a Bundle entry section is titled
  `Entry 1 — Patient/patient-a`.
- **Bundle navigation** works with the Pretty View: selecting an entry in the Bundle
  navigator renders that entry's pretty document.

When a datatype has no friendly rendering its primitive value text is shown directly,
so no value is ever silently dropped.

## App UI and navigation

The window is a modern desktop shell: a header bar with the application name,
Open/Validate actions, a tree search field, a theme toggle and a menu bar. The
left sidebar holds the resource tree and the Bundle navigator; the right pane
holds the document tabs (Pretty, Details, JSON, XML); a status bar shows the
validation messages at the bottom.

- **Select → jump:** clicking a node in the resource tree now scrolls the open
  document tab (Pretty, JSON and XML) to the corresponding section or line and
  highlights it. Double-clicking a `Reference` in the tree navigates to the
  referenced resource when it is present in the loaded Bundle.
- **Themes:** the look is provided by the AtlantaFX Primer theme plus the
  application stylesheets `src/main/resources/css/app.css` (tokens in
  `light.css` / `dark.css`). Click the **◐ / ☀** button in the header to switch
  between the light and the dark theme; the architecture supports adding more
  themes later without touching the Java code.
- **JSON view:** the JSON tab is a card with Copy and Format buttons.

## Packaging (Phase 7)


`mvn package` copies all runtime dependencies into `target/lib`, which makes packaging
with jpackage straightforward:

```bat
:: Windows (app-image; add --type msi for an installer)
jpackage --name FHIRViewer --input target/lib --main-jar fhir-viewer-0.1.0-SNAPSHOT.jar ^
  --main-class com.example.fhirviewer.Launcher --type app-image
```

```bash
# macOS / Linux
jpackage --name FHIRViewer --input target/lib --main-jar fhir-viewer-0.1.0-SNAPSHOT.jar \
  --main-class com.example.fhirviewer.Launcher --type app-image
```

Because JavaFX is resolved by Maven, the platform specific JavaFX jars already sit in
`target/lib` for the platform that performed the build. Build the package on the target
operating system to produce a native bundle.

## Roadmap (from the plan's future enhancements)

Drag and drop, FHIR server connectivity, FHIRPath evaluation, resource editing,
resource comparison, search within a resource, StructureDefinition browsing,
terminology lookup, multiple FHIR versions, themes and recent files.