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
