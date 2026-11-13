# Guide Viewer and Documentation Roadmap

Status: in progress
Last reviewed: 2026-11-13

First tranche implemented: copyable code, contextual guide links, guide
search/history/contents, all-guide integrity tests, screen fitting, external
link handling, and API signature checks. The item headings below record what
is done and what remains.

This roadmap records the follow-up ideas for the JavaFX guide viewer and the
SMART/scripting documentation, ranked by value. It is a plan, not a promise
that every item ships in one change.

## 1. Copyable code blocks — implemented

**Why:** The samples are meant to be pasted into the Scripting Console. The
first renderer used non-selectable `Text` nodes, and the initial fix did not
add a copy path.

**First tranche:** make the code block selectable and add a Copy button that
copies the sample without the language tag. Preserve line breaks and
indentation.

## 2. Contextual guide links — implemented

**Why:** Session lifetime and Log out are subtle; the Workbench and Scripting
Console should open the relevant guide section instead of making users find
the Help menu and scroll.

**First tranche:** add a Workbench guide button targeting the session/logout
section and a Scripting Console Guides menu targeting the scripting API and
SMART session sections.

## 3. In-guide search and navigation history — implemented

**Why:** The SMART guide is over 400 lines and the scripting guide over 200.
The native viewer has no browser Find, and a contents jump leaves no way back.

**First tranche:** find next/previous with match counts, a Back button for
anchor jumps, and a contents menu built from the rendered headings.

## 4. Documentation integrity tests for every guide — implemented

**Why:** The existing contents-link test covers only the User Guide. The code
fence regression reached the viewer despite a green suite, and docs/help
parity was checked by hand.

**First tranche:** verify anchors, fenced blocks and normalized docs/help
parity for the User, SMART and scripting guides.

## 5. Documented API snippets stay true — started

**Why:** Rendering cannot detect a sample that calls a removed or renamed
method. The guides should be checked against the API they document.

**Planned:** add signature checks for the SMART and scripting calls used in
samples, then execute read-only examples against fakes where practical.

## 6. Runnable SMART sandbox walkthrough — planned

**Why:** A reader needs a SMART-enabled server and a registered client before
the sample workflows can be tried. A known-good sandbox makes the guide
verifiable end to end.

**Planned:** document one concrete sandbox or bundled mock, its limitations,
and the exact setup needed for the sample workflows.

## 7. Fit guide windows to the display and remember size — partly implemented

**Why:** The guide window can extend beyond the desktop, cutting off tables
and long code lines.

**First tranche:** clamp the dialog to the visible screen bounds. Remembering
the user's last size and position remains planned.

## 8. Responsive tables and code — partly implemented

**Why:** The guides rely on multi-column tables and long code lines; fixed
layout and disabled horizontal scrolling can clip both.

**Planned:** provide horizontal scrolling or a stacked narrow layout for
tables, and a deliberate wrap/source-mode policy for code.

## 9. Accessibility and reading controls — planned

**Why:** These are long reference documents, and fixed small text or
non-semantic text nodes are barriers at high DPI and for low-vision users.

**Planned:** font-size/zoom controls, keyboard heading navigation, a clear
focus order, and accessibility metadata for headings and code.

## 10. Guide metadata and external-link handling — partly implemented

**Why:** Readers need to know whether a guide matches their build, and any
future external reference must not silently do nothing.

**Planned:** show the app version and review date for each guide; route
external HTTP(S) links through the system browser while keeping internal
anchors in the viewer.
