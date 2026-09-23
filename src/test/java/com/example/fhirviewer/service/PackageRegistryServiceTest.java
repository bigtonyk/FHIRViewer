package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.service.PackageRegistryService.PackageInfo;

/**
 * Tests npm-style packument parsing used to look up packages on
 * https://packages.fhir.org (no network access required).
 */
class PackageRegistryServiceTest {

    private static final String PACKUMENT = "{"
            + "\"name\": \"hl7.fhir.us.core\","
            + "\"description\": \"US Core Implementation Guide\","
            + "\"dist-tags\": {\"latest\": \"3.1.0\"},"
            + "\"versions\": {"
            + "  \"3.0.0\": {"
            + "    \"name\": \"hl7.fhir.us.core\","
            + "    \"version\": \"3.0.0\","
            + "    \"description\": \"None.\","
            + "    \"fhirVersion\": \"R4\","
            + "    \"dist\": {\"tarball\": \"https://packages.simplifier.net/hl7.fhir.us.core/3.0.0\"}"
            + "  },"
            + "  \"3.1.0\": {"
            + "    \"name\": \"hl7.fhir.us.core\","
            + "    \"version\": \"3.1.0\","
            + "    \"description\": \"Latest release\","
            + "    \"fhirVersion\": \"R4\","
            + "    \"dist\": {\"tarball\": \"https://packages.simplifier.net/hl7.fhir.us.core/3.1.0\"}"
            + "  }"
            + "}"
            + "}";

    @Test
    @DisplayName("Packument parses all versions, newest first")
    void parsePackumentReturnsNewestFirst() throws Exception {
        PackageRegistryService service = new PackageRegistryService();
        List<PackageInfo> results = service.parsePackument(PACKUMENT, null);

        assertEquals(2, results.size());
        assertEquals("3.1.0", results.get(0).getVersion());
        assertEquals("3.0.0", results.get(1).getVersion());
        assertEquals("hl7.fhir.us.core", results.get(0).getName());
        assertEquals("hl7.fhir.us.core", results.get(0).getTitle());
        assertEquals("R4", results.get(0).getFhirVersion());
        assertEquals("https://packages.simplifier.net/hl7.fhir.us.core/3.1.0",
                results.get(0).getDownloadUrl());
        assertEquals("Latest release", results.get(0).getDescription());
    }

    @Test
    @DisplayName("Version filter selects a single version")
    void parsePackumentFiltersByVersion() throws Exception {
        PackageRegistryService service = new PackageRegistryService();
        List<PackageInfo> results = service.parsePackument(PACKUMENT, "3.0.0");

        assertEquals(1, results.size());
        assertEquals("3.0.0", results.get(0).getVersion());
    }

    @Test
    @DisplayName("Placeholder version descriptions fall back to package description")
    void parsePackumentFallsBackToRootDescription() throws Exception {
        PackageRegistryService service = new PackageRegistryService();
        List<PackageInfo> results = service.parsePackument(PACKUMENT, "3.0.0");

        assertEquals("US Core Implementation Guide", results.get(0).getDescription());
    }

    @Test
    @DisplayName("Empty query returns no results without contacting the registry")
    void emptyQueryReturnsEmpty() {
        PackageRegistryService service = new PackageRegistryService();
        assertTrue(service.searchPackages(" ").isEmpty());
        assertTrue(service.searchPackages(null).isEmpty());
    }
}
