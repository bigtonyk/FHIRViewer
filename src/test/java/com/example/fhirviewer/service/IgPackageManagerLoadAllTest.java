package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        TgzFixtures.writeTgz(target, Map.of(
                "package/package.json", PACKAGE_JSON,
                "package/StructureDefinition-dummy.json", STRUCTURE_DEFINITION_JSON));
    }
}
