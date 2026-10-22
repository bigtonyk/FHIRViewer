# Master Considerations — FHIRViewer JShell Scripting

## 1. Objective and phases
Add optional Java scripting and automation to FHIRViewer using Java 21 JShell. Support both (a) an interactive console that retains state during a session and (b) saved scripts/workflows that can be rerun with explicit parameters and a defined execution context.

Implement in this order:
1. Basic scripting console and saved `.jsh` files.
2. FHIRViewer scripting API.
3. Automated testing and repeatable test suites.
4. SMART on FHIR integration.
5. Advanced scripting, parameters, batch processing, and plugin workflows.
6. User-facing scripting API documentation.

## 2. Mandatory technology constraints
- Target Java 21 and the repository's existing Maven/build conventions.
- Use JavaFX for UI and the public `jdk.jshell` API for embedded execution.
- Do not add JavaScript, Node.js, Electron, Python, or a second scripting engine.
- Reuse existing HAPI FHIR clients, resource parsing, validation, FHIRPath, server configuration, plugin architecture, SMART implementation, and logging where appropriate.
- Keep dependencies lean. Evaluate licensing, maintenance, and cross-platform packaging before adding an editor library.
- API names in this plan are conceptual. Inspect the repository and adapt them to existing patterns; do not invent duplicate service layers.

## 3. Repository-first process
Before coding, inspect Java/Maven versions, modules, packages, `module-info.java`, tests, JavaFX navigation/styles/threading, preferences/file dialogs, FHIR clients/services, validation, FHIRPath, server configuration, plugin interfaces, SMART implementation, and packaging (`jlink`, `jpackage`, or custom runtime). Record findings in `IMPLEMENTATION-STATUS.md`.

## 4. Execution model: interactive and saved workflows
- **Interactive console:** persistent JShell state until Reset Session; intended for exploration, troubleshooting, and one-off operations.
- **Saved workflow:** saved `.jsh` source, optional metadata/parameters, explicit target server/session, and a structured execution result. Each workflow run should use a fresh JShell session by default so previous console variables cannot affect results.
- Keep the interactive session separate from workflow/test execution. Never execute a saved script merely because it is opened, selected, imported, or loaded at startup.
- Support explicit Run, Stop/Cancel where feasible, Reset Session, Open, Save/Save As, Clear Output, and execution status.
- Use JShell source analysis to split complete snippets in the interactive editor. Do not treat JShell as a conventional `.java` file runner.
- Serialize calls within a JShell session. Use a dedicated worker and never block the JavaFX Application Thread.
- Cancellation is best-effort: arbitrary Java code may not be safely interruptible. Communicate this accurately.

## 5. Suggested architecture
- **Scripting UI:** editor, toolbar, console, diagnostics, run status, saved-script browser, parameters, and results.
- **Scripting coordinator:** owns JShell lifecycle, session isolation, execution queue, output capture, cancellation, and cleanup.
- **Script API facade:** deliberately exposed objects such as `fhir`, `viewer`, `validation`, `server`, `log`, and later `test` and `smart`.
- **Adapters:** connect facades to existing services without exposing JavaFX controllers, credential stores, or application internals.
- **Write policy service:** centralizes permission checks, confirmation prompts, operation classification, and audit-safe summaries.
- **Test runner/report model:** Phase 3.
- **SMART context provider:** Phase 4.
- **Workflow parameter and batch coordinator:** Phase 5.
- **API reference and examples:** Phase 6.

Do not let scripts manipulate JavaFX controls directly. UI requests go through facade methods and are dispatched safely to the JavaFX Application Thread. Network and potentially long-running operations run off the UI thread.

## 6. API design principles
Expose stable, documented application capabilities rather than raw controllers or internal service objects. The API should cover:
- `server`: selected server identity, base URL as safe metadata, capabilities, connection status, and supported operations.
- `fhir`: resource read/search/create/update/patch and other operations only where supported by existing services and server capabilities.
- `viewer`: show a resource or result set, inspect selected-resource context, and request supported UI actions.
- `validation`: invoke existing profile/IG validation and return structured issues.
- `log`: informational, warning, error, and progress events, with sensitive-data redaction.
- `smart`: only in Phase 4; status, authorized launch context, granted-scope metadata, and operations through the existing authorized client.
- `test`: only in Phase 3; assertions, named checks, and structured results.

Use actual HAPI FHIR types where they make the API clearer and stable. Avoid exposing raw HTTP clients, tokens, credential objects, JavaFX controllers, or mutable internal configuration objects. Document thread behavior, return types, exceptions, side effects, and availability conditions.

## 7. Write policy and destructive-operation confirmations
Writes are supported by the API, subject to existing authentication, authorization, server capability, and application policy.
- Read/search operations may run without a write confirmation.
- Create/update/patch operations must be explicit script calls and must return a structured outcome. Do not silently convert read-only intent into a write.
- **Destructive operations require a clear confirmation step immediately before execution.** At minimum this includes DELETE, bulk deletion, destructive batch actions, and transaction/batch bundles containing destructive entries. Show target server, operation type, resource type/ID or count, and a concise impact summary where known.
- A confirmation must not be bypassed by merely calling the same API through a different facade. Centralize checks in the write policy service.
- Never show a misleading confirmation after an operation has already run. If a workflow is unattended/headless and confirmation is required but unavailable, fail closed and report that explicit authorization is needed.
- Do not assume a FHIR transaction is safe: inspect bundle entries and classify side effects before submission. If impact cannot be determined, treat it as potentially destructive.
- Make test/batch workflows clearly identify their target server/environment. Never default to a production target for destructive examples.
- Log operation metadata and outcomes without logging tokens, credentials, or full patient resources by default.

## 8. Security and trust model
JShell executes Java code and is **not a security sandbox**. Code may read files, access the network, use reflection, inspect environment variables, or launch processes with the current user's permissions.
- Treat scripts as trusted code. Show a warning before first execution and when running scripts from untrusted sources.
- Never auto-run scripts on startup, file open, server connection, or SMART launch.
- Do not claim a facade, confirmation dialog, classpath restriction, or worker thread is a sandbox.
- Never expose SMART access/refresh tokens, client secrets, authorization headers, or credential-store objects.
- Redact secrets and avoid logging full sensitive FHIR resources by default.
- Do not bypass server authorization, OAuth scopes, validation policies, or selected server configuration.
- If untrusted community scripts become a requirement, design genuine process/OS-level isolation separately before implementing it.

## 9. FHIR and SMART behavior
- Reuse existing HAPI FHIR types and service abstractions; do not invent a duplicate resource model.
- Respect server capabilities, authentication, pagination, errors, timeouts, and rate limits.
- Distinguish logical IDs, server base URLs, and canonical URLs.
- SMART APIs use the existing authorized session and never widen scopes.
- Expose only launch context actually present and authorized. Patient context does not authorize arbitrary-patient access.
- Never expose raw tokens; execute authorized requests internally and return resources or sanitized results.
- Distinguish SMART sessions from ordinary server connections. Handle logout, expiry, server switching, and scope failures.

## 10. Incremental automation scope
- **Early:** interactive troubleshooting, one-off reads/searches, resource inspection, validation, and safe UI display.
- **Middle:** repeatable tests with assertions, isolated runs, test summaries, and exported reports.
- **Later:** parameterized workflows and batch resource processing/transformation with progress, cancellation where feasible, per-item results, failure policy, and bounded concurrency (sequential by default).
- Scheduling, headless/CLI execution, concurrent writes, and unattended production automation are future design decisions, not implicit Phase 5 requirements.

## 11. Cross-platform packaging
- Verify supported Windows, macOS, and Linux targets.
- Check bundled runtime modules required by JShell, including `jdk.jshell` and compiler modules.
- If using `jlink`, include and test required modules explicitly.
- Test the packaged app, not only IDE execution. Do not require a separate JDK if FHIRViewer normally bundles a runtime.
- Document platform-specific limitations.

## 12. Testing and phase completion
For each phase, run existing build/tests, add focused tests, preserve existing behavior, report only tests actually run, and update `IMPLEMENTATION-STATUS.md`. Include tests for UI responsiveness, session isolation, write-policy enforcement, confirmation cancellation, secret redaction, and cross-platform packaging as relevant. Do not mark a phase complete until its acceptance criteria are met. Do not silently implement later-phase features.

## 13. Non-goals unless separately approved
- Full IDE/debugger or full language-server support.
- Another scripting language.
- Claiming secure in-process execution of untrusted code.
- Direct JavaFX controller exposure.
- Redesigning the plugin architecture.
- Auto-run behavior.
- Scheduling, a standalone CLI runner, or unattended production execution.
- Making destructive writes/deletes the default.
