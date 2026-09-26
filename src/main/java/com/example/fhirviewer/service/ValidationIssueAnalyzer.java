package com.example.fhirviewer.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.ElementDefinition;
import org.hl7.fhir.r4.model.StructureDefinition;

import com.example.fhirviewer.model.ValidationIssue;
import com.example.fhirviewer.model.ValidationProfile;

import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;

/**
 * Splits validation output into real validation failures versus failures to
 * <em>resolve</em> terminology artifacts: a ValueSet or CodeSystem that no
 * loaded package and no core specification provides.
 *
 * <p>Two mechanisms: (1) a pre-check that walks the bindings of every profile
 * the resource claims in {@code meta.profile} and reports ValueSets that
 * cannot be fetched at all ("ValueSet not found"), which the validator would
 * otherwise skip silently; (2) classification of validator messages — every
 * ValueSet/CodeSystem URL a message references is fetched from the validation
 * support chain; unresolvable means a resolution failure, resolvable means a
 * genuine membership failure such as "code not in ValueSet".</p>
 */
final class ValidationIssueAnalyzer {

    /** URLs appearing in validation messages; trailing punctuation excluded. */
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s'\"<>\\)\\]\\},;]+");

    /** How much text before a URL is inspected to classify that URL. */
    private static final int CONTEXT = 48;

    private final IValidationSupport support;

    ValidationIssueAnalyzer(IValidationSupport support) {
        this.support = support;
    }

    /**
     * For each resolvable profile in {@code meta.profile}, reports profiles
     * that cannot be fetched (profile resolution), required/extensible
     * bindings whose ValueSet cannot be fetched or whose referenced
     * CodeSystems cannot be fetched (terminology resolution).
     */
    void checkProfileBindings(IBaseResource resource, List<ValidationIssue> issues) {
        // Contained ValueSets/CodeSystems are local definitions, not usages:
        // collect their canonicals first so references to them are not
        // misreported as unresolvable, then check the outer resource's
        // profile bindings against chain + contained set.
        java.util.Set<String> local = new java.util.LinkedHashSet<>();
        if (resource instanceof org.hl7.fhir.r4.model.DomainResource domain) {
            for (org.hl7.fhir.r4.model.Resource contained : domain.getContained()) {
                if (contained instanceof org.hl7.fhir.r4.model.ValueSet vs && vs.hasUrl()) {
                    local.add(stripVersion(vs.getUrl()));
                } else if (contained instanceof org.hl7.fhir.r4.model.CodeSystem cs
                        && cs.hasUrl()) {
                    local.add(stripVersion(cs.getUrl()));
                }
            }
        }
        if (resource instanceof org.hl7.fhir.r4.model.Resource r4) {
            checkResourceBindings(r4, issues, local);
        }
    }

    /**
     * @param self the resource whose {@code meta.profile} claims are checked
     * @param local canonicals of contained ValueSets/CodeSystems: resolvable locally
     */
    private void checkResourceBindings(org.hl7.fhir.r4.model.Resource self,
            List<ValidationIssue> issues, java.util.Set<String> local) {
        if (!self.hasMeta()) {
            return;
        }
        for (org.hl7.fhir.r4.model.CanonicalType profile : self.getMeta().getProfile()) {
            String profileUrl = stripVersion(profile.getValue());
            if (profileUrl.isEmpty()) {
                continue;
            }
            IBaseResource definition = support.fetchStructureDefinition(profileUrl);
            if (definition == null) {
                issues.add(ValidationIssue.profileResolutionFailure(
                        ValidationIssue.Severity.WARNING,
                        "Profile '" + profileUrl
                                + "' could not be found among the loaded packages or the core specification.",
                        "meta.profile", null, null));
                continue;
            }
            if (!(definition instanceof StructureDefinition sd)) {
                continue;
            }
            for (ElementDefinition element : elementsOf(sd)) {
                ElementDefinition.ElementDefinitionBindingComponent binding = element.getBinding();
                if (binding == null || !binding.hasValueSet()) {
                    continue;
                }
                String strength = binding.getStrength() == null ? "" : binding.getStrength().toCode();
                if (!"required".equals(strength) && !"extensible".equals(strength)) {
                    continue;
                }
                String valueSetUrl = stripVersion(binding.getValueSet());
                if (valueSetUrl.isEmpty() || local.contains(valueSetUrl)) {
                    // A "#"-local reference always resolves inside the resource.
                    continue;
                }
                IBaseResource valueSet = support.fetchValueSet(valueSetUrl);
                if (valueSet == null) {
                    issues.add(ValidationIssue.terminologyResolutionFailure(
                            ValidationIssue.Severity.WARNING,
                            "ValueSet '" + valueSetUrl + "' (binding at '" + element.getPath()
                                    + "' in profile '" + profileUrl
                                    + "') could not be found; codes there cannot be checked.",
                            element.getPath(), null, null));
                    continue;
                }
                // The ValueSet itself resolved: every CodeSystem its compose
                // references must resolve too, otherwise expansion (and hence
                // any membership check) cannot be performed. Local contained
                // CodeSystems count as resolved.
                checkValueSetCompose(valueSet, valueSetUrl, element.getPath(), profileUrl, issues,
                        local);
            }
        }
    }

    /**
     * Reports each CodeSystem referenced by a resolved ValueSet's compose
     * that cannot itself be resolved. A {@code system} with inline
     * {@code concept} entries expands without the CodeSystem, so only
     * concept-less references are checked.
     */
    private void checkValueSetCompose(IBaseResource resolved, String valueSetUrl,
            String path, String profileUrl, List<ValidationIssue> issues,
            java.util.Set<String> local) {
        if (!(resolved instanceof org.hl7.fhir.r4.model.ValueSet valueSet)
                || !valueSet.hasCompose()) {
            return;
        }
        List<org.hl7.fhir.r4.model.ValueSet.ConceptSetComponent> sets =
                new ArrayList<>(valueSet.getCompose().getInclude());
        sets.addAll(valueSet.getCompose().getExclude());
        Set<String> reported = new LinkedHashSet<>();
        for (var set : sets) {
            if (!set.hasSystem() || set.hasConcept()) {
                continue;
            }
            String system = stripVersion(set.getSystem());
            if (system.isEmpty() || !reported.add(system) || local.contains(system)
                    || support.fetchCodeSystem(system) != null) {
                continue;
            }
            issues.add(ValidationIssue.terminologyResolutionFailure(
                    ValidationIssue.Severity.WARNING,
                    "CodeSystem '" + system + "' (compose of ValueSet '" + valueSetUrl
                            + "', binding at '" + path + "' in profile '" + profileUrl
                            + "') could not be found; codes from that system cannot be checked.",
                    path, null, null));
        }
    }

    /**
     * Describes a resolvable profile for the results list. The package that
     * provides it is not known here; {@link ValidationService} prefers the
     * package-aware description from the resource index.
     */
    ValidationProfile describe(String canonical) {
        if (support != null) {
            IBaseResource definition = support.fetchStructureDefinition(canonical);
            if (definition instanceof StructureDefinition sd) {
                String title = sd.hasTitle() ? sd.getTitle()
                        : (sd.hasName() ? sd.getName() : "");
                return new ValidationProfile(canonical, title, "", "");
            }
        }
        return new ValidationProfile(canonical, "", "", "");
    }

    /** Converts one validator message into a classified issue. */
    ValidationIssue classify(SingleValidationMessage message) {
        boolean terminologyResolutionFailure =
                refersToUnresolvedTerminology(message.getMessage());
        return new ValidationIssue(
                toSeverity(message.getSeverity()),
                message.getMessage(),
                message.getLocationString(),
                message.getLocationLine(),
                message.getLocationCol(),
                false,
                terminologyResolutionFailure,
                com.example.fhirviewer.model.ValidationLevel.R4_BASE,
                null);
    }

    /**
     * True when a validator message is about terminology that could not be
     * <em>resolved</em> (unknown ValueSet or CodeSystem), as opposed to a code
     * that was checked against a resolvable ValueSet and is simply not a
     * member of it.
     */
    boolean refersToUnresolvedTerminology(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        // HAPI issue-code markers, when they appear in the message text.
        if (lower.contains("valueset_notfound") || lower.contains("unknown_codesystem")) {
            return true;
        }
        boolean terminologyMessage = lower.contains("valueset") || lower.contains("value set")
                || lower.contains("codesystem") || lower.contains("code system")
                || lower.contains("unable to expand");
        if (!terminologyMessage) {
            return false;
        }

        // Collect the URLs by kind first. HAPI's combined membership message
        // ("The Coding provided (system#code) was not found in the value set
        // 'Name' (vs-url)") names both a resolvable ValueSet and a CodeSystem
        // coding at once, so the check below must see the ValueSet first: a
        // resolved ValueSet means the code was checked and found absent, which
        // is a genuine failure — no matter what the coding lookup would say.
        List<String> valueSetUrls = new ArrayList<>();
        List<String> codeSystemUrls = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String url = matcher.group();
            String before = lower.substring(Math.max(0, matcher.start() - CONTEXT), matcher.start());
            String own = lower.substring(matcher.start(),
                    Math.min(lower.length(), matcher.end()));
            boolean valueSet;
            boolean codeSystem;
            if (own.contains("/valueset") || own.contains("/codesystem")) {
                valueSet = own.contains("/valueset") && !own.contains("/codesystem");
                codeSystem = own.contains("/codesystem") && !own.contains("/valueset");
            } else {
                valueSet = before.contains("valueset") || before.contains("value set");
                codeSystem = !valueSet
                        && (before.contains("codesystem") || before.contains("code system"));
            }
            if (valueSet) {
                valueSetUrls.add(url);
            } else if (codeSystem) {
                codeSystemUrls.add(url);
            }
        }

        // "ValueSet not found": a named ValueSet that cannot be fetched.
        for (String url : valueSetUrls) {
            if (support.fetchValueSet(stripVersion(url)) == null) {
                return true;
            }
        }
        // "CodeSystem not found": a named CodeSystem that cannot be fetched —
        // but only when the message names NO resolvable ValueSet. Inside the
        // combined membership message the coding's system URL is expected to
        // be unresolvable as a CodeSystem resource, yet the ValueSet resolved
        // and the code was genuinely checked and found absent.
        if (valueSetUrls.isEmpty()) {
            for (String url : codeSystemUrls) {
                if (support.fetchCodeSystem(stripVersion(url)) == null) {
                    return true;
                }
            }
        }

        // A resolvable ValueSet that still failed to expand usually means one
        // of its compose systems has no fetchable CodeSystem.
        boolean expandFailure = lower.contains("unable to expand")
                || lower.contains("could not be expanded")
                || lower.contains("no matches for code");
        if (!valueSetUrls.isEmpty() && expandFailure) {
            for (String url : valueSetUrls) {
                if (composeRefersToMissingCodeSystem(url)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean composeRefersToMissingCodeSystem(String valueSetUrl) {
        IBaseResource resolved = support.fetchValueSet(stripVersion(valueSetUrl));
        if (!(resolved instanceof org.hl7.fhir.r4.model.ValueSet valueSet) || !valueSet.hasCompose()) {
            return false;
        }
        for (var set : valueSet.getCompose().getInclude()) {
            if (refersToMissingCodeSystem(set)) {
                return true;
            }
        }
        for (var set : valueSet.getCompose().getExclude()) {
            if (refersToMissingCodeSystem(set)) {
                return true;
            }
        }
        return false;
    }

    private boolean refersToMissingCodeSystem(org.hl7.fhir.r4.model.ValueSet.ConceptSetComponent set) {
        // A system with listed concepts expands without fetching the CodeSystem.
        return set.hasSystem() && !set.hasConcept()
                && support.fetchCodeSystem(stripVersion(set.getSystem())) == null;
    }

    private static List<ElementDefinition> elementsOf(StructureDefinition sd) {
        if (sd.hasSnapshot() && sd.getSnapshot().hasElement()) {
            return sd.getSnapshot().getElement();
        }
        if (sd.hasDifferential() && sd.getDifferential().hasElement()) {
            return sd.getDifferential().getElement();
        }
        return List.of();
    }

    private static String stripVersion(String canonical) {
        if (canonical == null) {
            return "";
        }
        String trimmed = canonical.trim();
        // Version pins are "url|version"; messages also render codings as
        // "system#code" — neither suffix belongs to the canonical itself.
        int pipe = trimmed.indexOf('|');
        if (pipe >= 0) {
            trimmed = trimmed.substring(0, pipe);
        }
        int hash = trimmed.indexOf('#');
        if (hash >= 0) {
            trimmed = trimmed.substring(0, hash);
        }
        return trimmed.trim();
    }

    private static ValidationIssue.Severity toSeverity(ResultSeverityEnum severity) {
        if (severity == null) {
            return ValidationIssue.Severity.INFORMATION;
        }
        return switch (severity) {
            case FATAL -> ValidationIssue.Severity.FATAL;
            case ERROR -> ValidationIssue.Severity.ERROR;
            case WARNING -> ValidationIssue.Severity.WARNING;
            case INFORMATION -> ValidationIssue.Severity.INFORMATION;
        };
    }
}
