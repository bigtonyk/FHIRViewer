package com.spiraleyes.fhirviewer.workspace;

import java.util.UUID;

public record WorkspaceMetadata(
        int schemaVersion,
        UUID workspaceId,
        String name
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
