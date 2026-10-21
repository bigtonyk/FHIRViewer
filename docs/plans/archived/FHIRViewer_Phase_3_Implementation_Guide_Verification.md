# FHIRViewer — Phase 3 Implementation Guide Verification

## Objective

Add Implementation Guide (IG) verification to FHIRViewer so that users can validate FHIR resources against the profiles, terminology, extensions, and other conformance resources defined by an Implementation Guide.

Validation must be integrated into the existing FHIRViewer architecture without making validation a prerequisite for saving a resource.

---

## 3.1 Implementation Guide Selection

Implement the ability to select an Implementation Guide/package for validation.

Requirements:

- Allow the user to select an installed/available FHIR NPM package.
- Display the package name and version.
- Display the FHIR version supported by the package.
- Allow multiple IG packages to be loaded when required by dependencies.
- Clearly identify the currently active validation package(s).
- Provide a mechanism to remove/unload an IG package from the validation environment.

---

## 3.2 FHIR NPM Package Loading

Implement support for loading FHIR Implementation Guide packages distributed as FHIR NPM packages.

Requirements:

- Load package metadata.
- Load package resources.
- Support `StructureDefinition`.
- Support `ValueSet`.
- Support `CodeSystem`.
- Support `ImplementationGuide`.
- Support other conformance resources required by HAPI validation.
- Validate that the package is compatible with the resource's FHIR version.
- Handle package loading errors gracefully.
- Do not require users to manually copy individual StructureDefinition files into FHIRViewer.

The package-loading implementation should use HAPI FHIR's validation-support mechanisms rather than creating a separate custom validation system.

---

## 3.3 IG Package Dependencies

When an IG depends on other packages, those dependencies must also be available to the validator.

For example:

```text
US Core
   │
   ├── FHIR R4 Core
   └── dependent packages
```

Requirements:

- Read package dependency information from package metadata.
- Load required dependencies.
- Avoid loading duplicate package versions unnecessarily.
- Detect incompatible dependency versions.
- Report missing dependencies clearly.
- Ensure dependency resources are available through the same validation-support chain.

---

## 3.4 IG Package Loading & Profile Resolution

Ensure that resources referenced by canonical URLs can actually be resolved by the validator.

This is specifically required to address messages such as:

```text
Patient.Meta.Profile[0]

http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient

Profile reference has not been checked because it could not be found.
```

When US Core is loaded, FHIRViewer must be able to resolve:

```text
http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient
```

to the corresponding `StructureDefinition`.

Requirements:

- Integrate loaded IG resources with HAPI FHIR validation support.
- Resolve canonical `StructureDefinition` URLs.
- Resolve profiles referenced by `meta.profile`.
- Resolve profiles referenced by other conformance resources.
- Resolve `ValueSet` references.
- Resolve `CodeSystem` references.
- Resolve extensions where required.
- Support profiles supplied by dependencies.
- Distinguish between:
  - Profile successfully resolved
  - Profile exists but validation failed
  - Profile could not be resolved
- Do not report an unresolved profile as if the resource itself failed validation.
- Provide a clear diagnostic when a referenced profile cannot be found.

### Example

Given:

```json
{
  "resourceType": "Patient",
  "meta": {
    "profile": [
      "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
    ]
  }
}
```

and US Core is loaded, the validator should resolve the canonical URL and validate the Patient against the US Core Patient profile.

---

## 3.5 HAPI Validation Integration

Integrate the loaded IG packages with the existing HAPI FHIR validation infrastructure.

Requirements:

- Use HAPI FHIR's validation APIs.
- Configure an appropriate validation-support chain.
- Ensure FHIR R4 core definitions remain available.
- Add loaded IG packages to the validation support chain.
- Ensure package resources are available to `FhirInstanceValidator`.
- Do not duplicate the existing FHIRViewer resource parsing/model layer.
- Keep validation infrastructure separate from the editor/UI layer.

The architecture should be approximately:

```text
FHIRViewer
    │
    ├── Resource Editor
    │
    ├── Resource Tree
    │
    ├── Pretty View
    │
    └── Validation
          │
          ▼
    FhirInstanceValidator
          │
          ▼
    Validation Support Chain
          │
          ├── FHIR R4 Core
          ├── Loaded IG
          ├── IG Dependencies
          └── Local/Custom Resources
```

---

## 3.6 Validation Results UI

Add a validation results view/panel.

Display:

- Errors
- Warnings
- Information messages
- Profile resolution warnings
- Location/path of the problem
- Severity
- Diagnostic message
- Referenced profile, when applicable

For example:

```text
Validation Results
─────────────────────────────────────

✗ ERROR
Patient.name[0].family
Required element is missing.

⚠ WARNING
Patient.telecom[0]
Recommended element is missing.

⚠ PROFILE
US Core Patient
Profile could not be resolved.
```

The UI should allow the user to navigate from a validation issue to the relevant resource/path where practical.

---

## 3.7 Non-Blocking Validation

**Validation must never prevent a resource from being saved.**

Validation is advisory and diagnostic, not a persistence gate.

The save workflow should be:

```text
Edit Resource
     │
     ▼
Validate
     │
     ├── Valid ───────────────► Save
     │
     ├── Warnings ────────────► Save
     │
     └── Errors ──────────────► Save
                                  │
                                  ▼
                         Display validation
                              results
```

Do NOT implement:

```java
if (!validationResult.isValid()) {
    return;
}
```

Instead, validation should operate independently from persistence:

```java
ValidationResult result =
    validator.validateWithResult(resource);

displayValidationResults(result);

saveResource(resource);
```

Requirements:

- The Save operation must remain available when validation errors exist.
- Validation errors must not block persistence.
- Validation warnings must not block persistence.
- Unresolved profiles must not block persistence.
- Save the exact resource currently being edited.
- Do not silently modify or "fix" the resource because validation failed.
- Preserve validation results for the current resource version.
- Clearly indicate when a resource has not yet been validated.

---

## 3.8 Validation State and Resource Editing

Validation results should correspond to the current version of the resource.

When the resource changes:

```text
Resource modified
       │
       ▼
Previous validation results
       │
       └──► Mark as stale / not validated
```

The UI should indicate:

```text
Status: Modified
Validation: Not validated
```

After validation:

```text
Status: Modified
Validation: 2 errors, 1 warning
```

After saving:

```text
Status: Saved
Validation: 2 errors, 1 warning
```

If the resource is subsequently edited, the previous validation result should no longer be presented as validation of the new resource state.

---

## 3.9 Validation Diagnostics vs. Validation Failures

Clearly distinguish between different types of problems.

### Validation failure

The profile was found and the resource does not conform:

```text
✗ US Core Patient
Profile resolved.
Resource does not conform to the profile.
```

### Profile resolution problem

The resource references a profile that could not be found:

```text
⚠ US Core Patient
Profile could not be resolved.
Resource was not validated against this profile.
```

### Successful validation

```text
✓ US Core Patient
Profile resolved.
Resource conforms to the profile.
```

This distinction is important so that users don't interpret:

```text
Profile reference has not been checked because it could not be found
```

as meaning that the Patient itself is invalid.

---

## 3.10 Multiple IG Support

Allow FHIRViewer to have more than one package available to the validator.

Requirements:

- Support an IG and its dependencies simultaneously.
- Avoid conflicts between resources with the same canonical URL.
- Handle package/version conflicts explicitly.
- Display which package supplied a resolved profile.
- Use deterministic package/resource resolution.
- Provide useful diagnostics when two packages provide conflicting versions of a resource.

---

## 3.11 Automated Tests

Add tests covering:

### Package loading

- Load a valid FHIR NPM package.
- Load package resources.
- Load package dependencies.
- Reject incompatible FHIR versions.
- Handle missing dependencies.

### Profile resolution

- Resolve a known StructureDefinition.
- Resolve a profile referenced by `meta.profile`.
- Resolve a profile supplied by an IG dependency.
- Report an unresolved profile correctly.

### US Core

At minimum, test:

```text
http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient
```

and verify that a Patient referencing that profile can be resolved and validated when the appropriate US Core package is loaded.

### Validation

Test:

- Valid resource.
- Resource with validation warnings.
- Resource with validation errors.
- Resource with an unresolved profile.
- Resource with multiple profiles.

### Non-blocking save

Explicitly verify that all of the following can be saved:

```text
Valid resource              → Save succeeds
Warning-producing resource  → Save succeeds
Error-producing resource    → Save succeeds
Unresolved-profile resource → Save succeeds
```

The saved resource must exactly match the resource being edited.

---

## 3.12 Error Handling

Package and validation errors must not crash FHIRViewer.

Handle:

- Invalid package
- Missing package
- Corrupt package
- Missing dependency
- Unsupported FHIR version
- Unresolved profile
- Invalid StructureDefinition
- Validation engine errors
- Resource parsing errors

Parsing errors may prevent saving if the resource cannot be represented as a valid FHIR resource at all. This is separate from profile/validation failures.

---

## 3.13 Architecture Requirements

Keep the following concerns separated:

```text
Package Management
       │
       ▼
Validation Support
       │
       ▼
FHIR Validator
       │
       ▼
Validation Results
       │
       ▼
UI
```

Saving should remain independent:

```text
Resource Editor
       │
       ├──────────────► Validator
       │                    │
       │                    ▼
       │              Validation Results
       │
       └──────────────► Resource Storage
```

The validator must never become a dependency of the save operation.

---

## 3.14 Acceptance Criteria

Phase 3 is complete when:

1. FHIRViewer can load a FHIR NPM Implementation Guide package.
2. Package dependencies are loaded as required.
3. HAPI validation can access resources from the loaded packages.
4. `meta.profile` canonical URLs can be resolved.
5. US Core Patient can be resolved when the appropriate US Core package is loaded.
6. Resources can be validated against IG profiles.
7. Validation results are displayed clearly.
8. Profile-resolution failures are distinguished from actual validation failures.
9. Validation errors and warnings do not prevent saving.
10. An invalid resource can be saved unchanged.
11. Validation state becomes stale when the resource is edited.
12. Automated tests verify package loading, profile resolution, validation, and non-blocking save behavior.
13. Existing FHIRViewer functionality continues to work without an IG loaded.
14. The implementation remains extensible for future IGs and server-specific validation requirements.
