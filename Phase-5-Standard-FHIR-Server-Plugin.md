# FHIR Viewer — Phase 5 Implementation Plan
## Standard FHIR Server Plugin

### Objective
Create a generic plugin implementing standard FHIR REST server functionality.

This becomes the default integration for standards-compliant FHIR servers.

### Initial Operations
Support:
- `GET /metadata`
- Resource read.
- Resource search.
- Search parameters.
- Pagination.
- Resource type discovery.
- Standard FHIR error handling.

Example:

```text
FHIR Viewer
     │
     ▼
FHIR Server Plugin API
     │
     ▼
Standard FHIR REST Plugin
     │
     ▼
FHIR REST Server
```

### Capability Discovery
Use the server's CapabilityStatement where practical instead of assuming every server supports every operation.

### Search
Build search requests from:
- Resource Type.
- Search Parameter.
- Operator.
- Value.

Example:

```text
Patient
name = Smith
```

should produce the appropriate FHIR REST search request.

Support pagination using standard FHIR Bundle links.

### Error Handling
Handle:
- HTTP 400.
- HTTP 401.
- HTTP 403.
- HTTP 404.
- HTTP 409.
- HTTP 429.
- HTTP 500+.
- FHIR OperationOutcome.

Display useful information to the user.

### Authentication
Design authentication to be extensible.

Potential mechanisms:
- None.
- Basic.
- Bearer token.
- OAuth 2.0 / SMART on FHIR.

Do not make SMART authentication mandatory unless required by the current project.

### Testing
Use a mock or local FHIR server where practical.

Test:
- `/metadata`.
- Read.
- Search.
- Pagination.
- Invalid requests.
- OperationOutcome.
- Authentication failures.
- Server unavailable.
- Capability discovery.

### Completion Criteria
The FHIR Viewer can connect to a standards-compliant FHIR REST server through the generic plugin without server-specific code.

### Cline Execution Rules
- Build on the plugin API established in Phase 4.
- Keep the implementation standards-based.
- Do not add server-specific hacks to the core.
- Use CapabilityStatement where appropriate.
- Add automated tests.
- Run `mvn test` and `mvn verify` where practical.
- Stop when this phase is complete.
