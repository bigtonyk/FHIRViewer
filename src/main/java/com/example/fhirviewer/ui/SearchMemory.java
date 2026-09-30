package com.example.fhirviewer.ui;

import java.util.List;
import java.util.Objects;

import com.example.fhirviewer.server.SearchCriterion;

/**
 * Remembers the last search, so reopening a search screen shows it again.
 *
 * <p>Without this, opening a result and coming back gives an empty screen: the server, the
 * resource type and the parameters are all gone, and a user who wants to look at a second
 * result from the same search has to type it all again. The results themselves are not
 * kept — only what was asked for.</p>
 *
 * <p>Held by the main window and passed to both search screens, rather than held
 * statically, so it is visible in each constructor and can be given a fresh one in a test.
 * It is JavaFX-free and does no I/O; remembering a search is not persistence, and nothing
 * here survives the application closing.</p>
 *
 * <p>One slot, not a history. A stack of searches is a feature nobody asked for, and the
 * second-most-recent search is rarely the one wanted.</p>
 */
public final class SearchMemory {

    /**
     * What was searched for.
     *
     * @param serverName   the configured server's name, or {@code null} when none was chosen
     * @param resourceType the FHIR resource type searched, or {@code null}
     * @param criteria     the parameters, or one raw criterion; may be empty
     * @param pageSize     how many results were asked for
     */
    public record Search(String serverName, String resourceType,
            List<SearchCriterion> criteria, int pageSize) {

        public Search {
            criteria = List.copyOf(Objects.requireNonNullElse(criteria, List.of()));
        }

        /** True when there is enough here to run a search again. */
        public boolean isComplete() {
            return resourceType != null && !resourceType.isBlank() && !criteria.isEmpty();
        }
    }

    private Search last;

    /** The last search, or {@code null} when none has been made this session. */
    public Search last() {
        return last;
    }

    /**
     * Records a search.
     *
     * <p>Ignored when there is nothing to record. A search with no criteria is a legitimate
     * thing to run — it browses the whole type — but remembering an empty one would restore
     * a blank form and look like the feature had silently stopped working.</p>
     */
    public void remember(Search search) {
        if (search != null && search.isComplete()) {
            this.last = search;
        }
    }

    /** Forgets the last search. */
    public void clear() {
        last = null;
    }

    @Override
    public String toString() {
        return last == null ? "SearchMemory[none]" : "SearchMemory[" + last + "]";
    }
}
