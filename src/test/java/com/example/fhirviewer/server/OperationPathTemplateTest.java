package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Path templating, and specifically what it does to a value it did not expect.
 *
 * <p>Substituting a caller's text into a URL is the one place in this feature where a
 * value stops being data and starts being structure, so the rules are worth stating
 * outright: every value is encoded, encoding is unconditional, and a value with nothing
 * to encode is still encoded. A scheme where the safe case is fast and the dangerous case
 * needs care is a scheme that eventually ships a path traversal.</p>
 */
public class OperationPathTemplateTest {

    @Test
    @DisplayName("Placeholders are found in order, and a repeated one is reported once")
    void findsPlaceholdersInOrder() {
        assertEquals(List.of("jobId"),
                OperationPathTemplate.placeholdersIn("admin/export/{jobId}"));
        assertEquals(List.of("from", "to"),
                OperationPathTemplate.placeholdersIn("admin/{from}/to/{to}"));
        assertEquals(List.of("id"), OperationPathTemplate.placeholdersIn("admin/{id}/copy/{id}"));
        assertEquals(List.of(), OperationPathTemplate.placeholdersIn("admin/status"));
        assertEquals(List.of(), OperationPathTemplate.placeholdersIn(null));
    }

    @Test
    @DisplayName("A template with no placeholders is returned unchanged")
    void resolvesAPlainPath() {
        assertEquals("admin/status", OperationPathTemplate.resolve("admin/status", Map.of()));
    }

    @Test
    @DisplayName("Values are substituted where the template says")
    void substitutesValues() {
        assertEquals("admin/export/job-42",
                OperationPathTemplate.resolve("admin/export/{jobId}", Map.of("jobId", "job-42")));
        assertEquals("admin/a/to/b",
                OperationPathTemplate.resolve("admin/{from}/to/{to}",
                        Map.of("from", "a", "to", "b")));
    }

    @Test
    @DisplayName("Every missing value is named at once, not one per attempt")
    void reportsEveryMissingValueTogether() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OperationPathTemplate.resolve("admin/{from}/to/{to}", Map.of()));

        assertTrue(failure.getMessage().contains("from"), failure.getMessage());
        assertTrue(failure.getMessage().contains("to"), failure.getMessage());
    }

    @Test
    @DisplayName("A blank value is a missing value, not an empty segment")
    void treatsBlankAsMissing() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationPathTemplate.resolve("admin/export/{jobId}", Map.of("jobId", "   ")));
    }

    @Test
    @DisplayName("A value can never add structure to the path, whatever it contains")
    void valuesCannotAddStructure() {
        String hostile = "../../admin/secret?force=1#frag";

        String resolved = OperationPathTemplate.resolve("admin/export/{jobId}", Map.of("jobId", hostile));

        assertTrue(!resolved.contains(".."), "no traversal survives: " + resolved);
        assertTrue(!resolved.contains("?"), "no query can be started: " + resolved);
        assertTrue(!resolved.contains("#"), "no fragment can be started: " + resolved);
        assertEquals("admin/export/%2E%2E%2F%2E%2E%2Fadmin%2Fsecret%3Fforce%3D1%23frag", resolved);
    }

    @Test
    @DisplayName("A value is one segment even when it contains a slash")
    void aSlashStaysInsideTheSegment() {
        assertEquals("admin/export/Patient%2F1",
                OperationPathTemplate.resolve("admin/export/{jobId}", Map.of("jobId", "Patient/1")));
    }

    @Test
    @DisplayName("A space is %20, never the + that a form encoder would produce")
    void spacesAreNotPlusSigns() {
        assertEquals("admin/export/job%2042",
                OperationPathTemplate.resolve("admin/export/{jobId}", Map.of("jobId", "job 42")));
    }

    @Test
    @DisplayName("Unreserved characters are left alone, so a normal id reads normally")
    void leavesOrdinaryValuesReadable() {
        assertEquals("admin/export/job-42_v1~1",
                OperationPathTemplate.resolve("admin/export/{jobId}", Map.of("jobId", "job-42_v1~1")));
    }

    @Test
    @DisplayName("A non-ASCII value is encoded as UTF-8 bytes, not as its characters")
    void encodesNonAsciiAsBytes() {
        assertEquals("admin/export/%C3%A9t%C3%A9",
                OperationPathTemplate.resolve("admin/export/{jobId}", Map.of("jobId", "été")));
    }

    @Test
    @DisplayName("A template with no values at all is refused")
    void refusesAnEmptyTemplate() {
        assertThrows(IllegalArgumentException.class, () -> OperationPathTemplate.resolve(null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> OperationPathTemplate.resolve("  ", Map.of()));
    }

    @Test
    @DisplayName("A blank template with holes still refuses, rather than resolving to nothing")
    void doesNotResolveAgainstNullValues() {
        Map<String, String> none = new LinkedHashMap<>();
        assertThrows(IllegalArgumentException.class,
                () -> OperationPathTemplate.resolve("admin/{jobId}", none));
    }
}
