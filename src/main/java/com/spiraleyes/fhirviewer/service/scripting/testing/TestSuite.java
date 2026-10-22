package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.util.List;
import java.util.Objects;

/**
 * An ordered collection of test cases run sequentially by default.
 */
public record TestSuite(String name, List<TestCase> cases) {
    public TestSuite {
        Objects.requireNonNull(name, "name");
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public int size() { return cases.size(); }
}
