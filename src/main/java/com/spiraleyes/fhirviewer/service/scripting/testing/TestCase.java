package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.util.Objects;

/**
 * One named case in a saved test suite: a block of JShell source.
 *
 * <p>Each case runs in its own {@code ScriptingSession}, so a case may declare
 * the same names as another without interference.</p>
 *
 * @param name    the case name from its {@code // case:} marker; never blank
 * @param source  the case's source; never {@code null}
 */
public record TestCase(String name, String source) {

    public TestCase {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        if (name.isBlank()) {
            throw new IllegalArgumentException("A test case needs a name.");
        }
    }
}
