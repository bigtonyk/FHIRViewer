# Repository Assessment — FHIR Viewer Workspace System

## 1. Repository and build baseline

- Branch: `scripting`; working tree clean at assessment time.
- Build system: Maven (`pom.xml`), Java 21 (`maven.compiler.release=21`), JavaFX 21.0.8, HAPI FHIR 8.12.0, JUnit 5.14.4, SLF4J 2.0.19.
- Entry point: `com.spiraleyes.fhirviewer.Launcher` (ordinary main class) launches `com.spiraleyes.fhirviewer.Main` (JavaFX `Application`). `Main.start` creates `ui.MainWindow`, applies theme, shows stage, opens requested startup file via `MainWindow.openOnStartup`.
- Tests: `src/test/java` mirrors main packages; Maven Surefire. No real server required expected.

## 2. State owners and capture/restore surfaces

- Server profiles: `server.FhirServerConfiguration`, `server.FhirServerManager`, `server.FhirServerService`, `server.FhirServerPluginRegistry`, `ui.ServerManagerDialog`, `ui.ServerFormPanel`.
- REST workbench: `ui.RestConsoleDialog`, `ui.RestConsoleState`, `ui.RestRequestPane`, `ui.RestResponsePane`, `ui.RestHeaderList`, `ui.RestParameterList`, `ui.RestConsoleForm`; drafts/state via `RestConsoleState` Preferences node.
- Resource tree/tabs/selection/filters: `model.ResourceNode`, `ui.ResourceTreeView`, `ui.MainWindow`, `model.LoadedResource`, `fhir.ResourceTreeBuilder`.
- Editor buffers/dirty state: `ui.JsonView`, `ui.XmlView`, `ui.PrettyView`, `model.LoadedResource`, `ui.MainWindow` dirty handling (to be confirmed in later phases).
- FHIRPath: exact owner to be located before Phase 1 (search package not yet inspected).
- IG/package and validation: `model.IgPackageInfo`, `model.ValidationProfile`, `model.ValidationReport`, `model.ValidationIssue`, `model.ValidationLevel`.
- Local resources: `model.LoadedResource`, `util.FileSupport`, dialogs `OpenFromServerDialog`, `SaveToServerDialog`.
- Preferences/shutdown: `java.util.prefs.Preferences` nodes (`RestConsoleState`, `PluginManagerDialog`); JavaFX lifecycle in `Main`.

## 3. Credential storage

- Credentials are not to be stored in workspace/recovery. Existing server auth classes: `AdhocAuthentication`, `BasicServerAuthentication`, `BearerServerAuthentication`, `AnonymousServerAuthentication`, SMART auth. `RestConsoleState` explicitly avoids persisting credentials. Reuse existing auth/plugin settings; never serialize secrets.

## 4. Logging, file/archive utilities, scheduling

- Logging: `java.util.logging.Logger` (e.g. RestConsoleState).
- File utilities: `util.FileSupport`.
- Archive/ZIP: no existing workspace archive utility found; must implement carefully with traversal protection.
- Scheduling: no dedicated scheduler identified yet; use JavaFX/Executor conventions; integrate with existing lifecycle.

## 5. Proposed model/archive/recovery

- Use plain-data model objects under new `workspace` package (naming TBD by repository conventions).
- Archive: `.fhirworkspace` ZIP with `manifest.json`, `workspace.json`, etc.
- Recovery store: app-managed private directory, atomic writes, versioned snapshots.
- Last workspace path stored via Preferences or recovery metadata (not credentials).

## 6. Gaps/risks

- Exact FHIRPath owner and dirty-state APIs need deeper inspection before implementation.
- No existing archive utility; must implement secure ZIP handling.
- No existing scheduler abstraction; avoid competing shutdown hooks.
- Must not execute saved REST requests or scripts during restore.
- Offline tolerance and missing plugin/IG handling required.

## 7. Build/test commands

- `.\mvnw.cmd test`
- `.\mvnw.cmd -q test`
- `.\mvnw.cmd javafx:run`
- `.\mvnw.cmd -Pdist package` (packaging)
