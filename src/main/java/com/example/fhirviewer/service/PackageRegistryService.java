package com.example.fhirviewer.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service for discovering and downloading FHIR NPM packages from registries.
 */
public final class PackageRegistryService {

    private static final String FHIR_PACKAGE_REGISTRY_URL = "https://packages.fhir.org";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PackageRegistryService() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Looks up a package by exact id on the FHIR package registry. The registry is
     * npm-style: it serves one packument per package id containing every published
     * version, so an optional "#version" suffix can be used to pin a single version.
     * Returns an empty list when the package id is unknown to the registry.
     */
    public List<PackageInfo> searchPackages(String packageName) {
        List<PackageInfo> results = new ArrayList<>();
        String query = packageName == null ? "" : packageName.trim();
        if (query.isEmpty()) {
            return results;
        }

        String name = query;
        String versionFilter = null;
        int hash = query.indexOf('#');
        if (hash >= 0) {
            name = query.substring(0, hash).trim();
            versionFilter = query.substring(hash + 1).trim();
        }
        if (name.isEmpty()) {
            return results;
        }

        try {
            String lookupUrl = FHIR_PACKAGE_REGISTRY_URL + "/"
                    + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(lookupUrl))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return results;
            }
            if (response.statusCode() != 200) {
                throw new PackageRegistryException(
                        "Registry lookup failed with HTTP " + response.statusCode(), null);
            }
            results.addAll(parsePackument(response.body(), versionFilter));
        } catch (IOException | InterruptedException e) {
            throw new PackageRegistryException("Search failed", e);
        }
        return results;
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

    public PackageInfo getPackageInfo(String packageId) {
        // Implementation would fetch from registry
        return null;
    }

    public Path downloadPackage(PackageInfo packageInfo, Path destination) {
        if (packageInfo == null || packageInfo.getDownloadUrl() == null || packageInfo.getDownloadUrl().isBlank()) {
            throw new PackageRegistryException("No download URL available for package: " + 
                    (packageInfo != null ? packageInfo.getName() : "null"), null);
        }
        
        try {
            // Ensure destination directory exists
            java.nio.file.Files.createDirectories(destination);
            
            String downloadUrl = packageInfo.getDownloadUrl();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .header("Accept", "application/octet-stream")
                    .GET()
                    .build();
            
            // Create file name from package name and version
            String fileName = packageInfo.getName() + "-" + packageInfo.getVersion() + ".tgz";
            Path targetPath = destination.resolve(fileName);
            
            HttpResponse<Path> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofFile(targetPath));
            
            if (response.statusCode() == 200) {
                return targetPath;
            } else {
                // Clean up partial download
                java.nio.file.Files.deleteIfExists(targetPath);
                throw new PackageRegistryException(
                        "Download failed with status " + response.statusCode() + 
                        " for package " + packageInfo.getName(), null);
            }
        } catch (IOException | InterruptedException e) {
            throw new PackageRegistryException("Failed to download package: " + 
                    packageInfo.getName(), e);
        }
    }

    private String getString(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return f != null && !f.isNull() ? f.asText() : "";
    }

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

        public String getName() { return name; }
        public String getVersion() { return version; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public String getFhirVersion() { return fhirVersion; }
        public String getCanonicalUrl() { return canonicalUrl; }
        public String getDownloadUrl() { return downloadUrl; }
        public String getPackageId() { return packageId; }

        @Override
        public String toString() {
            return (title != null && !title.isEmpty() ? title : name) + 
                   (version != null && !version.isEmpty() ? " " + version : "");
        }

        public String getDisplayText() {
            StringBuilder sb = new StringBuilder();
            sb.append(getTitle()).append(" ").append(getVersion());
            if (getFhirVersion() != null && !getFhirVersion().isEmpty()) {
                sb.append("\n").append(getFhirVersion());
            }
            if (getDescription() != null && !getDescription().isEmpty()) {
                sb.append("\n").append(getDescription().substring(0, 
                        Math.min(getDescription().length(), 100)));
            }
            return sb.toString();
        }
    }

    public static final class PackageRegistryException extends RuntimeException {
        public PackageRegistryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}