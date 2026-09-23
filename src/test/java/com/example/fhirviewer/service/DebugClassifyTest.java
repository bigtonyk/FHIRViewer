package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.fhirviewer.fhir.FhirContextFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;

/** TEMPORARY diagnostics for classifier behaviour; delete after use. */
class DebugClassifyTest {

    private static final String CS_URL = "http://example.org/base/CodeSystem/blood";
    private static final String VS_URL = "http://example.org/ig/ValueSet/blood";

    @Test
    void debug(@TempDir Path dir) throws Exception {
        FhirContext ctx = FhirContextFactory.r4();
        TgzFixtures.writeTgz(dir.resolve("dep.ig-1.0.0.tgz"), Map.of(
                "package/package.json",
                "{\"name\": \"dep.ig\", \"version\": \"1.0.0\", \"fhirVersions\": [\"4.0.1\"],"
                        + "\"canonical\": \"http://example.org/dep\"}",
                "package/CodeSystem-blood.json",
                "{\"resourceType\": \"CodeSystem\", \"id\": \"blood\", \"url\": \"" + CS_URL
                        + "\", \"version\": \"1.0.0\", \"name\": \"BloodCS\", \"status\": \"active\","
                        + " \"content\": \"complete\","
                        + " \"concept\": [{\"code\": \"A\"}, {\"code\": \"B\"}]}"));
        TgzFixtures.writeTgz(dir.resolve("test.ig-1.0.0.tgz"), Map.of(
                "package/package.json",
                "{\"name\": \"test.ig\", \"version\": \"1.0.0\", \"fhirVersions\": [\"4.0.1\"],"
                        + "\"canonical\": \"http://example.org/ig\","
                        + "\"dependencies\": {\"dep.ig#1.0.0\": \"1.0.0\"}}",
                "package/ValueSet-blood.json",
                "{\"resourceType\": \"ValueSet\", \"id\": \"blood\", \"url\": \"" + VS_URL
                        + "\", \"version\": \"1.0.0\", \"name\": \"BloodVS\", \"status\": \"active\","
                        + " \"compose\": {\"include\": [{\"system\": \"" + CS_URL + "\"}]}}"));

        IgPackageManager manager = new IgPackageManager(ctx);
        manager.loadPackageFromFile(dir.resolve("test.ig-1.0.0.tgz"));

        ValidationSupportChain chain = new ValidationSupportChain(
                new DefaultProfileValidationSupport(ctx),
                new InMemoryTerminologyServerValidationSupport(ctx),
                new SnapshotGeneratingValidationSupport(ctx),
                manager.getNpmPackageValidationSupport());

        System.out.println("DBG VS plain  = " + chain.fetchValueSet(VS_URL));
        System.out.println("DBG VS version = " + chain.fetchValueSet(VS_URL + "|1.0.0"));
        System.out.println("DBG CS         = " + chain.fetchCodeSystem(CS_URL));

        String msg = "The Coding provided (http://example.org/base/CodeSystem/blood#ZZZ)"
                + " was not found in the value set 'BloodVS' (http://example.org/ig/ValueSet/blood|1.0.0),"
                + " and a code is required from this value set.  (error message = Unknown code"
                + " 'http://example.org/base/CodeSystem/blood#ZZZ'; Unknown code"
                + " 'http://example.org/base/CodeSystem/blood#ZZZ' for in-memory expansion of ValueSet"
                + " 'http://example.org/ig/ValueSet/blood')";

        Matcher m = Pattern.compile("https?://[^\\s'\"<>\\)\\]\\},;]+").matcher(msg);
        while (m.find()) {
            System.out.println("DBG url=[" + m.group() + "]");
        }

        ValidationIssueAnalyzer analyzer = new ValidationIssueAnalyzer(chain);
        System.out.println("DBG classify=" + analyzer.refersToUnresolvedTerminology(msg));
        assertTrue(true);
    }
}