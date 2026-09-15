package com.example.fhirviewer.pretty;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import com.example.fhirviewer.fhir.ElementProperty;
import com.example.fhirviewer.fhir.FhirModelAdapter;

/**
 * Renders FHIR values as human friendly text for the Pretty View.
 *
 * <p>Like the tree builder this class is generic: it special cases a small set
 * of <em>datatypes</em> (HumanName, Quantity, Reference, ...) whose values have
 * a conventional friendly rendering, and falls back to primitive text for
 * everything else. It never switches on the resource type.</p>
 */
public final class DataTypeFormatter {

    /** Well known terminology systems and their short names. */
    private static final Map<String, String> SYSTEM_NAMES = Map.of(
            "http://loinc.org", "LOINC",
            "http://snomed.info/sct", "SNOMED CT",
            "http://unitsofmeasure.org", "UCUM",
            "http://www.nlm.nih.gov/research/umls/rxnorm", "RxNorm");

    /**
     * FHIR date/time values, for example <code>2024-05-01T10:15:00Z</code>,
     * rendered as <code>2024-05-01 10:15:00 UTC</code> (date alone when no time
     * is present).
     */
    private static final Pattern DATE_TIME = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})(?:T(\\d{2}:\\d{2})(?::(\\d{2}(?:\\.\\d+)?))?(Z|[+-]\\d{2}:\\d{2})?)?$");

    /** Datatypes whose values get a one line summary instead of a sub block. */
    private static final Set<String> SUMMARIZABLE = Set.of(
            "HumanName", "Address", "ContactPoint", "Identifier", "CodeableConcept",
            "Coding", "Quantity", "Duration", "Age", "Money", "Reference", "Period",
            "Range", "Ratio", "Annotation", "Attachment", "Extension");

    private final FhirModelAdapter adapter;

    public DataTypeFormatter(FhirModelAdapter adapter) {
        this.adapter = adapter;
    }

    /** True when {@link #summarize} produces a useful one line rendering. */
    public boolean isSummarizable(IBase value) {
        return SUMMARIZABLE.contains(value.fhirType());
    }

    /** The text of a primitive value, formatted for display. */
    public String textOf(IBase value) {
        if (!(value instanceof IPrimitiveType<?> primitive)) {
            return "";
        }
        if (!primitive.hasValue()) {
            return "";
        }
        String text = primitive.getValueAsString();
        if (text == null || text.isBlank()) {
            return "";
        }
        return switch (value.fhirType()) {
            case "dateTime", "instant" -> prettifyDateTime(text);
            case "xhtml" -> stripXhtml(text);
            case "base64Binary" -> "[base64 data, " + text.length() + " characters]";
            default -> text;
        };
    }

    /**
     * A one line, human friendly rendering of a value, for example
     * <code>72 beats/minute</code> for a Quantity or
     * <code>Heart rate (LOINC 8867-4)</code> for a Coding.
     */
    public String summarize(IBase value) {
        return switch (value.fhirType()) {
            case "HumanName" -> summarizeHumanName(value);
            case "Address" -> summarizeAddress(value);
            case "ContactPoint" -> summarizeContactPoint(value);
            case "Identifier" -> summarizeIdentifier(value);
            case "CodeableConcept" -> summarizeCodeableConcept(value);
            case "Coding" -> summarizeCoding(value);
            case "Quantity", "Duration", "Age" -> summarizeQuantity(value);
            case "Money" -> summarizeMoney(value);
            case "Reference" -> summarizeReference(value);
            case "Period" -> summarizePeriod(value);
            case "Range" -> summarizeRange(value);
            case "Ratio" -> summarizeRatio(value);
            case "Annotation" -> summarizeAnnotation(value);
            case "Attachment" -> summarizeAttachment(value);
            case "Extension" -> summarizeExtension(value);
            default -> textOf(value);
        };
    }

    /** A short label for an extension, derived from its canonical URL. */
    public String extensionName(IBase extension) {
        String url = firstText(extension, "url");
        if (url == null || url.isBlank()) {
            return "Extension";
        }
        String tail = url;
        if (tail.startsWith("#")) {
            tail = tail.substring(1);
        } else {
            int slash = tail.lastIndexOf('/');
            if (slash >= 0) {
                tail = tail.substring(slash + 1);
            }
        }
        if (tail.isBlank()) {
            return "Extension";
        }
        return titleWords(tail);
    }

    private String summarizeHumanName(IBase name) {
        String text = firstText(name, "text");
        if (text != null && !text.isBlank()) {
            return text;
        }
        return joinNonBlank(
                firstText(name, "prefix"),
                joinedText(name, "given", " "),
                firstText(name, "family"),
                joinedText(name, "suffix", " "));
    }

    private String summarizeAddress(IBase address) {
        String state = firstText(address, "state");
        String postalCode = firstText(address, "postalCode");
        return joinWith(", ",
                joinedText(address, "line", ", "),
                firstText(address, "city"),
                joinNonBlank(state, postalCode),
                firstText(address, "country"));
    }

    private String summarizeContactPoint(IBase contact) {
        String value = firstText(contact, "value");
        String qualifier = joinNonBlank(firstText(contact, "system"), firstText(contact, "use"));
        if (qualifier.isBlank()) {
            return value == null ? "" : value;
        }
        return (value == null || value.isBlank() ? "" : value + " ")
                + "(" + qualifier + ")";
    }

    private String summarizeIdentifier(IBase identifier) {
        return firstText(identifier, "value");
    }

    private String summarizeCodeableConcept(IBase concept) {
        String text = firstText(concept, "text");
        if (text != null && !text.isBlank()) {
            return text;
        }
        for (ElementProperty property : adapter.propertiesOf(concept)) {
            if ("coding".equals(property.name())) {
                for (IBase coding : property.values()) {
                    String summary = summarizeCoding(coding);
                    if (!summary.isBlank()) {
                        return summary;
                    }
                }
            }
        }
        return "";
    }

    private String summarizeCoding(IBase coding) {
        String display = firstText(coding, "display");
        String code = firstText(coding, "code");
        String systemLabel = friendlySystemName(firstText(coding, "system"));
        // Only well known systems are shown with their code; unknown canonical
        // URLs would add noise without helping a human reader.
        if (systemLabel == null || !SYSTEM_NAMES.containsValue(systemLabel)) {
            if (display != null && !display.isBlank()) {
                return display;
            }
            return code == null ? "" : code;
        }
        String inParens = joinNonBlank(systemLabel, code);
        if (inParens.isBlank()) {
            return display == null ? "" : display;
        }
        return (display == null || display.isBlank())
                ? "(" + inParens + ")"
                : display + " (" + inParens + ")";
    }

    private String summarizeQuantity(IBase quantity) {
        String value = firstText(quantity, "value");
        String unit = firstText(quantity, "unit");
        String comparator = firstText(quantity, "comparator");
        String code = firstText(quantity, "code");
        StringBuilder sb = new StringBuilder();
        if (comparator != null && !comparator.isBlank()) {
            sb.append(comparator).append(' ');
        }
        sb.append(joinNonBlank(value, unit));
        if (sb.isEmpty() && code != null && !code.isBlank()) {
            sb.append(code);
        }
        if (value != null && !value.isBlank()) {
            String systemCode = joinNonBlank(friendlySystemName(firstText(quantity, "system")), code);
            if (!systemCode.isBlank()) {
                sb.append(" (").append(systemCode).append(')');
            }
        }
        return sb.toString();
    }

    private String summarizeMoney(IBase money) {
        return joinNonBlank(firstText(money, "value"), firstText(money, "currency"));
    }

    private String summarizeReference(IBase value) {
        String target = null;
        String display = null;
        if (value instanceof IBaseReference reference) {
            IIdType referenceElement = reference.getReferenceElement();
            target = referenceElement == null ? null : referenceElement.getValue();
            IPrimitiveType<String> displayElement = reference.getDisplayElement();
            display = displayElement != null && displayElement.hasValue() ? displayElement.getValueAsString() : null;
        }
        if (target == null || target.isBlank()) {
            return display == null ? "" : display;
        }
        String targetLabel = target.startsWith("#") ? "(contained resource)" : target;
        if (display == null || display.isBlank()) {
            return targetLabel;
        }
        return display + " (" + targetLabel + ")";
    }

    private String summarizePeriod(IBase period) {
        String start = firstText(period, "start");
        String end = firstText(period, "end");
        if ((start == null || start.isBlank()) && (end == null || end.isBlank())) {
            return "";
        }
        return (start == null || start.isBlank() ? "" : start)
                + " → "
                + (end == null || end.isBlank() ? "(ongoing)" : end);
    }

    private String summarizeRange(IBase range) {
        String low = summarizeQuantityIfPresent(range, "low");
        String high = summarizeQuantityIfPresent(range, "high");
        if (low.isBlank() && high.isBlank()) {
            return "";
        }
        if (low.isBlank()) {
            return "≤ " + high;
        }
        if (high.isBlank()) {
            return "≥ " + low;
        }
        return low + " → " + high;
    }

    private String summarizeRatio(IBase ratio) {
        String numerator = summarizeQuantityIfPresent(ratio, "numerator");
        String denominator = summarizeQuantityIfPresent(ratio, "denominator");
        if (numerator.isBlank() && denominator.isBlank()) {
            return "";
        }
        return numerator + " / " + denominator;
    }

    private String summarizeAnnotation(IBase annotation) {
        String time = firstText(annotation, "time");
        return joinNonBlank(
                firstText(annotation, "text"),
                time == null || time.isBlank() ? null : "(" + time + ")");
    }

    private String summarizeAttachment(IBase attachment) {
        String type = firstText(attachment, "contentType");
        return joinNonBlank(
                firstText(attachment, "title"),
                firstText(attachment, "url"),
                type == null || type.isBlank() ? null : "(" + type + ")");
    }

    private String summarizeExtension(IBase extension) {
        String name = extensionName(extension);
        for (ElementProperty property : adapter.propertiesOf(extension)) {
            if (property.name().startsWith("value") && !property.values().isEmpty()) {
                return name + " = " + summarize(property.values().get(0));
            }
        }
        return name;
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    /** The text of the first populated primitive child element with the given name. */
    private String firstText(IBase element, String name) {
        for (ElementProperty property : adapter.propertiesOf(element)) {
            if (!name.equals(property.name()) || property.values().isEmpty()) {
                continue;
            }
            String text = textOf(property.values().get(0));
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    /** All primitive values of a repeating child element, joined with ", ". */
    private String joinedText(IBase element, String name) {
        return joinedText(element, name, ", ");
    }

    /** All primitive values of a repeating child element, joined with the given separator. */
    private String joinedText(IBase element, String name, String separator) {
        StringBuilder sb = new StringBuilder();
        for (ElementProperty property : adapter.propertiesOf(element)) {
            if (!name.equals(property.name())) {
                continue;
            }
            for (IBase value : property.values()) {
                String text = textOf(value);
                if (!text.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append(separator);
                    }
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    /** Joins the given fragments with the separator, skipping null and blank ones. */
    private static String joinWith(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(separator);
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /** Summary of a child quantity element such as {@code Range.low}. */
    private String summarizeQuantityIfPresent(IBase parent, String name) {
        for (ElementProperty property : adapter.propertiesOf(parent)) {
            if (name.equals(property.name()) && !property.values().isEmpty()) {
                return summarizeQuantity(property.values().get(0));
            }
        }
        return "";
    }

    /** Joins the given fragments, skipping null and blank ones. */
    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /**
     * <code>http://loinc.org</code> becomes <code>LOINC</code>; unknown systems
     * fall back to the host and path without the scheme.
     */
    private static String friendlySystemName(String system) {
        if (system == null || system.isBlank()) {
            return null;
        }
        String named = SYSTEM_NAMES.get(system);
        return named != null ? named : system.replaceFirst("^https?://", "");
    }

    /** <code>2024-05-01T10:15:00Z</code> becomes <code>2024-05-01 10:15:00 UTC</code>. */
    private static String prettifyDateTime(String raw) {
        Matcher matcher = DATE_TIME.matcher(raw);
        if (!matcher.matches()) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(matcher.group(1));
        if (matcher.group(2) != null) {
            sb.append(' ').append(matcher.group(2));
            if (matcher.group(3) != null) {
                sb.append(':').append(matcher.group(3));
            }
            String zone = matcher.group(4);
            if (zone != null) {
                sb.append(zone.equals("Z") ? " UTC" : " " + zone);
            }
        }
        return sb.toString();
    }

    /** Reduces XHTML markup to plain text, used for Narrative contents. */
    private static String stripXhtml(String xhtml) {
        return xhtml.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    /** <code>patient-race</code> becomes <code>Patient Race</code>. */
    private static String titleWords(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split("[-_]")) {
            if (part.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}