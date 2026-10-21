package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.util.Objects;

/**
 * One named test in a suite: a source snippet plus the bindings it runs with.
 *
 * <p>{@code destructive} marks tests that perform writes/deletes; the runner
 * requires confirmation before executing them. {@code serverLabel} records the
 * target server/environment for reporting.</p>
 */
public record TestCase(
        String name,
        String source,
        com.spiraleyes.fhirviewer.service.scripting.api.ScriptBindings bindings,
        boolean destructive,
        String serverLabel) {

    public TestCase {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    }

    public static TestCase of(String name, String source,
            com.spiraleyes.fhirviewer.service.scripting.api.ScriptBindings bindings) {
        return new TestCase(name, source, bindings, false, "");
    }
}
