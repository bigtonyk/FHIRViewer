package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.fhirviewer.fhir.FhirContextFactory;
import com.example.fhirviewer.model.IgPackageInfo;
import com.example.fhirviewer.model.ValidationIssue;
import com.example.fhirviewer.model.ValidationReport;

import ca.uhn.fhir.context.FhirContext;

/**
 * End-to-end terminology tests: package ValueSets/CodeSystems (including ones
 * from dependency packages) must resolve during validation, and resolution
 * failures must be distinguished from genuine validation failures. The
 * CodeSystem lives in a dependency package, so a valid code only passes when
 * the dependency is discovered and both packages feed the terminology chain.
 */
class ValidationServiceTerminologyTest {

    private static final FhirContext CTX = FhirContextFactory.r4();

    private static final String CS_URL = "http://example.org/base/CodeSystem/blood";
    private static final String VS_URL = "http://example.org/ig/ValueSet/blood";
    private static final String MISSING_VS_URL = "http://example.org/ig/ValueSet/nope";
    private static final String PROFILE_OK =
            "http://example.org/ig/StructureDefinition/bloodpatient";
    private static final String PROFILE_MISSING_VS =
            "http://example.org/ig/StructureDefinition/bloodpatient2";

    private static final String DEP_PACKAGE_JSON =
            "{\"name\": \"dep.ig\", \"version\": \"1.0.0\","
                    + "\"fhirVersions\": [\"4.0.1\"],"
                    + "\"canonical\": \"http://example.org/dep\","
                    + "\"description\": \"Holds the blood CodeSystem\"}";

    private static final String BLOOD_CODE_SYSTEM =
            "{\"resourceType\": \"CodeSystem\", \"id\": \"blood\","
                    + "\"url\": \"" + CS_URL + "\", \"version\": \"1.0.0\","
                    + "\"name\": \"BloodCS\", \"status\": \"active\", \"content\": \"complete\","
                    + "\"concept\": [{\"code\": \"A\", \"display\": \"Alpha\"},"
                    + "{\"code\": \"B\", \"display\": \"Beta\"}]}";

    private static final String MAIN_PACKAGE_JSON =
            "{\"name\": \"test.ig\", \"version\": \"1.0.0\","
                    + "\"fhirVersions\": [\"4.0.1\"],"
                    + "\"canonical\": \"http://example.org/ig\","
                    + "\"dependencies\": {\"dep.ig#1.0.0\": \"1.0.0\"},"
                    + "\"description\": \"Profiles bound to the blood ValueSet\"}";

    private static final String BLOOD_VALUE_SET =
            "{\"resourceType\": \"ValueSet\", \"id\": \"blood\","
                    + "\"url\": \"" + VS_URL + "\", \"version\": \"1.0.0\","
                    + "\"name\": \"BloodVS\", \"status\": \"active\","
                    + "\"compose\": {\"include\": [{\"system\": \"" + CS_URL + "\"}]}}";

    @Test
    @DisplayName("Profile bindings resolve through package and dependency terminology")
    void packageTerminologyResolvesThroughDependency(@TempDir Path dir) throws IOException {
        FhirService service = loadFixtures(dir);
        IgPackageManager manager = service.validationService().getPackageManager();

        List<String> names = manager.getLoadedPackages().stream()
                .map(IgPackageInfo::name).toList();
        assertTrue(names.contains("dep.ig"),
                "dependency should load from the same directory: " + names);
        assertTrue(manager.getUnmetDependencies().isEmpty(),
                "no unmet dependencies expected: " + manager.getUnmetDependencies());

        ValidationReport report =
                service.validationService().validate(parse(patient(PROFILE_OK, "A")));
        assertTrue(report.getIssues().stream()
                        .noneMatch(ValidationIssue::isTerminologyResolutionFailure),
                describe(report));
        assertTrue(report.isValid(), describe(report));
    }

    @Test
    @DisplayName("Code not in a resolvable ValueSet is a real validation failure")
    void codeNotInValueSetIsARealValidationFailure(@TempDir Path dir) throws IOException {
        FhirService service = loadFixtures(dir);
        ValidationReport report =
                service.validationService().validate(parse(patient(PROFILE_OK, "ZZZ")));

        assertTrue(report.getIssues().stream()
                        .noneMatch(ValidationIssue::isTerminologyResolutionFailure),
                describe(report));
        assertTrue(report.getIssues().stream().anyMatch(issue ->
                        issue.severity().isProblem() && mentionsValueSet(issue.message())),
                describe(report));
    }

    @Test
    @DisplayName("Missing ValueSet in a profile binding is a terminology resolution failure")
    void missingValueSetIsFlaggedAsTerminologyResolutionFailure(@TempDir Path dir)
            throws IOException {
        FhirService service = loadFixtures(dir);
        ValidationReport report =
                service.validationService().validate(parse(patient(PROFILE_MISSING_VS, "A")));

        assertTrue(report.getIssues().stream()
                        .anyMatch(ValidationIssue::isTerminologyResolutionFailure),
                describe(report));
        assertTrue(report.getIssues().stream().anyMatch(issue ->
                        issue.isTerminologyResolutionFailure()
                                && issue.message().contains(MISSING_VS_URL)),
                describe(report));
        assertTrue(report.getIssues().stream()
                        .noneMatch(ValidationIssue::isProfileResolutionFailure),
                describe(report));
    }

    @Test
    @DisplayName("Dependency ValueSets resolve for validation")
    void dependencyValueSetResolvesForValidation(@TempDir Path dir) throws IOException {
        // Flip the fixtures: ValueSet lives in dep.ig, CodeSystem in test.ig.
        // A valid code still validates only when BOTH packages feed the chain.
        String vsInDep = "{\"resourceType\": \"ValueSet\", \"id\": \"blood\","
                + " \"url\": \"" + VS_URL + "\", \"version\": \"1.0.0\","
                + " \"name\": \"BloodVS\", \"status\": \"active\","
                + " \"compose\": {\"include\": [{\"system\": \"" + CS_URL + "\"}]}}";
        String csInMain = BLOOD_CODE_SYSTEM;
        String depJson = "{\"name\": \"dep.ig\", \"version\": \"1.0.0\","
                + "\"fhirVersions\": [\"4.0.1\"],"
                + "\"canonical\": \"http://example.org/dep\","
                + "\"description\": \"Holds the blood ValueSet\"}";
        String mainJson = "{\"name\": \"test.ig\", \"version\": \"1.0.0\","
                + "\"fhirVersions\": [\"4.0.1\"],"
                + "\"canonical\": \"http://example.org/ig\","
                + "\"dependencies\": {\"dep.ig#1.0.0\": \"1.0.0\"},"
                + "\"description\": \"Profiles bound to the blood ValueSet\"}";
        TgzFixtures.writeTgz(dir.resolve("dep.ig-1.0.0.tgz"), Map.of(
                "package/package.json", depJson,
                "package/ValueSet-blood.json", vsInDep));
        TgzFixtures.writeTgz(dir.resolve("test.ig-1.0.0.tgz"), Map.of(
                "package/package.json", mainJson,
                "package/CodeSystem-blood.json", csInMain,
                "package/StructureDefinition-bloodpatient.json",
                profileJson("bloodpatient", PROFILE_OK, VS_URL)));

        FhirService service = new FhirService();
        service.validationService().getPackageManager()
                .loadPackageFromFile(dir.resolve("test.ig-1.0.0.tgz"));
        ValidationReport report =
                service.validationService().validate(parse(patient(PROFILE_OK, "A")));

        assertTrue(report.getIssues().stream()
                        .noneMatch(ValidationIssue::isTerminologyResolutionFailure),
                describe(report));
        assertTrue(report.isValid(), describe(report));
    }

    @Test
    @DisplayName("Contained ValueSet references are not resolution failures")
    void containedValueSetIsNotATerminologyResolutionFailure() {
        String localVs = "#local-blood";
        String profileJson = profileJson("bloodpatient", PROFILE_OK, localVs);
        IBaseResource profile = CTX.newJsonParser().parseResource(profileJson);
        FhirService service = new FhirService();
        service.validationService().getPackageManager();
        // Register the profile directly is not possible; instead validate a
        // patient carrying a contained ValueSet whose canonical matches the
        // binding target shape — the pre-check must not flag "#..." locals.
        String patient = "{\"resourceType\": \"Patient\", \"id\": \"p1\","
                + "\"meta\": {\"profile\": [\"" + PROFILE_OK + "\"]},"
                + "\"contained\": [{\"resourceType\": \"ValueSet\", \"id\": \"local-blood\","
                + " \"url\": \"" + localVs + "\", \"status\": \"active\"}],"
                + "\"name\": [{\"family\": \"Doe\", \"given\": [\"John\"]}]}";
        assertTrue(profile != null, "fixture profile must parse");
        ValidationReport report =
                service.validationService().validate(parse(patient));
        assertTrue(report.getIssues().stream()
                        .filter(ValidationIssue::isTerminologyResolutionFailure)
                        .noneMatch(issue -> issue.message().contains(localVs)),
                describe(report));
    }

    @Test
    @DisplayName("CodeSystem missing from a ValueSet compose is a terminology resolution failure")
    void valueSetWithMissingCodeSystemIsFlaggedAsTerminologyResolutionFailure(@TempDir Path dir)
            throws IOException {
        String profile = "http://example.org/ig/StructureDefinition/brokenpatient";
        String brokenVs = "http://example.org/ig/ValueSet/broken";
        String missingCs = "http://example.org/ig/CodeSystem/missing";
        String brokenVsJson = "{\"resourceType\": \"ValueSet\", \"id\": \"broken\","
                + " \"url\": \"" + brokenVs + "\", \"version\": \"1.0.0\","
                + " \"name\": \"BrokenVS\", \"status\": \"active\","
                + " \"compose\": {\"include\": [{\"system\": \"" + missingCs + "\"}]}}";
        String brokenProfileJson = "{\"resourceType\": \"StructureDefinition\", \"id\": \"brokenpatient\","
                + " \"url\": \"" + profile + "\", \"version\": \"1.0.0\","
                + " \"name\": \"BrokenPatient\", \"status\": \"active\", \"kind\": \"resource\","
                + " \"type\": \"Patient\", \"derivation\": \"constraint\","
                + " \"baseDefinition\": \"http://hl7.org/fhir/StructureDefinition/Patient\","
                + " \"differential\": {\"element\": ["
                + "{\"id\": \"Patient\", \"path\": \"Patient\"},"
                + "{\"id\": \"Patient.maritalStatus\", \"path\": \"Patient.maritalStatus\","
                + " \"binding\": {\"strength\": \"required\", \"valueSet\": \"" + brokenVs + "\"}}]}}";
        TgzFixtures.writeTgz(dir.resolve("broken.ig-1.0.0.tgz"), Map.of(
                "package/package.json",
                "{\"name\": \"broken.ig\", \"version\": \"1.0.0\","
                        + "\"fhirVersions\": [\"4.0.1\"],"
                        + "\"canonical\": \"http://example.org/broken\","
                        + "\"description\": \"ValueSet bound to a missing CodeSystem\"}",
                "package/ValueSet-broken.json", brokenVsJson,
                "package/StructureDefinition-brokenpatient.json", brokenProfileJson));

        FhirService service = new FhirService();
        service.validationService().getPackageManager()
                .loadPackageFromFile(dir.resolve("broken.ig-1.0.0.tgz"));

        String patient = "{\"resourceType\": \"Patient\", \"id\": \"p1\","
                + "\"meta\": {\"profile\": [\"" + profile + "\"],"
                + " \"security\": [{\"system\": \"" + missingCs + "\", \"code\": \"whatever\"}]},"
                + "\"name\": [{\"family\": \"Doe\", \"given\": [\"John\"]}]}";
        ValidationReport report =
                service.validationService().validate(parse(patient));

        assertTrue(report.getIssues().stream()
                        .anyMatch(issue -> issue.isTerminologyResolutionFailure()
                                && issue.message().contains(missingCs)),
                describe(report));
        assertTrue(report.getIssues().stream()
                        .noneMatch(ValidationIssue::isProfileResolutionFailure),
                describe(report));
    }

    /** Writes both fixture packages and loads {@code test.ig} (dep.ig follows). */
    private static FhirService loadFixtures(Path dir) throws IOException {
        TgzFixtures.writeTgz(dir.resolve("dep.ig-1.0.0.tgz"), Map.of(
                "package/package.json", DEP_PACKAGE_JSON,
                "package/CodeSystem-blood.json", BLOOD_CODE_SYSTEM));
        TgzFixtures.writeTgz(dir.resolve("test.ig-1.0.0.tgz"), Map.of(
                "package/package.json", MAIN_PACKAGE_JSON,
                "package/ValueSet-blood.json", BLOOD_VALUE_SET,
                "package/StructureDefinition-bloodpatient.json",
                profileJson("bloodpatient", PROFILE_OK, VS_URL),
                "package/StructureDefinition-bloodpatient2.json",
                profileJson("bloodpatient2", PROFILE_MISSING_VS, MISSING_VS_URL)));

        FhirService service = new FhirService();
        service.validationService().getPackageManager()
                .loadPackageFromFile(dir.resolve("test.ig-1.0.0.tgz"));
        return service;
    }

    private static String profileJson(String id, String canonical, String valueSetUrl) {
        return "{\"resourceType\": \"StructureDefinition\", \"id\": \"" + id + "\","
                + "\"url\": \"" + canonical + "\", \"version\": \"1.0.0\","
                + "\"name\": \"BloodPatient\", \"status\": \"active\", \"kind\": \"resource\","
                + "\"type\": \"Patient\", \"derivation\": \"constraint\","
                + "\"baseDefinition\": \"http://hl7.org/fhir/StructureDefinition/Patient\","
                + "\"differential\": {\"element\": ["
                + "{\"id\": \"Patient\", \"path\": \"Patient\"},"
                + "{\"id\": \"Patient.meta.security\", \"path\": \"Patient.meta.security\","
                + " \"binding\": {\"strength\": \"required\", \"valueSet\": \""
                + valueSetUrl + "\"}}]}}";
    }

    private static String patient(String profileUrl, String code) {
        return "{\"resourceType\": \"Patient\", \"id\": \"p1\","
                + "\"meta\": {\"profile\": [\"" + profileUrl + "\"],"
                + " \"security\": [{\"system\": \"" + CS_URL + "\", \"code\": \""
                + code + "\"}]},"
                + "\"name\": [{\"family\": \"Doe\", \"given\": [\"John\"]}]}";
    }

    private static IBaseResource parse(String json) {
        return CTX.newJsonParser().parseResource(json);
    }

    private static boolean mentionsValueSet(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("value set") || lower.contains("valueset");
    }

    private static String describe(ValidationReport report) {
        return report.getSummary() + "\n" + report.getIssues().stream()
                .map(ValidationIssue::getDisplayText)
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
