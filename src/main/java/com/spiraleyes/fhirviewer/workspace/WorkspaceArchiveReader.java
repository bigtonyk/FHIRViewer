package com.spiraleyes.fhirviewer.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validate all ZIP entries before reading manifest/workspace.
 */
public class WorkspaceArchiveReader {
    private static final int MAX_TOTAL_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ENTRIES = 500;
    private static final int MAX_SCHEMA = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Validate all ZIP entries before reading manifest/workspace.
     */
    private static void validateZipEntries(ZipFile zip) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        Set<String> seenNames = new HashSet<>();
        long totalBytes = 0;

        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String name = e.getName();

            if (name.startsWith("/")) {
                throw new IOException("Unsafe archive entry (absolute path): " + name);
            }
            if (name.contains("..")) {
                throw new IOException("Unsafe archive entry (path traversal): " + name);
            }
            if (!seenNames.add(name)) {
                throw new IOException("Duplicate archive entry: " + name);
            }
            totalBytes += e.getSize();
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new IOException("Archive exceeds size limit of " + (MAX_TOTAL_BYTES / (1024 * 1024)) + " MB");
            }
        }

        if (zip.size() > MAX_ENTRIES) {
            throw new IOException("Archive entry count (" + zip.size() + ") exceeds limit of " + MAX_ENTRIES);
        }
    }

    /**
     * Migrate workspace metadata from an older schema version to the current one.
     */
    private static WorkspaceMetadata migrateIfNeeded(WorkspaceMetadata meta) {
        int fromVersion = meta.schemaVersion();
        int toVersion = WorkspaceMetadata.getTargetSchemaVersion(fromVersion);

        if (fromVersion == toVersion) return meta;

        String name = meta.name();
        UUID workspaceId = meta.workspaceId();
        Map<String, Object> connections = meta.connections();
        Map<String, Object> restWorkbench = meta.restWorkbench();
        Map<String, Object> treeTabs = meta.treeTabs();
        Map<String, Object> fhirPath = meta.fhirPath();
        Map<String, Object> validation = meta.validation();

        UUID lastRecoverySnapshotId = meta.lastRecoverySnapshotId();
        String lastRecoverySnapshotTimestamp = meta.lastRecoverySnapshotTimestamp();
        UUID savedBaselineWorkspaceId = meta.savedBaselineWorkspaceId();
        String localContentRoot = meta.localContentRoot();

        if (lastRecoverySnapshotId == null) lastRecoverySnapshotId = UUID.randomUUID();
        if (lastRecoverySnapshotTimestamp == null) lastRecoverySnapshotTimestamp = "";
        if (savedBaselineWorkspaceId == null) savedBaselineWorkspaceId = UUID.randomUUID();
        if (localContentRoot == null) localContentRoot = "";

        return new WorkspaceMetadata(
                toVersion,
                workspaceId,
                name,
                connections,
                restWorkbench,
                treeTabs,
                fhirPath,
                validation,
                lastRecoverySnapshotId,
                lastRecoverySnapshotTimestamp,
                savedBaselineWorkspaceId,
                localContentRoot
        );
    }

    /**
     * Read workspace metadata from a ZIP archive.
     */
    public static WorkspaceMetadata read(Path source) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("Workspace file not found: " + source);
        try (ZipFile zip = new ZipFile(source.toFile())) {
            validateZipEntries(zip);

            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            ZipEntry workspaceEntry = zip.getEntry("workspace.json");
            if (manifestEntry == null || workspaceEntry == null)
                throw new IOException("Missing required archive entries (manifest.json or workspace.json)");

            Manifest manifest;
            try {
                manifest = MAPPER.readValue(zip.getInputStream(manifestEntry), Manifest.class);
            } catch (IOException e) {
                throw new IOException("Failed to parse manifest.json: " + e.getMessage(), e);
            }

            if (manifest == null) {
                throw new IOException("Manifest is empty or null");
            }

            if (manifest.schemaVersion() > MAX_SCHEMA) {
                throw new IOException("Unsupported workspace schema version: " + manifest.schemaVersion());
            }

            if (manifest.schemaVersion() < 1) {
                throw new IOException("Invalid workspace schema version: " + manifest.schemaVersion());
            }

            try {
                WorkspaceMetadata raw = MAPPER.readValue(zip.getInputStream(workspaceEntry), WorkspaceMetadata.class);
                return migrateIfNeeded(raw);
            } catch (IOException e) {
                throw new IOException("Failed to parse workspace.json: " + e.getMessage(), e);
            }
        }
    }

    public record Manifest(int schemaVersion) {}
}