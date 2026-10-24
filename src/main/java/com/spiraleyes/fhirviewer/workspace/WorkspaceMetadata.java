package com.spiraleyes.fhirviewer.workspace;

import java.util.Map;
import java.util.UUID;

public record WorkspaceMetadata(
        int schemaVersion,
        UUID workspaceId,
        String name,
        Map<String, Object> connections,
        Map<String, Object> restWorkbench,
        Map<String, Object> treeTabs,
        Map<String, Object> fhirPath,
        Map<String, Object> validation
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public WorkspaceMetadata(int schemaVersion, UUID workspaceId, String name) {
        this(schemaVersion, workspaceId, name, null, null, null, null, null);
    }
}

