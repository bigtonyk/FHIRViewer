// Copyright (c) 2026 FHIRViewer contributors. SPDX-License-Identifier: GPL-3.0-only.
package com.example.fhirviewer.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Discovers and downloads FHIR NPM packages from the HL7 FHIR package registry.
 *
 * <p>Packages are identified by their official FHIR package id (Update 2), for
 * example {@code hl7.fhir.us.core}. The registry is npm-style: it serves one
 * packument per package id listing every published version and has no keyword
 * search endpoint, so keyword queries ("us core") are resolved through the
 * built-in {@link KnownPackages} catalogue and then looked up by id
 * (Update 3).</p>
 *
 * <p>Packuments are cached in memory for the session; {@link #clearCache()}
 * backs the "Refresh Package Catalog" operation (Update 19).</p>
 */
public final class PackageRegistryService {

    /** FHIR package registry root; packuments live at {@code <root>/<package id>}. */
    public static final String REGISTRY_URL = "https://packages.fhir.org";

    private static final Logger logger = Logger.getLogger(PackageRegistryService.class.getName());

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    /** Package id -> packument JSON, so one session does not refetch metadata. */
    private final Map<String, String> packumentCache = new ConcurrentHashMap<>();

    public PackageRegistryService() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Finds packages by package id, IG name, package name or description.
     *
     * <p>A query containing a '.' and no spaces is treated as a package id and
     * looked up directly. Otherwise the {@link KnownPackages} catalogue maps the
     * keywords to package ids (for example "us core" to
     * {@code hl7.fhir.us.core}). An optional {@code #version} suffix pins a
     * single version.</p>
     *
     * @return one result per matching version, newest first, or an empty list
     *         when no candidate package id is known to the registry
     */
    public List<PackageInfo> searchPackages(String query) {
        List<PackageInfo> results = new ArrayList<>();
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return results;
        }
        String versionFilter = versionFilterOf(trimmed);
        for (String packageId : candidateIds(trimmed)) {
            String packument = fetchPackument(packageId);
            if (packument == null) {
                continue; // unknown to the registry
            }
            for (PackageInfo info : parse(packument, versionFilter)) {
                results.add(withCatalogTitle(info));
            }
        }
        return results;
    }

    /**
     * The package ids a query could refer to: the query itself when it looks
     * like a package id, plus every catalogue entry matching its keywords.
     * Package-private for unit testing.
     */
    List<String> candidateIds(String query) {
        String name = stripVersionFilter(query == null ? "" : query.trim());
        List<String> ids = new ArrayList<>();
        if (name.isEmpty()) {
            return ids;
        }
        boolean looksLikePackageId = name.indexOf('.') >= 0 && name.indexOf(' ') < 0;
        if (looksLikePackageId) {
            ids.add(name);
        }
        for (KnownPackages.Entry entry : KnownPackages.search(name)) {
            if (!ids.contains(entry.id())) {
                ids.add(entry.id());
            }
        }
        return ids;
    }
    /** Every published version of a package id, newest first. */
    public List<String> getVersions(String packageId) {
        String packument = fetchPackument(stripVersionFilter(packageId));
        if (packument == null) {
            throw new PackageRegistryException("Package '" + packageId
                    + "' was not found in the FHIR package registry (" + REGISTRY_URL + ").", null);
        }
        List<String> result = new ArrayList<>();
        for (PackageInfo info : parse(packument, null)) {
            result.add(info.getVersion());
        }
        return result;
    }

    /** One exact package version. */
    public PackageInfo getPackage(String packageId, String version) {
        return resolveVersion(packageId, version, null);
    }

    /**
     * Resolves the package version to install.
     *
     * @param packageId         the FHIR package id
     * @param requestedVersion  the exact version, or blank for "best available"
     * @param targetFhirVersion the FHIR version to prefer, for example {@code 4.0.1}
     * @throws PackageRegistryException when the package or a requested version
     *                                  does not exist
     */
    public PackageInfo resolveVersion(String packageId, String requestedVersion,
            String targetFhirVersion) {
        String id = stripVersionFilter(packageId);
        String packument = fetchPackument(id);
        if (packument == null) {
            throw new PackageRegistryException("Package '" + id
                    + "' was not found in the FHIR package registry (" + REGISTRY_URL + ").", null);
        }
        List<PackageInfo> versions = parse(packument, null);
        if (versions.isEmpty()) {
            throw new PackageRegistryException(
                    "Package '" + id + "' has no published versions.", null);
        }
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            String wanted = requestedVersion.trim();
            for (PackageInfo info : versions) {
                if (info.getVersion().equals(wanted)) {
                    return info;
                }
            }
            throw new PackageRegistryException("Version '" + wanted + "' of package '" + id
                    + "' was not found. Available versions: " + versionList(versions), null);
        }
        if (targetFhirVersion != null && !targetFhirVersion.isBlank()) {
            for (PackageInfo info : versions) {
                if (isCompatible(info.getFhirVersion(), targetFhirVersion)) {
                    return info;
                }
            }
            logger.warning("No " + targetFhirVersion + " release of " + id
                    + " is published; using the newest version " + versions.get(0).getVersion());
        }
        return versions.get(0);
    }

    /** Forgets cached registry metadata so the next lookup refetches it. */
    public void clearCache() {
        packumentCache.clear();
    }

    /** Number of packuments currently cached. */
    public int getCachedPackageCount() {
        return packumentCache.size();
    }
    // ------------------------------------------------------------------
    // Registry access
    // ------------------------------------------------------------------

    /**
     * Fetches one packument, using the session cache.
     *
     * @return the packument JSON, or null when the registry does not know the
     *         package id
     * @throws PackageRegistryException when the registry cannot be reached
     */
    private String fetchPackument(String packageId) {
        String cached = packumentCache.get(packageId);
        if (cached != null) {
            return cached;
        }
        String url = REGISTRY_URL + "/"
                + java.net.URLEncoder.encode(packageId, StandardCharsets.UTF_8);
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PackageRegistryException("Cannot reach the FHIR package registry at "
                    + REGISTRY_URL + ". Check your network connection. (" + e.getMessage() + ")", e);
        }
        if (response.statusCode() == 404) {
            return null; // unknown package id
        }
        if (response.statusCode() != 200) {
            throw new PackageRegistryException("The FHIR package registry replied with HTTP "
                    + response.statusCode() + " for package '" + packageId + "'.", null);
        }
        packumentCache.put(packageId, response.body());
        return response.body();
    }

    /**
     * Parses an npm-style packument, reporting unreadable metadata as a
     * registry failure with a displayable message.
     */
    private List<PackageInfo> parse(String packumentJson, String versionFilter) {
        try {
            return parsePackument(packumentJson, versionFilter);
        } catch (IOException e) {
            throw new PackageRegistryException(
                    "The registry returned unreadable package metadata: " + e.getMessage(), e);
        }
    }

    /**
     * Parses an npm-style packument into one result per published version,
     * newest version first. Package-private for unit testing.
     */
    List<PackageInfo> parsePackument(String packumentJson, String versionFilter) throws IOException {
        List<PackageInfo> results = new ArrayList<>();
        JsonNode root = objectMapper.readTree(packumentJson);
        JsonNode versions = root.get("versions");
        if (versions == null || !versions.isObject()) {
            return results;
        }

        List<String> versionIds = new ArrayList<>();
        versions.fieldNames().forEachRemaining(versionIds::add);
        Collections.reverse(versionIds); // registries list oldest first

        String rootName = getString(root, "name");
        String rootDescription = getString(root, "description");

        for (String versionId : versionIds) {
            if (versionFilter != null && !versionFilter.isEmpty()
                    && !versionFilter.equals(versionId)) {
                continue;
            }
            JsonNode version = versions.get(versionId);
            String name = getString(version, "name");
            if (name.isEmpty()) {
                name = rootName;
            }
            String description = getString(version, "description");
            if (description.isEmpty() || "None.".equals(description)) {
                description = rootDescription;
            }
            results.add(new PackageInfo(
                    name,
                    versionId,
                    name, // title: packuments carry no separate title
                    description,
                    getString(version, "fhirVersion"),
                    "", // canonical is not published in the packument
                    version.path("dist").path("tarball").asText(""),
                    name));
        }
        return results;
    }

    /** Adds the catalogued IG name so the UI can show "US Core" for hl7.fhir.us.core. */
    private static PackageInfo withCatalogTitle(PackageInfo info) {
        for (KnownPackages.Entry entry : KnownPackages.search(info.getPackageId())) {
            if (entry.id().equalsIgnoreCase(info.getPackageId())) {
                return info.withTitle(entry.title());
            }
        }
        return info;
    }

    private static boolean isCompatible(String packageFhirVersion, String target) {
        if (packageFhirVersion == null || packageFhirVersion.isBlank()) {
            return false;
        }
        String declared = packageFhirVersion.trim().toLowerCase(java.util.Locale.ROOT);
        String wanted = target.trim().toLowerCase(java.util.Locale.ROOT);
        if (declared.equals(wanted) || declared.startsWith(wanted)) {
            return true;
        }
        // Packuments may say "R4" while the target is "4.0.1".
        String major = wanted.substring(0, Math.min(3, wanted.length()));
        return ("4.0".equals(major) && declared.equals("r4"));
    }

    private static String versionList(List<PackageInfo> versions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < versions.size() && i < 12; i++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(versions.get(i).getVersion());
        }
        return sb.toString();
    }

    private static String stripVersionFilter(String query) {
        String value = query == null ? "" : query.trim();
        int hash = value.indexOf('#');
        return hash < 0 ? value : value.substring(0, hash).trim();
    }

    private static String versionFilterOf(String query) {
        int hash = query.indexOf('#');
        if (hash < 0) {
            return null;
        }
        String version = query.substring(hash + 1).trim();
        return version.isEmpty() ? null : version;
    }

    private String getString(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return f != null && !f.isNull() ? f.asText() : "";
    }
    // ------------------------------------------------------------------
    // Download (Update 19: never download the same package twice)
    // ------------------------------------------------------------------

    /** True when the exact package version is already present in the directory. */
    public boolean isCached(Path destination, PackageInfo packageInfo) {
        Path file = destination == null || packageInfo == null
                ? null
                : destination.resolve(fileNameOf(packageInfo));
        try {
            return file != null && Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Downloads a package into a directory, reusing an existing file when the
     * same package version is already there.
     *
     * @return the downloaded (or reused) .tgz file
     * @throws PackageRegistryException when the download fails
     */
    public Path downloadPackage(PackageInfo packageInfo, Path destination) {
        if (packageInfo == null) {
            throw new PackageRegistryException("No package selected for download.", null);
        }
        if (packageInfo.getDownloadUrl() == null || packageInfo.getDownloadUrl().isBlank()) {
            throw new PackageRegistryException("The registry published no download location for "
                    + packageInfo.getPackageId() + " " + packageInfo.getVersion() + ".", null);
        }
        String label = packageInfo.getPackageId() + " " + packageInfo.getVersion();
        try {
            Files.createDirectories(destination);
            Path targetPath = destination.resolve(fileNameOf(packageInfo));
            if (Files.isRegularFile(targetPath) && Files.size(targetPath) > 0) {
                logger.info("Reusing installed package file " + targetPath);
                return targetPath;
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(packageInfo.getDownloadUrl()))
                    .header("Accept", "application/octet-stream")
                    .GET()
                    .build();
            HttpResponse<Path> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofFile(targetPath));
            if (response.statusCode() == 200) {
                return targetPath;
            }
            Files.deleteIfExists(targetPath); // clean up a partial download
            throw new PackageRegistryException("Download of " + label + " failed with HTTP "
                    + response.statusCode() + ".", null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PackageRegistryException("Could not download " + label
                    + " from " + packageInfo.getDownloadUrl() + ". Check your network connection. ("
                    + e.getMessage() + ")", e);
        }
    }

    /** The file name a package version is stored under. */
    public static String fileNameOf(PackageInfo packageInfo) {
        return packageInfo.getPackageId() + "-" + packageInfo.getVersion() + ".tgz";
    }

    /** One published version of a package. */
    public static final class PackageInfo {
        private final String name, version, title, description, fhirVersion;
        private final String canonicalUrl, downloadUrl, packageId;

        public PackageInfo(String name, String version, String title, String description,
                          String fhirVersion, String canonicalUrl, String downloadUrl,
                          String packageId) {
            this.name = name; this.version = version; this.title = title;
            this.description = description; this.fhirVersion = fhirVersion;
            this.canonicalUrl = canonicalUrl; this.downloadUrl = downloadUrl;
            this.packageId = packageId;
        }

        /** A copy with a different display title. */
        public PackageInfo withTitle(String newTitle) {
            return new PackageInfo(name, version, newTitle, description, fhirVersion,
                    canonicalUrl, downloadUrl, packageId);
        }

        public String getName() { return name; }
        public String getVersion() { return version; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public String getFhirVersion() { return fhirVersion; }
        public String getCanonicalUrl() { return canonicalUrl; }
        public String getDownloadUrl() { return downloadUrl; }
        public String getPackageId() { return packageId; }

        /** For example {@code hl7.fhir.us.core#9.0.0}. */
        public String getKey() { return packageId + "#" + version; }

        @Override
        public String toString() {
            return (title != null && !title.isEmpty() ? title : name) + 
                   (version != null && !version.isEmpty() ? " " + version : "");
        }

        public String getDisplayText() {
            StringBuilder sb = new StringBuilder();
            sb.append(getTitle()).append(" ").append(getVersion());
            sb.append("\n").append(getPackageId());
            if (getFhirVersion() != null && !getFhirVersion().isEmpty()) {
                sb.append("  |  FHIR ").append(getFhirVersion());
            }
            if (getDescription() != null && !getDescription().isEmpty()) {
                sb.append("\n").append(getDescription().substring(0, 
                        Math.min(getDescription().length(), 100)));
            }
            return sb.toString();
        }
    }

    /** A registry or download failure with a message suitable for display. */
    public static final class PackageRegistryException extends RuntimeException {
        public PackageRegistryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    // END OF CLASS
}
