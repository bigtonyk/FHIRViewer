// Copyright (c) 2026 FHIRViewer contributors. SPDX-License-Identifier: GPL-3.0-only.
package com.example.fhirviewer.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Meta;

import com.example.fhirviewer.model.ValidationIssue;
import com.example.fhirviewer.model.ValidationLevel;
import com.example.fhirviewer.model.ValidationProfile;
import com.example.fhirviewer.model.ValidationReport;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;

/**
 * Validates resources with HAPI FHIR, including support for loaded IG packages.
 *
 * <p>Validation runs in two phases so results can say where a problem came
 * from (Update 10): the resource is first validated against its base FHIR R4
 * definition, then against every Implementation Guide profile it claims in
 * {@code meta.profile} or that the caller selected explicitly (Update 11/12).
 * Issues only the profile produces are attributed to that profile and the
 * package that provides it.</p>
 */
public class ValidationService {

    /**
     * Pseudo profile meaning "validate against the base FHIR R4 definition
     * only", used by the profile picker so a user can explicitly ignore
     * {@code meta.profile} (Updates 11/12).
     */
    public static final String BASE_DEFINITION_ONLY = "urn:fhirviewer:profile:base-r4";

    private final FhirContext context;
    private volatile FhirValidator validator;
    /** Validation support chain the cached validator was built with. */
    private volatile IValidationSupport validationSupport;
    /** Package revision the cached validator was built with; -1 before the first build. */
    private volatile long validatorRevision = -1;
    private final IgPackageManager packageManager;

    public ValidationService(FhirContext context) {
        this.context = context;
        this.packageManager = new IgPackageManager(context);
    }

    public IgPackageManager getPackageManager() {
        return packageManager;
    }

    /**
     * Validates a resource against its base definition and the IG profiles it
     * claims in {@code meta.profile}.
     *
     * <p>Validation failures are converted into a {@link ValidationReport} containing an
     * error message rather than thrown, so the UI can display them.</p>
     */
    public ValidationReport validate(IBaseResource resource) {
        return validate(resource, null);
    }

    /**
     * Validates a resource.
     *
     * @param requestedProfile canonical URL of the profile to validate against, or
     *                         null/blank to use the resource's own {@code meta.profile}.
     *                         Selecting a profile never modifies the resource
     *                         (Update 11), so a resource without {@code meta.profile}
     *                         can still be validated against an installed profile.
     */
    public ValidationReport validate(IBaseResource resource, String requestedProfile) {
        if (resource == null) {
            return ValidationReport.successful("");
        }
        List<ValidationIssue> issues = new ArrayList<>();
        List<ValidationProfile> validatedProfiles = new ArrayList<>();
        try {
            FhirValidator current = validator();
            IValidationSupport support = validationSupport;
            ValidationIssueAnalyzer analyzer = new ValidationIssueAnalyzer(support);
            // Profile and terminology resolution checks run before the validator so
            // missing artifacts are distinguished from real validation failures.
            analyzer.checkProfileBindings(resource, issues);

            List<String> resolvable = new ArrayList<>();
            for (String canonical : requestedProfiles(resource, requestedProfile)) {
                if (support != null && support.fetchStructureDefinition(canonical) != null) {
                    resolvable.add(canonical);
                } else {
                    issues.add(ValidationIssue.profileResolutionFailure(
                            ValidationIssue.Severity.WARNING,
                            profileNotAvailable(canonical), "meta.profile", null, null));
                }
            }

            Set<String> baseIssueKeys = validateAgainstBase(current, analyzer, resource, issues);
            validateAgainstProfiles(current, analyzer, resource, resolvable,
                    baseIssueKeys, issues, validatedProfiles);
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            issues.add(new ValidationIssue(
                    ValidationIssue.Severity.ERROR,
                    "Validation could not be completed: " + detail,
                    "", null, null));
        }
        return new ValidationReport(labelOf(resource), issues, validatedProfiles);
    }

    // ------------------------------------------------------------------
    // The two validation phases
    // ------------------------------------------------------------------

    /** Phase one: validate the resource against its base FHIR R4 definition. */
    private Set<String> validateAgainstBase(FhirValidator validator,
            ValidationIssueAnalyzer analyzer, IBaseResource resource,
            List<ValidationIssue> issues) {
        Set<String> keys = new LinkedHashSet<>();
        ValidationResult result = validator.validateWithResult(
                copyWithProfiles(resource, List.of()));
        for (SingleValidationMessage message : result.getMessages()) {
            ValidationIssue issue = analyzer.classify(message);
            keys.add(keyOf(issue));
            issues.add(issue);
        }
        return keys;
    }

    /**
     * Phase two: validate against each resolvable IG profile. Issues the base
     * pass already reported are dropped; the remainder are attributed to the
     * profile that produced them.
     */
    private void validateAgainstProfiles(FhirValidator validator,
            ValidationIssueAnalyzer analyzer, IBaseResource resource,
            List<String> resolvableProfiles, Set<String> baseIssueKeys,
            List<ValidationIssue> issues, List<ValidationProfile> validatedProfiles) {
        for (String canonical : resolvableProfiles) {
            ValidationProfile profile = describe(canonical, analyzer);
            validatedProfiles.add(profile);
            ValidationResult result = validator.validateWithResult(
                    copyWithProfiles(resource, List.of(canonical)));
            for (SingleValidationMessage message : result.getMessages()) {
                ValidationIssue issue = analyzer.classify(message);
                if (baseIssueKeys.contains(keyOf(issue))) {
                    continue; // already reported by the base pass
                }
                issues.add(issue.withLevel(ValidationLevel.IG_PROFILE, profile));
            }
        }
    }

    /** Profile description including the installed package that provides it. */
    private ValidationProfile describe(String canonical, ValidationIssueAnalyzer analyzer) {
        ValidationProfile indexed = packageManager.describeProfile(canonical);
        return indexed != null ? indexed : analyzer.describe(canonical);
    }

    /** Profiles to validate against: the caller's selection or {@code meta.profile}. */
    private static List<String> requestedProfiles(IBaseResource resource, String requestedProfile) {
        if (BASE_DEFINITION_ONLY.equals(requestedProfile)) {
            return List.of(); // base definition validation only
        }
        if (requestedProfile != null && !requestedProfile.isBlank()) {
            return List.of(stripVersion(requestedProfile.trim()));
        }
        if (!(resource instanceof DomainResource domain) || !domain.hasMeta()) {
            return List.of();
        }
        List<String> canonicals = new ArrayList<>();
        for (CanonicalType profile : domain.getMeta().getProfile()) {
            String value = stripVersion(profile.getValue());
            if (!value.isBlank() && !canonicals.contains(value)) {
                canonicals.add(value);
            }
        }
        return canonicals;
    }

    /** A copy of the resource carrying exactly the given {@code meta.profile} entries. */
    private IBaseResource copyWithProfiles(IBaseResource resource, List<String> profiles) {
        IBaseResource copy = copyOf(resource);
        if (!(copy instanceof DomainResource domain)) {
            return copy;
        }
        Meta meta = domain.getMeta();
        List<CanonicalType> canonicals = new ArrayList<>();
        for (String profile : profiles) {
            canonicals.add(new CanonicalType(profile));
        }
        meta.setProfile(canonicals);
        return copy;
    }

    /**
     * A deep copy of a resource, so the two validation phases can apply
     * different {@code meta.profile} values without touching the caller's
     * resource (Update 11). R4 resources copy natively; anything else is
     * round-tripped through the parser.
     */
    private IBaseResource copyOf(IBaseResource resource) {
        if (resource instanceof org.hl7.fhir.r4.model.Resource r4) {
            return r4.copy();
        }
        String serialized = context.newJsonParser().encodeResourceToString(resource);
        return context.newJsonParser().parseResource(serialized);
    }

    /** Message shown when a profile cannot be resolved from the installed packages. */
    private String profileNotAvailable(String canonical) {
        StringBuilder sb = new StringBuilder();
        sb.append("Profile not available: '").append(canonical).append("'. ");
        List<String> installed = packageManager.getLoadedPackages().stream()
                .map(pkg -> pkg.name() + " " + pkg.version())
                .collect(Collectors.toList());
        if (installed.isEmpty()) {
            sb.append("No Implementation Guide packages are installed. Use Implementation Guide > ")
                    .append("Manage Packages to install the required package.");
        } else {
            sb.append("The required Implementation Guide package is not installed. Installed")
                    .append(" packages: ").append(String.join(", ", installed)).append('.');
        }
        return sb.toString();
    }

    /** Identity of an issue, used to de-duplicate the two validation phases. */
    private static String keyOf(ValidationIssue issue) {
        return issue.severity() + "|" + issue.location() + "|" + issue.message();
    }

    /** Removes a version suffix ({@code url|1.0.0}) from a canonical reference. */
    private static String stripVersion(String canonical) {
        if (canonical == null) {
            return "";
        }
        int pipe = canonical.indexOf('|');
        return (pipe < 0 ? canonical : canonical.substring(0, pipe)).trim();
    }

    /**
     * The validator for the current package set. Rebuilt whenever packages are
     * installed, activated or deactivated so the change always takes effect.
     */
    private FhirValidator validator() {
        long revision = packageManager.getRevision();
        FhirValidator existing = validator;
        if (existing != null && validatorRevision == revision) {
            return existing;
        }
        synchronized (this) {
            if (validator == null || validatorRevision != revision) {
                validator = createValidator();
                validatorRevision = revision;
            }
            return validator;
        }
    }

    private FhirValidator createValidator() {
        // Chain order mirrors HAPI's own examples: profiles, packages,
        // snapshots, then terminology. NPM support must come before the
        // snapshot generator so package profiles resolve, and package
        // ValueSets/CodeSystems must be visible to the in-memory
        // terminology server that expands bindings.
        List<IValidationSupport> supports = new ArrayList<>();
        supports.add(new DefaultProfileValidationSupport(context));
        NpmPackageValidationSupport npmSupport = packageManager.getNpmPackageValidationSupport();
        if (npmSupport != null) {
            supports.add(npmSupport);
        }
        supports.add(new SnapshotGeneratingValidationSupport(context));
        supports.add(new InMemoryTerminologyServerValidationSupport(context));

        IValidationSupport chain = new ValidationSupportChain(
                supports.toArray(new IValidationSupport[0]));
        validationSupport = chain;

        FhirValidator fhirValidator = new FhirValidator(context);
        fhirValidator.registerValidatorModule(new FhirInstanceValidator(chain));
        return fhirValidator;
    }

    private static String labelOf(IBaseResource resource) {
        String type = resource.fhirType();
        IIdType id = resource.getIdElement();
        if (id == null || !id.hasIdPart()) {
            return type;
        }
        return type + "/" + id.getIdPart();
    }
}
