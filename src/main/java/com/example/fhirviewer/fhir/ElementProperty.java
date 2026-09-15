package com.example.fhirviewer.fhir;

import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBase;

/**
 * A single FHIR element ("property") of a model object, described in a way that is
 * independent of the FHIR version and of the HAPI model classes.
 *
 * <p>Populated by {@link FhirModelAdapter} implementations from the model metadata,
 * so the tree builder never needs to know about individual resources or elements.</p>
 *
 * @param name       the element name, for example <code>family</code>
 * @param typeCode   the declared datatype, for example <code>string</code>
 * @param definition the specification documentation for the element, when available
 * @param min        minimum cardinality
 * @param max        maximum cardinality (use {@link Integer#MAX_VALUE} for <code>*</code>)
 * @param values     the values present in this instance (empty when the element is not populated)
 */
public record ElementProperty(
        String name,
        String typeCode,
        String definition,
        int min,
        int max,
        List<IBase> values) {

    public ElementProperty {
        name = name == null ? "" : name;
        typeCode = typeCode == null ? "" : typeCode;
        values = List.copyOf(Objects.requireNonNull(values, "values"));
    }

    /** True when the element is allowed to repeat. */
    public boolean isRepeating() {
        return max != 1;
    }

    /** True when the instance populates this element. */
    public boolean hasValues() {
        return !values.isEmpty();
    }

    /** True when at least one value is required by the specification. */
    public boolean isRequired() {
        return min > 0;
    }
}