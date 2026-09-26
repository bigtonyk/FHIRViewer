# FHIR Viewer — Phase 2 Implementation Plan
## Edit JSON/XML In Place

### Objective
Allow the user to directly edit the JSON or XML representation of the current FHIR resource.

### Features
Add editable JSON/XML views alongside the existing Tree and Pretty views.

```text
Resource
 ├── Tree View
 ├── Pretty View
 ├── JSON Editor
 └── XML Editor
```

The user must be able to:
1. Edit JSON.
2. Edit XML.
3. Validate the edited representation.
4. Parse it into a HAPI FHIR resource.
5. Replace the current resource.
6. Refresh the tree, Pretty View, FHIRPath results, and serialized views.

### Apply Changes
Do not continuously parse while typing.

Use an explicit operation such as:

```text
Apply Changes
```

Flow:

```text
JSON/XML Editor
      ↓
Parse
      ↓
Validate
      ↓
HAPI FHIR Resource
      ↓
Application Resource State
      ↓
Refresh Views
```

### Error Handling
If parsing or validation fails:
- Do not replace the current resource.
- Display the error.
- Identify the approximate location when available.
- Leave the edited document intact.

### Resource Synchronization
All views must represent the same underlying resource.

```text
JSON/XML
   ↓
HAPI Resource
   ↓
Tree
Pretty View
FHIRPath
JSON
XML
```

### Architecture
Create or strengthen a central resource state/update mechanism.

Suggested:

```text
ResourceState
   │
   ├── current Resource
   ├── resource type
   └── change notification
          │
          ├── Tree
          ├── Pretty View
          ├── JSON
          ├── XML
          └── FHIRPath
```

### Testing
Test:
- Valid JSON modification.
- Valid XML modification.
- Invalid JSON.
- Invalid XML.
- Resource replacement.
- Tree refresh.
- Pretty View refresh.
- FHIRPath refresh.
- JSON/XML synchronization.
- Failed update leaves original resource unchanged.

### Completion Criteria
A user can edit JSON or XML, apply the changes, and have the entire FHIR Viewer update to the new resource without reopening it.

### Cline Execution Rules
- Inspect the existing architecture before implementation.
- Reuse Phase 1 resource-editing/state mechanisms where appropriate.
- Do not implement server plugins or IG verification.
- Preserve existing resource loading/display behavior.
- Keep parsing and synchronization logic outside JavaFX controllers.
- Add automated tests.
- Run `mvn test` and report results.
- Stop when this phase is complete.
