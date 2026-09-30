package com.example.fhirviewer.server;

import java.util.List;
import java.util.Objects;

/**
 * An application-level resource search.
 *
 * <p>The UI fills in this request; the plugin translates it into whatever the server
 * needs (query parameters for standard REST, something else for vendor plugins). The UI
 * never builds query URLs or server-specific syntax itself.</p>
 */
public final class SearchRequest {

    private final String resourceType;
    private final List<SearchCriterion> criteria;
    private final int pageSize;

    public SearchRequest(String resourceType, List<SearchCriterion> criteria, int pageSize) {
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("A resource type is required.");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("The page size must be at least 1.");
        }
        this.resourceType = resourceType;
        this.criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria"));
        this.pageSize = pageSize;
        rejectMixedCriteria();
    }

    /**
     * Refuses a request holding both a raw search string and name/value pairs.
     *
     * <p>Both kinds are sent by different routes — a raw string goes on the query line
     * untouched, a pair is built by the plugin — so a request carrying both has no single
     * correct answer. Silently dropping one kind would return a search the user did not
     * ask for, and a raw search with nothing applied returns <em>everything</em>, which is
     * the worst outcome available. Refusing at construction means the question never arises
     * where it is expensive to get wrong.</p>
     */
    private void rejectMixedCriteria() {
        boolean raw = criteria.stream().anyMatch(SearchCriterion::isRaw);
        boolean exact = criteria.stream().anyMatch(criterion -> !criterion.isRaw());
        if (raw && exact) {
            throw new IllegalArgumentException(
                    "A search is either a raw search string or a list of parameters, not both."
                            + " Use the raw form to send everything in one string.");
        }
    }

    /** True when every criterion is a raw search string rather than a name/value pair. */
    public boolean isRawSearch() {
        return !criteria.isEmpty() && criteria.stream().allMatch(SearchCriterion::isRaw);
    }

    /** The FHIR resource type to search, for example <code>Patient</code>. */
    public String resourceType() {
        return resourceType;
    }

    /** The search parameters, in UI order. Never {@code null}; may be empty. */
    public List<SearchCriterion> criteria() {
        return criteria;
    }

    /** How many results to ask for per page. */
    public int pageSize() {
        return pageSize;
    }

    @Override
    public String toString() {
        return resourceType + " " + criteria + " (page size " + pageSize + ")";
    }
}
