# FHIRViewer — FHIR Server Connectivity Plugin Architecture

## Objective

Add the ability for FHIRViewer to connect to, search, and read resources from remote FHIR servers.

The architecture MUST be plugin-based so that support for different FHIR server APIs, authentication mechanisms, search capabilities, and vendor-specific behavior can be added without modifying the core FHIRViewer application.

The first implementation should establish a clean generic FHIR server abstraction and include a standard REST/FHIR implementation. Vendor-specific implementations can then be added as plugins.

The existing application is a Java desktop application using:

- Java
- Maven
- HAPI FHIR
- JavaFX
- Cross-platform Windows/macOS/Linux support

Do NOT redesign the existing application unnecessarily. Integrate this capability into the current architecture.

---

# 1. Core Design Principles

## 1.1 Separate the UI from server connectivity

The JavaFX UI must never directly contain:

- HTTP calls
- HAPI FHIR client creation
- authentication logic
- vendor-specific API logic
- URL construction
- server-specific search syntax

The UI should communicate with a generic service such as:

    FhirServerConnection
    FhirServerPlugin
    FhirServerService

The exact names should be chosen after inspecting the existing project structure.

---

## 1.2 Use a plugin interface

Create a stable interface representing a FHIR server integration.

Conceptually:

    FhirServerPlugin

The interface should provide capabilities such as:

- plugin ID
- display name
- description
- supported FHIR versions
- supported authentication methods
- ability to test a connection
- ability to search resources
- ability to read a resource
- optional capability discovery
- optional custom server configuration
- optional vendor-specific behavior

Example conceptual API:

    interface FhirServerPlugin {

        String getId();

        String getDisplayName();

        boolean supports(FhirServerConfiguration configuration);

        ConnectionResult testConnection(
            FhirServerConfiguration configuration);

        SearchResult search(
            FhirServerConfiguration configuration,
            SearchRequest request);

        ResourceResult read(
            FhirServerConfiguration configuration,
            String resourceType,
            String resourceId);

        CapabilityStatement getCapabilities(...);
    }

The actual API should be designed based on the existing application and HAPI FHIR APIs.

Do not blindly copy this example.

---

# 2. Generic FHIR Server Model

Create a configuration model representing a FHIR server.

It should contain information such as:

    FhirServerConfiguration

Possible fields:

- server name
- base URL
- FHIR version
- plugin ID
- authentication configuration
- timeout settings
- optional headers
- optional custom settings
- whether TLS certificate validation is enabled
- connection status

Do NOT store passwords, OAuth refresh tokens, or other secrets directly in ordinary configuration files.

Design authentication so it can later support:

- Anonymous
- Basic authentication
- Bearer token
- OAuth 2.0
- SMART on FHIR
- API keys
- vendor-specific authentication

Authentication should itself be extensible.

Conceptually:

    FhirAuthenticationProvider

Possible implementations:

    AnonymousAuthenticationProvider
    BasicAuthenticationProvider
    BearerTokenAuthenticationProvider
    OAuth2AuthenticationProvider
    SmartOnFhirAuthenticationProvider

Only implement authentication methods actually required for the initial feature. Establish the interfaces so additional methods can be added later.

---

# 3. Generic Search API

Create an application-level search abstraction.

The UI should NOT construct FHIR query URLs itself.

For example:

    SearchRequest

could contain:

- resource type
- search parameters
- sort parameters
- page size
- paging information
- optional count
- optional text search
- optional date range
- optional patient context

Example:

    SearchRequest
        resourceType = "Patient"
        parameters:
            name = "Smith"
            birthdate = "..."
        pageSize = 50

The plugin converts this generic request into whatever API the server requires.

---

# 4. Search Results

Create a generic result model.

For example:

    SearchResult

It should support:

- returned resources
- total count when available
- paging information
- continuation token / next URL when available
- errors
- warnings
- server metadata

The core application should not assume every server supports:

- total counts
- paging
- `_count`
- `_offset`
- `_include`
- `_revinclude`
- advanced search parameters

The plugin should advertise capabilities.

---

# 5. Resource Reading

Implement a generic operation:

    read(resourceType, resourceId)

For example:

    read("Patient", "12345")

The plugin returns a HAPI FHIR resource or another suitable application-level representation.

The existing FHIRViewer pretty-view functionality should then be reused to display the resource.

The server functionality should NOT create a second resource-rendering system.

---

# 6. Standard FHIR REST Plugin

Create the first concrete plugin:

    StandardFhirRestPlugin

This plugin should support standard FHIR REST operations using HAPI FHIR.

Initially support:

    GET [base]/metadata

    GET [base]/[resourceType]/[id]

    GET [base]/[resourceType]?[search parameters]

Use HAPI FHIR rather than implementing FHIR parsing manually.

Support at minimum:

- FHIR R4
- JSON
- standard REST search
- read by ID
- capability statement retrieval
- paging where supported

Structure the implementation so R4 can later be extended to R5 and other versions.

---

# 7. Vendor-Specific Plugins

The architecture must allow vendor-specific plugins without changing the core application.

Examples of future plugins might include:

    EpicFhirPlugin
    CernerFhirPlugin
    AthenaHealthFhirPlugin
    CustomVendorPlugin

Do NOT implement these now unless required by the existing project.

Instead, prove the architecture by implementing the standard REST plugin.

Vendor plugins may override:

- authentication
- endpoint construction
- search syntax
- pagination
- custom headers
- capability detection
- proprietary operations
- OAuth/SMART configuration
- resource transformations

---

# 8. Plugin Discovery

Create a plugin registry.

Conceptually:

    FhirServerPluginRegistry

Responsibilities:

- discover available plugins
- register plugins
- retrieve plugin by ID
- determine which plugin supports a server configuration
- expose plugin list to the UI

The registry should be independent of JavaFX.

Prefer Java's standard plugin mechanisms where practical, such as:

    ServiceLoader

or another lightweight mechanism appropriate for the existing Maven project.

Avoid introducing a heavyweight plugin framework unless there is a compelling reason.

---

# 9. External Plugin Support

Design the architecture so that plugins can eventually be distributed separately from the main application.

Ideal future structure:

    FHIRViewer/
        core/
        ui/
        plugins/
            standard-fhir/
            epic/
            cerner/
            athena/
        ...

The initial implementation does NOT need to support downloading/installing plugins from the Internet.

However, avoid tightly coupling plugin classes to UI classes.

The long-term goal is that a plugin can be added without changing the FHIRViewer core.

---

# 10. Server Manager UI

Add a new area to the JavaFX application for managing FHIR servers.

Possible UI concept:

    FHIR Servers

        + Add Server

        My Test Server
        https://example.com/fhir
        FHIR R4
        Connected

        Epic Test Server
        https://...
        SMART on FHIR
        Not Connected

The exact UI should follow the application's existing modernized JavaFX design.

Do NOT create a dated-looking JavaFX dialog if the application already has a newer visual design.

---

# 11. Add Server Dialog

Create a dialog/wizard for adding a server.

Initial fields:

- Server Name
- FHIR Base URL
- FHIR Version
- Connection Type / Plugin
- Authentication

Example:

    Server Name:
        My HAPI Server

    FHIR Base URL:
        https://example.com/fhir

    FHIR Version:
        R4

    Integration:
        Standard FHIR REST

    Authentication:
        None

Buttons:

    Test Connection
    Save
    Cancel

Test Connection should:

1. Validate the URL.
2. Select the appropriate plugin.
3. Perform a capability/metadata request.
4. Determine whether the server is reachable.
5. Determine the FHIR version where possible.
6. Display useful error information.
7. Avoid freezing the JavaFX UI thread.

---

# 12. Asynchronous Network Operations

ALL network operations must be asynchronous.

Never perform HTTP/FHIR requests directly on the JavaFX Application Thread.

Use an appropriate mechanism already present in the project, or introduce a lightweight service/task abstraction.

Operations requiring background execution include:

- connection tests
- capability retrieval
- resource searches
- resource reads
- paging
- authentication

The UI should display appropriate progress/busy states.

---

# 13. Server Selection

Add the ability to select an active FHIR server.

The active server should be available to the application through a service rather than being stored directly in JavaFX controllers.

Conceptually:

    FhirServerManager

Responsibilities:

- list configured servers
- add server
- remove server
- update server
- select active server
- retrieve active server
- connect/disconnect
- persist configuration

---

# 14. Search UI

Add a FHIR server search capability to the existing viewer.

Possible workflow:

    Select FHIR Server
            ↓
    Select Resource Type
            ↓
    Enter Search Criteria
            ↓
    Execute Search
            ↓
    Display Results
            ↓
    Select Resource
            ↓
    Existing Pretty Viewer

Initially support a simple search interface.

For example:

    Resource Type:
        Patient

    Name:
        Smith

    Birth Date:
        1960-01-01

    [Search]

Results:

    Patient/12345
    John Smith
    1960-01-01

    Patient/67890
    Jane Smith
    1960-01-01

Do not attempt to build an exhaustive FHIR search-builder UI in the first implementation.

The architecture should allow one to be added later.

---

# 15. Generic Resource Search

The search UI should eventually support arbitrary resource types.

For example:

    Patient
    Observation
    Condition
    Encounter
    MedicationRequest
    DiagnosticReport
    Procedure
    ServiceRequest
    AllergyIntolerance

The core application should not hard-code vendor-specific resource types.

Use the CapabilityStatement to discover supported resources where possible.

---

# 16. CapabilityStatement

When connecting to a FHIR server, retrieve:

    GET [base]/metadata

Use the resulting CapabilityStatement to determine:

- FHIR version
- supported resource types
- supported interactions
- search parameters
- paging behavior where documented
- server capabilities

Cache the CapabilityStatement for the configured connection when appropriate.

The UI can eventually use this information to dynamically configure available resource types and search options.

---

# 17. Error Handling

Create a common error model.

Examples:

    FhirConnectionException
    FhirAuthenticationException
    FhirAuthorizationException
    FhirNotFoundException
    FhirServerException
    FhirSearchException

Do not expose raw Java stack traces to normal users.

Display useful messages such as:

    Unable to connect to FHIR server.

    Server returned HTTP 401 Unauthorized.

    Check the configured authentication credentials.

For debugging, retain detailed logging.

---

# 18. Logging

Use the project's existing logging framework if one exists.

Log:

- plugin selected
- server URL
- request type
- response status
- timing
- errors

NEVER log:

- passwords
- access tokens
- refresh tokens
- authorization headers
- sensitive patient data

Be especially careful because FHIR resources contain PHI.

---

# 19. Security

Treat remote FHIR data as potentially containing PHI.

Requirements:

- HTTPS should be strongly preferred.
- Do not disable TLS certificate validation by default.
- Never write credentials into normal application logs.
- Never write OAuth tokens into normal logs.
- Avoid unnecessary caching of patient resources.
- Clearly separate server configuration from authentication secrets.
- Do not persist retrieved patient resources unless explicitly implemented as a user feature.

For development/testing, allow local HTTP servers such as:

    http://localhost:8080/fhir

but make the security implications clear in the UI if appropriate.

---

# 20. Configuration Persistence

Persist server definitions locally.

Possible structure:

    servers.json

or an application-specific configuration mechanism already used by FHIRViewer.

Persist:

- server name
- base URL
- plugin ID
- FHIR version
- non-secret configuration
- authentication type

Do NOT persist secrets in plain text.

Create an abstraction such as:

    FhirServerRepository

so the storage mechanism can later be changed.

---

# 21. Dependency Management

Inspect the existing pom.xml before adding dependencies.

Reuse existing HAPI FHIR dependencies whenever possible.

Do not introduce duplicate HTTP libraries or competing FHIR client implementations unnecessarily.

Before adding a dependency:

1. Check whether the project already provides equivalent functionality.
2. Check compatibility with the existing Java version.
3. Check compatibility with the existing HAPI FHIR version.
4. Keep the dependency footprint small.

---

# 22. Testing Architecture

Create tests for each layer.

## Plugin tests

Test:

- plugin registration
- plugin discovery
- plugin selection
- configuration validation
- standard REST URL construction
- search parameter handling
- resource reads
- error handling

## Integration tests

Use a local/test FHIR server where practical.

The tests should verify:

    connect
        ↓
    metadata
        ↓
    search Patient
        ↓
    read Patient
        ↓
    return HAPI resource

Do not require an external production FHIR server for automated tests.

---

# 23. Mock FHIR Server

If practical, add a lightweight test setup using an embedded or containerized FHIR server.

HAPI FHIR's testing facilities may be appropriate.

The goal is to make this test possible:

    mvn test

without requiring Internet access or a real healthcare organization's server.

---

# 24. Plugin API Versioning

Design the plugin interface with future compatibility in mind.

For example:

    FhirServerPlugin API version 1

A plugin should declare its API version.

Do not over-engineer this in the first release, but avoid exposing internal application classes unnecessarily through the plugin interface.

The plugin API should use stable domain models such as:

    FhirServerConfiguration
    SearchRequest
    SearchResult
    ResourceResult
    ConnectionResult

rather than JavaFX controls or internal UI objects.

---

# 25. Suggested Package Structure

Adapt this to the existing project rather than blindly creating it.

Possible structure:

    com.toniken.fhirviewer
        ├── core
        │   ├── model
        │   │   ├── FhirServerConfiguration
        │   │   ├── SearchRequest
        │   │   ├── SearchResult
        │   │   └── ...
        │   │
        │   ├── plugin
        │   │   ├── FhirServerPlugin
        │   │   ├── FhirServerPluginRegistry
        │   │   └── ...
        │   │
        │   ├── server
        │   │   ├── FhirServerManager
        │   │   └── FhirServerRepository
        │   │
        │   └── auth
        │       ├── FhirAuthenticationProvider
        │       └── ...
        │
        ├── plugins
        │   └── standard
        │       └── StandardFhirRestPlugin
        │
        └── ui
            ├── server
            ├── search
            └── ...

Use the actual project's package naming conventions.

---

# 26. Important Architectural Rule

The dependency direction should be:

    JavaFX UI
          ↓
    FHIRViewer Application/Core
          ↓
    Plugin API
          ↑
    FHIR Server Plugins
          ↓
    HAPI FHIR / HTTP / Vendor APIs

The core should NOT depend on a specific vendor plugin.

A vendor plugin should depend on the plugin API, not on JavaFX.

This is critical.

---

# 27. Initial Implementation Scope

Do NOT attempt to implement everything at once.

Implement in phases.

## Phase 1 — Architecture

Create:

- FhirServerConfiguration
- FhirServerPlugin
- FhirServerPluginRegistry
- FhirServerManager
- FhirServerRepository
- SearchRequest
- SearchResult
- connection/error models

Add unit tests.

---

## Phase 2 — Standard FHIR REST Plugin

Implement:

- plugin registration
- R4 support
- metadata
- read
- search
- paging
- error handling
- HAPI FHIR integration

Test against a local/test FHIR server.

---

## Phase 3 — Server Management UI

Add:

- FHIR server list
- Add Server
- Edit Server
- Delete Server
- Test Connection
- Select Active Server

Follow the existing FHIRViewer UI architecture and visual style.

---

## Phase 4 — Search UI

Add:

- resource type selection
- search fields
- execute search
- result list
- loading state
- errors
- paging

Selecting a result should open the existing resource viewer.

---

## Phase 5 — Capability-Based UI

Use CapabilityStatement information to dynamically determine:

- available resources
- supported interactions
- search capabilities

Do not make this phase a prerequisite for basic searching.

---

## Phase 6 — Authentication

Add authentication providers incrementally:

1. Anonymous
2. Bearer token
3. Basic authentication if needed
4. OAuth 2.0
5. SMART on FHIR

SMART/OAuth should be designed as a plugin/provider architecture rather than hard-coded into the standard REST plugin.

---

# 28. Future Plugin Examples

Once the architecture is working, it should be possible to add something like:

    Epic SMART Plugin

without changing:

- search UI
- resource viewer
- FHIR server manager
- core models
- application startup

The Epic plugin would provide its own:

- OAuth configuration
- SMART discovery
- authentication
- endpoint behavior
- vendor-specific search behavior

Likewise, another vendor could provide a different plugin.

---

# 29. AI Agent Development Instructions

Before modifying code:

1. Inspect the entire existing project structure.
2. Identify the current application architecture.
3. Identify existing HAPI FHIR usage.
4. Identify existing JavaFX controllers/views.
5. Identify existing configuration/persistence.
6. Identify existing logging.
7. Identify existing test infrastructure.
8. Identify the Java and Maven versions.
9. Identify whether a service/dependency-injection pattern already exists.

Do NOT replace existing architecture simply to implement this feature.

After inspection, produce a short implementation plan specific to the actual repository.

Then implement the feature incrementally.

After each major phase:

1. Compile the project.
2. Run tests.
3. Fix errors.
4. Verify that existing FHIRViewer functionality still works.
5. Only then proceed to the next phase.

---

# 30. Acceptance Criteria

The implementation is complete when all of the following are true:

### Core

- FHIRViewer has a clean FHIR server abstraction.
- The UI does not contain server-specific HTTP logic.
- Server implementations are plugins.
- Plugins can be registered/discovered independently of the UI.

### Standard FHIR

- A standard FHIR REST plugin exists.
- R4 servers are supported.
- CapabilityStatement can be retrieved.
- Resources can be searched.
- Individual resources can be read.
- Paging is supported where available.

### UI

- Users can add a FHIR server.
- Users can test the connection.
- Users can select a server.
- Users can search resources.
- Users can select a search result.
- The existing FHIR resource viewer displays the retrieved resource.

### Reliability

- Network operations do not block the JavaFX UI thread.
- Connection and HTTP errors are handled cleanly.
- Existing application functionality continues to work.
- Automated tests exist for the plugin architecture and standard REST implementation.

### Extensibility

Adding a new server integration should primarily require:

    1. Create a new plugin.
    2. Implement FhirServerPlugin.
    3. Register/discover the plugin.

It should NOT require modifying the core search UI or existing resource viewer.

---

# Final Requirement

Prioritize a clean, maintainable plugin architecture over adding a large number of features in the first iteration.

The most important outcome of this work is NOT merely "FHIRViewer can connect to a FHIR server."

The most important outcome is:

    FHIRViewer can connect to different kinds of FHIR servers
    through replaceable plugins without coupling server-specific
    behavior to the application core or JavaFX UI.

Keep the first implementation small, testable, and extensible.