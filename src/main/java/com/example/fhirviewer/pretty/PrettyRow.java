package com.example.fhirviewer.pretty;

/**
 * One label/value line inside a {@link PrettyBlock}, for example
 * <code>City: Springfield</code>.
 *
 * <p>Part of the presentation model built by {@link PrettyModelBuilder} and
 * rendered by the JavaFX Pretty View; free of FHIR and JavaFX types.</p>
 *
 * @param label       the row label, for example <code>Postal Code</code>; empty when the
 *                    row only makes sense in the context of its section
 * @param value       the rendered value
 * @param elementName the FHIR element the row was rendered from, for example
 *                    <code>postalCode</code>; used by the UI to jump from the
 *                    resource tree to the matching row
 */
public record PrettyRow(String label, String value, String elementName) {

    public PrettyRow(String label, String value) {
        this(label, value, "");
    }

    public PrettyRow {
        label = label == null ? "" : label;
        value = value == null ? "" : value;
        elementName = elementName == null ? "" : elementName;
    }
}