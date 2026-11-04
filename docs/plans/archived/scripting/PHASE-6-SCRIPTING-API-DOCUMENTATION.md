# Phase 6 — User-Facing Scripting API Documentation

## Goal
Create clear documentation explaining how users access FHIRViewer features from JShell scripts. Document the real implemented API, not imagined or aspirational methods.

## Scope and required documentation

Create a user-facing section in the repository's existing documentation location. If no convention exists, use `docs/scripting/`. Include:

1. **Getting started**
   - How to open the scripting workspace.
   - Create, run, stop, reset, open, and save scripts.
   - Interactive state persistence versus a fresh test run.
   - Reading compile errors, runtime exceptions, logs, and test results.

2. **API inventory**
   - Every object actually exposed to scripts, such as `fhir`, `viewer`, `validation`, `server`, `log`, `test`, and `smart`.
   - Exact method signatures, argument and return types, examples, errors, thread behavior, and side effects.
   - Requirements for selected server or active SMART session.
   - Read/write/delete policy and confirmation behavior.
   - Explicit unsupported operations and limitations.

3. **FHIR resource operations**
   - Read/search examples using actual signatures.
   - Resource types, IDs, search parameters, pagination, errors, and validation.
   - How HAPI FHIR resources are represented and inspected.
   - How to request resource display without manipulating JavaFX controls directly.

4. **Validation and FHIRPath**
   - Validate resources and inspect structured issues.
   - FHIRPath functions if exposed.
   - Required validation packages/Implementation Guides and behavior when dependencies are missing.
   - Document only implemented features.

5. **Server and plugin operations**
   - Inspect selected server identity/capabilities.
   - Discover supported operations and use plugin-provided functions.
   - Document vendor-specific limits and unsupported-operation behavior.
   - Include Firely/SmileCDR examples only if the actual plugin APIs support them.

6. **Automated testing**
   - Assertion API and result states.
   - Run saved tests independently of interactive history.
   - Interpret summaries and export reports.
   - Set up test servers and synthetic data safely.
   - Warn clearly about destructive tests.

7. **SMART on FHIR**
   - Check for an authorized session.
   - Read available context/scope information.
   - Use FHIR operations through the authorized session.
   - Handle expiry/logout and scope restrictions.
   - State clearly that raw tokens/secrets are never exposed.

8. **Examples cookbook**
   - Basic Java/JShell example.
   - Read a resource.
   - Search resources.
   - Validate a resource.
   - Display a resource/search result.
   - Assertion-based test.
   - Inspect server capabilities.
   - SMART context example if supported.
   - Examples must compile against the actual API and use synthetic/example identifiers.

9. **Security and troubleshooting**
   - JShell runs Java with the user's permissions and is not a sandbox.
   - Only run scripts the user trusts; never paste secrets into scripts/logs.
   - Common errors: no selected server, auth failure, missing scope, unsupported operation, compile errors, runtime-module problems, and cancellation limitations.
   - Cross-platform packaging notes.

## Implementation approach
1. Inventory actual script objects and methods.
2. Compare implementation against Phases 2–5 and identify documentation gaps.
3. Write docs from real signatures; compile/run every executable example where feasible.
4. Add a documentation smoke test or example-validation approach if practical.
5. Verify links, headings, and in-app help references.
6. Add an entry point from the scripting workspace/help menu if consistent with existing UI.
7. Update status with docs created, examples verified, and gaps.

Do not add runtime features solely to match an example. Update or remove examples that do not fit the implementation.

## Acceptance criteria
- Users can find docs from the app or repository.
- Every exposed object/method is accurately documented.
- Examples match the current implementation and have been checked.
- The guide explains how scripts access FHIRViewer features without exposing controller internals.
- Security, SMART scopes, write policies, and JShell limitations are explicit.
- Missing features are clearly identified.
- Docs are usable offline.
- `IMPLEMENTATION-STATUS.md` is updated.

## Deliverable
User-facing guide, API reference, examples cookbook, troubleshooting section, documentation entry point, and updated status.
