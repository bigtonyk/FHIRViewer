# FHIRViewer JShell Scripting — Implementation Status

Copy this file to `IMPLEMENTATION-STATUS.md` in the repository and update it after each phase.

## Repository assessment
- Java version:
- Maven/build command:
- JavaFX/navigation approach:
- Module/runtime packaging:
- Existing FHIR services reused:
- Existing validation/FHIRPath services reused:
- Existing plugin abstractions reused:
- Existing SMART implementation/status:
- Existing write/confirmation patterns:
- Documentation conventions:

## Phase status

| Phase | Status | Notes |
|---|---|---|
| 1. Interactive console and saved `.jsh` files | Not started | |
| 2. FHIRViewer scripting API and write policy | Not started | |
| 3. Automated testing and repeatable workflows | Not started | |
| 4. SMART on FHIR integration | Not started | |
| 5. Advanced scripting and batch workflows | Not started | |
| 6. Scripting API documentation | Not started | |

Suggested values: Not started, In progress, Complete, Blocked, Deferred.

## Execution model decisions
- Interactive JShell session lifecycle:
- Saved workflow fresh-session behavior:
- Script storage/metadata approach:
- Target server/environment selection:
- Parameter handling:
- Stop/cancel limitations:

## Write policy and security review
- Create/update/patch policy:
- Destructive operations covered:
- Confirmation dialog location and impact details:
- Confirmation cancellation test:
- Bundle/batch destructive-operation handling:
- Secret/resource redaction:
- Script trust warning:
- Known limitations (JShell is not a sandbox):

## Latest work
- Date:
- Phase:
- Summary:
- Files changed:
- Tests/build commands run:
- Results:
- Known limitations:
- Packaging/platform checks:
- Recommended next step:

## Rules
- Record only tests actually run.
- List failures and blockers clearly.
- Do not mark a phase Complete until its acceptance criteria are met.
- Do not claim JShell scripts are sandboxed.
- Do not claim destructive operations are protected until the central policy and cancellation paths are tested.
