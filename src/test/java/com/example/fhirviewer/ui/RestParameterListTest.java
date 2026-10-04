package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Query parameters: order, repetition, flags and encoding.
 *
 * <p>The two that would be easy to get wrong are order and repetition. {@code _include}
 * legitimately repeats and some servers care about the order, and a {@code HashMap} in the
 * middle of the pipeline would silently discard both — so those are pinned down here.
 */
class RestParameterListTest {

    @Test
    @DisplayName("Parameters keep the order they were added in")
    void orderIsPreserved() {
        RestParameterList parameters = RestParameterList.empty()
                .add("_count", "5").add("name", "Smith").add("_include", "Patient:organization");

        assertEquals(List.of("_count", "name", "_include"),
                parameters.entries().stream().map(RestParameterList.Parameter::name).toList());
    }

    @Test
    @DisplayName("A repeated name keeps both values, in order")
    void repeatedNamesSurvive() {
        RestParameterList parameters = RestParameterList.empty()
                .add("_include", "Patient:organization")
                .add("_include", "Patient:general-practitioner");

        assertEquals(List.of("Patient:organization", "Patient:general-practitioner"),
                parameters.toQueryMap().get("_include"));
    }

    @Test
    @DisplayName("The query map is ordered, so a repeatable parameter stays repeatable")
    void queryMapKeepsBoth() {
        java.util.Map<String, List<String>> query = RestParameterList.empty()
                .add("name", "Smith").add("_include", "a").add("_include", "b")
                .toQueryMap();

        assertEquals(List.of("name", "_include"), List.copyOf(query.keySet()));
        assertEquals(List.of("a", "b"), query.get("_include"));
    }

    @Test
    @DisplayName("A blank name is dropped when adding, but kept when editing")
    void blankNamesAreTreatedAsEditsNotValues() {
        assertTrue(RestParameterList.empty().add("  ", "value").isEmpty());

        RestParameterList one = RestParameterList.of("name", "Smith");
        assertEquals("", one.replace(0, new RestParameterList.Parameter("", "")).entries()
                .get(0).name(), "a half-typed grid row must not be dropped mid-keystroke");
    }

    @Test
    @DisplayName("A query string parses, with or without the leading question mark")
    void parsesAQueryString() {
        assertEquals(List.of("name", "_count"),
                RestParameterList.parse("name=Smith&_count=5").entries().stream()
                        .map(RestParameterList.Parameter::name).toList());
        assertEquals(List.of("name", "_count"),
                RestParameterList.parse("?name=Smith&_count=5").entries().stream()
                        .map(RestParameterList.Parameter::name).toList());
    }

    @Test
    @DisplayName("A bare name is a flag with an empty value")
    void bareNameIsAFlag() {
        RestParameterList parsed = RestParameterList.parse("_pretty&name=Smith");

        assertEquals("", parsed.value("_pretty"));
        assertEquals("Smith", parsed.value("name"));
    }

    @Test
    @DisplayName("Empty pairs are skipped rather than sent as an empty name")
    void emptyPairsAreSkipped() {
        assertEquals(2, RestParameterList.parse("a=1&&b=2&").size());
    }

    @Test
    @DisplayName("Percent-encoded values are decoded on the way in")
    void valuesAreDecoded() {
        RestParameterList parsed = RestParameterList.parse("name=John%20Smith&birthdate=1980-01-01");

        assertEquals("John Smith", parsed.value("name"));
        assertEquals("1980-01-01", parsed.value("birthdate"));
    }

    @Test
    @DisplayName("A stray percent sign is data, not a reason to refuse")
    void malformedEscapeIsKept() {
        assertEquals("100%", RestParameterList.parse("q=100%").value("q"));
    }

    @Test
    @DisplayName("Encoding covers everything outside the unreserved set")
    void encodingEscapesWhatItMust() {
        assertEquals("name=John%20Smith",
                RestParameterList.of("name", "John Smith").toQueryString());
        assertEquals("a=a-b.c_d~e", RestParameterList.of("a", "a-b.c_d~e").toQueryString(),
                "the unreserved set stays readable, which is the point of choosing it");
        assertEquals("q=a%26b", RestParameterList.of("q", "a&b").toQueryString(),
                "a value must not be able to add another parameter");
    }

@Test
    @DisplayName("A query string round-trips through parse and render")
    void roundTrips() {
        RestParameterList original = RestParameterList.empty()
                .add("name", "John Smith").add("_count", "5").add("_pretty", "");

        assertEquals(original.entries(),
                RestParameterList.parse(original.toQueryString()).entries());
    }

    @Test
    @DisplayName("Removal and replacement are out of range errors, not silent no-ops")
    void editsAreChecked() {
        RestParameterList parameters = RestParameterList.of("name", "Smith");

        assertThrows(IndexOutOfBoundsException.class, () -> parameters.remove(5));
        assertThrows(IndexOutOfBoundsException.class,
                () -> parameters.replace(-1, new RestParameterList.Parameter("a", "b")));
    }

    @Test
    @DisplayName("The list is immutable: an edit never changes the original")
    void editsDoNotMutate() {
        RestParameterList original = RestParameterList.of("name", "Smith");
        RestParameterList edited = original.add("_count", "5");

        assertEquals(1, original.size());
        assertEquals(2, edited.size());
    }

    @Test
    @DisplayName("An empty or blank query string is simply no parameters")
    void emptyInputIsNoParameters() {
        assertTrue(RestParameterList.parse("").isEmpty());
        assertTrue(RestParameterList.parse(null).isEmpty());
        assertTrue(RestParameterList.parse("   ").isEmpty());
    }

    @Test
    @DisplayName("toString counts parameters but never prints a value")
    void toStringHidesValues() {
        assertFalse(RestParameterList.of("name", "Smith").toString().contains("Smith"));
    }
}