# FHIR Viewer — Phase 4 Implementation Plan
## Firely Server Plugin

### Objective
Implement the first FHIR server plugin using Firely Server.

This phase should prove that the plugin architecture works while providing useful Firely Server support.

### Plugin Boundary

```text
FHIR Viewer Core
       │
       │ Plugin API
       ▼
Firely Server Plugin
       │
       ▼
Firely Server
```

The core application must not contain Firely-specific behavior.

### Initial Capabilities
Support:
- Server configuration.
- Connect/test connection.
- Search.
- Resource retrieval/read.
- Resource type selection.
- FHIR version identification where practical.
- Authentication configuration where required.

Example:

```text
FHIR Servers
 └── Firely Server
      ├── Connect
      ├── Search
      └── Read
```

### Plugin API
Define a generic interface before implementing Firely-specific behavior.

Conceptually:

```java
public interface FhirServerPlugin {

    String getId();

    String getName();

    boolean supports(String capability);

    ServerConnection connect(ServerConfiguration configuration);

    SearchResult search(SearchRequest request);

    Resource read(ResourceReference reference);
}
```

Adapt the API to the existing application architecture rather than blindly copying this example.

### Firely Implementation
All Firely-specific:
- URLs.
- API behavior.
- Authentication.
- Search behavior.
- Server quirks.

must remain inside the Firely plugin.

### Testing
Test:
- Plugin discovery.
- Plugin initialization.
- Server connection.
- Search.
- Read.
- Error handling.
- Invalid URL.
- Authentication failure.
- Server unavailable.
- Resource conversion.

### Completion Criteria
The FHIR Viewer can connect to a Firely Server through the plugin mechanism and retrieve resources without Firely-specific code in the core application.

### Cline Execution Rules
- Inspect the existing application architecture before introducing plugin infrastructure.
- Keep plugin API generic.
- Do not implement the Standard FHIR plugin yet.
- Do not add Firely-specific conditionals to core code.
- Add automated tests.
- Run `mvn test`.
- Document architectural decisions in the implementation summary.
- Stop when Phase 4 is complete.
