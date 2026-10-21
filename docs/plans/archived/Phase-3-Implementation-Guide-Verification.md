# FHIR Viewer — Phase 3 Implementation Plan
## Implementation Guide Verification

### Objective
Allow users to verify a FHIR resource against an Implementation Guide/profile.

### Workflow

```text
Load Resource
      ↓
Select IG/Profile
      ↓
Validate
      ↓
Validation Results
```

### Validation Results
Identify:
- Profile validation errors.
- Required elements.
- Cardinality violations.
- Type violations.
- Binding/value-set problems.
- Invariant violations.
- Unsupported elements where applicable.
- Warnings.
- Informational messages.

Where practical, allow selecting a validation result to locate the corresponding resource node.

### UI
Provide an IG/profile selector and validation action.

Example:

```text
Implementation Guide
[ Select Profile ▼ ]

[ Validate ]

Results
────────────────────────────
ERROR   Patient.name required
WARNING ...
VALID   ...
```

### Architecture
Keep validation logic outside JavaFX controllers.

Suggested:

```text
IGValidationService
    ├── loadImplementationGuide(...)
    ├── loadProfiles(...)
    ├── validate(...)
    └── getValidationResults(...)
```

The interface should remain generic enough to support different IGs and validation implementations.

### Implementation Guide Support
Do not hard-code a single IG.

The design should allow:
- US Core.
- Da Vinci IGs.
- Other published IGs.
- Custom/local IGs.

### Testing
Test:
- Valid resource/profile.
- Missing required element.
- Invalid cardinality.
- Invalid datatype.
- Invalid binding.
- Multiple validation errors.
- Warning handling.
- Profile selection.
- Validation result display.

### Completion Criteria
A user can select an Implementation Guide/profile and validate the current resource with understandable validation results displayed in the UI.

### Cline Execution Rules
- Inspect existing HAPI FHIR dependencies and validation capabilities first.
- Use existing project architecture where possible.
- Do not implement server plugins.
- Keep validation independent of the JavaFX UI.
- Add automated tests.
- Run `mvn test` and report results.
- Do not begin Phase 4 automatically.
