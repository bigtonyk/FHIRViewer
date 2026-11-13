# FHIR Viewer — Phase 1 Implementation Plan
## Add/Delete Nodes and FHIRPath Entries

### Objective
Allow users to modify the resource structure through the existing FHIR tree/FHIRPath UI.

### Features
1. Add FHIR node
   - Add a child element to the selected FHIR node.
   - Determine valid child elements from the FHIR resource definition.
   - Support primitive and complex elements.
   - Support repeating elements.
   - Prompt for an initial value when appropriate.

2. Delete FHIR node
   - Delete the selected node.
   - Handle primitive, complex, repeating, and nested elements.
   - Require confirmation where appropriate.

3. FHIRPath entries
   - Add/delete entries from the existing FHIRPath display/list.
   - Recalculate expressions against the current resource.
   - Distinguish valid, invalid, and empty-result expressions.

4. Resource synchronization
   - Tree changes update the underlying HAPI FHIR resource.
   - Pretty View reflects changes.
   - JSON/XML representations reflect changes.

### Architecture
Create or reuse a resource editing service rather than putting editing logic directly in JavaFX controllers.

Suggested responsibilities:

```text
ResourceEditService
 ├── addNode(...)
 ├── deleteNode(...)
 ├── setNodeValue(...)
 └── updateFHIRPathEntries(...)
```

### Testing
Test:
- Add primitive element.
- Add complex element.
- Add repeating element.
- Delete primitive element.
- Delete complex element.
- Delete repeating element.
- Invalid add operation.
- FHIRPath recalculation.
- Resource serialization after modifications.
- Pretty View synchronization.

### Completion Criteria
A user can load a FHIR resource, add/delete nodes, modify FHIRPath entries, and see the resulting resource consistently represented throughout the application.

### Cline Execution Rules
- Inspect the existing code before changing anything.
- Do not implement later phases.
- Preserve existing functionality.
- Follow existing naming/style conventions.
- Keep JavaFX UI logic separate from resource-editing logic.
- Use HAPI FHIR APIs where practical.
- Add tests for new behavior.
- Run `mvn test` and report results.
- Do not proceed to another phase automatically.
