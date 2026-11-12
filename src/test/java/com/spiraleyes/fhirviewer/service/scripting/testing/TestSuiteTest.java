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
    @DisplayName("A blank buffer cannot be parsed as a suite")
    void blankBufferIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TestSuite.parse("empty.jsh", "  \n"));
    }
}
