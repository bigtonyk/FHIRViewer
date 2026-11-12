# FHIRViewer — Code Analysis, Seventh Pass

Date: 2026-11-12
Continues `CODE-ANALYSIS-ISSUES.md`, `CODE-ANALYSIS-ISSUES-ROUND2.md`,
`CODE-ANALYSIS-ISSUES-ROUND3.md`, `CODE-ANALYSIS-ISSUES-ROUND4.md`,
`CODE-ANALYSIS-ISSUES-ROUND5.md` and `CODE-ANALYSIS-ISSUES-ROUND6.md`. None of those passes
named the pretty-view package in its scope, and the sixth pass ended by pointing at it.

Scope: `com.spiraleyes.fhirviewer.pretty` - `PrettyModelBuilder` (382 lines),
`DataTypeFormatter` (436), `PrettyBlock` (105), `PrettyDocument` (39), `PrettyRow` (27) - the
renderer that consumes it (`ui/PrettyView`, 334 lines), the two call sites that feed it
(`FhirService.buildPrettyView` and `DocumentSession`), and the package's two test classes
(`PrettyModelBuilderTest`, 303 lines; `DataTypeFormatterTest`, 222 lines).

Method: static reading of every file above. Line numbers are from `7724874`, where the full
suite was green (1,028 tests). No fix was made and no JavaFX window was opened during this
pass.

## Priority legend

| Priority | Meaning |
|----------|---------|
| **P0** | Data loss, security exposure, or a crash in ordinary use. None found. |
| **P1** | A feature that does not work as presented, or a defect users hit in normal use. |
| **P2** | Robustness or maintainability problem that costs work, or a user-visible defect in a secondary screen. |
| **P3** | Hygiene, naming, consistency, stale documentation. |

IDs are stable: `R7-*` are new here.

## 1. Why this package, and what is sound

The pretty view is the default reading surface: `DocumentSession.display` selects its tab, and
`DocumentSession.select` rebuilds it for the node the user clicked, so every resource opened
passes through `PrettyModelBuilder`. It had never been in a pass's scope.

What was checked and found sound:

- The traversal is genuinely generic. `PrettyModelBuilder` switches only on the *element kind*
  (primitive, summarizable datatype, complex) and on nothing else; no resource type or element
  name is hard-coded, and `DataTypeFormatter.summarize` switches only on datatype.
- The model types are immutable where it matters: `PrettyDocument` and `PrettyBlock` copy their
  lists in compact constructors, and `PrettyRow` normalises nulls, so a builder cannot mutate a
  document after the fact.
- The depth guard (`MAX_DEPTH = 12`) stops Bundle-in-Bundle recursion with a labelled row rather
  than a stack overflow.
- The renderer clears the previous highlight before replacing content, drops a stale highlight
  when a selection has no matching label, and guards a null scene transform before scrolling.
- The two test classes cover the parts that are easy to get wrong: repeating groups into
  numbered sections, repeating primitives joined into one row, focused views of a group, a
  primitive and a reference, every summarizer, date formatting and extension naming.

The package performs no I/O, starts no threads and carries no charset conversion; the Unicode
arrows and comparison signs in `DataTypeFormatter` are literal UTF-8 and were verified clean.

## 2. Outstanding findings

### R7-1. A nested extension with sub-extensions renders as its title and nothing else

**Bug - P2. Fixed 2026-11-12.** `PrettyModelBuilder.renderInto` summarises every summarizable datatype below the
document root (line 276), and `Extension` is in that set (`DataTypeFormatter.SUMMARIZABLE`).
`DataTypeFormatter.summarizeExtension` (lines 295-302) returns `name + " = " + value` only when
the extension has a `value[x]`; an extension built from sub-extensions has none, so it returns
the bare name.

The result is that `Patient.name[0].extension` whose children carry the actual data appears in
the Pretty View as a single row such as "Patient Race", with no indication that it has content.
The top-level case is fine - an extension on the resource itself is rendered as a section - but
anything nested inside a complex element (a name, an address, a coding) goes through the summary
path. JSON, XML and the tree still show the children, so nothing is lost from the document, but
the primary reading view quietly reports an empty-looking extension.

Fixed by `PrettyModelBuilder.isContainerExtension`: an extension without a `value[x]` is kept
out of the summary path and rendered as a section, so its sub-extensions appear as rows.
`PrettyModelBuilderTest.nestedExtensionShowsItsChildren` covers the case.

### R7-2. Narrative text keeps its HTML entities

**Bug - P3. Fixed 2026-11-12.** `DataTypeFormatter.stripXhtml` (lines 419-421) removes tags and collapses
whitespace, but never decodes entities. A `Narrative.div` containing `&amp;`, `&lt;`, `&#39;`
or `&nbsp;` renders with those sequences literally, so "Smith &amp; Sons" appears as
"Smith &amp; Sons" in the Pretty View. Paragraph and line breaks are flattened at the same time,
which is a lesser version of the same problem. The raw JSON view is correct; this is the one view
that claims to be human friendly.

Fixed by `decodeEntities`, which decodes the five XML entities and numeric character
references after the tags are removed, so an escaped angle bracket stays text.
`DataTypeFormatterTest.stripsMarkupAndDecodesEntities` covers the case.

### R7-3. Unknown terminology systems are dropped for Coding and printed for Quantity

**Consistency - P3. Fixed 2026-11-12.** `summarizeCoding` (lines 182-190) deliberately hides a system that is not
one of the four known ones, keeping only the display or the bare code. `summarizeQuantity`
(lines 214-216) does the opposite: it strips the scheme and prints the remainder, so a unit from
an unregistered system reads as `example.org/units (… )`.

Two consequences follow. The same policy question is answered two ways in one class, and a coding
whose system is local loses the only thing that distinguishes it: two nested CodeableConcepts
with code `A1` from different systems render identically, while the top-level section view -
which shows System and Code as rows - does not. The comment above the Coding branch explains the
noise it is avoiding, but the quantity branch has no matching comment, which is how the split went unnoticed.

Fixed by choosing the policy that keeps the information: `summarizeCoding` now always includes
the system, shortened the same way Quantity shortens it, so `Coding("http://example.org/race",
"white", "White")` reads `White (example.org/race white)`.
`DataTypeFormatterTest.showsUnknownSystems` checks both datatypes.

### R7-4. A focused primitive is titled with its datatype

**Usability - P3. Fixed 2026-11-12.** `PrettyModelBuilder.build(IBase element, String elementName)` (lines 112-124)
sets the document's resource type to `element.fhirType()`, and `PrettyView.headerNode` renders
that as the page title. Selecting `Patient.birthDate` therefore opens a document headed
"date", and selecting `Patient.name[0].given` opens one headed "string". The element the user
selected appears only as the row label ("Birth Date"), and the datatype - a term the view exists
to hide - becomes the most prominent text on the page.

Fixed by titling a focused primitive with its element name (`Birth Date`, and `Given` for a
repeated primitive group) and leaving the datatype to complex values, where it is the useful
identity; `Patient.name[0]` still reads `HumanName`. `PrettyModelBuilderTest.primitiveNodeIsTitledWithItsElementName` covers the case,
and the two focused-primitive tests that pinned the old title now assert the element name.

### R7-5. A new document keeps the old scroll position

**Usability - P3. Fixed 2026-11-12.** `PrettyView.show` (lines 59-80) replaces the content and clears the
highlight, but never resets the scroll position. `setVvalue` is only called from
`scrollToShow` (lines 304 and 308), which runs from a tree selection. Opening resource B while
resource A was scrolled halfway down leaves B positioned proportionally to A's height - or
pinned at the bottom - until the reader scrolls or clicks a node. Switching resources is the
common case, so this is hit far more often than the selection path it works around.

Fixed by resetting the vertical value once the new content is installed, so a document always
opens at the top; `PrettyViewScrollTest.showingADocumentResetsTheScrollPosition` covers it.

### R7-6. An out-of-range index renders the last value instead of failing to resolve

**Robustness - P3. Fixed 2026-11-12.** `PrettyModelBuilder.resolveElement` and `resolveProperty` (lines 194 and
210) both use `Math.min(index, property.values().size() - 1)`. A path naming `name[4]` when only
two names exist silently resolves to `name[1]`, so a stale path shows a different element's
value rather than falling back to the full document the way an unresolvable path does
(`build` at lines 100-103 and 204-207). Nothing produces a stale path today - the tree is rebuilt
after every edit - which is what makes this worth recording rather than fixing in a hurry: the
fallback hides the bug it would otherwise expose.

Fixed by treating an index outside the populated values as an unresolvable path, so both
callers take the existing fallback to the whole document.
`PrettyModelBuilderTest.outOfRangePathShowsTheWholeDocument` builds a node whose path names
`identifier[4]` on a patient with two identifiers and checks the full document comes back.

## 3. Limitations

- Static reading only. No JavaFX window was opened for this pass, so R7-1, R7-4 and R7-5 are
  established from the model and the renderer, not from watching the screen.
- R7-1's severity is about what the view shows, not about lost data: the JSON, XML, FHIRPath and
  tree views still carry the sub-extensions.
- Large-document performance was not measured. A Bundle with hundreds of entries renders one
  JavaFX node per row and section into a plain `VBox`, which is the shape that gets slow, but no
  timing was taken and no finding is claimed without one.
- The summarisation policy (which datatypes become one line) is deliberate design; this pass
  questions only where that policy makes content unreachable (R7-1) or inconsistent (R7-3).
- The suite was green at `7724874`; no test was run for this pass, and none of these findings has
  a test yet.

## 4. Suggested order

1. **R7-1** first: it is the only finding where the pretty view misreports content, and the fix
   is local - summarise an extension only when it has a `value[x]`, otherwise fall through to a
   section block so its children render.
2. **R7-2** next, because it is cheap: decode the five standard entities after stripping tags.
3. **R7-5**, a one-line reset in `show`, with a test that shows the scroll property rather than
   the rendering.
4. **R7-4** and **R7-3** together, since both are wording decisions: title the focused document
   with the element name, and choose one policy for unknown systems.
5. **R7-6** last, or when the tree paths gain another producer.
