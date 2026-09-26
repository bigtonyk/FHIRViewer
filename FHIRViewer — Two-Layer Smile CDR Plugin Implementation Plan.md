# FHIRViewer — Two-Layer Smile CDR Plugin Implementation Plan

## Objective

Implement Smile CDR support in FHIRViewer using a two-layer architecture:

1. **Standard FHIR REST Layer**
   - Implements generic FHIR REST functionality.
   - Handles standard operations such as metadata, search, read, paging, and FHIR Bundles.
   - Can be reused by many FHIR servers.

2. **Smile CDR Plugin Layer**
   - Identifies and configures Smile CDR.
   - Provides Smile-specific authentication and capabilities.
   - Uses the Standard FHIR REST Layer rather than duplicating FHIR functionality.
   - Provides a place for future Smile CDR-specific operations.

The architectural goal is:

    FHIRViewer
         |
         v
    FhirServerPlugin API
         |
         +-----------------------+
         |                       |
         v                       v
    StandardFhirRest        SmileCdrPlugin
         |                       |
         |                 Smile-specific
         |                 configuration/
         |                 authentication/
         |                 capabilities
         |                       |
         +-----------+-----------+
                     |
                     v
               FHIR REST Client
                     |
                     v
                  HAPI FHIR
                     |
                     v
                 HTTP/HTTPS
                     |
                     v
                 FHIR Server

The critical design principle is:

**SmileCdrPlugin must NOT become a second FHIR REST client.**

---

# 1. Inspect the Existing Project First

Before changing code:

1. Inspect the complete FHIRViewer project structure.
2. Identify the current Java/Maven version.
3. Identify the HAPI FHIR version.
4. Identify existing FHIR client code.
5. Identify existing JavaFX controllers/views.
6. Identify existing configuration/persistence.
7. Identify existing logging.
8. Identify existing tests.
9. Identify the plugin architecture from the previous FHIR server implementation.
10. Identify how resources are currently loaded into the pretty viewer.

Do not replace existing architecture unnecessarily.

Produce a short implementation map before making changes.

---

# 2. Two-Layer Architecture

The implementation must clearly separate generic FHIR functionality from Smile CDR functionality.

## Layer 1 — Standard FHIR REST

Create or reuse:

    StandardFhirRestClient

This component performs standard FHIR REST operations.

It should know about:

- FHIR base URL
- HAPI FHIR
- FHIR versions
- HTTP requests
- search
- read
- metadata
- FHIR Bundles
- paging
- HTTP errors
- OperationOutcome

It should NOT know about:

- Smile CDR
- Epic
- Cerner
- Athena
- vendor authentication
- JavaFX

---

## Layer 2 — Smile CDR Plugin

Create:

    SmileCdrPlugin

This implements:

    FhirServerPlugin

The Smile plugin should use the Standard FHIR REST layer.

It should contain only Smile-specific functionality such as:

- Smile CDR identification
- Smile-specific configuration
- Smile-specific authentication providers
- Smile CDR capability detection
- future Smile-specific operations
- optional Smile-specific endpoint behavior

The Smile plugin should delegate normal FHIR operations to:

    StandardFhirRestClient

---

# 3. Dependency Direction

The dependency direction should be:

    UI
     |
     v
    FhirServerManager
     |
     v
    FhirServerPlugin API
     |
     +--------------------------+
     |                          |
     v                          v
    StandardFhirRestPlugin    SmileCdrPlugin
     |                          |
     +------------+-------------+
                  |
                  v
        StandardFhirRestClient
                  |
                  v
              HAPI FHIR

The core application must NOT depend directly on Smile CDR classes.

The JavaFX UI must NOT depend directly on Smile CDR classes.

---

# 4. Standard FHIR REST Layer

Create a reusable standard REST implementation.

Possible classes:

    StandardFhirRestPlugin
    StandardFhirRestClient
    StandardFhirRestConfiguration

Adapt names to the existing project.

---

# 5. Standard FHIR Operations

The standard layer should initially support:

    GET [base]/metadata

    GET [base]/[resourceType]/[id]

    GET [base]/[resourceType]?[search parameters]

It should also support:

- search Bundle processing
- total counts
- next-page links
- previous-page links
- FHIR OperationOutcome
- HTTP error handling
- FHIR version detection

---

# 6. StandardFhirRestClient API

Create a clean application-level API.

Conceptually:

    interface FhirRestClient {

        CapabilityStatement getCapabilityStatement();

        Resource read(
            String resourceType,
            String resourceId);

        SearchResult search(
            SearchRequest request);

    }

The actual interface should use the project's existing domain models.

Do not expose:

- JavaFX classes
- HTTP implementation details
- Smile-specific classes

---

# 7. Standard Search

The standard REST layer should accept:

    SearchRequest

containing information such as:

    resourceType
    parameters
    pageSize
    sort
    patientContext

Example:

    Resource Type:
        Patient

    Parameters:
        family = Smith

The StandardFhirRestClient converts this into:

    GET /Patient?family=Smith

The UI does not construct the URL.

---

# 8. Standard Search Result

Create or reuse:

    SearchResult

It should contain:

- resources
- total count
- next-page URL/token
- previous-page URL/token
- warnings
- errors

FHIR search responses are normally returned as:

    Bundle
        type = searchset

The standard REST layer parses the Bundle and converts it into the application-level SearchResult.

The UI should never have to know how the FHIR Bundle is structured.

---

# 9. Paging

FHIR servers commonly return paging information in:

    Bundle.link

The StandardFhirRestClient should understand:

    relation = next
    relation = previous

Do not reconstruct paging URLs when the server has supplied the links.

Expose paging through the generic SearchResult.

---

# 10. Resource Read

The standard REST layer handles:

    GET [base]/Patient/123

    GET [base]/Observation/456

    GET [base]/Condition/789

and any other valid FHIR resource.

The result should be returned using the existing HAPI FHIR representation expected by FHIRViewer.

The existing pretty viewer should display the resource.

---

# 11. CapabilityStatement

The standard REST layer owns:

    GET [base]/metadata

It should:

1. Retrieve the CapabilityStatement.
2. Parse it using HAPI FHIR.
3. Determine FHIR version.
4. Determine supported resources.
5. Determine supported interactions.
6. Determine search capabilities.
7. Cache it appropriately.

This is NOT Smile-specific.

---

# 12. Standard FHIR Plugin

Create:

    StandardFhirRestPlugin

This is the generic implementation that can be used by servers that implement normal FHIR REST.

For example:

    StandardFhirRestPlugin
          |
          +---- HAPI FHIR REST
          |
          +---- FHIR R4
          |
          +---- metadata
          +---- search
          +---- read
          +---- paging

This plugin should be usable even when the server is not Smile CDR.

---

# 13. Smile CDR Plugin

Create:

    SmileCdrPlugin

Implement:

    FhirServerPlugin

The Smile plugin should primarily provide:

    Plugin ID:
        smile-cdr

    Display Name:
        Smile CDR

    Description:
        Smile CDR FHIR Server

    Supported FHIR:
        R4 initially

---

# 14. Smile Plugin Delegation

The Smile plugin should contain a StandardFhirRestClient.

Conceptually:

    SmileCdrPlugin
          |
          v
    StandardFhirRestClient
          |
          v
       HAPI FHIR

For example:

    SmileCdrPlugin.search(request)

should ultimately delegate to:

    StandardFhirRestClient.search(request)

It should NOT implement another search method that constructs its own FHIR URLs.

---

# 15. Smile Server Detection

The Smile plugin should provide a mechanism to determine whether a server appears to be Smile CDR.

Possible evidence includes:

- CapabilityStatement information
- server software metadata
- configured plugin selection
- known Smile-specific extensions
- administrator-selected integration type

Do NOT make server detection overly dependent on a particular string in the CapabilityStatement.

The user should always be able to explicitly select:

    Smile CDR

when configuring a server.

Automatic detection can be added later.

---

# 16. Smile Configuration

Create:

    SmileCdrConfiguration

Only include Smile-specific settings.

Possible future settings:

    Smile CDR base URL
    authentication type
    OAuth configuration
    SMART configuration
    trusted-client configuration

Do not duplicate:

    base URL
    FHIR version
    search parameters
    timeout

if those are already part of the generic FhirServerConfiguration.

Use inheritance, composition, or a generic settings map as appropriate for the existing architecture.

---

# 17. Base URL

The Smile plugin must NOT assume a particular endpoint path.

For example, the user might configure:

    https://example.com/fhir

or:

    https://example.com/member-fhir

The configured FHIR base URL must be treated as authoritative.

Therefore:

    baseUrl + "/metadata"

must be used for capability discovery.

Do not hard-code:

    /fhir

Do not hard-code a Smile CDR module name.

---

# 18. Smile Connection Test

The Smile plugin's connection test should use the standard REST layer.

Flow:

    SmileCdrPlugin
          |
          v
    StandardFhirRestClient
          |
          v
    GET /metadata
          |
          v
    CapabilityStatement

The Smile plugin may then perform Smile-specific validation if necessary.

For example:

    Standard validation:
        Is this a valid FHIR server?

    Smile validation:
        Does this appear to be Smile CDR?

Keep those responsibilities separate.

---

# 19. FHIR Version

The StandardFhirRestClient should determine the FHIR version from:

    CapabilityStatement.fhirVersion

For the initial Smile plugin:

    Support R4.

Do not hard-code R4 into the generic REST client.

The generic client should be designed to support future versions.

---

# 20. Authentication Architecture

Authentication must be separate from both:

    StandardFhirRestClient

and:

    SmileCdrPlugin

Use:

    FhirAuthenticationProvider

Conceptually:

    FhirRestClient
          |
          v
    AuthenticationProvider
          |
          +---- Anonymous
          +---- Bearer Token
          +---- OAuth2
          +---- SMART
          +---- Smile-specific provider

The REST client should simply ask the authentication provider to prepare the outgoing request.

---

# 21. Anonymous Authentication

Implement:

    AnonymousAuthenticationProvider

This should work with Smile CDR environments that permit anonymous access.

This is useful for initial development and testing.

---

# 22. Bearer Token Authentication

Implement:

    BearerTokenAuthenticationProvider

It should add:

    Authorization: Bearer <token>

to outgoing requests.

The token must never be logged.

The token must not be stored in ordinary server configuration.

---

# 23. SMART on FHIR

Design SMART/OAuth support as a reusable authentication provider.

Do not make SMART functionality specific to Smile CDR.

The architecture should eventually support:

    SmartOnFhirAuthenticationProvider

usable by:

    Smile CDR
    Epic
    Cerner
    other SMART-compatible servers

This is an important consequence of the two-layer design.

SMART is a FHIR ecosystem capability, not inherently a Smile-specific feature.

---

# 24. Smile-Specific Authentication

If Smile CDR requires behavior that is not part of the generic authentication providers, create:

    SmileCdrAuthenticationProvider

or a more specific provider.

Do not put authentication code directly into SmileCdrPlugin.

Potential future Smile-specific provider:

    SmileTrustedClientAuthenticationProvider

This should only be implemented if actually required.

---

# 25. Trusted Client Mode

Smile CDR supports Trusted Client Mode.

Do not enable or send trusted-client headers automatically.

If implemented later, make it an explicit authentication choice.

For example:

    Authentication:
        Smile Trusted Client

Configuration could include:

    Username
    Permissions

Display a warning that this authentication mode requires a trusted server environment.

This feature should not be part of the initial basic Smile implementation unless required.

---

# 26. Smile-Specific Capabilities

The Smile plugin should expose a capability mechanism for operations that are not generic FHIR REST functionality.

Potential future operations include:

    $get-resource-counts
    $reindex
    $expunge
    $graphql
    Smile administration operations

These should live behind:

    SmileCdrPlugin

rather than:

    StandardFhirRestClient

---

# 27. Generic vs Smile Responsibility

Use this rule when deciding where code belongs.

## Put it in Standard FHIR REST if:

It is defined by the FHIR REST specification or commonly implemented FHIR behavior.

Examples:

    metadata
    read
    search
    paging
    Bundle processing
    OperationOutcome
    resource CRUD
    transactions
    batch
    standard authentication mechanisms

## Put it in SmileCdrPlugin if:

It exists specifically because the server is Smile CDR.

Examples:

    Smile-specific operations
    Smile-specific configuration
    Smile-specific authentication
    Smile-specific server detection
    Smile-specific extensions
    Smile-specific administration APIs

## Put it in a generic authentication provider if:

The functionality applies to multiple FHIR vendors.

Examples:

    OAuth 2.0
    SMART on FHIR
    Bearer tokens
    OIDC

This separation should be enforced during implementation.

---

# 28. JavaFX Server Configuration

The UI should present:

    Add FHIR Server

    Server Name:
        Smile Test Server

    Base URL:
        https://example.com/fhir

    Integration:
        Smile CDR

    FHIR Version:
        Auto Detect

    Authentication:
        Anonymous

    [Test Connection]

The UI should not know how Smile CDR performs the connection.

It simply asks:

    FhirServerManager.testConnection(...)

---

# 29. Search UI

The search UI remains completely vendor-neutral.

Example:

    Server:
        Smile Test Server

    Resource:
        Patient

    Family Name:
        Smith

    [Search]

The UI calls:

    FhirServerManager.search(...)

The manager routes the request to:

    SmileCdrPlugin

The plugin delegates standard FHIR search to:

    StandardFhirRestClient

The result returns to the UI as:

    SearchResult

---

# 30. Resource Viewer

When the user selects a search result:

    UI
      |
      v
    FhirServerManager.read(...)
      |
      v
    SmileCdrPlugin
      |
      v
    StandardFhirRestClient
      |
      v
    HAPI FHIR Resource
      |
      v
    Existing FHIRViewer

Do not create Smile-specific rendering logic.

---

# 31. Error Handling

Standard FHIR errors belong in the generic REST layer.

Handle:

    400
    401
    403
    404
    429
    500
    502
    503

Parse FHIR:

    OperationOutcome

when available.

Smile-specific errors can be interpreted by the Smile plugin only when they contain Smile-specific semantics.

---

# 32. Logging

The StandardFhirRestClient should provide safe HTTP logging.

Never log:

- access tokens
- OAuth codes
- passwords
- Authorization headers
- complete FHIR resources
- PHI

SmileCdrPlugin should also follow these rules.

Safe example:

    Smile CDR request
    GET /Patient/{id}
    Status: 200
    Duration: 138 ms

---

# 33. Testing the Two Layers

Tests must explicitly verify the architectural separation.

## Standard REST Tests

Test:

    metadata
    read
    search
    Bundle parsing
    paging
    OperationOutcome
    HTTP errors

These tests should NOT mention Smile CDR.

---

## Smile Plugin Tests

Test:

    plugin identification
    Smile configuration
    plugin registration
    Smile-specific capability detection
    authentication selection

The Smile plugin tests should mock the StandardFhirRestClient where appropriate.

---

# 34. Smile Integration Test

Provide an optional integration test:

    connect
       ↓
    metadata
       ↓
    Patient search
       ↓
    Patient read

The test should use a developer-controlled Smile CDR instance.

The normal build:

    mvn test

must not require a live Smile CDR server.

---

# 35. Mock REST Tests

The StandardFhirRestClient should have HTTP-level tests using a mock HTTP server.

For example:

    Mock HTTP Server

        /metadata
        /Patient?family=Smith
        /Patient/123

This allows the standard FHIR REST layer to be thoroughly tested without any actual FHIR server.

The mock responses should contain realistic FHIR JSON.

---

# 36. Implementation Sequence

Implement the feature in this order.

## Phase 1 — Inspect Existing Architecture

Do not modify code.

Document:

- existing plugin API
- existing FHIR client
- existing configuration
- existing JavaFX architecture
- existing tests

---

## Phase 2 — Extract Standard REST Layer

If the existing implementation already contains generic REST functionality, refactor it into:

    StandardFhirRestClient

Do not duplicate it.

Verify:

    metadata
    read
    search

still work.

---

## Phase 3 — Standard REST Plugin

Create:

    StandardFhirRestPlugin

It should wrap:

    StandardFhirRestClient

Verify it works independently of Smile CDR.

---

## Phase 4 — Smile Plugin

Create:

    SmileCdrPlugin

Register it with the plugin registry.

At this stage it may simply delegate all standard operations to:

    StandardFhirRestClient

Verify that FHIRViewer can identify:

    Smile CDR

as an available integration.

---

## Phase 5 — Smile Configuration

Add Smile-specific configuration only where necessary.

Do not duplicate generic FHIR server configuration.

---

## Phase 6 — Smile Connection

Implement:

    Test Connection

using:

    GET /metadata

Verify the CapabilityStatement.

---

## Phase 7 — Smile Patient Search

Test:

    Patient?family=Smith

Verify:

- search
- Bundle parsing
- result display

---

## Phase 8 — Smile Resource Read

Test:

    Patient/{id}

Display the result using the existing pretty viewer.

---

## Phase 9 — Generic Resource Support

Verify:

    Observation
    Condition
    Encounter
    DiagnosticReport
    MedicationRequest
    Procedure

No Smile-specific resource code should be required.

---

## Phase 10 — Authentication

Implement:

    Anonymous

then:

    Bearer Token

Then design:

    SMART/OAuth

as a reusable authentication provider.

---

## Phase 11 — Smile-Specific Capabilities

Only after basic FHIR functionality works, add infrastructure for:

    $get-resource-counts
    $graphql
    $reindex
    $expunge

Do not implement administrative functionality merely to prove the plugin works.

---

# 37. Acceptance Criteria

The implementation is complete when:

## Architecture

- Standard FHIR REST functionality is isolated.
- Smile CDR does not duplicate the FHIR REST client.
- SmileCdrPlugin delegates standard operations.
- JavaFX contains no Smile-specific HTTP code.
- Authentication is independently extensible.

## Standard FHIR

- `/metadata` works.
- Resource read works.
- Search works.
- Search Bundles are parsed.
- Paging works.
- OperationOutcome is handled.

## Smile CDR

- Smile CDR can be configured.
- Smile CDR can be connection-tested.
- Smile CDR CapabilityStatement is retrieved.
- Patient search works.
- Patient read works.
- Existing FHIRViewer resource rendering works.

## Security

- Tokens are not logged.
- PHI is not logged.
- TLS validation remains enabled.
- Secrets are not stored in ordinary configuration.

## Testing

- Standard REST tests work without Smile CDR.
- Smile plugin tests work without a live server.
- Optional Smile integration tests work against a real test instance.
- `mvn test` works offline/without external server dependencies.

---

# 38. Architectural Validation Test

Before considering the implementation complete, perform this thought experiment:

### Add Epic

Could a developer add:

    EpicPlugin

without changing:

    Search UI
    Resource Viewer
    FhirServerManager
    SearchResult
    SearchRequest
    StandardFhirRestClient

?

If the answer is no, the abstraction needs improvement.

### Add Cerner

Could a developer add:

    CernerPlugin

without changing Smile CDR code?

If the answer is no, the Smile plugin contains functionality that belongs in the generic layer.

### Add another standard FHIR server

Could a developer simply configure:

    StandardFhirRestPlugin

without writing another plugin?

If yes, the two-layer design is working correctly.

---

# Final Architecture

The desired final architecture is:

                         FHIRViewer UI
                              |
                              v
                     FhirServerManager
                              |
                              v
                      FhirServerPlugin
                              |
                 +------------+------------+
                 |                         |
                 v                         v
       StandardFhirRestPlugin        SmileCdrPlugin
                 |                         |
                 |                    Smile-specific
                 |                    configuration
                 |                    authentication
                 |                    capabilities
                 |                         |
                 +------------+------------+
                              |
                              v
                    StandardFhirRestClient
                              |
                              v
                         HAPI FHIR
                              |
                              v
                         HTTP/HTTPS
                              |
                 +------------+-------------+
                 |                          |
                 v                          v
          Generic FHIR Server          Smile CDR

The most important rule is:

**If the code would also be useful for Epic, Cerner, Athena, HAPI FHIR, or another standards-compliant FHIR server, it belongs in the Standard FHIR layer—not in SmileCdrPlugin.**

The Smile plugin should be intentionally thin.

The initial working path should be:

    Add Smile CDR Server
             ↓
    SmileCdrPlugin
             ↓
    StandardFhirRestClient
             ↓
    GET /metadata
             ↓
    CapabilityStatement
             ↓
    Search Patient
             ↓
    FHIR Bundle
             ↓
    Select Patient
             ↓
    Read Patient
             ↓
    Existing FHIRViewer Pretty View

Build that path first. Then add SMART/OAuth and Smile-specific functionality incrementally.