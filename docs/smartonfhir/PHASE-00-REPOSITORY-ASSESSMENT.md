# Phase 00 — Repository assessment and architecture

## Objective

Understand the existing FHIRViewer implementation and determine how SMART on FHIR support can be added without disrupting existing functionality.

## Instructions to Cline

Inspect the repository and document:

- Maven module and package structure.
- Java, JavaFX, HAPI FHIR, and Jackson versions.
- Application entry point and JavaFX navigation.
- View/controller/service conventions.
- FHIR server configuration and persistence.
- HAPI FHIR client construction and authentication mechanisms.
- Jackson configuration.
- Plugin interfaces and extension mechanisms.
- Logging, error reporting, and testing conventions.
- Java module descriptor and packaging requirements, if present.
- Existing HTTP utilities, background-task patterns, and preferences storage.
- Any existing OAuth/OIDC code or dependencies.

Identify reusable components for server configuration, connection management, resource rendering, JSON formatting, diagnostics, and session handling.

## Proposed responsibility boundaries

Recommend repository-appropriate packages and classes for:

1. SMART UI: JavaFX views, controls, navigation, and presentation state.
2. Discovery service: SMART metadata retrieval and validation.
3. Authorization service: PKCE, browser launch, callback handling, token exchange, and session lifecycle.
4. Authorized FHIR access: connect an authorized session to HAPI FHIR.
5. Launch context model: patient/encounter context and session metadata.
6. Diagnostics: structured events and safe, redacted messages.

Prefer plain Java services and small data models. Do not introduce a dependency-injection framework without a concrete need.

Evaluate Nimbus OAuth 2.0 SDK as an optional dependency. Do not add it if existing dependencies already provide the needed, maintained functionality. Do not write cryptographic protocol implementations from scratch.

## Constraints

- Do not implement SMART functionality in this phase.
- Do not modify unrelated production code.
- Do not upgrade dependencies without a specific, documented reason.
- Do not assume all FHIR servers support SMART.
- Keep SMART client authorization separate from server-specific plugin logic.

## Deliverables

Create or update:

- `docs/implementation/smart-on-fhir/PHASE-00-REPOSITORY-ASSESSMENT.md`
- `docs/implementation/smart-on-fhir/PHASE-01-WORKBENCH-UI.md`
- `docs/implementation/smart-on-fhir/PHASE-02-SMART-DISCOVERY.md`
- `docs/implementation/smart-on-fhir/PHASE-03-OAUTH-PKCE.md`
- `docs/implementation/smart-on-fhir/PHASE-04-AUTHORIZED-FHIR-ACCESS.md`
- `docs/implementation/smart-on-fhir/PHASE-05-EHR-LAUNCH.md`
- `docs/implementation/smart-on-fhir/PHASE-06-DIAGNOSTICS-AND-TESTING.md`
- `docs/implementation/smart-on-fhir/IMPLEMENTATION-STATUS.md`

The repository documents should be consistent with this phase set. Adapt paths to the existing repository conventions if necessary.

## Acceptance criteria

- Architecture and integration points are documented.
- Current versions and dependencies are known.
- Proposed dependency changes are justified.
- Risks and test approach are documented.
- No unrelated production code has changed.
- Stop and report findings before starting Phase 01.
