package com.example.fhirviewer.fhir;

import java.util.List;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseExtension;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import com.example.fhirviewer.model.ElementInfo;
import com.example.fhirviewer.model.ResourceNode;

/**
 * Builds the structured resource tree by walking the FHIR model dynamically.
 *
 * <p>Nothing in this class knows about <code>Patient</code>, <code>HumanName</code> or
 * any other concrete FHIR type: element names, datatypes, cardinalities and
 * documentation all come from the model metadata supplied by the
 * {@link FhirModelAdapter}. Adding support for a new FHIR resource therefore
 * requires no changes here.</p>
 *
 * <p>The generated hierarchy follows the shape used in the project plan:</p>
 * <pre>
 * Patient
 *   name
 *     [0]
 *       family: Smith
 *       given
 *         [0]: John
 *         [1]: Jacob
 * </pre>
 */
public class ResourceTreeBuilder {

    private final FhirModelAdapter adapter;

    public ResourceTreeBuilder(FhirModelAdapter adapter) {
        this.adapter = adapter;
    }

    /**
     * Builds a tree for a resource.
     *
     * @param resource           the resource to display
     * @param includeUnpopulated when {@code true} elements that are defined but not
     *                           populated are shown as empty nodes
     * @return the root node of the tree
     */
    public ResourceNode build(IBaseResource resource, boolean includeUnpopulated) {
        return build(resource, resource.fhirType(), idOf(resource), includeUnpopulated);
    }

    /**
     * Builds a tree for a resource using an explicit label (used for Bundle entries,
     * where the label identifies the entry resource).
     */
    public ResourceNode build(IBaseResource resource, String label, String valueText, boolean includeUnpopulated) {
        ElementInfo info = ElementInfo.builder()
                .path(resource.fhirType())
                .name(label)
                .typeCode(resource.fhirType())
                .kind(ElementInfo.Kind.RESOURCE)
                .cardinality(0, 1)
                .valueText(valueText)
                .build();
        ResourceNode root = new ResourceNode(label, info, valueText);
        addChildren(root, resource, resource.fhirType(), includeUnpopulated);
        return root;
    }

    private void addChildren(ResourceNode parent, IBase element, String path, boolean includeUnpopulated) {
        addChildProperties(parent, adapter.propertiesOf(element), path, includeUnpopulated);
    }

    private void addChildProperties(
            ResourceNode parent,
            List<ElementProperty> properties,
            String path,
            boolean includeUnpopulated) {

        for (ElementProperty property : properties) {
            List<IBase> values = property.values();

            if (values.isEmpty() && !includeUnpopulated) {
                continue;
            }

            String propertyPath = path + "." + property.name();

            if (property.isRepeating() || values.size() > 1) {
                addRepeatingProperty(parent, property, values, propertyPath, includeUnpopulated);
            } else if (values.isEmpty()) {
                // Only reachable when includeUnpopulated is enabled.
                ElementInfo info = ElementInfo.builder()
                        .path(propertyPath)
                        .name(property.name())
                        .typeCode(property.typeCode())
                        .definition(property.definition())
                        .cardinality(property.min(), property.max())
                        .kind(ElementInfo.Kind.COMPLEX)
                        .build();
                parent.addChild(new ResourceNode(property.name(), info));
            } else {
                addValueNode(
                        parent,
                        property,
                        values.get(0),
                        property.name(),
                        propertyPath,
                        property.min(),
                        property.max(),
                        includeUnpopulated);
            }
        }
    }

    private void addRepeatingProperty(
            ResourceNode parent,
            ElementProperty property,
            List<IBase> values,
            String propertyPath,
            boolean includeUnpopulated) {

        ElementInfo groupInfo = ElementInfo.builder()
                .path(propertyPath)
                .name(property.name())
                .typeCode(property.typeCode())
                .definition(property.definition())
                .cardinality(property.min(), property.max())
                .kind(ElementInfo.Kind.COMPLEX)
                .build();
        ResourceNode group = new ResourceNode(property.name(), groupInfo);
        parent.addChild(group);

        for (int index = 0; index < values.size(); index++) {
            addValueNode(
                    group,
                    property,
                    values.get(index),
                    "[" + index + "]",
                    propertyPath + "[" + index + "]",
                    1,
                    1,
                    includeUnpopulated);
        }
    }

    /**
     * Adds the node that carries a single value: a primitive value is rendered inline,
     * complex values become expandable parents of their own children.
     */
    private void addValueNode(
            ResourceNode parent,
            ElementProperty property,
            IBase value,
            String label,
            String path,
            int min,
            int max,
            boolean includeUnpopulated) {

        ElementInfo.Kind kind = kindOf(value);
        String valueText = valueTextOf(value);
        ElementInfo info = ElementInfo.builder()
                .path(path)
                .name(property.name())
                .typeCode(value.fhirType())
                .definition(property.definition())
                .cardinality(min, max)
                .kind(kind)
                .valueText(valueText)
                .build();

        ResourceNode node = new ResourceNode(labelFor(value, label), info, valueText);
        parent.addChild(node);

        // Primitives only get children when they carry something extra (an id or an
        // extension); complex values, references and nested resources are expanded.
        List<ElementProperty> childProperties = adapter.propertiesOf(value);
        if (isComplex(value) || hasAnyValues(childProperties)) {
            addChildProperties(node, childProperties, path, includeUnpopulated);
        }
    }

    /** The text shown inline for a value: primitives render their value, references their target. */
    private String valueTextOf(IBase value) {
        if (value instanceof IPrimitiveType<?> primitive) {
            return primitive.hasValue() ? primitive.getValueAsString() : null;
        }
        if (value instanceof IBaseReference reference) {
            return referenceTextOf(reference);
        }
        if (value instanceof IBaseResource resource) {
            String id = idOf(resource);
            return id == null ? resource.fhirType() : resource.fhirType() + "/" + id;
        }
        return null;
    }

    private String referenceTextOf(IBaseReference reference) {
        IIdType referenceElement = reference.getReferenceElement();
        String referenceValue = referenceElement == null ? null : referenceElement.getValue();
        IPrimitiveType<String> display = reference.getDisplayElement();
        String displayValue = display != null && display.hasValue() ? display.getValueAsString() : null;

        if (referenceValue == null || referenceValue.isBlank()) {
            return displayValue;
        }
        if (displayValue == null || displayValue.isBlank()) {
            return referenceValue;
        }
        return referenceValue + " (" + displayValue + ")";
    }

    /** Extensions are labelled with their canonical URL so they are easy to identify. */
    private String labelFor(IBase value, String defaultLabel) {
        if (value instanceof IBaseExtension<?, ?> extension) {
            String url = extension.getUrl();
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return defaultLabel;
    }

    private ElementInfo.Kind kindOf(IBase value) {
        if (value instanceof IPrimitiveType<?>) {
            return ElementInfo.Kind.PRIMITIVE;
        }
        if (value instanceof IBaseExtension<?, ?>) {
            return ElementInfo.Kind.EXTENSION;
        }
        if (value instanceof IBaseReference) {
            return ElementInfo.Kind.REFERENCE;
        }
        if (value instanceof IBaseResource) {
            return ElementInfo.Kind.NESTED_RESOURCE;
        }
        return ElementInfo.Kind.COMPLEX;
    }

    private boolean isComplex(IBase value) {
        return !(value instanceof IPrimitiveType<?>);
    }

    private boolean hasAnyValues(List<ElementProperty> properties) {
        for (ElementProperty property : properties) {
            if (property.hasValues()) {
                return true;
            }
        }
        return false;
    }

    private static String idOf(IBaseResource resource) {
        IIdType id = resource.getIdElement();
        if (id == null || !id.hasIdPart()) {
            return null;
        }
        return id.getIdPart();
    }
}