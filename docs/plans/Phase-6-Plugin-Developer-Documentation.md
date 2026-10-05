# FHIR Viewer — Phase 6 Implementation Plan
## Plugin Developer Documentation

### Objective
Document how another developer can create a FHIR Viewer server plugin.

Documentation must describe the actual plugin API produced by Phases 4 and 5, not a hypothetical API.

### Documentation Location

Create:

```text
docs/
 └── plugin-development.md
```

Adapt the location to the existing repository documentation structure if one already exists.

### Documentation Contents

#### 1. Plugin Architecture

Explain:

```text
FHIR Viewer
    │
    ├── Core
    │
    ├── Plugin API
    │
    └── Plugins
          ├── Standard FHIR
          ├── Firely
          └── Third Party
```

#### 2. Creating a Plugin

Provide step-by-step instructions:

```text
1. Create plugin project/module.
2. Add FHIR Viewer plugin dependency.
3. Implement plugin interface.
4. Implement connection.
5. Implement search.
6. Implement read.
7. Register plugin.
8. Build plugin.
9. Install plugin.
10. Test plugin.
```

Use the actual process implemented by the project.

#### 3. Plugin API Reference
Document every public interface/class required by plugin developers.

#### 4. Server-Specific Behavior
Explain where server-specific behavior belongs.

Good:

```text
FirelyPlugin.java
```

Bad:

```text
if (serverType == FIREFLY) ...
```

inside core application code.

#### 5. Authentication
Document how plugins implement authentication and how credentials/configuration are handled.

#### 6. Error Handling
Document expected exceptions, error results, OperationOutcome handling, and user-facing error behavior.

#### 7. Testing
Explain how to test plugins against:
- Mock servers.
- Local servers.
- Remote servers.

#### 8. Example Plugin
Use the Standard FHIR plugin as the primary reference implementation.

Use the Firely plugin to demonstrate server-specific behavior.

### Completion Criteria
A developer unfamiliar with the project can read the documentation and create a working FHIR Viewer server plugin without reverse-engineering the source code.

### Cline Execution Rules
- Inspect the final plugin architecture before writing documentation.
- Document the actual public API and workflows.
- Include real examples from the completed plugins.
- Do not redesign the plugin architecture as part of this phase unless a concrete documentation gap exposes a defect.
- Ensure code examples compile or accurately reflect the current API.
- Update repository documentation indexes/README if appropriate.
- Stop when the documentation is complete.
