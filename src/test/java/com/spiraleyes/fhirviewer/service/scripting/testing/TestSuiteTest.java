package com.spiraleyes.fhirviewer.service.scripting.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestSuiteTest {

    @Test
    @DisplayName("Case markers split the buffer into named cases")
    void markersSplitCases() {
        String buffer = """
                // case: advertises a patient
                int one = 1;
                System.out.println(one);

                // case: reads it back
                int two = 2;
                """;

        TestSuite suite = TestSuite.parse("checks.jsh", buffer);

        assertEquals("checks.jsh", suite.name());
        assertEquals(List.of("advertises a patient", "reads it back"),
                suite.cases().stream().map(TestCase::name).toList());
        assertTrue(suite.cases().get(0).source().contains("int one = 1;"));
        assertTrue(suite.cases().get(1).source().contains("int two = 2;"));
    }

    @Test
    @DisplayName("A buffer without markers is one case named after the suite")
    void bufferWithoutMarkersIsOneCase() {
        TestSuite suite = TestSuite.parse("script.jsh", "int answer = 42;");

        assertEquals(1, suite.cases().size());
        assertEquals("script.jsh", suite.cases().get(0).name());
        assertEquals("int answer = 42;", suite.cases().get(0).source());
    }

    @Test
    @DisplayName("Text before the first marker is refused rather than dropped")
    void textBeforeFirstMarkerIsRefused() {
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
                () -> TestSuite.parse("suite.jsh", "int setup = 1;\n// case: first\nint a = 1;"));

        assertTrue(problem.getMessage().contains("first // case: marker"),
                "the message should name the marker: " + problem.getMessage());
    }

    @Test
    @DisplayName("A marker without a name is refused instead of joining the previous case")
    void namelessMarkerIsRefused() {
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
                () -> TestSuite.parse("suite.jsh", "// case: first\nint a = 1;\n// case:   \nint b = 2;"));

        assertTrue(problem.getMessage().contains("needs a name"), problem.getMessage());
    }

    @Test
    @DisplayName("A repeated case name is refused rather than reported ambiguously")
    void duplicateCaseNamesAreRefused() {
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
                () -> TestSuite.parse("suite.jsh",
                        "// case: checks\nint a = 1;\n// case: checks\nint b = 2;"));

        assertTrue(problem.getMessage().contains("repeats the case name \"checks\""),
                problem.getMessage());
        assertTrue(problem.getMessage().contains("line 3"),
                "the message should name the repeated marker: " + problem.getMessage());
    }

    @Test
    @DisplayName("A case with no source is refused whether it is blank or only comments")
    void caseWithoutSourceIsRefused() {
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> TestSuite.parse("suite.jsh",
                        "// case: empty\n\n// case: filled\nint a = 1;"));
        assertTrue(blank.getMessage().contains("The case \"empty\" from line 1 has no source"),
                blank.getMessage());

        IllegalArgumentException comments = assertThrows(IllegalArgumentException.class,
                () -> TestSuite.parse("suite.jsh", "// case: notes\n// TODO later\n"
                        + "/* still nothing */\n// case: filled\nint a = 1;"));
        assertTrue(comments.getMessage().contains("The case \"notes\" from line 1 has no source"),
                comments.getMessage());
    }

    @Test
    @DisplayName("Literal contents with comment markers stay in the case source")
    void literalsWithCommentMarkersStaySource() {
        // Each entry starts with a literal, so the scanner meets a quote before any marker.
        String body = String.join("\n",
                "\"https://example.org/a/*b*/c\";",
                "\"she said \\\"// not a comment\\\"\";",
                "\"\"\"",
                "// inside the text block",
                "/* inside too */",
                "\"\"\";");

        TestSuite suite = TestSuite.parse("literals.jsh", "// case: literals\n" + body);

        // Exact equality checks the name and that every marker inside the literals survived.
        assertEquals(List.of(new TestCase("literals", body + "\n")), suite.cases());
    }

    @Test
    @DisplayName("An unterminated block comment leaves no source")
    void unterminatedBlockCommentIsRefused() {
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
                () -> TestSuite.parse("suite.jsh", "// case: unfinished\n/* TODO"));

        assertTrue(problem.getMessage().contains("The case \"unfinished\" from line 1 has no source"),
                problem.getMessage());
    }

    @Test
    @DisplayName("Leading and trailing comments do not hide the code between them")
    void commentsAroundCodeAreNotTheWholeCase() {
        TestSuite suite = TestSuite.parse("mixed.jsh",
                "// case: mixed\n// lead\n/* then */ int value = 1; // tail");

        assertEquals(1, suite.cases().size());
        assertTrue(suite.cases().get(0).source().contains("int value = 1;"));
    }

    @Test
    @DisplayName("A blank buffer cannot be parsed as a suite")
    void blankBufferIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TestSuite.parse("empty.jsh", "  \n"));
    }
}
