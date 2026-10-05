package com.example.fhirviewer.ui;

import java.util.List;

import com.example.fhirviewer.server.SearchCriterion;

/**
 * Turns the one parameter/value pair a search screen offers into a {@link SearchCriterion}.
 *
 * <p>Two screens now collect a search - {@code Tools > Search FHIR Server} and
 * {@code File > Open from Server} - and they used to build the criterion independently.
 * That is a small duplication with an outsized failure mode: the two could disagree about
 * what counts as a valid criterion, and a search that worked in one dialog would fail in
 * the other for no visible reason.</p>
 *
 * <p>Extracted here, rather than left as a static method on one of the dialogs, so the
 * rule has no JavaFX dependency and can be tested by a headless build.</p>
 */
public final class SearchCriteriaBuilder {

    private SearchCriteriaBuilder() {
        // static helpers
    }

    /**
     * Builds a one-element criterion list from what the user typed.
     *
     * <p>Both fields blank means "no criteria", which is a valid search: a server answers
     * {@code Patient?} with everything it is willing to return, and a user who wants the
     * first page of a type should not have to invent a filter to get it. One field
     * blank is a mistake rather than a half-filter, and is refused: {@code name=} matches
     * nothing on most servers and everything on some, which is worse than an error.</p>
     *
     * @param parameter the search parameter name, for example {@code name}
     * @param value     the value to look for
     * @return the criteria, never {@code null}; empty when both arguments are blank
     * @throws IllegalArgumentException when only one of the two is supplied, or the
     *                                  parameter name is blank once trimmed
     */
    public static List<SearchCriterion> from(String parameter, String value) {
        String name = parameter == null ? "" : parameter.trim();
        String wanted = value == null ? "" : value.trim();
        if (name.isEmpty() && wanted.isEmpty()) {
            return List.of();
        }
        if (name.isEmpty() || wanted.isEmpty()) {
            throw new IllegalArgumentException("Enter both a search parameter and a value.");
        }
        // SearchCriterion rejects a blank name itself; the message it produces is the
        // clearer one, so it is allowed to propagate.
        return List.of(new SearchCriterion(name, wanted));
    }

    /**
     * Builds a one-element list holding the user's own search string.
     *
     * <p>Refuses an empty string rather than returning no criteria. In raw mode "no
     * criteria" would be a search with nothing applied, which is every resource of the
     * type — the one result the user did not ask for and might not be entitled to. A
     * blank-row search in parameter mode is still allowed, because that is a visible,
     * deliberate choice; an empty raw field is more likely a forgotten switch.</p>
     *
     * @param query what the user typed, for example {@code name:contains=Smith&_sort=-date}
     * @return a single raw criterion
     * @throws IllegalArgumentException when nothing usable is left to search for
     */
    public static List<SearchCriterion> raw(String query) {
        return List.of(SearchCriterion.raw(query));
    }
}
