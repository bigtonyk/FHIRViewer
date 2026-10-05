# FHIRViewer Phase 3 — Incremental IG Validation Updates

## Purpose

Phase 3 is already partially implemented, including the Implementation Guide/package UI.

**Do not rebuild the existing Phase 3 UI.**

This update extends the existing Phase 3 implementation to provide real FHIR package discovery, installation, dependency resolution, profile resolution, and validation.

Before making changes, inspect the existing Phase 3 implementation and reuse its existing models, services, UI components, and naming conventions wherever practical.

---

# Update 1 — Audit Existing Phase 3

First inspect the existing Phase 3 implementation.

Determine:

- Existing package/IG models
- Existing package search UI
- Existing installed-package UI
- Existing package storage
- Existing validator
- Existing validation results model
- Existing profile handling
- Existing package download code
- Existing FHIR parser/validator dependencies
- Existing configuration/preferences
- Existing tests

Do not duplicate functionality that already exists.

Create a short implementation assessment before modifying code.

Identify each required feature as:

```text
Already implemented
Partially implemented
Missing
Needs modification
```

Then proceed with the updates below.

---

# Update 2 — Use FHIR Package IDs

The package system must use official FHIR package IDs as the primary identifiers.

Examples:

```text
hl7.fhir.r4.core
hl7.fhir.us.core
hl7.fhir.uv.smart-app-launch
hl7.fhir.us.davinci-pdex
hl7.fhir.us.davinci-pas
hl7.fhir.us.davinci-dtr
hl7.fhir.us.davinci-crd
hl7.fhir.us.davinci-cdex
hl7.fhir.us.davinci-hrex
hl7.fhir.us.carin-bb
hl7.fhir.us.ecr
```

Do not use the human-readable IG name as the package identifier.

The UI may display:

```text
US Core
```

but internally use:

```text
hl7.fhir.us.core
```

---

# Update 3 — Package Registry Search

Update the existing package search implementation so it can find packages using:

- Package ID
- IG name
- Package name
- Description

For example:

```text
us core
```

must find:

```text
hl7.fhir.us.core
```

and:

```text
hl7.fhir.us.davinci-pdex
```

must be directly searchable by package ID.

The package registry should be accessed through a service abstraction rather than directly from the UI.

Suggested interface:

```text
PackageRegistryService

searchPackages(query)
getPackage(packageId, version)
getVersions(packageId)
downloadPackage(packageId, version)
```

Reuse an existing service if Phase 3 already has one.

---

# Update 4 — Package Version Handling

Package selection must include the package version.

Display:

```text
Package:
US Core

ID:
hl7.fhir.us.core

Version:
9.0.0

FHIR Version:
R4
```

Do not assume the latest version is always appropriate.

The registry should provide available versions.

The application should retain the exact installed version.

---

# Update 5 — Dependency Resolution

Add dependency resolution to the existing installation process.

When the user installs:

```text
hl7.fhir.us.core
```

the application must inspect its package metadata and determine required dependencies.

Automatically identify missing dependencies such as:

```text
hl7.fhir.r4.core
hl7.terminology.r4
hl7.fhir.uv.extensions.r4
```

Do not require the user to manually search for each dependency.

Before installation, show an installation summary:

```text
Install:

hl7.fhir.us.core
9.0.0

Dependencies:
- hl7.fhir.r4.core
- hl7.terminology.r4
- hl7.fhir.uv.extensions.r4
```

Reuse the existing package UI.

---

# Update 6 — Package Storage

Verify that installed packages are stored separately from application code.

Use a structure similar to:

```text
FHIRViewer/
    packages/
        hl7.fhir.r4.core/
            4.0.1/
        hl7.fhir.us.core/
            9.0.0/
```

The exact location can follow the existing Phase 3 implementation.

Do not change the location unnecessarily if the existing implementation already has a suitable package directory.

---

# Update 7 — Installed vs Active Packages

If the current UI supports only installed packages, add the concept of:

```text
Installed
Active for Validation
```

A package can be installed without being active.

Example:

```text
Installed:
    US Core
    PDex
    PAS

Active:
    US Core
    PDex
```

Only active packages should participate in automatic profile validation.

If this distinction is already implemented, verify that it works correctly rather than duplicating it.

---

# Update 8 — Package Resource Index

After installing a package, index its FHIR conformance resources.

At minimum support:

```text
StructureDefinition
ValueSet
CodeSystem
ConceptMap
ImplementationGuide
CapabilityStatement
SearchParameter
```

Create a canonical URL lookup mechanism.

For example:

```text
http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient
```

must resolve to the corresponding StructureDefinition from the installed US Core package.

The validator should not need to know where the package was downloaded from.

---

# Update 9 — Profile Resolution

Update the validator to resolve profiles from installed packages.

For a resource containing:

```json
{
  "meta": {
    "profile": [
      "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
    ]
  }
}
```

FHIRViewer must:

1. Read `meta.profile`.
2. Resolve the canonical URL.
3. Find the corresponding StructureDefinition.
4. Determine its package.
5. Validate against that profile.

If the profile cannot be found, report:

```text
Profile not available

The resource references:

http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient

The required Implementation Guide package is not installed.
```

Do not silently ignore the profile.

---

# Update 10 — Base R4 Validation

Every resource should first be validated against the base FHIR R4 definition.

Example:

```text
Patient
Observation
Condition
Encounter
```

The validator must be able to distinguish:

```text
FHIR R4 validation
```

from:

```text
Implementation Guide profile validation
```

Results should indicate which level generated the issue.

---

# Update 11 — Profile Validation Without meta.profile

A resource does not have to contain `meta.profile`.

If:

```json
{
  "resourceType": "Patient"
}
```

has no `meta.profile`, FHIRViewer must still allow the user to validate it against an installed profile.

Example:

```text
Validate Against:

FHIR R4 Patient

US Core Patient
```

The user can select:

```text
US Core Patient
```

and run validation.

Do not require users to modify the resource simply to specify a profile.

---

# Update 12 — Manual Profile Selection

Add or complete profile selection using installed packages.

The user should be able to select from compatible profiles for the current resource type.

For a Patient, for example:

```text
FHIR R4 Patient
US Core Patient
Other installed Patient profiles
```

Only profiles applicable to the current resource type should be displayed.

---

# Update 13 — Validation Results

Improve the existing validation results UI as necessary.

Each result should provide:

```text
Severity
Message
Resource Path
Profile
```

Severity:

```text
ERROR
WARNING
INFORMATION
```

Example:

```text
ERROR
Patient.identifier
Required element is missing.

Profile:
US Core Patient 9.0.0
```

Where possible, selecting a validation error should locate the corresponding node in the resource tree.

---

# Update 14 — Save Must Not Be Blocked

This requirement is mandatory.

Validation errors must never make the resource unsaveable.

Workflow:

```text
Edit
  ↓
Validate
  ↓
Errors found
  ↓
User chooses Save
  ↓
Resource is saved
```

The application may warn the user:

```text
This resource contains validation errors.
Save anyway?
```

but must provide a way to save.

Add a regression test for this behavior.

---

# Update 15 — Validation After JSON/XML Editing

Ensure Phase 3 integrates correctly with the Phase 2 editing functionality.

After JSON/XML is edited:

```text
JSON/XML editor
      ↓
Parse resource
      ↓
Refresh resource tree
      ↓
Refresh pretty view
      ↓
Validate current resource
```

The validator must always validate the **current edited resource**, not an earlier cached version.

If the edited JSON/XML is syntactically invalid:

- Show the parse error.
- Do not run FHIR validation.
- Do not destroy the last valid resource state.

---

# Update 16 — Package/FHIR Version Compatibility

FHIRViewer is currently targeting R4.

Package metadata must identify the FHIR version.

Do not load an incompatible R5-only package into the R4 validation context.

The architecture should allow future support for:

```text
R4
R4B
R5
```

but this update only needs to implement R4.

---

# Update 17 — Initial Package Catalog

Add these packages to the initial available-package catalog/search examples.

## Required

```text
hl7.fhir.r4.core
hl7.fhir.us.core
```

## Optional

```text
hl7.fhir.uv.smart-app-launch

hl7.fhir.us.davinci-pdex
hl7.fhir.us.davinci-pas
hl7.fhir.us.davinci-dtr
hl7.fhir.us.davinci-crd
hl7.fhir.us.davinci-cdex
hl7.fhir.us.davinci-hrex

hl7.fhir.us.carin-bb
hl7.fhir.us.ecr
```

Do not hard-code package versions as permanent values.

The package registry remains authoritative for available versions.

---

# Update 18 — Error Handling

Handle package and validation failures cleanly.

Support:

- Registry unavailable
- Network failure
- Package not found
- Version not found
- Download failure
- Corrupt package
- Missing dependency
- Dependency conflict
- Unsupported FHIR version
- Profile not found
- Validator failure

Display useful user-facing messages rather than raw exceptions.

---

# Update 19 — Package Cache

Do not download the same package repeatedly.

If a requested package/version is already installed:

```text
hl7.fhir.us.core
9.0.0
```

reuse the local package.

Provide a manual:

```text
Refresh Package Catalog
```

operation for registry metadata.

---

# Update 20 — Testing

Add/update automated tests for:

### Package discovery

```text
Search "US Core"
→ finds hl7.fhir.us.core
```

### Package installation

```text
Install US Core
→ package installed
```

### Dependency resolution

```text
Install US Core
→ required dependencies detected
```

### Profile lookup

```text
US Core Patient canonical URL
→ StructureDefinition resolved
```

### meta.profile validation

```text
Patient with US Core profile
→ validates against US Core Patient
```

### Manual profile validation

```text
Patient without meta.profile
→ user selects US Core Patient
→ validates against US Core Patient
```

### Missing package

```text
Patient references an unavailable profile
→ clear "profile not installed" message
```

### Save behavior

```text
Invalid resource
→ validation errors
→ resource can still be saved
```

### Editing

```text
Edit JSON
→ tree refresh
→ pretty view refresh
→ validator uses edited resource
```

---

# Update 21 — Use Existing Sample Resources

Use the existing sample resources for regression testing:

```text
Patient
Practitioner
Organization
Encounter
Observation
Condition
MedicationRequest
DiagnosticReport
AllergyIntolerance
CarePlan
```

At least the following should be tested against US Core where applicable:

```text
Patient
Practitioner
Organization
Encounter
Observation
Condition
AllergyIntolerance
MedicationRequest
DiagnosticReport
CarePlan
```

Do not modify the original test resources merely to make them pass validation.

The tests should include both valid and intentionally invalid examples.

---

# Update 22 — Do Not Rebuild Existing Phase 3 UI

This is an explicit constraint.

Before implementing anything:

- Inspect existing Phase 3 UI.
- Preserve existing functionality.
- Extend existing components.
- Reuse existing services/models.
- Only modify UI where required for the new functionality.

Do not create a second package manager or second validation screen.

---

# Update 23 — Architecture

The desired separation is:

```text
FHIRViewer UI
     |
     +----------------------+
     |                      |
Package Manager       Validation Service
     |                      |
Package Registry       Profile Resolver
     |                      |
Dependency Resolver    Package Resource Index
     |                      |
     +----------+-----------+
                |
          Local Packages
```

The UI should not directly download packages.

The validator should not directly communicate with the package registry.

The validator should request resources through the package/profile resolver.

---

# Implementation Order

Implement the updates in this order:

```text
1. Audit existing Phase 3
2. Package ID/version handling
3. Registry search
4. Dependency resolution
5. Package installation/storage
6. Package resource indexing
7. Profile resolution
8. Base R4 + IG validation
9. meta.profile handling
10. Manual profile selection
11. Validation results
12. Save despite errors
13. JSON/XML editing integration
14. Error handling/caching
15. Automated tests
16. Full end-to-end testing
```

Each step should be independently testable.

After each step:

1. Compile.
2. Run relevant tests.
3. Fix regressions.
4. Do not proceed if the previous step has broken existing Phase 3 functionality.

---

# Definition of Done

Phase 3 updates are complete when FHIRViewer can:

1. Search the FHIR package registry.
2. Find `hl7.fhir.us.core`.
3. Display available versions.
4. Install a selected version.
5. Automatically resolve dependencies.
6. Store the package locally.
7. Index its StructureDefinitions.
8. Open a Patient resource.
9. Resolve `us-core-patient` from `meta.profile`.
10. Validate against the US Core Patient profile.
11. Validate a Patient without `meta.profile` against a manually selected US Core Patient profile.
12. Display useful validation errors/warnings.
13. Locate validation problems in the resource tree when possible.
14. Revalidate after JSON/XML editing.
15. Save a resource even when validation errors exist.

Most importantly:

**Do not replace the existing Phase 3 implementation. Extend it.**
