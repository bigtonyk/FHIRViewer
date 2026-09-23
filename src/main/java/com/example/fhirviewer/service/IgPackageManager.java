package com.example.fhirviewer.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;

import com.example.fhirviewer.fhir.FhirContextFactory;
import com.example.fhirviewer.model.IgPackageInfo;
import com.example.fhirviewer.model.ValidationIssue;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.instance.model.api.IBase;

/**
 * Manages loaded FHIR NPM Implementation Guide packages.
 */
public final class IgPackageManager {

    private final FhirContext context;
    private final Map<String, IgPackageInfo> loadedPackages;
    private NpmPackageValidationSupport npmSupport;

    public IgPackageManager() {
        this(FhirContextFactory.r4());
    }

    public IgPackageManager(FhirContext context) {
        this.context = context;
        this.loadedPackages = new ConcurrentHashMap<>();
    }

    public IgPackageInfo loadPackageFromClasspath(String classpath) {
        try {
            if (npmSupport == null) {
                npmSupport = new NpmPackageValidationSupport(context);
            }
            npmSupport.loadPackageFromClasspath(classpath);
            IgPackageInfo info = parsePackageInfo(classpath);
            trackPackage(info);
            return info;
        } catch (Exception e) {
            throw new IgPackageException("Failed to load package: " + classpath, e);
        }
    }

    public List<IgPackageInfo> getLoadedPackages() {
        return List.copyOf(loadedPackages.values());
    }

    public int getLoadedPackageCount() {
        return loadedPackages.size();
    }

    public boolean hasLoadedPackages() {
        return !loadedPackages.isEmpty();
    }

    public boolean unloadPackage(String canonicalUrl) {
        if (canonicalUrl == null) return false;
        return loadedPackages.values().removeIf(pkg ->
                pkg.canonicalUrl().equals(canonicalUrl));
    }

    public void clearAllPackages() {
        loadedPackages.clear();
    }

    public boolean canResolveProfile(String profileUrl) {
        if (profileUrl == null || profileUrl.isBlank()) return false;
        if (npmSupport == null) return false;
        try {
            IBase profile = npmSupport.fetchStructureDefinition(profileUrl);
            return profile != null;
        } catch (Exception e) {
            return false;
        }
    }

    public ValidationIssue validateProfileResolution(String profileUrl, String location) {
        if (canResolveProfile(profileUrl)) {
            return null;
        }
        return ValidationIssue.profileResolutionFailure(
                ValidationIssue.Severity.WARNING,
                "Profile reference has not been checked because it could not be found: " + profileUrl,
                location, null, null);
    }

    public NpmPackageValidationSupport getNpmPackageValidationSupport() {
        return npmSupport;
    }

    private IgPackageInfo parsePackageInfo(String classpath) {
        String path = classpath;
        if (path.startsWith("classpath:")) path = path.substring(10);
        if (path.startsWith("/")) path = path.substring(1);
        int lastSlash = path.lastIndexOf('/');
        String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (filename.endsWith(".tgz")) filename = filename.substring(0, filename.length() - 4);
        int lastDash = filename.lastIndexOf('-');
        if (lastDash > 0) {
            return new IgPackageInfo(filename.substring(0, lastDash),
                    filename.substring(lastDash + 1), "R4", "", filename);
        }
        return new IgPackageInfo(filename, "1.0.0", "R4", "", filename);
    }

    private void trackPackage(IgPackageInfo info) {
        loadedPackages.put(info.canonicalUrl() + "/" + info.version(), info);
    }

    public static final class IgPackageException extends RuntimeException {
        public IgPackageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}