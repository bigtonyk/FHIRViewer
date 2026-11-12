package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A saved suite of test cases, parsed from an ordinary {@code .jsh} buffer.
 *
 * <p>The format is deliberately small: a line whose only content is
 * {@code // case: name} starts a case, and everything after it up to the next
 * marker is that case's source. A buffer without markers is one case named
 * after the suite, which makes any saved script runnable as a test.</p>
 *
 * <p>Text before the first marker is rejected rather than silently dropped or
 * silently attached to the first case: a shared preamble could not affect the
 * other cases anyway, because every case starts in a fresh session.</p>
 *
 * @param name  the suite name, normally the file name; never blank
 * @param cases the cases in file order; never empty
 */
public record TestSuite(String name, List<TestCase> cases) {

    private static final Pattern MARKER =
            Pattern.compile("^\\s*//\\s*case:\\s*(\\S.*)$", Pattern.CASE_INSENSITIVE);
    /** A marker with no name: refused rather than folded into the previous case. */
    private static final Pattern NAMELESS_MARKER =
            Pattern.compile("^\\s*//\\s*case:\\s*$", Pattern.CASE_INSENSITIVE);

    public TestSuite {
        Objects.requireNonNull(name, "name");
        cases = cases == null ? List.of() : List.copyOf(cases);
        if (name.isBlank()) {
            throw new IllegalArgumentException("A test suite needs a name.");
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("A test suite needs at least one case.");
        }
    }

    /**
     * Parses a buffer into cases.
     *
     * @param name   the suite name, normally the file name
     * @param source the buffer's text
     * @return the parsed suite
     * @throws IllegalArgumentException when a marker has no name, or text precedes
     *                                  the first marker
     */
    public static TestSuite parse(String name, String source) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        List<TestCase> cases = new ArrayList<>();
        String currentName = null;
        StringBuilder body = new StringBuilder();
        String[] lines = source.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (NAMELESS_MARKER.matcher(lines[index]).matches()) {
                throw new IllegalArgumentException("The // case: marker on line "
                        + (index + 1) + " needs a name.");
            }
            Matcher marker = MARKER.matcher(lines[index]);
            if (marker.matches()) {
                if (currentName == null && !body.toString().isBlank()) {
                    throw new IllegalArgumentException("Text before the first // case: marker"
                            + " on line " + (index + 1) + " belongs to no case.");
                }
                if (currentName != null) {
                    cases.add(new TestCase(currentName, body.toString()));
                }
                currentName = marker.group(1).trim();
                body.setLength(0);
            } else {
                body.append(lines[index]).append('\n');
            }
        }
        if (currentName == null) {
            if (source.isBlank()) {
                throw new IllegalArgumentException("The buffer has no source to test.");
            }
            return new TestSuite(name, List.of(new TestCase(name, source)));
        }
        cases.add(new TestCase(currentName, body.toString()));
        return new TestSuite(name, cases);
    }
}
