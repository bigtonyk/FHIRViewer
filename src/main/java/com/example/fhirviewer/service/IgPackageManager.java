// Copyright (c) 2026 FHIRViewer contributors. SPDX-License-Identifier: GPL-3.0-only.
package com.example.fhirviewer.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.ByteProvider;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.npm.NpmPackage.NpmPackageFolder;

import com.example.fhirviewer.model.IgPackageInfo;
import com.example.fhirviewer.model.ValidationProfile;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;

/**
 * Manages installed FHIR NPM packages: parsing them, deciding which are active
 * for validation, and exposing their conformance resources through the
 * validation support chain and the canonical URL index.
 *
 * <p>An installed package can be deactivated without being removed: only active
 * packages participate in automatic profile validation (Update 7).</p>
 */
public class IgPackageManager {

    private static final Logger logger = Logger.getLogger(IgPackageManager.class.getName());

    /** The FHIR major version this build validates. */
    private static final String SUPPORTED_VERSION_PREFIX = "4.0";

    private final FhirContext context;
    /** Installed packages by key ("name#version"), in load order. */
    private final Map<String, PackageContent> packages = new LinkedHashMap<>();
    private final Set<String> inactivePackages = new LinkedHashSet<>();
    private final Set<String> unmetDependencies = new LinkedHashSet<>();
    private final PackageResourceIndex resourceIndex = new PackageResourceIndex();
    private NpmPackageValidationSupport npmPackageValidationSupport;
    /** Bumped whenever the active package set changes; used to invalidate caches. */
    private volatile long revision;

    /** Creates a package manager without a FhirContext. */
    public IgPackageManager() {
        this(null);
    }

    public IgPackageManager(FhirContext context) {
        this.context = context;
    }

    /** A binary file carried in a package's "other" folder. */
    private record BinaryFile(String name, byte[] bytes) {
    }

    /** A parsed package: metadata, resources the validator can use, and its binaries. */
    private static final class PackageContent {
        private final IgPackageInfo info;
        private final List<IBaseResource> resources = new ArrayList<>();
        private final List<BinaryFile> binaries = new ArrayList<>();
        private final List<String> dependencies;

        PackageContent(IgPackageInfo info, List<String> dependencies) {
            this.info = info;
            this.dependencies = dependencies == null ? List.of() : dependencies;
        }

        String key() {
            return keyOf(info.name(), info.version());
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /** Installs and activates a package from a .tgz file. */
    public IgPackageInfo loadPackageFromFile(Path packagePath) throws IOException {
        NpmPackage npm = NpmPackageReader.read(packagePath);
        PackageContent content = parse(npm);
        register(content);
        resolveDependencies(content.dependencies, packagePath.getParent());
        return content.info;
    }

    /** Installs and activates a package bundled on the classpath. */
    public IgPackageInfo loadPackageFromClasspath(String resourcePath) throws IOException {
        InputStream in = getClass().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IgPackageException(IgPackageException.Kind.UNREADABLE,
                    "Package resource not found: " + resourcePath);
        }
        PackageContent content;
        try (InputStream stream = in) {
            content = parse(NpmPackageReader.read(stream));
        }
        register(content);
        resolveDependencies(content.dependencies, null);
        return content.info;
    }

    /** Parses a package into FHIR model resources, rejecting unsupported FHIR versions. */
    private PackageContent parse(NpmPackage npm) throws IOException {
        if (context == null) {
            throw new IgPackageException(IgPackageException.Kind.CORRUPT,
                    "Cannot load packages: no FHIR validation context is configured.");
        }
        NpmPackageReader.PackageMetadata metadata = NpmPackageReader.metadataOf(npm);
        requireSupportedFhirVersion(metadata);
        PackageContent content = new PackageContent(toPackageInfo(metadata), metadata.dependencies());
        parseResources(npm, content.resources);
        parseBinaries(npm, content.binaries);
        return content;
    }

    /**
     * Refuses to install a package that targets a different FHIR version, so an
     * R5-only package can never enter the R4 validation context (Update 16).
     */
    private static void requireSupportedFhirVersion(NpmPackageReader.PackageMetadata metadata)
            throws IgPackageException {
        String declared = metadata.fhirVersion();
        if (declared == null || declared.isBlank()) {
            return; // Unknown: the validator reports anything it cannot use.
        }
        String normalised = declared.trim().toLowerCase(Locale.ROOT);
        if (normalised.startsWith(SUPPORTED_VERSION_PREFIX) || normalised.equals("r4")) {
            return;
        }
        String friendly = normalised.startsWith("4.3") ? "R4B" : declared;
        throw new IgPackageException(IgPackageException.Kind.UNSUPPORTED_FHIR_VERSION,
                "Package '" + metadata.name() + "' targets FHIR " + friendly
                        + "; this build validates FHIR R4 packages only.");
    }
    // ------------------------------------------------------------------
    // Installed / active state (Update 7)
    // ------------------------------------------------------------------

    /** Adds a parsed package, ignoring an identical load and replacing older versions. */
    private void register(PackageContent content) {
        String name = content.info.name();
        PackageContent existing = packages.get(content.key());
        if (existing != null) {
            return; // already installed with this exact version
        }
        // Only one version of a package id is active at a time.
        packages.entrySet().removeIf(entry -> entry.getValue().info.name().equals(name));
        packages.put(content.key(), content);
        inactivePackages.remove(content.key());
        unmetDependencies.removeIf(key -> packageNameOf(key).equals(name));
        rebuildSupport();
        logger.info("Installed IG package: " + content.info.name() + " " + content.info.version());
    }

    /** Removes a package entirely (installed and no longer active). */
    public boolean unloadPackage(String packageId) {
        String target = packageId == null ? "" : packageId.trim();
        boolean removed = packages.entrySet().removeIf(entry ->
                entry.getKey().equals(target)
                        || entry.getValue().info.name().equals(target)
                        || entry.getValue().info.canonicalUrl().equals(target));
        if (removed) {
            inactivePackages.remove(target);
            rebuildSupport();
        }
        return removed;
    }

    /** Activates or deactivates an installed package ("name", "name#version" or canonical). */
    public boolean setActive(String packageId, boolean active) {
        PackageContent content = find(packageId);
        if (content == null) {
            return false;
        }
        boolean changed = active
                ? inactivePackages.remove(content.key())
                : inactivePackages.add(content.key());
        if (changed) {
            rebuildSupport();
        }
        return changed;
    }

    /** True when the package participates in validation. */
    public boolean isActive(String packageId) {
        PackageContent content = find(packageId);
        return content != null && !inactivePackages.contains(content.key());
    }

    /** Every installed package, in install order. */
    public List<IgPackageInfo> getLoadedPackages() {
        return packages.values().stream().map(content -> content.info).toList();
    }

    /** Installed packages that participate in validation. */
    public List<IgPackageInfo> getActivePackages() {
        return packages.values().stream()
                .filter(content -> !inactivePackages.contains(content.key()))
                .map(content -> content.info)
                .toList();
    }

    /** The installed package with this id, or null when it is not installed. */
    public IgPackageInfo getPackage(String packageId) {
        PackageContent content = find(packageId);
        return content == null ? null : content.info;
    }

    public int getLoadedPackageCount() {
        return packages.size();
    }

    public boolean hasLoadedPackages() {
        return !packages.isEmpty();
    }

    public NpmPackageValidationSupport getNpmPackageValidationSupport() {
        return npmPackageValidationSupport;
    }

    /** Removes every installed package. */
    public void clearAllPackages() {
        packages.clear();
        inactivePackages.clear();
        unmetDependencies.clear();
        rebuildSupport();
    }

    /** Increments whenever the active package set changes; used to invalidate caches. */
    public long getRevision() {
        return revision;
    }

    /** Declared dependencies that are neither loaded, core-provided, nor found on disk. */
    public List<String> getUnmetDependencies() {
        return List.copyOf(unmetDependencies);
    }

    /** Canonical URL index over the conformance resources of the active packages. */
    public PackageResourceIndex getResourceIndex() {
        return resourceIndex;
    }

    /** Describes the profile canonical URL for validation results, or null when unknown. */
    public ValidationProfile describeProfile(String canonical) {
        return resourceIndex.describe(canonical);
    }

    /** Installed profiles that constrain the given resource type (Update 12). */
    public List<PackageResourceIndex.IndexedResource> profilesFor(String resourceType) {
        return resourceIndex.profilesFor(resourceType);
    }
    // ------------------------------------------------------------------
    // Support chain and index rebuilding
    // ------------------------------------------------------------------

    /**
     * Rebuilds the validation support and the canonical index from the active
     * packages. Called whenever the installed/active set changes so a
     * deactivated package stops resolving immediately.
     */
    private void rebuildSupport() {
        resourceIndex.clear();
        boolean anyActive = packages.values().stream()
                .anyMatch(content -> !inactivePackages.contains(content.key()));
        NpmPackageValidationSupport support = context == null || !anyActive
                ? null
                : new NpmPackageValidationSupport(context);
        for (PackageContent content : packages.values()) {
            if (inactivePackages.contains(content.key())) {
                continue;
            }
            if (support != null) {
                for (IBaseResource resource : content.resources) {
                    support.addResource(resource);
                }
                for (BinaryFile binary : content.binaries) {
                    support.addBinary(binary.bytes(), binary.name());
                }
            }
            resourceIndex.addPackage(content.info.name(), content.info.version(),
                    content.resources);
        }
        npmPackageValidationSupport = support;
        revision++;
    }

    /** The installed package matching an id, "name#version" key or canonical URL. */
    private PackageContent find(String packageId) {
        String target = packageId == null ? "" : packageId.trim();
        if (target.isEmpty()) {
            return null;
        }
        PackageContent direct = packages.get(target);
        if (direct != null) {
            return direct;
        }
        return packages.values().stream()
                .filter(content -> content.info.name().equals(target)
                        || content.info.canonicalUrl().equals(target))
                .findFirst()
                .orElse(null);
    }

    private static String keyOf(String name, String version) {
        return name + "#" + version;
    }

    private static String packageNameOf(String dependencyKey) {
        int hash = dependencyKey.indexOf('#');
        return hash < 0 ? dependencyKey : dependencyKey.substring(0, hash);
    }
    // ------------------------------------------------------------------
    // Installing from storage and dependency resolution
    // ------------------------------------------------------------------

    /**
     * Installs every .tgz file in the given directory (non-recursive, in
     * file-name order). Files that fail to install are skipped and logged; a
     * missing directory is not an error and installs nothing.
     *
     * @return the number of packages installed
     */
    public int loadAllFrom(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return 0;
        }
        List<Path> tgzFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.tgz")) {
            stream.forEach(tgzFiles::add);
        } catch (IOException e) {
            logger.warning("Cannot list package directory " + directory + ": " + e.getMessage());
            return 0;
        }
        tgzFiles.sort(Comparator.comparing(Path::getFileName));
        int installed = 0;
        for (Path file : tgzFiles) {
            try {
                loadPackageFromFile(file);
                installed++;
            } catch (IOException | RuntimeException e) {
                logger.warning("Skipping package " + file + ": " + e.getMessage());
            }
        }
        return installed;
    }

    /** True when the dependency key is satisfied by the core specification. */
    public static boolean isCoreProvided(String dependencyName) {
        if (dependencyName == null) {
            return false;
        }
        return dependencyName.equals("hl7.fhir.core")
                || dependencyName.matches("hl7\\.fhir\\.r\\d+b?\\.core");
    }

    /**
     * Loads dependency packages found next to the package being installed.
     * Core specification dependencies are provided by
     * DefaultProfileValidationSupport; anything else that cannot be found is
     * recorded as unmet and reported to the user.
     */
    private void resolveDependencies(List<String> dependencyKeys, Path searchDirectory) {
        for (String key : dependencyKeys) {
            String name = packageNameOf(key);
            String version = dependencyVersionOf(key);
            if (name.isEmpty() || isInstalledByName(name) || isCoreProvided(name)) {
                continue;
            }
            Path file = searchDirectory == null
                    ? null
                    : findDependencyFile(searchDirectory, name, version);
            if (file != null) {
                try {
                    loadPackageFromFile(file);
                } catch (IOException | RuntimeException e) {
                    logger.warning("Cannot load dependency " + key + ": " + e.getMessage());
                    unmetDependencies.add(key);
                }
                continue;
            }
            unmetDependencies.add(key);
        }
    }

    private static String dependencyVersionOf(String dependencyKey) {
        int hash = dependencyKey.indexOf('#');
        return hash < 0 ? null : dependencyKey.substring(hash + 1).trim();
    }

    private static Path findDependencyFile(Path directory, String name, String version) {
        if (version != null && !version.isEmpty()) {
            Path exact = directory.resolve(name + "-" + version + ".tgz");
            if (Files.isRegularFile(exact)) {
                return exact;
            }
        }
        Path plain = directory.resolve(name + ".tgz");
        if (Files.isRegularFile(plain)) {
            return plain;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, name + "-*.tgz")) {
            for (Path candidate : stream) {
                return candidate;
            }
        } catch (IOException e) {
            // Fall through: the dependency cannot be located here.
        }
        return null;
    }

    private boolean isInstalledByName(String name) {
        return packages.values().stream().anyMatch(content -> content.info.name().equals(name));
    }
    // ------------------------------------------------------------------
    // Archive contents
    // ------------------------------------------------------------------

    /** Parses every JSON conformance resource in the package's "package" folder. */
    private void parseResources(NpmPackage npm, List<IBaseResource> target) {
        NpmPackageFolder folder = npm.getFolders().get("package");
        if (folder == null) {
            return;
        }
        IParser parser = context.newJsonParser()
                .setParserErrorHandler(new LenientErrorHandler(false));
        for (String file : folder.listFiles()) {
            if (!file.toLowerCase(Locale.US).endsWith(".json")) {
                continue;
            }
            String json = new String(folder.getContent().get(file), StandardCharsets.UTF_8);
            try {
                target.add(parser.parseResource(json));
            } catch (RuntimeException e) {
                logger.fine("Skipping unreadable package entry " + file + ": " + e.getMessage());
            }
        }
    }

    /** Loads binary files from the package's "other" folder. */
    private void parseBinaries(NpmPackage npm, List<BinaryFile> target) throws IOException {
        for (String file : npm.list("other")) {
            byte[] bytes = ByteProvider.forStream(npm.load("other", file)).getBytes();
            target.add(new BinaryFile(file, bytes));
        }
    }

    private static IgPackageInfo toPackageInfo(NpmPackageReader.PackageMetadata metadata) {
        String canonicalBase = metadata.canonical().isEmpty()
                ? metadata.name()
                : metadata.canonical();
        String canonical = metadata.version().isEmpty()
                ? canonicalBase
                : canonicalBase + "/" + metadata.version();
        return new IgPackageInfo(metadata.name(), metadata.version(), metadata.fhirVersion(),
                canonical, metadata.description());
    }
}
