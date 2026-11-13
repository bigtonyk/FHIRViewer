package com.spiraleyes.fhirviewer.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class WorkspaceArchiveWriter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkspaceArchiveWriter() {}

    public static void write(Path target, WorkspaceMetadata metadata) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        // A target such as "untitled.fhirworkspace" is a file name with no directory, and
        // getParent() is then null. Resolving the absolute path first gives the working
        // directory to create instead of an NPE the caller can only report as "null".
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            writeEntry(zos, "manifest.json", MAPPER.writeValueAsBytes(new Manifest(metadata.schemaVersion())));
            writeEntry(zos, "workspace.json", MAPPER.writeValueAsBytes(metadata));
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    public record Manifest(int schemaVersion) {}
}
