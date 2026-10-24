package com.spiraleyes.fhirviewer.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

public class WorkspaceService {
    private Path currentPath;
    private WorkspaceMetadata current;
    private boolean dirty;

    public WorkspaceMetadata newWorkspace(String name) {
        WorkspaceMetadata meta = new WorkspaceMetadata(WorkspaceMetadata.CURRENT_SCHEMA_VERSION, UUID.randomUUID(), name == null ? "Untitled" : name);
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
        return meta;
    }

    public void save() throws IOException {
        if (current == null) throw new IllegalStateException("No workspace open");
        if (currentPath == null) throw new IllegalStateException("Use Save As");
        WorkspaceArchiveWriter.write(currentPath, current);
        dirty = false;
    }

    public void saveAs(Path path) throws IOException {
        if (current == null) throw new IllegalStateException("No workspace open");
        WorkspaceArchiveWriter.write(path, current);
        currentPath = path;
        dirty = false;
    }

    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public Path getCurrentPath() { return currentPath; }
    public WorkspaceMetadata getCurrent() { return current; }
}
