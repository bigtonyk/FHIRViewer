package com.example.fhirviewer.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.ByteProvider;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.model.JsonProperty;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.npm.NpmPackage.NpmPackageFolder;

import com.example.fhirviewer.model.IgPackageInfo;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;

/**
 * Manages loading and unloading of FHIR NPM packages for validation.
 */
public class IgPackageManager {
    private static final Logger logger = Logger.getLogger(IgPackageManager.class.getName());
    private final FhirContext context;
    private final List<IgPackageInfo> loadedPackages;
    private final Set<String> unmetDependencies = new LinkedHashSet<>();
    private NpmPackageValidationSupport npmPackageValidationSupport;
    /** Bumped whenever the loaded package set changes; used to invalidate caches. */
    private volatile long revision;

    /** Creates a package manager without a FhirContext. */
    public IgPackageManager() {
        this(null);
    }

    public IgPackageManager(FhirContext context) {
        this.context = context;
        this.loadedPackages = new ArrayList<>();
    }

    public IgPackageInfo loadPackageFromFile(Path packagePath) throws IOException {
        if (!Files.exists(packagePath)) {
            throw new IOException("Package file not found: " + packagePath);
        }
        Loaded loaded;
        try (InputStream is = Files.newInputStream(packagePath)) {
            loaded = loadFromStream(is);
        }
        registerPackage(loaded);
        resolveDependencies(loaded.dependencies(), packagePath.getParent());
        return loaded.info();
    }

    public IgPackageInfo loadPackageFromClasspath(String resourcePath) throws IOException {
        Loaded loaded;
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            loaded = loadFromStream(is);
        }
        registerPackage(loaded);
        resolveDependencies(loaded.dependencies(), null);
        return loaded.info();
    }

    private Loaded loadFromStream(InputStream is) throws IOException {
        if (context == null) {
            throw new IOException("Cannot load package: no FhirContext configured");
        }
        if (npmPackageValidationSupport == null) {
            npmPackageValidationSupport = new NpmPackageValidationSupport(context);
        }
        NpmPackage npm = NpmPackage.fromPackage(is);
        if (npm.getFolders().containsKey("package")) {
            loadResourcesFromPackage(npm);
            loadBinariesFromPackage(npm);
        }
        return new Loaded(toPackageInfo(npm), dependencyKeys(npm));
    }

    /** A parsed package plus the dependency keys its package.json declares. */
    private record Loaded(IgPackageInfo info, List<String> dependencies) {
    }

    public boolean unloadPackage(String packageId) {
        boolean removed = loadedPackages.removeIf(pkg ->
                (pkg.name() + "#" + pkg.version()).equals(packageId) ||
                pkg.canonicalUrl().equals(packageId));
        if (removed) {
            revision++;
        }
        return removed;
    }

    public int getLoadedPackageCount() {
        return loadedPackages.size();
    }

    public List<IgPackageInfo> getLoadedPackages() {
        return new ArrayList<>(loadedPackages);
    }

    public NpmPackageValidationSupport getNpmPackageValidationSupport() {
        return npmPackageValidationSupport;
    }

    public boolean hasLoadedPackages() {
        return !loadedPackages.isEmpty();
    }

    public void clearAllPackages() {
        loadedPackages.clear();
        unmetDependencies.clear();
        npmPackageValidationSupport = null;
        revision++;
    }

    /** Increments whenever the loaded package set changes; used to invalidate caches. */
    public long getRevision() {
        return revision;
    }

    /** Declared dependencies that are neither loaded, core-provided, nor found on disk. */
    public List<String> getUnmetDependencies() {
        return List.copyOf(unmetDependencies);
    }

    /**
     * Loads every .tgz file in the given directory (non-recursive, in file-name
     * order). Files that fail to load are skipped and logged; a missing
     * directory is not an error and loads nothing.
     *
     * @return the number of packages loaded
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
        int loaded = 0;
        for (Path file : tgzFiles) {
            try {
                loadPackageFromFile(file);
                loaded++;
            } catch (IOException | RuntimeException e) {
                logger.warning("Skipping package " + file + ": " + e.getMessage());
            }
        }
        return loaded;
    }

    /** Adds a parsed package, ignoring an identical load and replacing older versions. */
    private void registerPackage(Loaded loaded) {
        IgPackageInfo info = loaded.info();
        IgPackageInfo existing = loadedPackages.stream()
                .filter(pkg -> pkg.name().equals(info.name()))
                .findFirst()
                .orElse(null);
        if (existing != null && existing.version().equals(info.version())) {
            return; // already loaded
        }
        if (existing != null) {
            loadedPackages.remove(existing);
        }
        loadedPackages.add(info);
        revision++;
        unmetDependencies.removeIf(key -> key.split("#", 2)[0].equals(info.name()));
        logger.info("Loaded IG package: " + info.name() + " " + info.version());
    }

    /** Dependency keys ("name" or "name#version") declared by package.json. */
    private static List<String> dependencyKeys(NpmPackage npm) {
        JsonObject packageJson = npm.getNpm();
        if (packageJson == null || !packageJson.hasObject("dependencies")) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (JsonProperty property : packageJson.getJsonObject("dependencies").getProperties()) {
            keys.add(property.getName());
        }
        return keys;
    }

    /**
     * Makes dependency ValueSets and CodeSystems available by loading
     * dependency packages found next to the package being loaded. Core
     * specification dependencies are provided by DefaultProfileValidationSupport;
     * anything else that cannot be found is recorded as unmet.
     */
    private void resolveDependencies(List<String> dependencyKeys, Path searchDirectory) {
        for (String key : dependencyKeys) {
            String name = key;
            String version = null;
            int hash = key.indexOf('#');
            if (hash >= 0) {
                name = key.substring(0, hash).trim();
                version = key.substring(hash + 1).trim();
            }
            if (name.isEmpty() || isLoadedByName(name)) {
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
            if (isProvidedByCore(name)) {
                continue;
            }
            unmetDependencies.add(key);
        }
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
            // Fall through: dependency cannot be located here.
        }
        return null;
    }

    private boolean isLoadedByName(String name) {
        return loadedPackages.stream().anyMatch(pkg -> pkg.name().equals(name));
    }

    /** Dependencies that DefaultProfileValidationSupport already provides. */
    private static boolean isProvidedByCore(String name) {
        return name.equals("hl7.fhir.core")
                || name.matches("hl7\\.fhir\\.r\\d+b?\\.core")
                || name.startsWith("hl7.fhir.uv.extensions")
                || name.equals("hl7.terminology");
    }

    /** Parses all conformance resources in the package's "package" folder. */
    private void loadResourcesFromPackage(NpmPackage npm) {
        NpmPackageFolder folder = npm.getFolders().get("package");
        IParser parser = context.newJsonParser()
                .setParserErrorHandler(new LenientErrorHandler(false));
        for (String file : folder.listFiles()) {
            if (file.toLowerCase(Locale.US).endsWith(".json")) {
                String json = new String(folder.getContent().get(file), StandardCharsets.UTF_8);
                IBaseResource resource = parser.parseResource(json);
                npmPackageValidationSupport.addResource(resource);
            }
        }
    }

    /** Loads binary files from the package's "other" folder. */
    private void loadBinariesFromPackage(NpmPackage npm) throws IOException {
        for (String file : npm.list("other")) {
            byte[] bytes = ByteProvider.forStream(npm.load("other", file)).getBytes();
            npmPackageValidationSupport.addBinary(bytes, file);
        }
    }

    private IgPackageInfo toPackageInfo(NpmPackage npm) {
        String name = safe(npm.name());
        String version = safe(npm.version());
        String fhirVersion = safe(npm.fhirVersion());
        String description = safe(npm.description());
        String canonicalBase = safe(npm.canonical());
        if (canonicalBase.isEmpty()) {
            canonicalBase = name;
        }
        String canonical = version.isEmpty() ? canonicalBase : canonicalBase + "/" + version;
        return new IgPackageInfo(name, version, fhirVersion, canonical, description);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
