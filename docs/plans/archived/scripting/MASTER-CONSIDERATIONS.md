# Master Considerations — FHIRViewer JShell Scripting

## 1. Objective and stages
Add optional embedded Java scripting to FHIRViewer using Java 21 JShell. Implement these six phases in order:
1. Basic scripting console
2. FHIRViewer scripting API
3. Automated testing
4. SMART on FHIR integration
5. Advanced scripting
6. User-facing scripting API documentation

## 2. Mandatory technology constraints
- Target Java 21 and the repository's existing Maven/build conventions.
- Use JavaFX for the UI and the public `jdk.jshell` API for embedded execution.
- Do not add a JavaScript frontend, Node.js, Electron, Python, or another scripting engine.
- Reuse existing HAPI FHIR clients, resource parsing, validation, FHIRPath, server configuration, plugin architecture, and logging where appropriate.
- Keep dependencies lean. Evaluate licensing and packaging before adding an editor library.
- Treat API names in these documents as conceptual; inspect the repository and adapt them to existing patterns.

## 3. Repository-first process
Before coding, inspect Java/Maven versions, modules, packages, `module-info.java`, tests, JavaFX navigation/styles/threading, file dialogs/preferences, FHIR client/services, validation, FHIRPath, server configuration, plugin interfaces, SMART implementation, and packaging (`jlink`, `jpackage`, or custom runtime). Record findings in `IMPLEMENTATION-STATUS.md`. Do not guess class names, duplicate service layers, or refactor unrelated features.

## 4. Suggested architecture
- **Scripting UI:** editor, toolbar, console, diagnostics, status, Open/Save.
- **Scripting coordinator:** owns JShell lifecycle and serializes calls to each session.
- **Session factory/configuration:** creates/resets/closes sessions and configures imports/classpath/execution control as supported.
- **Script API facade:** deliberately exposed objects such as `fhir`, `viewer`, `validation`, `server`, `log`, and later `test` and `smart`.
- **Adapters:** connect script APIs to existing services without exposing JavaFX controllers or internals.
- **Test runner/report model:** Phase 3.
- **SMART context provider:** Phase 4.
- **API reference and examples:** Phase 6.

Do not let scripts manipulate JavaFX controls directly. UI actions must go through supported facade methods and be dispatched safely to the JavaFX Application Thread.

## 5. JShell behavior and execution
- JShell evaluates snippets, not conventional `.java` files with a `main()` method. Use JShell source analysis to identify complete snippets in a multi-line editor buffer.
- Preserve interactive state between submissions; provide Reset Session.
- Run saved tests in a fresh session for reproducibility, separate from interactive session behavior.
- Surface compile diagnostics, runtime exceptions, and output.
- Verify output capture with the chosen execution configuration.
- Use a dedicated worker; never block the JavaFX Application Thread.
- Implement Stop/Cancel honestly: arbitrary Java code cannot always be safely interrupted.
- Close sessions on reset and application shutdown.
- Avoid arbitrary dynamic classpath additions initially.

## 6. Security and trust model
JShell executes Java code and is **not a security sandbox**. Scripts may read files, access the network, use reflection, inspect environment variables, or launch processes with the current user's permissions.
- Treat scripts as trusted code; show a warning before first execution and when running scripts from untrusted sources.
- Never auto-run scripts on startup, file open, server connection, or SMART launch.
- Do not claim a confirmation dialog, facade, classpath restriction, or worker thread is a sandbox.
- Never expose SMART access/refresh tokens, client secrets, authorization headers, or credential-store objects.
- Redact credentials and avoid logging full sensitive FHIR resources by default.
- Writes/deletes must be opt-in and require explicit policy/confirmation.
- Examples use synthetic data and read-only operations by default.
- Do not bypass server authorization, OAuth scopes, validation policies, or selected server configuration.
- If untrusted community scripts become a requirement, design genuine process/OS-level isolation before implementing that capability.

## 7. FHIR and SMART behavior
- Prefer existing HAPI FHIR types and service abstractions; do not invent a duplicate resource model.
- Respect server capabilities, authentication, pagination, errors, timeouts, and rate limits.
- Keep network operations off the UI thread.
- Distinguish logical IDs, server base URLs, and canonical URLs.
- SMART APIs use the existing authorized session and never widen scopes.
- Expose only launch context actually present and authorized. Patient context does not authorize access to arbitrary patients.
- Never expose raw tokens; perform authorized requests internally and return resources or sanitized results.
- Distinguish SMART sessions from ordinary server connections.

## 8. Cross-platform packaging
- Verify supported Windows, macOS, and Linux targets.
- Check bundled runtime modules required by JShell, including `jdk.jshell` and compiler modules.
- If using `jlink`, include and test required modules explicitly.
- Test the packaged app, not only IDE execution.
- Do not require users to install a separate JDK if FHIRViewer normally bundles a runtime.
- Document platform-specific limitations.

## 9. Testing and phase completion
For every phase, run existing build/tests, add focused tests, preserve existing behavior, report only tests actually run, and update `IMPLEMENTATION-STATUS.md` with files changed, results, limitations, and next step. Do not silently implement later-phase features.

## 10. Non-goals
Unless separately approved, do not build a full IDE/debugger, add another scripting language, execute untrusted code securely in-process, expose JavaFX controllers, redesign plugins, auto-run scripts, or make writes/deletes the default.
