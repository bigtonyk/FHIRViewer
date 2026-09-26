// Copyright (c) 2026 FHIRViewer contributors. SPDX-License-Identifier: GPL-3.0-only.
package com.example.fhirviewer.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.example.fhirviewer.model.IgPackageInfo;
import com.example.fhirviewer.service.PackageRegistryService.PackageInfo;

/**
 * Turns "install hl7.fhir.us.core" into a complete, dependency-resolved
 * installation plan and then installs it (Update 5).
 *
 * <p>Dependencies are discovered from each package's own package.json, so the
 * closure is real rather than catalogue-based: core specification dependencies
 * are reported as provided by the validator, packages already installed are
 * reused, and everything else is downloaded with its declared version (or the
 * best R4-compatible version when a dependency does not pin one).</p>
 *
 * <p>The UI shows the plan before installing, so the user sees exactly which
 * packages will be added.</p>
 */
public class PackageInstaller {

    private static final Logger logger = Logger.getLogger(PackageInstaller.class.getName());

    /** The FHIR version this build validates; dependency versions prefer it. */
    public static final String TARGET_FHIR_VERSION = "4.0";

    /** Where package versions come from: the registry in production, a fake in tests. */
    public interface PackageSource {

        /** Resolves the exact version to install. */
        PackageInfo resolve(String packageId, String requestedVersion);

        /** Downloads (or reuses) the package file in the destination directory. */
        Path fetch(PackageInfo packageInfo, Path destination);
    }

    /** What will happen to one entry of an install plan. */
    public enum Status {
        /** The package will be downloaded and installed. */
        TO_INSTALL("will install"),
        /** The same package id and version is already installed. */
        ALREADY_INSTALLED("already installed"),
        /** Provided by the FHIR core specification, not a package to install. */
        CORE_PROVIDED("provided by core");

        private final String displayName;

        Status(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * One entry of an install plan.
     *
     * @param packageId   the FHIR package id
     * @param version     the resolved version, or blank when unknown
     * @param fhirVersion the FHIR version the package targets
     * @param status      what will happen to this package
     * @param requiredBy  the package id that depends on it, blank for the root
     * @param file        the downloaded file, null when nothing is downloaded
     */
    public record PlannedPackage(String packageId, String version, String fhirVersion,
            Status status, String requiredBy, Path file) {

        /** For example {@code hl7.fhir.r4.core (provided by core)}. */
        public String describe() {
            StringBuilder sb = new StringBuilder(packageId);
            if (version != null && !version.isBlank()) {
                sb.append(' ').append(version);
            }
            sb.append(" (").append(status.getDisplayName());
            if (requiredBy != null && !requiredBy.isBlank()) {
                sb.append(", required by ").append(requiredBy);
            }
            sb.append(')');
            return sb.toString();
        }
    }

    /** A dependency-resolved installation plan. */
    public record InstallPlan(String rootPackageId, String rootVersion,
            List<PlannedPackage> packages) {

        public List<PlannedPackage> toInstall() {
            return packages.stream()
                    .filter(pkg -> pkg.status() == Status.TO_INSTALL)
                    .toList();
        }

        public List<PlannedPackage> dependencies() {
            return packages.stream()
                    .filter(pkg -> pkg.requiredBy() != null && !pkg.requiredBy().isBlank())
                    .toList();
        }

        /** The installation summary shown to the user before installing. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("Install:\n\n").append(rootPackageId);
            if (rootVersion != null && !rootVersion.isBlank()) {
                sb.append("\n").append(rootVersion);
            }
            List<PlannedPackage> deps = dependencies();
            if (deps.isEmpty()) {
                sb.append("\n\nNo additional dependencies are required.");
                return sb.toString();
            }
            sb.append("\n\nDependencies:");
            for (PlannedPackage dependency : deps) {
                sb.append("\n- ").append(dependency.packageId());
                if (dependency.version() != null && !dependency.version().isBlank()) {
                    sb.append(' ').append(dependency.version());
                }
                sb.append(" (").append(dependency.status().getDisplayName()).append(')');
            }
            return sb.toString();
        }
    }
    /** The outcome of installing a plan. */
    public record InstallResult(List<String> installed, List<String> failures) {

        public boolean isSuccess() {
            return failures.isEmpty();
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (installed.isEmpty()) {
                sb.append("No packages needed installing.");
            } else {
                sb.append("Installed: ").append(String.join(", ", installed));
            }
            if (!failures.isEmpty()) {
                sb.append(". Could not install: ").append(String.join("; ", failures));
            }
            return sb.toString();
        }
    }

    private final PackageSource source;
    private final IgPackageManager packageManager;
    private final Path storageDirectory;

    public PackageInstaller(PackageSource source, IgPackageManager packageManager,
            Path storageDirectory) {
        this.source = source;
        this.packageManager = packageManager;
        this.storageDirectory = storageDirectory;
    }

    /** An installer backed by the live FHIR package registry. */
    public static PackageInstaller usingRegistry(PackageRegistryService registry,
            IgPackageManager packageManager, Path storageDirectory) {
        return new PackageInstaller(new PackageSource() {
            @Override
            public PackageInfo resolve(String packageId, String requestedVersion) {
                return registry.resolveVersion(packageId, requestedVersion, TARGET_FHIR_VERSION);
            }

            @Override
            public Path fetch(PackageInfo packageInfo, Path destination) {
                return registry.downloadPackage(packageInfo, destination);
            }
        }, packageManager, storageDirectory);
    }

    // ------------------------------------------------------------------
    // Planning
    // ------------------------------------------------------------------

    /**
     * Resolves the package plus its full dependency closure without installing
     * anything the user has not seen yet. Package files needed for inspection
     * (and later installation) are placed in the storage directory.
     *
     * @throws PackageRegistryException when a package or version does not exist
     */
    public InstallPlan plan(String packageId, String requestedVersion) {
        Map<String, PlannedPackage> planned = new LinkedHashMap<>();
        List<String[]> queue = new ArrayList<>();
        queue.add(new String[] { packageId, requestedVersion, "" });
        while (!queue.isEmpty()) {
            String[] request = queue.remove(0);
            String id = request[0];
            String version = request[1];
            String requiredBy = request[2];
            if (planned.containsKey(id)) {
                continue;
            }
            if (IgPackageManager.isCoreProvided(id)) {
                planned.put(id, new PlannedPackage(id, version == null ? "" : version,
                        "", Status.CORE_PROVIDED, requiredBy, null));
                continue;
            }
            if (packageManager != null && isInstalledVersion(packageManager.getPackage(id), version)) {
                var installed = packageManager.getPackage(id);
                planned.put(id, new PlannedPackage(id, installed.version(),
                        installed.fhirVersion(), Status.ALREADY_INSTALLED, requiredBy, null));
                continue;
            }
            PackageInfo info = source.resolve(id, version);
            Path file = source.fetch(info, storageDirectory);
            planned.put(id, new PlannedPackage(info.getPackageId(), info.getVersion(),
                    info.getFhirVersion(), Status.TO_INSTALL, requiredBy, file));
            for (String dependencyKey : dependenciesOf(file)) {
                int hash = dependencyKey.indexOf('#');
                String dependencyId = hash < 0
                        ? dependencyKey
                        : dependencyKey.substring(0, hash).trim();
                String dependencyVersion = hash < 0
                        ? ""
                        : dependencyKey.substring(hash + 1).trim();
                if (!dependencyId.isEmpty() && !planned.containsKey(dependencyId)) {
                    queue.add(new String[] { dependencyId, dependencyVersion, id });
                }
            }
        }
        List<PlannedPackage> packages = new ArrayList<>(planned.values());
        String rootVersion = packages.isEmpty() ? "" : packages.get(0).version();
        return new InstallPlan(packageId, rootVersion, packages);
    }

    /** Declared dependencies of a downloaded package file. */
    private static List<String> dependenciesOf(Path packageFile) {
        try {
            return NpmPackageReader.readMetadata(packageFile).dependencies();
        } catch (IOException e) {
            logger.warning("Cannot read dependencies of " + packageFile + ": " + e.getMessage());
            return List.of();
        }
    }

    /** True when the installed package satisfies the requested version. */
    private static boolean isInstalledVersion(IgPackageInfo installed, String requestedVersion) {
        if (installed == null) {
            return false;
        }
        return requestedVersion == null || requestedVersion.isBlank()
                || requestedVersion.trim().equals(installed.version());
    }

    // ------------------------------------------------------------------
    // Installing
    // ------------------------------------------------------------------

    /**
     * Installs every package the plan marks as {@code TO_INSTALL} and activates
     * it. A package that cannot be read is reported instead of aborting the
     * whole installation.
     */
    public InstallResult install(InstallPlan plan) {
        List<String> installed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (PlannedPackage planned : plan.toInstall()) {
            String label = planned.packageId() + " " + planned.version();
            try {
                packageManager.loadPackageFromFile(planned.file());
                installed.add(label);
                logger.info("Installed " + label);
            } catch (IOException | RuntimeException e) {
                failures.add(label + ": " + e.getMessage());
                logger.warning("Could not install " + label + ": " + e.getMessage());
            }
        }
        for (String unmet : packageManager.getUnmetDependencies()) {
            failures.add("missing dependency " + unmet);
        }
        return new InstallResult(installed, failures);
    }
}
