// Copyright (c) 2026 FHIRViewer contributors. SPDX-License-Identifier: GPL-3.0-only.
package com.example.fhirviewer.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.fhir.FhirContextFactory;

import ca.uhn.fhir.context.FhirContext;

/**
 * Shared offline fixtures: a small demo package whose Patient profile requires
 * an identifier, plus Patient resources to validate against it. Used by the
 * profile, package installer and activation tests.
 */
final class TestPackages {

    /** Canonical URL of the demo Patient profile. */
    static final String PROFILE_URL = "http://example.org/ig/StructureDefinition/demo-patient";
    /** Title of the demo Patient profile, shown in validation results. */
    static final String PROFILE_TITLE = "Demo Patient";
    /** FHIR package id of the demo package. */
    static final String PACKAGE_ID = "demo.ig";
    /** Version of the demo package. */
    static final String PACKAGE_VERSION = "1.0.0";
    /** File name the demo package is stored under. */
    static final String PACKAGE_FILE = PACKAGE_ID + "-" + PACKAGE_VERSION + ".tgz";

    private static final FhirContext CONTEXT = FhirContextFactory.r4();

    private TestPackages() {
    }

    /** package.json of the demo package; depends only on the R4 core specification. */
    static String packageJson() {
        return "{\"name\": \"" + PACKAGE_ID + "\", \"version\": \"" + PACKAGE_VERSION + "\","
                + "\"fhirVersions\": [\"4.0.1\"],"
                + "\"canonical\": \"http://example.org/ig\","
                + "\"dependencies\": {\"hl7.fhir.r4.core\": \"4.0.1\"},"
                + "\"description\": \"Demo profile package\"}";
    }

    /** A Patient profile that adds a required identifier. */
    static String profileJson() {
        return "{\"resourceType\": \"StructureDefinition\", \"id\": \"demo-patient\","
                + " \"url\": \"" + PROFILE_URL + "\", \"name\": \"DemoPatient\","
                + " \"title\": \"" + PROFILE_TITLE + "\", \"version\": \"" + PACKAGE_VERSION + "\","
                + " \"status\": \"active\", \"kind\": \"resource\", \"abstract\": false,"
                + " \"type\": \"Patient\","
                + " \"baseDefinition\": \"http://hl7.org/fhir/StructureDefinition/Patient\","
                + " \"derivation\": \"constraint\","
                + " \"differential\": {\"element\": ["
                + "{\"id\": \"Patient\", \"path\": \"Patient\"},"
                + "{\"id\": \"Patient.identifier\", \"path\": \"Patient.identifier\", \"min\": 1}]}}";
    }

    /** Writes the demo package and returns its file. */
    static Path writeDemoPackage(Path directory) throws IOException {
        Path file = directory.resolve(PACKAGE_FILE);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("package/package.json", packageJson());
        entries.put("package/StructureDefinition-demo-patient.json", profileJson());
        TgzFixtures.writeTgz(file, entries);
        return file;
    }

    /** A Patient that claims the demo profile but has no identifier. */
    static String patientWithProfile() {
        return "{\"resourceType\": \"Patient\", \"id\": \"p1\","
                + "\"meta\": {\"profile\": [\"" + PROFILE_URL + "\"]},"
                + "\"name\": [{\"family\": \"Doe\", \"given\": [\"John\"]}]}";
    }

    /** A Patient with the identifier the profile requires. */
    static String patientWithIdentifier() {
        return "{\"resourceType\": \"Patient\", \"id\": \"p2\","
                + "\"meta\": {\"profile\": [\"" + PROFILE_URL + "\"]},"
                + "\"identifier\": [{\"system\": \"http://example.org/mrn\", \"value\": \"123\"}],"
                + "\"name\": [{\"family\": \"Doe\", \"given\": [\"Jane\"]}]}";
    }

    /** A Patient with neither an identifier nor a claimed profile. */
    static String patientWithoutProfile() {
        return "{\"resourceType\": \"Patient\", \"id\": \"p3\","
                + "\"name\": [{\"family\": \"Roe\", \"given\": [\"Sue\"]}]}";
    }

    /** Parses a resource with the R4 context. */
    static IBaseResource parse(String json) {
        return CONTEXT.newJsonParser().parseResource(json);
    }
}
