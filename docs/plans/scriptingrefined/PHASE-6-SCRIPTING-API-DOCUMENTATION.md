# Phase 6 — User-Facing Scripting API Documentation

## Goal
Create clear documentation explaining how users access FHIRViewer features from interactive JShell and saved workflows. Document the actual implemented API, not imagined or aspirational methods.

## Scope and required documentation
Create a user-facing section in the repository's existing documentation location; if no convention exists, use `docs/scripting/`.

1. **Getting started:** open the scripting workspace; create/run/stop/reset/open/save scripts; interactive state versus fresh workflow/test sessions; output, diagnostics, logs, and results.
2. **Execution models:** interactive troubleshooting versus saved workflow/test execution; parameter entry; target server selection; cancellation limitations; script trust warning.
3. **API inventory:** every exposed object (`fhir`, `viewer`, `validation`, `server`, `log`, `test`, `smart`, and any implemented plugin API), with exact signatures, types, examples, errors, thread behavior, side effects, and availability conditions.
4. **FHIR resource operations:** read/search/create/update/patch and any other supported operations; IDs, search parameters, pagination, errors, capabilities, and HAPI FHIR resource types.
5. **Validation and FHIRPath:** supported validation APIs, structured issues, loaded IG/profile requirements, FHIRPath functions if exposed, and missing-dependency behavior.
6. **Viewer and server APIs:** safe selected-resource context, supported display actions, server metadata/capabilities, and limitations.
7. **Writes and destructive operations:** how writes are authorized; when confirmation appears; what destructive operations include; how cancellation works; batch/transaction behavior; why unattended destructive actions fail closed without confirmation.
8. **Automated testing:** assertion API, result states, fresh-session isolation, suites, report export, synthetic data, explicit test server selection, and destructive-test warnings.
9. **SMART on FHIR:** active-session checks, available context/scopes, authorized operations, expiry/logout, scope restrictions, and explicit statement that raw tokens/secrets are never exposed.
10. **Batch workflows:** parameters, pagination, per-item outcomes, continue/stop policy, progress, cancellation, and safe transformation preview.
11. **Examples cookbook:** basic Java/JShell, read/search, validate, show resource, server capabilities, assertions, SMART context, parameterized workflow, and batch transform where implemented. Examples must compile against the real API and use synthetic/example identifiers.
12. **Security and troubleshooting:** JShell is not a sandbox; run only trusted scripts; don't paste secrets into scripts/logs; common compile, server, auth, scope, unsupported-operation, runtime-module, packaging, and cancellation errors.

## Implementation approach
1. Inventory actual exposed objects and method signatures.
2. Compare implementation against Phases 1–5 and identify documentation gaps.
3. Write docs from real signatures; compile/run every executable example where feasible.
4. Add documentation smoke tests or example-validation tooling if practical.
5. Verify links, headings, and in-app help references.
6. Update `IMPLEMENTATION-STATUS.md` with docs created, examples verified, and gaps.

Do not add runtime features solely to match an example. Update or remove examples that do not fit the implementation.

## Acceptance criteria
- Users can find docs from the app or repository and use them offline.
- Every exposed object/method is accurately documented.
- Examples match the implementation and have been checked.
- Interactive and saved-workflow execution differences are clear.
- Security, SMART scopes, write policy, destructive confirmations, and JShell limitations are explicit.
- Missing/deferred features are clearly identified.

## Deliverable
User-facing guide, API reference, examples cookbook, troubleshooting section, documentation entry point, and updated status.
