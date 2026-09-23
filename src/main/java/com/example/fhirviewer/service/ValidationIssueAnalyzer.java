package com.example.fhirviewer.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.ElementDefinition;
import org.hl7.fhir.r4.model.StructureDefinition;

import com.example.fhirviewer.model.ValidationIssue;

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
     * that cannot be fetched (profile resolution) and required/extensible
     * bindings whose ValueSet cannot be fetched (terminology resolution).
     */
    void checkProfileBindings(IBaseResource resource, List<ValidationIssue> issues) {
        if (!(resource instanceof org.hl7.fhir.r4.model.Resource r4) || !r4.hasMeta()) {
            return;
        }
        for (org.hl7.fhir.r4.model.CanonicalType profile : r4.getMeta().getProfile()) {
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
                if (valueSetUrl.isEmpty() || support.fetchValueSet(valueSetUrl) != null) {
                    continue;
                }
                issues.add(ValidationIssue.terminologyResolutionFailure(
                        ValidationIssue.Severity.WARNING,
                        "ValueSet '" + valueSetUrl + "' (binding at '" + element.getPath()
                                + "' in profile '" + profileUrl
                                + "') could not be found; codes there cannot be checked.",
                        element.getPath(), null, null));
            }
        }
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
                terminologyResolutionFailure);
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

        boolean valueSetMentioned = false;
        java.util.List<String> valueSetUrls = new java.util.ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String url = matcher.group();
            String before = lower.substring(Math.max(0, matcher.start() - CONTEXT), matcher.start());
            String path = lower.substring(matcher.start(), Math.min(lower.length(), matcher.end()));
            boolean valueSet = path.contains("/valueset")
                    || before.contains("valueset") || before.contains("value set");
            boolean codeSystem = path.contains("/codesystem")
                    || before.contains("codesystem") || before.contains("code system");
            if (valueSet) {
                valueSetMentioned = true;
                valueSetUrls.add(url);
                if (support.fetchValueSet(stripVersion(url)) == null) {
                    return true; // "ValueSet not found"
                }
            } else if (codeSystem && support.fetchCodeSystem(stripVersion(url)) == null) {
                return true; // CodeSystem named by the message is unknown
            }
        }

        // A resolvable ValueSet that still failed to expand usually means one
        // of its compose systems has no fetchable CodeSystem.
        boolean expandFailure = lower.contains("unable to expand")
                || lower.contains("could not be expanded")
                || lower.contains("no matches for code");
        if (valueSetMentioned && expandFailure) {
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
