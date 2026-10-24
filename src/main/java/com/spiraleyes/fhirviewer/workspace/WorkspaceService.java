package com.spiraleyes.fhirviewer.workspace;

import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.service.IgPackageManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WorkspaceService {
    private Path currentPath;
    private WorkspaceMetadata current;
    private boolean dirty;
    private final List<WorkspaceStateProvider> providers = new ArrayList<>();

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
        return meta;
    }

    public WorkspaceMetadata open(Path path) throws IOException {
        WorkspaceMetadata meta = WorkspaceArchiveReader.read(path);
        current = meta;
        currentPath = path;
        dirty = false;
        restoreAll(meta);
        return meta;
    }

    public void save() throws IOException {
        if (current == null) throw new IllegalStateException("No workspace open");
        if (currentPath == null) throw new IllegalStateException("Use Save As");
        WorkspaceMetadata meta = captureAll(current);
        WorkspaceArchiveWriter.write(currentPath, meta);
        current = meta;
        dirty = false;
    }

    public void saveAs(Path path) throws IOException {
        if (current == null) throw new IllegalStateException("No workspace open");
        WorkspaceMetadata meta = captureAll(current);
        WorkspaceArchiveWriter.write(path, meta);
        current = meta;
        currentPath = path;
        dirty = false;
    }

    public void captureAll() {
        if (current != null) current = captureAll(current);
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

    private void restoreAll(WorkspaceMetadata meta) {
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

