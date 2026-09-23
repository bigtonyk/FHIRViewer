package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.uhn.fhir.context.FhirContext;

/**
 * Tests bulk-loading of downloaded .tgz packages (the startup auto-load path).
 * Builds a minimal but real npm-style package archive in a temp directory.
 */
class IgPackageManagerLoadAllTest {

    private static final String PACKAGE_JSON = "{"
            + "\"name\": \"test.ig\","
            + "\"version\": \"1.0.0\","
            + "\"fhirVersion\": \"R4\","
            + "\"fhirVersions\": [\"4.0.1\"],"
            + "\"canonical\": \"http://example.org/ig\","
            + "\"description\": \"Test IG\""
            + "}";

    private static final String STRUCTURE_DEFINITION_JSON = "{"
            + "\"resourceType\": \"StructureDefinition\","
            + "\"id\": \"dummy\","
            + "\"url\": \"http://example.org/ig/StructureDefinition/dummy\","
            + "\"name\": \"Dummy\","
            + "\"status\": \"draft\","
            + "\"kind\": \"complex-type\","
            + "\"abstract\": false,"
            + "\"type\": \"Dummy\""
            + "}";

    @Test
    @DisplayName("loadAllFrom loads a .tgz package from the directory")
    void loadAllFromLoadsTgzFiles(@TempDir Path dir) throws IOException {
        writeTestTgz(dir.resolve("test.ig-1.0.0.tgz"));

        IgPackageManager manager = new IgPackageManager(FhirContext.forR4());
        int loaded = manager.loadAllFrom(dir);

        assertEquals(1, loaded);
        assertEquals(1, manager.getLoadedPackageCount());
        assertEquals("test.ig", manager.getLoadedPackages().get(0).name());
        assertEquals("1.0.0", manager.getLoadedPackages().get(0).version());
        assertEquals("http://example.org/ig/1.0.0",
                manager.getLoadedPackages().get(0).canonicalUrl());
        assertTrue(manager.hasLoadedPackages());
    }

    @Test
    @DisplayName("loadAllFrom ignores files that are not .tgz")
    void loadAllFromIgnoresOtherFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("readme.txt"), "not a package");

        IgPackageManager manager = new IgPackageManager(FhirContext.forR4());
        assertEquals(0, manager.loadAllFrom(dir));
        assertEquals(0, manager.getLoadedPackageCount());
    }

    @Test
    @DisplayName("loadAllFrom tolerates a missing directory")
    void loadAllFromMissingDirectory(@TempDir Path dir) {
        IgPackageManager manager = new IgPackageManager(FhirContext.forR4());
        assertEquals(0, manager.loadAllFrom(dir.resolve("does-not-exist")));
        assertEquals(0, manager.loadAllFrom(null));
    }

    /** Writes a minimal npm-style tgz: package/package.json + one StructureDefinition. */
    private static void writeTestTgz(Path target) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("package/package.json", PACKAGE_JSON.getBytes(StandardCharsets.UTF_8));
        entries.put("package/StructureDefinition-dummy.json",
                STRUCTURE_DEFINITION_JSON.getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            writeTarEntry(tar, entry.getKey(), entry.getValue());
        }
        tar.write(new byte[1024]); // end-of-archive: two zero blocks

        try (java.util.zip.GZIPOutputStream gz =
                     new java.util.zip.GZIPOutputStream(Files.newOutputStream(target))) {
            gz.write(tar.toByteArray());
        }
    }

    /** Appends one ustar tar entry (512-byte header, data, zero padding). */
    private static void writeTarEntry(ByteArrayOutputStream out, String name, byte[] data) {
        byte[] header = new byte[512];
        putFixed(header, 0, name, 100);
        putOctal(header, 100, 0644, 8);          // mode
        putOctal(header, 108, 0, 8);             // uid
        putOctal(header, 116, 0, 8);             // gid
        putOctal(header, 124, data.length, 12);  // size
        putOctal(header, 136, 0, 12);            // mtime
        Arrays.fill(header, 148, 156, (byte) ' '); // checksum placeholder
        header[156] = '0';                        // typeflag: regular file
        putFixed(header, 257, "ustar", 6);
        putFixed(header, 263, "00", 2);

        long sum = 0;
        for (byte b : header) {
            sum += b & 0xffL;
        }
        putFixed(header, 148, String.format("%06o", sum), 6);
        header[154] = 0;
        header[155] = ' ';

        out.write(header, 0, 512);
        out.write(data, 0, data.length);
        int padding = (512 - (data.length % 512)) % 512;
        out.write(new byte[padding], 0, padding);
    }

    private static void putFixed(byte[] header, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private static void putOctal(byte[] header, int offset, long value, int length) {
        String digits = Long.toOctalString(value);
        StringBuilder field = new StringBuilder();
        while (field.length() + digits.length() < length - 1) {
            field.append('0');
        }
        field.append(digits).append('\0');
        putFixed(header, offset, field.toString(), length);
    }
}
