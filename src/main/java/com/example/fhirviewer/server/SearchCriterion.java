package com.example.fhirviewer.server;

import java.util.Objects;

/**
 * One search parameter: a name/value pair such as <code>name = Smith</code>.
 *
 * <p>Only exact pairs are modelled for now: the plugin turns each pair into one query
 * parameter. Modifiers, prefixes, chains and composites can be added later as new
 * criterion kinds without changing this type.</p>
 */
public final class SearchCriterion {

    private final String name;
    private final String value;

    public SearchCriterion(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A search parameter name is required.");
        }
        this.name = name;
        this.value = Objects.requireNonNull(value, "value");
    }

    /** The search parameter name, for example <code>name</code>. */
    public String name() {
        return name;
    }

    /** The value to look for, for example <code>Smith</code>. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return name + "=" + value;
    }
}
