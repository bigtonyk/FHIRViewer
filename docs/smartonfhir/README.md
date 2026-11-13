# FHIRViewer — SMART on FHIR implementation plan

This folder contains one implementation instruction document per phase for Cline.

## Recommended workflow

1. Run **Phase 00** first and review its findings.
2. Implement one phase at a time. Do not ask Cline to implement the entire project in one pass.
3. After each phase, review the code changes, test results, and updated `IMPLEMENTATION-STATUS.md`.
4. Do not proceed until the current phase's acceptance criteria pass.

## Project constraints

- Java 21, JavaFX, HAPI FHIR, Jackson, Maven.
- Keep the implementation Java-only; no JavaScript, TypeScript, Node.js, Electron, or separate web frontend.
- Reuse the existing FHIRViewer architecture and styling.
- Do not refactor unrelated functionality or replace existing dependencies unnecessarily.
- Keep SMART authorization separate from server-specific plugin implementations.
- Use the system browser for interactive OAuth authorization, not JavaFX WebView as the default.
- Use Authorization Code with PKCE S256 for the native public desktop client.
- Do not embed client secrets or log tokens, authorization codes, PKCE verifiers, or sensitive patient data.
- Build and test after every phase. Update the phase document and `IMPLEMENTATION-STATUS.md` with actual results.

## Files

- `PHASE-00-REPOSITORY-ASSESSMENT.md`
- `PHASE-01-WORKBENCH-UI.md`
- `PHASE-02-SMART-DISCOVERY.md`
- `PHASE-03-OAUTH-PKCE.md`
- `PHASE-04-AUTHORIZED-FHIR-ACCESS.md`
- `PHASE-05-EHR-LAUNCH.md`
- `PHASE-06-DIAGNOSTICS-AND-TESTING.md`
- `IMPLEMENTATION-STATUS.md`

## Initial Cline prompt

Read `README.md` and `PHASE-00-REPOSITORY-ASSESSMENT.md`. Work on Phase 00 only. Inspect the repository and create/update the plan documents, but do not implement SMART functionality or change existing production behavior. Report findings and stop for review.
