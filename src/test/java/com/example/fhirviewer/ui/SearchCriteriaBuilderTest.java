package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.SearchCriterion;

/**
 * Tests the rule both search screens share.
 *
 * <p>Small on purpose: it exists because {@code ServerSearchDialog} and
 * {@code OpenFromServerDialog} both collect a name/value pair, and the failure this
 * guards against is the two quietly disagreeing - a search that works in one dialog
 * failing in the other for no visible reason.</p>
 */
class SearchCriteriaBuilderTest {

    @Test
    @DisplayName("Both fields blank means no criteria, which is a valid search")
    void blankIsNoCriteria() {
        assertTrue(SearchCriteriaBuilder.from("", "").isEmpty());
        assertTrue(SearchCriteriaBuilder.from("   ", "  ").isEmpty());
        assertTrue(SearchCriteriaBuilder.from(null, null).isEmpty());
    }

    @Test
    @DisplayName("A name and a value become one trimmed criterion")
    void buildsOneCriterion() {
        List<SearchCriterion> criteria = SearchCriteriaBuilder.from(" name ", " Smith ");

        assertEquals(1, criteria.size());
        assertEquals("name", criteria.get(0).name());
        assertEquals("Smith", criteria.get(0).value());
    }

    @Test
    @DisplayName("A parameter with no value is refused rather than matched loosely")
    void halfACriterionIsRefused() {
        IllegalArgumentException noValue = assertThrows(IllegalArgumentException.class,
                () -> SearchCriteriaBuilder.from("name", ""));
        assertTrue(noValue.getMessage().toLowerCase(java.util.Locale.ROOT).contains("both"),
                "the message should say what is missing: " + noValue.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> SearchCriteriaBuilder.from("", "Smith"));
    }

    @Test
    @DisplayName("The returned list cannot be modified by a caller")
    void resultIsImmutable() {
        List<SearchCriterion> criteria = SearchCriteriaBuilder.from("name", "Smith");

        assertThrows(UnsupportedOperationException.class, () -> criteria.add(
                new SearchCriterion("gender", "male")));
    }
}
