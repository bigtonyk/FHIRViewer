# Master Considerations — FHIRViewer Scripting and Automation

## Goals and use cases
Use Java 21 JShell to support interactive troubleshooting, repeatable tests, batch validation/analysis/transformation, scripted FHIR workflows, and eventually plugin-specific operations.

## Inspect and reuse existing services first
Before changing code, inspect the current JShell/scripting implementation, REST Workbench request execution and authentication, FHIR context/client services, validation and IG support, SMART on FHIR, plugin abstractions (including SmileCDR and Firely), logging, JavaFX threading, packaging, and tests. Expose narrow facades around existing services instead of duplicating them.

## REST Workbench boundary
The REST Workbench remains the manual REST interface. Scripting adds reusable `.jsh` files, multi-step workflows, parameters, assertions, repeatable runs, batch operations, and programmatic access. Both features should use shared request execution where practical. Do not rebuild the request editor in the scripting console.

## Runtime and UI
- Target Java 21 and `jdk.jshell`; check packaging and supported operating systems.
- Keep long-running operations off the JavaFX Application Thread.
- Scripts should call application services, not manipulate JavaFX controls directly.
- Define interactive-session state behavior. Prefer a fresh JShell context for each saved-script run unless a user explicitly chooses session-bound execution.
- JShell is trusted-code execution, not a security sandbox. Never advertise arbitrary scripts as safe for untrusted execution.

## Proposed public script API
Names are conceptual; inspect the code and choose names consistent with its architecture.
- `fhir`: use existing FHIR context, parsing, encoding, and resource model.
- `server`: inspect configured/active server; search/read and later supported writes through shared client services.
- `validation`: invoke the existing validation engine and loaded IG/profile configuration.
- `log`: structured script messages.
- `viewer`: publish results through supported application APIs, never direct JavaFX controls.
- `smart`: expose authorized launch context, not raw token values.
- `plugins`: later-phase capabilities advertised by installed plugins.
- `rest`: only if needed as a thin facade over shared Workbench request services, not a second HTTP stack.

Keep APIs typed, small, documented, testable, and versionable. Document method signatures, argument/return types, examples, and limitations.

## Safety and privacy
- Do not expose or log access/refresh tokens, client secrets, passwords, or authorization headers.
- Make create/update effects clear. Require centralized confirmation for DELETE, broad/destructive batch jobs, transaction bundles with writes, and mutating plugin operations.
- Confirm target server, operation, resource type/ID, and scope/count when known.
- Respect server authorization and SMART scopes; confirmation does not replace authorization.
- Avoid logging patient payloads by default. Treat imported scripts as executable trusted code requiring review.

## FHIR/interoperability
Respect configured FHIR release, server base URL, authentication, pagination, versioning, resource IDs/references, CapabilityStatement limits, OperationOutcome, non-2xx responses, and server-specific support for transactions/conditional operations/custom endpoints. Distinguish local validation findings from server responses. Validation failures should remain reportable without unnecessarily preventing local inspection or saving.

## SMART on FHIR
Reuse the existing SMART implementation. Clearly distinguish patient/encounter/user launch context from server selection. Handle missing context, expired authorization, and insufficient scopes. Never promise access beyond granted scopes or expose raw tokens by default.

## Testing and phase completion
Every phase should include unit tests, failure-path tests, REST Workbench regression checks, Java 21 build/package verification, and isolated integration tests where practical. Never run destructive tests against production/clinical servers. At each phase end, record code changes, tests and results, manual checks, limitations, status, and go/no-go recommendation in `IMPLEMENTATION_STATUS.md`.

## Documentation
Keep docs aligned with actual implementation: setup, interactive vs saved execution, API reference, examples, server selection, parameters, write confirmations, SMART restrictions, plugin extensions, troubleshooting, Java version, and known limitations.
