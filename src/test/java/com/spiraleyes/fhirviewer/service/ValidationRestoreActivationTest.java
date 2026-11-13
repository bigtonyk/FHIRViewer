package com.spiraleyes.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.uhn.fhir.context.FhirContext;

import com.spiraleyes.fhirviewer.workspace.ValidationWorkspaceStateProvider;

/**
 * Restoring a workspace must change which installed packages take part in validation.
 *
 * <p>Kept in this package so it can build a real package archive with {@link TgzFixtures}:
 * the provider needs a genuinely installed package, because its job is to act on what is
 * installed rather than to install anything.</p>
 */
class ValidationRestoreActivationTest {

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
    @DisplayName("restore re-activates an installed package the archive had active")
    void restoreReactivatesAnInstalledPackage(@TempDir Path dir) throws IOException {
        IgPackageManager manager = managerWithPackage(dir);
        assertTrue(manager.setActive("test.ig", false), "the package starts active");
        assertFalse(manager.isActive("test.ig"));

        new ValidationWorkspaceStateProvider(manager).restore(snapshot(true));

        assertTrue(manager.isActive("test.ig"),
                "an archive that had the package active must activate it again");
    }

    @Test
    @DisplayName("restore deactivates an installed package the archive had inactive")
    void restoreDeactivatesAnInstalledPackage(@TempDir Path dir) throws IOException {
        IgPackageManager manager = managerWithPackage(dir);
        assertTrue(manager.isActive("test.ig"));

        new ValidationWorkspaceStateProvider(manager).restore(snapshot(false));

        assertFalse(manager.isActive("test.ig"),
                "a package the archive did not activate must not stay active");
    }

    @Test
    @DisplayName("restore leaves packages the archive does not name alone")
    void restoreLeavesUnnamedPackagesAlone(@TempDir Path dir) throws IOException {
        IgPackageManager manager = managerWithPackage(dir);
        assertTrue(manager.isActive("test.ig"));

        // An archive that never saw this package: it is absent from "packages" entirely.
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("packages", List.of());
        empty.put("activePackages", List.of());
        new ValidationWorkspaceStateProvider(manager).restore(empty);

        assertTrue(manager.isActive("test.ig"),
                "installing a package after the save must not be undone by a restore");
    }

    /** A manager holding one installed, active package named {@code test.ig 1.0.0}. */
    private static IgPackageManager managerWithPackage(Path dir) throws IOException {
        TgzFixtures.writeTgz(dir.resolve("test.ig-1.0.0.tgz"), Map.of(
                "package/package.json", PACKAGE_JSON,
                "package/StructureDefinition-dummy.json", STRUCTURE_DEFINITION_JSON));
        IgPackageManager manager = new IgPackageManager(FhirContext.forR4());
        assertEquals(1, manager.loadAllFrom(dir));
        assertEquals("test.ig", manager.getLoadedPackages().get(0).name());
        return manager;
    }

    /** The validation section of a workspace archive, as capture would have written it. */
    private static Map<String, Object> snapshot(boolean active) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("name", "test.ig");
        pkg.put("version", "1.0.0");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("packages", List.of(pkg));
        snapshot.put("activePackages", active ? List.of("test.ig") : List.of());
        snapshot.put("inactivePackages", active ? List.of() : List.of("test.ig"));
        return snapshot;
    }
}
