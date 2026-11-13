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
        Map<String, Object> validation,
        UUID lastRecoverySnapshotId,
        String lastRecoverySnapshotTimestamp,
        UUID savedBaselineWorkspaceId,
        String localContentRoot
) {
    /** Current stable schema version (incremented on breaking changes). */
    public static final int CURRENT_SCHEMA_VERSION = 3;

    /** Previous schema versions for migration support. */
    public static final int SCHEMA_VERSION_1 = 1;
    public static final int SCHEMA_VERSION_2 = 2;

    /**
     * Migration path from an older schema version to the current one.
     * Returns the target schema version to migrate to.
     */
    public static int getTargetSchemaVersion(int fromVersion) {
        if (fromVersion >= CURRENT_SCHEMA_VERSION) return fromVersion;
        if (fromVersion >= 1) return CURRENT_SCHEMA_VERSION; // v1 -> v3
        if (fromVersion >= 2) return CURRENT_SCHEMA_VERSION; // v2 -> v3
        return CURRENT_SCHEMA_VERSION; // unknown -> v3
    }

    /**
     * Check if a schema version is supported for opening.
     * Unsupported future versions are rejected.
     */
    public static boolean isSchemaVersionSupported(int version) {
        return version >= 1 && version <= CURRENT_SCHEMA_VERSION;
    }

    public WorkspaceMetadata(int schemaVersion, UUID workspaceId, String name) {
        this(schemaVersion, workspaceId, name, null, null, null, null, null, null, null, null, null);
    }

    public WorkspaceMetadata(int schemaVersion, UUID workspaceId, String name,
                             Map<String, Object> connections, Map<String, Object> restWorkbench,
                             Map<String, Object> treeTabs, Map<String, Object> fhirPath,
                             Map<String, Object> validation) {
        this(schemaVersion, workspaceId, name, connections, restWorkbench, treeTabs, fhirPath, validation, null, null, null, null);
    }
}

