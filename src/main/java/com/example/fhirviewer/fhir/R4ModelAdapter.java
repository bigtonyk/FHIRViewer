package com.example.fhirviewer.fhir;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Property;
import org.hl7.fhir.r4.model.Resource;

import com.example.fhirviewer.model.BundleEntryInfo;

/**
 * FHIR R4 implementation of {@link FhirModelAdapter}.
 *
 * <p>This is the only class in the code base that references the version specific
 * model package <code>org.hl7.fhir.r4.model</code>. Everything is derived from the
 * model metadata that HAPI exposes through {@link Base#children()}, so no individual
 * FHIR resource or element is hard-coded.</p>
 */
public final class R4ModelAdapter implements FhirModelAdapter {

    /** The suffix the model metadata uses for choice elements, for example <code>value[x]</code>. */
    private static final String CHOICE_SUFFIX = "[x]";

    @Override
    public String fhirVersionName() {
        return "R4";
    }

    @Override
    public List<ElementProperty> propertiesOf(IBase element) {
        List<ElementProperty> properties = new ArrayList<>();
        if (!(element instanceof Base base)) {
            return properties;
        }
        for (Property property : base.children()) {
            List<IBase> values = new ArrayList<>();
            for (Base value : property.getValues()) {
                if (value != null) {
                    values.add(value);
                }
            }
            properties.add(new ElementProperty(
                    elementName(property, values),
                    property.getTypeCode(),
                    property.getDefinition(),
                    property.getMinCardinality(),
                    property.getMaxCardinality(),
                    values));
        }
        return properties;
    }

    /**
     * Returns the element name, resolving choice elements to the concrete type that is
     * present in the instance.
     *
     * <p>The model metadata names a choice element <code>value[x]</code>; the FHIR
     * specification names the concrete element after the type in use, for example
     * <code>valueQuantity</code> or <code>effectiveDateTime</code>. Using the concrete
     * name keeps the paths shown in the tree identical to the paths used in the
     * specification and in FHIRPath.</p>
     */
    private static String elementName(Property property, List<IBase> values) {
        String name = property.getName();
        if (name == null || !name.endsWith(CHOICE_SUFFIX) || values.isEmpty()) {
            return name == null ? "" : name;
        }
        String typeCode = values.get(0).fhirType();
        if (typeCode == null || typeCode.isBlank()) {
            return name;
        }
        return name.substring(0, name.length() - CHOICE_SUFFIX.length())
                + Character.toUpperCase(typeCode.charAt(0))
                + typeCode.substring(1);
    }

    @Override
    public List<BundleEntryInfo> entriesOf(IBaseResource resource) {
        if (!(resource instanceof Bundle bundle)) {
            return List.of();
        }
        List<BundleEntryInfo> entries = new ArrayList<>();
        List<BundleEntryComponent> entryComponents = bundle.getEntry();
        for (int index = 0; index < entryComponents.size(); index++) {
            BundleEntryComponent entry = entryComponents.get(index);
            Resource entryResource = entry.getResource();
            String resourceType = entryResource == null ? null : entryResource.fhirType();
            String resourceId = null;
            if (entryResource != null) {
                IIdType idElement = entryResource.getIdElement();
                if (idElement != null && idElement.hasIdPart()) {
                    resourceId = idElement.getIdPart();
                }
            }
            entries.add(BundleEntryInfo.of(index, entry.getFullUrl(), resourceType, resourceId, entryResource));
        }
        return entries;
    }
}