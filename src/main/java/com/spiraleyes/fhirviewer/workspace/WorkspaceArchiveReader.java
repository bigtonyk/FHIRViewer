package com.spiraleyes.fhirviewer.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class WorkspaceArchiveReader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SCHEMA = WorkspaceMetadata.CURRENT_SCHEMA_VERSION;

    private WorkspaceArchiveReader() {}

    public static WorkspaceMetadata read(Path source) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("Workspace file not found: " + source);
        try (ZipFile zip = new ZipFile(source.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (name.startsWith("/") || name.contains("..") || name.contains("\\"))
                    throw new IOException("Unsafe archive entry: " + name);
            }
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            ZipEntry workspaceEntry = zip.getEntry("workspace.json");
            if (manifestEntry == null || workspaceEntry == null)
                throw new IOException("Missing required archive entries");
            Manifest manifest = MAPPER.readValue(zip.getInputStream(manifestEntry), Manifest.class);
            if (manifest.schemaVersion() > MAX_SCHEMA)
                throw new IOException("Unsupported workspace schema version: " + manifest.schemaVersion());
            if (manifest.schemaVersion() < 1)
                throw new IOException("Invalid workspace schema version: " + manifest.schemaVersion());
            return MAPPER.readValue(zip.getInputStream(workspaceEntry), WorkspaceMetadata.class);
        }
    }

    public record Manifest(int schemaVersion) {}
}
