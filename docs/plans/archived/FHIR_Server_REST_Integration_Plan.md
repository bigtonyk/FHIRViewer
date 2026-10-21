# FHIRViewer — FHIR Server Plugin REST Integration

## Goal

Extend the existing FHIRViewer FHIR Server Plugin API so that server plugins can communicate directly with their target FHIR servers using HTTP/REST, **and expose that functionality to the FHIRViewer UI through a common, discoverable API**.

The functionality should support:

* Standard FHIR REST operations
* Server-specific/custom REST endpoints
* GET/POST/PUT/PATCH/DELETE operations as appropriate
* Reading and writing FHIR resources
* Searching/querying resources
* Server capability/status queries
* Authentication and authorization
* Server-specific operations
* UI access to supported operations
* Future support for additional server APIs without modifying the core application

The architecture should keep **FHIRViewer core independent of vendor-specific APIs**, while allowing the Viewer UI to interact with those APIs through the Plugin API.

---

# Phase 1 — Inspect the Existing Plugin Architecture

Before modifying code, inspect the current FHIR Server Plugin implementation thoroughly.

Identify:

* Plugin interfaces
* Abstract plugin/base classes
* Plugin discovery/registration
* Plugin lifecycle
* Server connection configuration
* Existing FHIR server abstractions
* Existing HTTP/network code
* Existing authentication code
* Existing resource read/write functionality
* Existing UI integration
* Existing plugin configuration/storage
* Existing Smile/Firely-specific code

Also inspect how the current UI communicates with plugins.

Determine whether there is already a pattern for:

```text
UI
  |
  v
Application/service layer
  |
  v
Plugin API
```

If such a pattern exists, extend it instead of creating a parallel architecture.

---

# Phase 2 — Define the Common REST API

Add a common REST abstraction that can be used by all FHIR server plugins.

The API should support:

* GET
* POST
* PUT
* PATCH
* DELETE
* Query parameters
* Headers
* Request body
* Content type
* Accept type
* HTTP status
* Response headers
* Response body
* Error information
* Request timeout
* Cancellation where practical

Keep the API independent of JavaFX.

The REST API should be usable by:

```text
Plugin implementation
Viewer UI
Application/service layer
Automated operations
```

Do not force UI code to construct raw HTTP requests itself.

---

# Phase 3 — Create a Server REST Request/Response Model

Introduce appropriate request and response objects if the existing architecture does not already provide them.

A request should support:

```text
HTTP method
URL/path
query parameters
headers
body
content type
accept type
```

A response should expose:

```text
HTTP status code
headers
body
success/failure
FHIR OperationOutcome when available
```

Keep these objects UI-neutral.

---

# Phase 4 — Implement the Common HTTP Transport

Create or extend the shared HTTP transport used by server plugins.

The transport should handle:

* HTTP/HTTPS
* Connection timeout
* Read timeout
* Request timeout
* HTTP redirects where appropriate
* Headers
* Request body
* Response body
* Error responses
* TLS configuration
* Connection failures
* Logging/debugging

Use the existing project's HTTP implementation if one exists.

If none exists, prefer the standard Java HTTP client available in the project's supported Java version.

The transport should NOT contain Smile CDR or Firely-specific logic.

---

# Phase 5 — Authentication Architecture

Inspect the existing authentication support and extend it so plugins can use it.

Support the mechanisms required by the server plugins, potentially including:

```text
No authentication
Basic authentication
Bearer token
OAuth 2.0 / SMART on FHIR
Custom authentication
```

Authentication should be separate from REST operations.

Conceptually:

```text
Viewer UI
    |
Plugin API
    |
FhirServerClient
    |
AuthenticationProvider
    |
HTTP Transport
```

Do not expose credentials or tokens unnecessarily to UI components.

---

# Phase 6 — Standard FHIR REST Operations

Add convenient high-level operations for standard FHIR interactions.

Support, where the server provides them:

```text
Read
Create
Update
Patch
Delete
Search
History
Metadata
Batch
Transaction
FHIR operations
```

Examples:

```text
GET /Patient/123

POST /Patient

PUT /Patient/123

PATCH /Patient/123

DELETE /Patient/123

GET /Patient?name=Smith

GET /metadata
```

Do not assume every server supports every operation.

The plugin/server capability layer should identify supported functionality.

---

# Phase 7 — High-Level FHIR Client API

On top of the low-level REST API, create a higher-level FHIR-oriented API where appropriate.

For example:

```text
readResource(type, id)
createResource(resource)
updateResource(resource)
deleteResource(type, id)
search(type, parameters)
getCapabilityStatement()
executeOperation(...)
```

Adapt these to the existing Plugin API.

The purpose is to allow both the UI and other application components to perform common FHIR operations without knowing HTTP implementation details.

---

# Phase 8 — Custom/Vendor REST Operations

A server plugin must be able to expose REST functionality that is NOT part of the standard FHIR REST API.

Examples:

```text
Smile CDR custom endpoint
Firely Server endpoint
Vendor-specific operation
Server management/status endpoint
```

Do not hard-code these endpoints into FHIRViewer core.

Instead, allow plugins to expose discoverable custom operations.

Conceptually:

```java
List<ServerOperation> getAvailableOperations();
```

An operation should describe:

```text
operation ID
display name
description
HTTP method
relative path
parameter definitions
request body requirements
expected response
required permissions
```

For example:

```text
ID: smile.status
Name: Smile CDR Status
Method: GET
Path: /custom/status
```

The actual endpoint must be verified against the vendor's documentation.

---

# Phase 9 — Separate Standard and Vendor APIs

Maintain a clear distinction between:

```text
FHIR Standard API
```

and:

```text
Vendor API
```

Conceptually:

```text
FHIRViewer Core
       |
       +----------------------+
       |                      |
       v                      v
Standard FHIR API       Plugin API
       |                      |
       |              +-------+-------+
       |              |               |
       v              v               v
FHIR REST        Smile Plugin    Firely Plugin
                      |               |
                      v               v
                Smile REST       Firely REST
```

The core application should never contain vendor-specific logic such as:

```java
if (serverType == SMILE) ...
```

Vendor-specific behavior belongs in the plugin.

---

# Phase 10 — Server Capabilities

Extend the server plugin API so a plugin can report server capabilities.

Potential capabilities:

```text
FHIR version
FHIR REST support
read
create
update
patch
delete
search
history
batch
transaction
conditional operations
$operations
custom endpoints
authentication mechanisms
```

The plugin should also be able to expose vendor-specific capabilities.

These capabilities must be accessible to the Viewer UI.

The UI should use them to determine which commands, buttons, menus, and actions should be available.

---

# Phase 11 — Server Status

Add a standardized mechanism for querying server status.

Conceptually:

```java
ServerStatus getStatus();
```

The result could contain:

```text
reachable
HTTP status
FHIR version
server name
server version
latency
authentication state
error message
```

For standard FHIR servers this may use:

```text
GET /metadata
```

For vendor plugins, the plugin may use a vendor-specific status endpoint.

The UI must be able to invoke this operation and display the result.

---

# Phase 12 — UI Plugin Operations API

**This is a critical phase.**

The Viewer UI must have a clean way to invoke functionality exposed by the server plugin.

Do NOT have JavaFX controllers directly construct REST calls.

The architecture should be:

```text
Viewer UI
    |
    v
Viewer/Application Service
    |
    v
FhirServerPlugin API
    |
    +---------------------+
    |                     |
    v                     v
Standard FHIR API     Custom Operations
    |                     |
    +----------+----------+
               |
               v
        REST Transport
```

The UI should be able to:

```text
connect to server
disconnect
test connection
retrieve status
retrieve capabilities
read resources
search resources
create resources
update resources
patch resources
delete resources
execute standard FHIR operations
execute plugin-specific operations
```

---

# Phase 13 — UI Operation Discovery

The UI should NOT need to know that an operation belongs to Smile CDR or Firely Server.

Instead, it should ask the active plugin:

```text
What operations do you support?
```

For example:

```text
Server
 ├── Status
 ├── Capabilities
 ├── Search
 ├── Read Resource
 ├── Create Resource
 ├── Update Resource
 ├── Delete Resource
 └── Custom Operations
      ├── Smile Status
      ├── Smile Tenant Information
      └── Smile Server Operation
```

For Firely:

```text
Server
 ├── Status
 ├── Capabilities
 ├── Search
 ├── Read Resource
 ├── Create Resource
 ├── Update Resource
 ├── Delete Resource
 └── Custom Operations
      ├── Firely Operation A
      └── Firely Operation B
```

The actual operation list comes from the plugin.

This prevents the core UI from becoming vendor-specific.

---

# Phase 14 — UI Operation Model

Create or extend a model representing an operation exposed to the UI.

Conceptually:

```text
ServerOperation
    ID
    Name
    Description
    Category
    HTTP method
    Parameters
    Request body type
    Response type
    Requires authentication
    Required capability
```

Potential categories:

```text
CONNECTION
STATUS
FHIR
SEARCH
RESOURCE
ADMINISTRATION
CUSTOM
```

The model should contain enough metadata for the UI to construct an appropriate interaction without knowing the implementation details.

---

# Phase 15 — UI Parameter/Input Handling

Custom REST operations may require parameters.

The UI needs a generic mechanism for entering operation parameters.

For example:

```text
Operation: Search Resource

Resource Type: Patient
Name: Smith
Birth Date: 1960-01-01
```

Or:

```text
Operation: Vendor Operation

Parameter A: __________
Parameter B: __________
```

For operations requiring a request body, the UI should support an appropriate editor.

For FHIR operations, reuse the existing JSON/XML/FHIR editors where possible.

Do not create a second JSON editor.

---

# Phase 16 — UI Results Handling

The UI should have a generic way to display REST results.

Depending on the response, it should be able to display:

### FHIR Resource

Open it in the existing FHIR resource viewer.

### FHIR Bundle

Allow the existing resource/tree UI to display the Bundle/resources.

### JSON

Display using the existing JSON viewer/editor.

### XML

Display using the existing XML viewer/editor.

### OperationOutcome

Display useful error/diagnostic information.

### Plain text

Display in an appropriate text view.

### HTTP metadata

Display status code, headers, and request duration where useful.

The REST layer should return structured results; the UI decides how to display them.

---

# Phase 17 — UI Server Connection Workflow

Review the existing server connection UI and integrate the new functionality.

The connection screen should eventually support:

```text
Server URL
FHIR version/server type
Authentication
Credentials/token configuration
Connect
Test Connection
```

After connection:

```text
Connected
Server status
FHIR version
Server capabilities
```

The UI should maintain the currently active server/plugin context.

---

# Phase 18 — UI Resource Operations

Integrate the standard REST functionality into the existing resource UI.

Where appropriate, provide actions such as:

```text
Read
Refresh
Create
Update
Delete
```

For example, when viewing:

```text
Patient/123
```

the UI could expose:

```text
Refresh from Server
Edit
Save to Server
Delete from Server
```

The UI should call the Plugin API rather than directly issuing HTTP requests.

---

# Phase 19 — UI Search

Integrate server search into the existing Viewer UI.

The search UI should allow:

```text
Resource Type
Search Parameters
Execute Search
```

Results should use the existing resource display/tree infrastructure.

Support FHIR Bundle pagination.

The UI should obtain pagination URLs/operations from the REST client rather than constructing them itself.

---

# Phase 20 — UI Custom Operations

Provide a generic UI location for plugin-specific operations.

For example:

```text
Server
   |
   +-- Standard Operations
   |
   +-- Custom Operations
          |
          +-- Smile CDR Status
          +-- Smile Tenant Information
          +-- ...
```

The core UI should not contain:

```java
if (plugin instanceof SmilePlugin)
```

Instead:

```text
activePlugin.getAvailableOperations()
```

should populate the UI.

This makes custom operations automatically available to future plugins.

---

# Phase 21 — Generic FHIR Server Plugin

Update/create the generic FHIR server plugin to demonstrate standard REST functionality.

It should support:

```text
GET /metadata
GET resource
POST resource
PUT resource
PATCH resource if supported
DELETE resource
FHIR search
pagination
```

This becomes the reference implementation for plugin authors.

The Viewer UI should be able to use all supported functionality through the common API.

---

# Phase 22 — Smile CDR Plugin

Extend the Smile CDR plugin using the same REST framework.

Separate:

```text
Standard FHIR REST calls
```

from:

```text
Smile-specific REST calls
```

The Smile plugin should demonstrate how a vendor plugin adds custom operations without modifying FHIRViewer core.

Potential areas to investigate include:

* Smile CDR server status
* Smile-specific administration APIs
* tenant information
* server/database status
* Smile-specific operations
* configuration APIs

Do NOT invent endpoint paths.

Verify endpoints against the appropriate Smile CDR documentation for the supported version.

Every supported Smile operation should be discoverable by the Viewer UI through the Plugin API.

---

# Phase 23 — Firely Server Plugin

Apply the same architecture to Firely Server.

Support:

```text
standard FHIR REST
```

plus:

```text
Firely-specific REST APIs
```

Investigate the supported Firely Server version and documentation before implementing custom endpoints.

Every supported Firely operation should be discoverable by the Viewer UI.

---

# Phase 24 — UI Asynchronous Operations

REST calls must not block the JavaFX UI thread.

All network operations should execute asynchronously/background.

The UI should support:

```text
loading state
progress where appropriate
success
failure
cancellation where practical
```

The UI must remain responsive while:

* searching
* downloading resources
* uploading resources
* retrieving metadata
* executing custom operations
* checking server status

Use the project's existing JavaFX asynchronous/task architecture if one exists.

Do not introduce a second task framework unnecessarily.

---

# Phase 25 — UI Error Handling

The UI should translate REST failures into useful messages.

Examples:

```text
401 — Authentication required
403 — Access denied
404 — Resource not found
409 — Resource conflict
429 — Server rate limit reached
500 — Server error
Network timeout
TLS/certificate error
FHIR OperationOutcome
```

Where possible, provide:

```text
HTTP status
server message
OperationOutcome diagnostics
request path
```

Do not expose credentials or tokens.

---

# Phase 26 — Logging and Diagnostics

Add structured logging around REST operations.

Capture:

```text
server/plugin
HTTP method
endpoint/path
status code
duration
success/failure
error information
```

Do NOT log:

* passwords
* OAuth tokens
* Authorization headers
* unnecessary patient data

Provide a configurable debug/trace mode if the application already supports one.

---

# Phase 27 — Plugin Developer API

Document how another developer would create a server plugin.

The documentation should show:

1. Creating a plugin
2. Registering the plugin
3. Defining server configuration
4. Creating the REST client
5. Adding authentication
6. Implementing standard FHIR operations
7. Adding a custom endpoint
8. Defining server capabilities
9. Implementing server status
10. Defining UI-visible operations
11. Defining operation parameters
12. Returning operation results
13. Handling errors
14. Testing the plugin

Include a small example plugin.

The example should demonstrate both:

```text
Standard FHIR operation
```

and:

```text
Custom vendor operation
```

being exposed to the Viewer UI.

---

# Phase 28 — Testing

Create unit and integration tests.

## REST tests

Test:

* Request construction
* URL construction
* Query parameters
* Headers
* Authentication headers
* Response parsing
* HTTP status handling
* OperationOutcome parsing
* Error handling
* Pagination
* Capability handling

## Plugin tests

Test:

* Plugin discovery
* Standard operations
* Custom operations
* Capability discovery
* Status
* Authentication
* UI operation metadata

## UI tests

Where practical, verify:

* Operations appear when supported
* Unsupported operations are hidden/disabled
* Parameters are presented correctly
* Results are displayed correctly
* Errors are displayed correctly
* Long-running calls do not block the UI

Use mock HTTP servers for deterministic tests.

Do not make the normal test suite dependent on an external production server.

---

# Phase 29 — Backward Compatibility

Verify:

* Existing plugins still load.
* Existing plugin discovery still works.
* Existing FHIRViewer functionality still works.
* Existing configuration files remain compatible.
* Existing UI functionality remains intact.

If an interface must change, prefer:

* default methods
* adapter classes
* versioned interfaces
* backward-compatible abstractions

rather than forcing every existing plugin to be rewritten.

---

# Phase 30 — Security Review

Inspect:

* credential storage
* token handling
* HTTPS validation
* certificate validation
* authorization headers
* logging
* sensitive resource logging
* URL construction
* user-controlled query parameters
* custom endpoint handling

Do not disable TLS certificate validation as a shortcut.

Do not log authentication credentials or bearer tokens.

---

# Expected Final Architecture

The resulting architecture should conceptually look like:

```text
                         FHIRViewer
                             |
                       Viewer UI
                             |
                    Application Service
                             |
                       Plugin API
                             |
              +--------------+--------------+
              |                             |
       Standard FHIR API              Custom Operations
              |                             |
              |                    +--------+--------+
              |                    |                 |
              v                    v                 v
       Generic FHIR           Smile Plugin     Firely Plugin
       Server Plugin                |                 |
              |                     v                 v
              +-------------- REST Client ------------+
                             |
                    Authentication
                             |
                       HTTP Transport
                             |
                 FHIR / Vendor Server
```

The critical architectural rule is:

> **The Viewer UI talks to the Plugin API, never directly to vendor-specific REST endpoints.**

The plugin describes what operations are available, the Plugin API executes them, and the UI presents the results.

This allows future plugins such as:

```text
Smile CDR
Firely Server
HAPI FHIR
Azure Health Data Services
Google Cloud Healthcare API
AWS HealthLake
Custom/private FHIR server
```

to expose their capabilities to the Viewer without requiring vendor-specific changes to the core UI.

---

# Implementation Order

Cline should implement the work in this order:

1. Inspect current Plugin API
2. Inspect current UI/plugin interaction
3. Document current architecture
4. Identify reusable HTTP/authentication code
5. Design REST abstraction
6. Implement common REST transport
7. Implement authentication integration
8. Implement standard FHIR operations
9. Implement response/error handling
10. Implement server capabilities
11. Implement server status
12. Implement custom plugin operations
13. **Implement UI operation discovery**
14. **Implement UI operation execution**
15. **Implement UI parameter/input handling**
16. **Implement UI result handling**
17. **Integrate standard resource operations into the Viewer**
18. **Integrate FHIR search/pagination into the Viewer**
19. Update generic FHIR plugin
20. Update Smile plugin
21. Update Firely plugin
22. Add asynchronous UI handling
23. Add plugin developer documentation
24. Add unit/integration/UI tests
25. Perform backward-compatibility review
26. Perform security review

## Important Cline Instructions

Do not start by creating a large number of new classes.

First inspect the existing code and determine what can be reused.

Do not duplicate:

* HTTP clients
* authentication systems
* FHIR parsers
* server configuration
* plugin registration
* logging
* JSON/XML editors
* JavaFX task/asynchronous infrastructure

Do not put Smile CDR or Firely-specific logic into FHIRViewer core.

Do not assume that a vendor's REST API is the same as its FHIR REST API.

Keep standard FHIR operations separate from vendor-specific operations.

**The UI must communicate through the Plugin API. It must never contain vendor-specific REST URLs or vendor-specific branching.**

Before implementing any vendor-specific endpoint, verify it against the appropriate vendor documentation.

At each significant stage:

1. Compile the project.
2. Run the existing tests.
3. Fix regressions before continuing.
4. Keep changes reasonably isolated and reviewable.

The final result should provide a clean foundation for future FHIR server plugins and make the plugin's REST capabilities directly usable from the FHIRViewer UI.
