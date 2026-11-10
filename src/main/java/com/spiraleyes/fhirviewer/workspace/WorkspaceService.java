package com.spiraleyes.fhirviewer.workspace;

import java.util.prefs.Preferences;

import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.service.IgPackageManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WorkspaceService {
    private static final String PREFS_NODE = "workspace";
    private static final String KEY_LAST_ID = "lastId";
    private static final String KEY_LAST_NAME = "lastName";
    private static final String KEY_LAST_PATH = "lastPath";

    private Path currentPath;
    private WorkspaceMetadata current;
    private boolean dirty;
    private final List<WorkspaceStateProvider> providers = new ArrayList<>();
    private Preferences prefs;

    /** Backward-compatible constructor: providers run without real subsystem wiring. */
    public WorkspaceService() {
        this(null, null, null);
    }

    /**
     * Creates the service with real subsystem adapters wired to the providers.
     *
     * @param serverManager  configured FHIR servers (may be null)
     * @param pluginRegistry server plugin registry (may be null)
     * @param packageManager installed IG packages (may be null)
     */
    public WorkspaceService(FhirServerManager serverManager,
                            FhirServerPluginRegistry pluginRegistry,
                            IgPackageManager packageManager) {
        this.prefs = Preferences.userRoot().node(PREFS_NODE);
        providers.add(new ConnectionsWorkspaceStateProvider(serverManager, pluginRegistry));
        providers.add(new RestWorkbenchWorkspaceStateProvider());
        providers.add(new TreeTabsWorkspaceStateProvider());
        providers.add(new FhirPathWorkspaceStateProvider());
        providers.add(new ValidationWorkspaceStateProvider(packageManager));
    }

    /**
     * Builds a service whose providers are exactly the given ones.
     *
     * <p>For tests: the real constructor wires the five feature providers, none of which can
     * be made to fail, so the state a failed restore leaves behind would otherwise be
     * untestable.</p>
     */
    WorkspaceService(List<WorkspaceStateProvider> providers) {
        this.prefs = Preferences.userRoot().node(PREFS_NODE);
        this.providers.addAll(providers);
    }

    public WorkspaceMetadata newWorkspace(String name) {
        WorkspaceMetadata meta = new WorkspaceMetadata(WorkspaceMetadata.CURRENT_SCHEMA_VERSION, UUID.randomUUID(), name);
        current = meta;
        currentPath = null;
        dirty = true;
        // Persist that a new workspace was created (no path yet)
        prefs.put(KEY_LAST_ID, meta.workspaceId().toString());
        prefs.put(KEY_LAST_NAME, name);
        return meta;
    }

    /**
     * Opens an archive and applies its state.
     *
     * <p>The archive becomes the current workspace only after its sections have been
     * applied. Adopting it first meant that a failing provider left the service pointing at
     * an archive the window never showed: Save would then have written over a file the user
     * had not opened, and the caller's "failed to open" message described an open that had
     * half happened.</p>
     *
     * <p>On failure the previous workspace is put back where the providers can take it, the
     * recorded last-opened archive is left alone, and the failure is reported as an
     * {@link IOException} — the type {@link #openLastFromPreferences()} already treats as
     * "nothing to open", so an unrestorable archive cannot abort start-up.</p>
     */
    public WorkspaceMetadata open(Path path) throws IOException {
        WorkspaceMetadata meta = WorkspaceArchiveReader.read(path);
        WorkspaceMetadata previous = current;
        Path previousPath = currentPath;
        boolean wasDirty = dirty;
        try {
            restoreAll(meta);
        } catch (RuntimeException e) {
            // Some providers may already have applied their section before this one failed:
            // restore the workspace we were on, so the features do not hold a mixture of two
            // archives. Best effort — an archive that cannot be applied is the real failure.
            if (previous != null) {
                try {
                    restoreAll(previous);
                } catch (RuntimeException ignored) {
                    // Reported below as the failure to open the archive that was asked for.
                }
            }
            current = previous;
            currentPath = previousPath;
            dirty = wasDirty;
            throw new IOException("Could not restore workspace " + path.getFileName()
                    + ": " + e.getMessage(), e);
        }
        current = meta;
        currentPath = path;
        dirty = false;
        // Persist this as the last used workspace
        prefs.put(KEY_LAST_PATH, path.toString());
        prefs.put(KEY_LAST_ID, meta.workspaceId().toString());
        prefs.put(KEY_LAST_NAME, meta.name());
        return meta;
    }

    /**
     * Opens the workspace recorded in preferences, if its archive is still on disk.
     *
     * <p>Returns {@code null} rather than inventing a workspace. At start-up there is
     * nothing to save yet, and a fresh empty workspace would arrive already dirty, so
     * closing the window would ask about work nobody did. An archive that has moved or
     * cannot be read clears the recorded identity and reports the same "nothing open",
     * which Save turns into a Save As.</p>
     *
     * @return the workspace that was opened, or {@code null} when there was none
     */
    public WorkspaceMetadata openLastFromPreferences() {
        String savedPath = prefs.get(KEY_LAST_PATH, null);
        if (savedPath == null) {
            return null;
        }

        Path path = Path.of(savedPath);
        if (!Files.isRegularFile(path)) {
            // Archive missing or moved: forget it, so the next Save asks where.
            forgetLastWorkspace();
            return null;
        }

        try {
            return open(path);
        } catch (IOException e) {
            // Corrupt or incompatible archive: forget it rather than failing to start.
            forgetLastWorkspace();
            return null;
        }
    }

    /** Drops the recorded last-workspace identity: there is no archive to reopen. */
    private void forgetLastWorkspace() {
        prefs.remove(KEY_LAST_PATH);
        prefs.remove(KEY_LAST_ID);
        prefs.remove(KEY_LAST_NAME);
    }

    public void save() throws IOException {
        if (current == null) throw new IllegalStateException("No workspace open");
        if (currentPath == null) throw new IllegalStateException("Use Save As");
        WorkspaceMetadata meta = captureAll(current);
        WorkspaceArchiveWriter.write(currentPath, meta);
        current = meta;
        dirty = false;
        // Persist this as the last used workspace
        prefs.put(KEY_LAST_PATH, currentPath.toString());
        prefs.put(KEY_LAST_ID, meta.workspaceId().toString());
        prefs.put(KEY_LAST_NAME, meta.name());
    }

    public void saveAs(Path path) throws IOException {
        if (current == null) throw new IllegalStateException("No workspace open");
        WorkspaceMetadata meta = captureAll(current);
        WorkspaceArchiveWriter.write(path, meta);
        current = meta;
        currentPath = path;
        dirty = false;
        // Persist this as the last used workspace
        prefs.put(KEY_LAST_PATH, path.toString());
        prefs.put(KEY_LAST_ID, meta.workspaceId().toString());
        prefs.put(KEY_LAST_NAME, meta.name());
    }

    public void captureAll() {
        if (current != null) current = captureAll(current);
    }

    public WorkspaceMetadata captureAllCurrent() {
        return captureAll(current);
    }

    private WorkspaceMetadata captureAll(WorkspaceMetadata base) {
        java.util.Map<String, Object> connections = null, rest = null, tree = null, fp = null, val = null;
        for (WorkspaceStateProvider p : providers) {
            Object snap = p.capture();
            switch (p.providerId()) {
                case "connections" -> connections = asMap(snap);
                case "rest-workbench" -> rest = asMap(snap);
                case "tree-tabs" -> tree = asMap(snap);
                case "fhirpath" -> fp = asMap(snap);
                case "validation" -> val = asMap(snap);
            }
        }
        return new WorkspaceMetadata(base.schemaVersion(), base.workspaceId(), base.name(), connections, rest, tree, fp, val);
    }

    public void restoreAll(WorkspaceMetadata meta) {
        for (WorkspaceStateProvider p : providers) {
            Object snap = switch (p.providerId()) {
                case "connections" -> meta.connections();
                case "rest-workbench" -> meta.restWorkbench();
                case "tree-tabs" -> meta.treeTabs();
                case "fhirpath" -> meta.fhirPath();
                case "validation" -> meta.validation();
                default -> null;
            };
            if (snap != null) p.restore(snap);
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> asMap(Object o) {
        if (o instanceof java.util.Map) return (java.util.Map<String, Object>) o;
        return java.util.Map.of();
    }

    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public Path getCurrentPath() { return currentPath; }
    public WorkspaceMetadata getCurrent() { return current; }
}

