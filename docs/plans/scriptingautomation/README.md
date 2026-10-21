# FHIRViewer Scripting and Automation Roadmap

This plan defines four implementation phases, in order:
1. Foundation
2. Testing, Debugging, and Interoperability
3. Interoperability Automation
4. Extensibility and Ecosystem

Read `MASTER_CONSIDERATIONS.md` first. Implement one phase at a time, inspecting the repository and existing work before coding. Preserve completed functionality, merge requirements rather than duplicating them, update `IMPLEMENTATION_STATUS.md`, and do not proceed until acceptance criteria pass or a deferral is explicitly approved.

## REST Workbench boundary
The existing REST Workbench remains the interactive tool for manually composing and executing individual REST requests. Scripting adds multi-step workflows, repeatability, assertions, batch processing, transformations, and API access to FHIRViewer services. Reuse the Workbench's underlying request/client/authentication services where appropriate; do not rebuild its UI or create a parallel HTTP stack.

## Suggested Cline workflow
For each phase: inspect repository and current implementation; classify requirements as implemented/partial/missing/blocked; propose a minimal plan; implement only the current phase; run tests and manual checks; update status and docs; summarize results and stop.
