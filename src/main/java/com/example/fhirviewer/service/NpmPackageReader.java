// Copyright (c) 2026 FHIRViewer contributors. SPDX-License-Identifier: GPL-3.0-only.
package com.example.fhirviewer.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.model.JsonProperty;
import org.hl7.fhir.utilities.npm.NpmPackage;

/**
 * Reads FHIR NPM package archives (.tgz): the package.json metadata, the
 * declared dependencies and the conformance resources they carry.
 *
 * <p>Deliberately free of any {@code FhirContext}: the package installer needs
 * dependency metadata before a validation context exists, so parsing of
 * resources into FHIR model objects happens in {@link IgPackageManager}.</p>
 */
public final class NpmPackageReader {

    private static final Logger logger = Logger.getLogger(NpmPackageReader.class.getName());

    private NpmPackageReader() {
    }

    /**
     * Package identity as declared in package.json.
     *
     * @param name         the official FHIR package id, for example {@code hl7.fhir.us.core}
     * @param version      the exact package version, for example {@code 9.0.0}
     * @param fhirVersion  the FHIR version the package targets, for example {@code 4.0.1}
     * @param canonical    the declared canonical base
     * @param description  the human readable description
     * @param dependencies declared dependency keys, each {@code name} or {@code name#version}
     */
    public record PackageMetadata(String name, String version, String fhirVersion,
            String canonical, String description, List<String> dependencies) {

        public PackageMetadata {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    /**
     * Parses an archive. The returned {@link NpmPackage} keeps its content in
     * memory, so the stream can be closed by the caller.
     *
     * @throws IOException when the stream is not a readable NPM package
     */
    public static NpmPackage read(InputStream in) throws IOException {
        NpmPackage npm;
        try {
            npm = NpmPackage.fromPackage(in);
        } catch (RuntimeException e) {
            throw new IOException("Not a readable FHIR NPM package: " + e.getMessage(), e);
        }
        if (!npm.getFolders().containsKey("package")) {
            throw new IOException("Not a FHIR NPM package: no 'package' folder in the archive");
        }
        return npm;
    }

    /** Parses an archive from disk. */
    public static NpmPackage read(Path packageFile) throws IOException {
        if (!Files.exists(packageFile)) {
            throw new IOException("Package file not found: " + packageFile);
        }
        try (InputStream in = Files.newInputStream(packageFile)) {
            return read(in);
        }
    }

    /** Metadata only, without keeping the archive. */
    public static PackageMetadata readMetadata(Path packageFile) throws IOException {
        return metadataOf(read(packageFile));
    }

    /** The metadata declared by a parsed package. */
    public static PackageMetadata metadataOf(NpmPackage npm) {
        String name = safe(npm.name());
        String version = safe(npm.version());
        String canonical = safe(npm.canonical());
        if (canonical.isEmpty()) {
            canonical = name;
        }
        return new PackageMetadata(name, version, fhirVersionOf(npm, canonical),
                canonical, safe(npm.description()), dependenciesOf(npm));
    }

    /** Dependency keys ({@code name} or {@code name#version}) declared in package.json. */
    public static List<String> dependenciesOf(NpmPackage npm) {
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
     * The FHIR version a package targets. {@code NpmPackage.fhirVersion()}
     * throws when package.json declares neither {@code fhirVersions} nor a
     * core dependency, so the raw fields are read first and the accessor is
     * only used as a fallback.
     */
    private static String fhirVersionOf(NpmPackage npm, String canonical) {
        JsonObject packageJson = npm.getNpm();
        if (packageJson != null) {
            var array = packageJson.getJsonArray("fhirVersions");
            if (array != null && array.size() != null && array.size() > 0) {
                String first = array.get(0).asString();
                if (first != null && !first.isBlank()) {
                    return first;
                }
            }
            String single = packageJson.asString("fhirVersion");
            if (single != null && !single.isBlank()) {
                return single;
            }
        }
        try {
            return safe(npm.fhirVersion());
        } catch (RuntimeException e) {
            logger.fine("Package " + canonical + " declares no FHIR version: " + e.getMessage());
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
