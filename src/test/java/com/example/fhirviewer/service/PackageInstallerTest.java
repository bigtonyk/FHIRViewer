// Copyright (c) 2026 FHIRViewer contributors. SPDX-License-Identifier: GPL-3.0-only.
package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.fhirviewer.fhir.FhirContextFactory;
import com.example.fhirviewer.service.PackageInstaller.InstallPlan;
import com.example.fhirviewer.service.PackageInstaller.PlannedPackage;
import com.example.fhirviewer.service.PackageInstaller.Status;
import com.example.fhirviewer.service.PackageRegistryService.PackageInfo;

import ca.uhn.fhir.context.FhirContext;

/**
 * Tests dependency-resolved package planning and installation.
 *
 * <p>The package source is a fake, so no network access is needed: published
 * packages are real .tgz archives written to a temp directory, and the planner
 * reads their package.json dependency declarations exactly as it would for a
 * downloaded IG.</p>
 */
class PackageInstallerTest {

    private static final String ROOT = "example.ig";
    private static final String DEPENDENCY = "example.dependency";
    private static final String TRANSITIVE = "example.transitive";
    private static final String CORE = "hl7.fhir.r4.core";

    @Test
    @DisplayName("plan pins the version a dependency declares")
    void planPinsDeclaredDependencyVersion(@TempDir Path dir) throws IOException {
        Fixture fixture = new Fixture(dir);
        fixture.publish(ROOT, "1.0.0", Map.of(DEPENDENCY, "2.1.0"));
        fixture.publish(DEPENDENCY, "2.1.0", Map.of());

        InstallPlan plan = fixture.installer().plan(ROOT, "");

        PlannedPackage dependency = plan.packages().stream()
                .filter(pkg -> pkg.packageId().equals(DEPENDENCY))
                .findFirst()
                .orElseThrow();
        assertEquals(Status.TO_INSTALL, dependency.status());
        assertEquals("2.1.0", dependency.version());
        assertEquals(ROOT, dependency.requiredBy());
        assertTrue(fixture.source().resolved.contains(DEPENDENCY + "#2.1.0"),
                "the declared version must be passed to the registry: "
                        + fixture.source().resolved);
    }

    @Test
    @DisplayName("plan follows dependencies transitively")
    void planFollowsTransitiveDependencies(@TempDir Path dir) throws IOException {
        Fixture fixture = new Fixture(dir);
        fixture.publish(ROOT, "1.0.0", Map.of(DEPENDENCY, "2.1.0"));
        fixture.publish(DEPENDENCY, "2.1.0", Map.of(TRANSITIVE, "3.0.0"));
        fixture.publish(TRANSITIVE, "3.0.0", Map.of());

        InstallPlan plan = fixture.installer().plan(ROOT, "");

        assertEquals(3, plan.packages().size());
        PlannedPackage transitive = plan.packages().stream()
                .filter(pkg -> pkg.packageId().equals(TRANSITIVE))
                .findFirst()
                .orElseThrow();
        assertEquals("3.0.0", transitive.version());
        assertEquals(DEPENDENCY, transitive.requiredBy());
        assertEquals(2, plan.dependencies().size());
    }

    @Test
    @DisplayName("plan reports core specification dependencies as provided by core")
    void planReportsCoreProvidedDependency(@TempDir Path dir) throws IOException {
        Fixture fixture = new Fixture(dir);
        fixture.publish(ROOT, "1.0.0", Map.of(CORE, "4.0.1"));

        InstallPlan plan = fixture.installer().plan(ROOT, "");

        PlannedPackage core = plan.packages().stream()
                .filter(pkg -> pkg.packageId().equals(CORE))
                .findFirst()
                .orElseThrow();
        assertEquals(Status.CORE_PROVIDED, core.status());
        assertTrue(plan.toInstall().stream().noneMatch(pkg -> pkg.packageId().equals(CORE)),
                "a core dependency must never be downloaded");
    }

    @Test
    @DisplayName("plan reuses an installed dependency instead of downloading it")
    void planReusesInstalledDependency(@TempDir Path dir) throws IOException {
        Fixture fixture = new Fixture(dir);
        fixture.publish(ROOT, "1.0.0", Map.of(DEPENDENCY, "2.1.0"));
        fixture.publish(DEPENDENCY, "2.1.0", Map.of());
        fixture.manager().loadPackageFromFile(
                fixture.publish(DEPENDENCY, "2.1.0", Map.of()));

        InstallPlan plan = fixture.installer().plan(ROOT, "");

        PlannedPackage dependency = plan.packages().stream()
                .filter(pkg -> pkg.packageId().equals(DEPENDENCY))
                .findFirst()
                .orElseThrow();
        assertEquals(Status.ALREADY_INSTALLED, dependency.status());
        assertEquals("2.1.0", dependency.version());
        assertFalse(fixture.source().fetched.contains(DEPENDENCY + "#2.1.0"),
                "an installed dependency must not be downloaded again");
    }

    @Test
    @DisplayName("plan downloads a dependency when the installed version differs")
    void planReplacesMismatchingInstalledVersion(@TempDir Path dir) throws IOException {
        Fixture fixture = new Fixture(dir);
        fixture.publish(ROOT, "1.0.0", Map.of(DEPENDENCY, "2.1.0"));
        fixture.publish(DEPENDENCY, "2.1.0", Map.of());
        fixture.manager().loadPackageFromFile(
                fixture.publish(DEPENDENCY, "1.0.0", Map.of()));

        InstallPlan plan = fixture.installer().plan(ROOT, "");

        PlannedPackage dependency = plan.packages().stream()
                .filter(pkg -> pkg.packageId().equals(DEPENDENCY))
                .findFirst()
                .orElseThrow();
        assertEquals(Status.TO_INSTALL, dependency.status());
        assertEquals("2.1.0", dependency.version());
    }

    @Test
    @DisplayName("plan fetches each package exactly once")
    void planFetchesEachPackageOnce(@TempDir Path dir) throws IOException {
        Fixture fixture = new Fixture(dir);
        fixture.publish(ROOT, "1.0.0", Map.of(DEPENDENCY, "2.1.0"));
        fixture.publish(DEPENDENCY, "2.1.0", Map.of(ROOT, "1.0.0"));

        InstallPlan plan = fixture.installer().plan(ROOT, "");

        assertEquals(2, plan.packages().size());
        assertEquals(1, fixture.source().fetched.stream()
                .filter(key -> key.equals(ROOT + "#1.0.0")).count(),
                "a circular dependency must not be downloaded twice: "
                        + fixture.source().fetched);
    }

    /**
     * A package id, version and dependency set, plus the manager and a
     * network-free source serving real .tgz archives.
     */
    private static final class Fixture {

        private final FakeSource source;
        private final IgPackageManager manager;
        private final Path storageDirectory;
        private final Path archiveDirectory;

        private Fixture(Path root) {
            this.archiveDirectory = root.resolve("registry");
            this.storageDirectory = root.resolve("packages");
            this.manager = new IgPackageManager(FhirContextFactory.r4());
            this.source = new FakeSource(archiveDirectory);
        }

        private PackageInstaller installer() {
            return new PackageInstaller(source, manager, storageDirectory);
        }

        private IgPackageManager manager() {
            return manager;
        }

        private FakeSource source() {
            return source;
        }

        /**
         * Writes a real npm-style .tgz with a package.json declaring the given
         * dependencies, and publishes it so the source can serve it.
         *
         * @return the archive file
         */
        private Path publish(String packageId, String version,
                Map<String, String> dependencies) throws IOException {
            Files.createDirectories(archiveDirectory);
            Path file = archiveDirectory.resolve(packageId + "-" + version + ".tgz");
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("package/package.json", packageJson(packageId, version, dependencies));
            TgzFixtures.writeTgz(file, entries);
            source.published.put(packageId + "#" + version, file);
            return file;
        }
    }

    /**
     * Serves pre-written archives. The real registry is never contacted, so the
     * planner exercises dependency discovery against actual package.json files.
     */
    private static final class FakeSource implements PackageInstaller.PackageSource {

        private final Path archiveDirectory;
        private final Map<String, Path> published = new LinkedHashMap<>();
        private final List<String> resolved = new ArrayList<>();
        private final List<String> fetched = new ArrayList<>();

        private FakeSource(Path archiveDirectory) {
            this.archiveDirectory = archiveDirectory;
        }

        @Override
        public PackageInfo resolve(String packageId, String requestedVersion) {
            String version = requestedVersion == null || requestedVersion.isBlank()
                    ? latestPublishedVersion(packageId)
                    : requestedVersion.trim();
            String key = packageId + "#" + version;
            if (!published.containsKey(key)) {
                throw new PackageRegistryService.PackageRegistryException(
                        "Package '" + key + "' is not published.", null);
            }
            resolved.add(key);
            return new PackageInfo(packageId, version, packageId, "Test package",
                    "4.0.1", "http://example.org/" + packageId,
                    archiveDirectory.resolve(packageId + "-" + version + ".tgz")
                            .toUri().toString(),
                    packageId);
        }

        @Override
        public Path fetch(PackageInfo packageInfo, Path destination) {
            Path source = published.get(packageInfo.getKey());
            fetched.add(packageInfo.getKey());
            try {
                Files.createDirectories(destination);
                Path target = destination.resolve(packageInfo.getPackageId() + "-"
                        + packageInfo.getVersion() + ".tgz");
                if (!Files.isRegularFile(target)) {
                    Files.copy(source, target);
                }
                return target;
            } catch (IOException e) {
                throw new PackageRegistryService.PackageRegistryException(
                        "Cannot store " + packageInfo.getKey() + ": " + e.getMessage(), e);
            }
        }

        /** The most recently published version stands in for "best available". */
        private String latestPublishedVersion(String packageId) {
            String latest = null;
            String prefix = packageId + "#";
            for (String key : published.keySet()) {
                if (key.startsWith(prefix)) {
                    latest = key.substring(prefix.length());
                }
            }
            if (latest == null) {
                throw new PackageRegistryService.PackageRegistryException(
                        "Package '" + packageId + "' is not published.", null);
            }
            return latest;
        }
    }

    /** A package.json with the dependency object shape a real IG uses. */
    private static String packageJson(String packageId, String version,
            Map<String, String> dependencies) {
        StringBuilder declared = new StringBuilder();
        for (Map.Entry<String, String> dependency : dependencies.entrySet()) {
            if (declared.length() > 0) {
                declared.append(", ");
            }
            declared.append('"').append(dependency.getKey())
                    .append("\": \"").append(dependency.getValue()).append('"');
        }
        return "{\"name\": \"" + packageId + "\", \"version\": \"" + version + "\","
                + "\"fhirVersions\": [\"4.0.1\"],"
                + "\"canonical\": \"http://example.org/" + packageId + "\","
                + "\"dependencies\": {" + declared + "}}";
    }
}

