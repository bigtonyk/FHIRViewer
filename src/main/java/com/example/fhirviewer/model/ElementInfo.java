package com.example.fhirviewer.model;

import java.util.Objects;

/**
 * Immutable description of a single element (or list entry) inside a FHIR resource.
 *
 * <p>This type is deliberately free of JavaFX and HAPI types so that it can be
 * produced by the FHIR layer and consumed by the UI layer.</p>
 */
public final class ElementInfo {

    /** Broad classification of an element, used for display purposes only. */
    public enum Kind {
        /** The root node: a resource such as <code>Patient</code>. */
        RESOURCE,
        /** A primitive value such as <code>string</code> or <code>date</code>. */
        PRIMITIVE,
        /** A complex datatype or backbone element such as <code>HumanName</code>. */
        COMPLEX,
        /** A <code>Reference</code> to another resource. */
        REFERENCE,
        /** An <code>Extension</code>. */
        EXTENSION,
        /** A resource nested inside another resource (contained or bundle entry). */
        NESTED_RESOURCE
    }

    private final String path;
    private final String name;
    private final String typeCode;
    private final String definition;
    private final int min;
    private final int max;
    private final Kind kind;
    private final String valueText;

    private ElementInfo(Builder builder) {
        this.path = builder.path;
        this.name = builder.name;
        this.typeCode = builder.typeCode;
        this.definition = builder.definition;
        this.min = builder.min;
        this.max = builder.max;
        this.kind = builder.kind;
        this.valueText = builder.valueText;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    /** The FHIR datatype name, for example <code>string</code> or <code>HumanName</code>. */
    public String getTypeCode() {
        return typeCode;
    }

    /** The specification documentation for the element, when available. */
    public String getDefinition() {
        return definition;
    }

    public boolean hasDefinition() {
        return definition != null && !definition.isBlank();
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public Kind getKind() {
        return kind;
    }

    /** The rendered primitive value, or {@code null} when the element has no primitive value. */
    public String getValueText() {
        return valueText;
    }

    public boolean hasValueText() {
        return valueText != null && !valueText.isEmpty();
    }

    /** Cardinality in FHIR notation, for example <code>0..1</code>, <code>1..*</code>. */
    public String getCardinality() {
        return min + ".." + (max == Integer.MAX_VALUE ? "*" : Integer.toString(max));
    }

    /** True when the element may repeat. */
    public boolean isRepeating() {
        return max != 1;
    }

    /** True when at least one value is required. */
    public boolean isRequired() {
        return min > 0;
    }

    public boolean isResource() {
        return kind == Kind.RESOURCE || kind == Kind.NESTED_RESOURCE;
    }

    @Override
    public String toString() {
        return path + " : " + typeCode + " " + getCardinality();
    }

    /** Mutable builder for {@link ElementInfo}. */
    public static final class Builder {

        private String path = "";
        private String name = "";
        private String typeCode = "";
        private String definition;
        private int min;
        private int max = 1;
        private Kind kind = Kind.COMPLEX;
        private String valueText;

        public Builder path(String value) {
            this.path = Objects.requireNonNull(value, "path");
            return this;
        }

        public Builder name(String value) {
            this.name = Objects.requireNonNull(value, "name");
            return this;
        }

        public Builder typeCode(String value) {
            this.typeCode = value == null ? "" : value;
            return this;
        }

        public Builder definition(String value) {
            this.definition = value;
            return this;
        }

        public Builder cardinality(int newMin, int newMax) {
            this.min = newMin;
            this.max = newMax;
            return this;
        }

        public Builder kind(Kind value) {
            this.kind = Objects.requireNonNull(value, "kind");
            return this;
        }

        public Builder valueText(String value) {
            this.valueText = value;
            return this;
        }

        public ElementInfo build() {
            return new ElementInfo(this);
        }
    }
}