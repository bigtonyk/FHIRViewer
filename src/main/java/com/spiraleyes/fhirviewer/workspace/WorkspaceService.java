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

    public WorkspaceMetadata open(Path path) throws IOException {
        WorkspaceMetadata meta = WorkspaceArchiveReader.read(path);
        current = meta;
        currentPath = path;
        dirty = false;
        restoreAll(meta);
        // Persist this as the last used workspace
        prefs.put(KEY_LAST_PATH, path.toString());
        prefs.put(KEY_LAST_ID, meta.workspaceId().toString());
        prefs.put(KEY_LAST_NAME, meta.name());
        return meta;
    }

    /** Open the last saved workspace from preferences if no path is given. */
    public WorkspaceMetadata openLastFromPreferences() {
        String savedPath = prefs.get(KEY_LAST_PATH, null);
        String savedId = prefs.get(KEY_LAST_ID, null);
        String savedName = prefs.get(KEY_LAST_NAME, null);

        if (savedPath == null || savedId == null) {
            // No prior workspace; start fresh
            return newWorkspace("(new)");
        }

        Path path = Path.of(savedPath);
        if (!Files.isRegularFile(path)) {
            // Archive missing/moved – start fresh and clear preference
            prefs.remove(KEY_LAST_PATH);
            prefs.remove(KEY_LAST_ID);
            prefs.remove(KEY_LAST_NAME);
            return newWorkspace("(recovered)");
        }

        try {
            WorkspaceMetadata meta = open(path);
            // Update preferences with the just-opened workspace identity
            prefs.put(KEY_LAST_PATH, path.toString());
            prefs.put(KEY_LAST_ID, meta.workspaceId().toString());
            prefs.put(KEY_LAST_NAME, meta.name());
            return meta;
        } catch (IOException e) {
            // Corrupt/archive incompatible – start fresh and clear preference
            prefs.remove(KEY_LAST_PATH);
            prefs.remove(KEY_LAST_ID);
            prefs.remove(KEY_LAST_NAME);
            return newWorkspace("(recovered)");
        }
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

