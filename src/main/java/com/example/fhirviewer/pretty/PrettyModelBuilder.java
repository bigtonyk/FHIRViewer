package com.example.fhirviewer.pretty;

import java.util.List;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseExtension;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import com.example.fhirviewer.fhir.ElementProperty;
import com.example.fhirviewer.fhir.FhirModelAdapter;

/**
 * Builds the presentation model of a FHIR resource for the Pretty View.
 *
 * <p>The traversal is completely generic and driven by the model metadata the
 * same way the resource tree is: primitives and summarizable datatypes become
 * label/value rows, complex values become titled sections, repeating values are
 * numbered, and Bundle entries and contained resources become nested sections.
 * No FHIR resource or element is hard-coded; only datatypes receive special
 * formatting (see {@link DataTypeFormatter}).</p>
 */
public final class PrettyModelBuilder {

    /** Guard against pathological nesting (Bundle in Bundle in Bundle ...). */
    private static final int MAX_DEPTH = 12;

    /**
     * Resource level elements that are either redundant (the id is shown in the
     * document header) or pure noise in a human readable view.
     */
    private static final Set<String> RESOURCE_LEVEL_SKIPS =
            Set.of("id", "meta", "implicitRules", "language");

    private final FhirModelAdapter adapter;
    private final DataTypeFormatter formatter;

    public PrettyModelBuilder(FhirModelAdapter adapter) {
        this.adapter = adapter;
        this.formatter = new DataTypeFormatter(adapter);
    }

    /** Builds the presentation model for the supplied resource. */
    public PrettyDocument build(IBaseResource resource) {
        PrettyBlock.Builder header = PrettyBlock.builder("header");
        PrettyBlock.Builder body = PrettyBlock.builder("document");

        for (ElementProperty property : adapter.propertiesOf(resource)) {
            if (property.values().isEmpty()) {
                continue;
            }
            switch (property.name()) {
                case "meta" -> renderInto(header, property.values().get(0), 1);
                case "language" -> header.row("Language", formatter.textOf(property.values().get(0)));
                default -> {
                    // handled by the generic traversal below
                }
            }
        }
        renderInto(body, resource, 0);

        return new PrettyDocument(
                resource.fhirType(),
                resourceId(resource),
                header.rows(),
                body.rows(),
                body.children());
    }

    // ------------------------------------------------------------------
    // Traversal
    // ------------------------------------------------------------------

    /**
     * Renders the populated elements of a complex value or resource into a
     * block: primitives as rows, summarizable datatypes as rows (blocks at the
     * top level), everything else as child blocks.
     */
    private void renderInto(PrettyBlock.Builder target, IBase element, int depth) {
        boolean topLevel = depth == 0;
        for (ElementProperty property : adapter.propertiesOf(element)) {
            if (property.values().isEmpty()) {
                continue;
            }
            if (element instanceof IBaseResource && RESOURCE_LEVEL_SKIPS.contains(property.name())) {
                continue;
            }
            // The section title already names an extension, so the raw URL row is noise.
            if (element instanceof IBaseExtension<?, ?> && "url".equals(property.name())) {
                continue;
            }
            String label = friendly(property.name());
            List<IBase> values = property.values();
            IBase first = values.get(0);

            if (first instanceof IPrimitiveType<?>) {
                String joined = joinedPrimitiveText(values);
                if (!joined.isBlank()) {
                    target.row(label, joined);
                }
            } else if (formatter.isSummarizable(first) && !topLevel) {
                addSummaryRows(target, label, values);
            } else if (depth >= MAX_DEPTH) {
                target.row(label, "(recursion limit reached)");
            } else {
                for (int index = 0; index < values.size(); index++) {
                    target.child(valueBlock(label, values.get(index), index + 1, depth + 1));
                }
            }
        }
    }

    /** One or more rows with a one line summary per value. */
    private void addSummaryRows(PrettyBlock.Builder target, String label, List<IBase> values) {
        if (values.size() == 1) {
            String summary = formatter.summarize(values.get(0));
            if (!summary.isBlank()) {
                target.row(label, summary);
            }
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            String summary = formatter.summarize(values.get(index));
            if (!summary.isBlank()) {
                target.row(label + " " + (index + 1), summary);
            }
        }
    }

    /** A titled section for one value, filled with its own content. */
    private PrettyBlock valueBlock(String label, IBase value, int indexOneBased, int depth) {
        if (value instanceof IBaseResource resource) {
            // Nested resources (Bundle entries, contained resources) get an outer
            // section named after the element and an inner one named after the
            // resource itself, so both the position and the identity are visible.
            PrettyBlock.Builder outer = PrettyBlock.builder(label + " " + indexOneBased);
            outer.child(resourceBlock(resource, depth));
            return outer.build();
        }
        String title = "Extension".equals(value.fhirType())
                ? formatter.extensionName(value)
                : label + " " + indexOneBased;
        PrettyBlock.Builder block = PrettyBlock.builder(title);
        renderInto(block, value, depth);
        if (block.rows().isEmpty() && block.children().isEmpty()) {
            block.row("", "(no populated values)");
        }
        return block.build();
    }

    /** A section titled after the resource identity, for example <code>Patient/patient-a</code>. */
    private PrettyBlock resourceBlock(IBaseResource resource, int depth) {
        PrettyBlock.Builder block = PrettyBlock.builder(resource.fhirType() + idSuffix(resource));
        renderInto(block, resource, depth);
        return block.build();
    }

    private String resourceId(IBaseResource resource) {
        IIdType id = resource.getIdElement();
        return id == null ? null : id.getIdPart();
    }

    private String idSuffix(IBaseResource resource) {
        String id = resourceId(resource);
        return id == null || id.isBlank() ? "" : "/" + id;
    }

    private String joinedPrimitiveText(List<IBase> values) {
        StringBuilder sb = new StringBuilder();
        for (IBase value : values) {
            String text = formatter.textOf(value);
            if (!text.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /** <code>birthDate</code> becomes <code>Birth Date</code>. */
    private static String friendly(String elementName) {
        StringBuilder sb = new StringBuilder();
        for (String part : elementName.split("(?<!^)(?=[A-Z])")) {
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
