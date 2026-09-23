package com.example.fhirviewer.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.ByteProvider;
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
    private NpmPackageValidationSupport npmPackageValidationSupport;

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
        IgPackageInfo packageInfo;
        try (InputStream is = Files.newInputStream(packagePath)) {
            packageInfo = loadFromStream(is);
        }
        loadedPackages.add(packageInfo);
        logger.info("Loaded IG package: " + packageInfo.name() + " " + packageInfo.version());
        return packageInfo;
    }

    public IgPackageInfo loadPackageFromClasspath(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            IgPackageInfo packageInfo = loadFromStream(is);
            loadedPackages.add(packageInfo);
            logger.info("Loaded IG package: " + packageInfo.name() + " " + packageInfo.version());
            return packageInfo;
        }
    }

    private IgPackageInfo loadFromStream(InputStream is) throws IOException {
        if (context == null) {
            throw new IOException("Cannot load package: no FhirContext configured");
        }
        if (npmPackageValidationSupport == null) {
            npmPackageValidationSupport = new NpmPackageValidationSupport(context);
        }
        NpmPackage npm = NpmPackage.fromPackage(is);
        IgPackageInfo packageInfo = toPackageInfo(npm);
        if (npm.getFolders().containsKey("package")) {
            loadResourcesFromPackage(npm);
            loadBinariesFromPackage(npm);
        }
        return packageInfo;
    }

    public boolean unloadPackage(String packageId) {
        return loadedPackages.removeIf(pkg ->
                (pkg.name() + "#" + pkg.version()).equals(packageId) ||
                pkg.canonicalUrl().equals(packageId));
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
        npmPackageValidationSupport = null;
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
