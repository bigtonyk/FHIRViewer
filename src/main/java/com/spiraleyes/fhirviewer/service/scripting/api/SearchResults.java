package com.spiraleyes.fhirviewer.service.scripting.api;

import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * One page of search results, as a script can see it.
 *
 * <p>The server may or may not report a total or paging links; both are normal.
 */
public record SearchResults(
        List<IBaseResource> resources,
        Integer total,
        String nextPageToken,
        String previousPageToken) {

    public SearchResults {
        resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        total = total == null ? null : total;
        nextPageToken = nextPageToken == null ? null : nextPageToken;
        previousPageToken = previousPageToken == null ? null : previousPageToken;
    }

    /** True when the server reported a {@code next} link for this page. */
    public boolean hasNextPage() {
        return nextPageToken != null && !nextPageToken.isBlank();
    }

    /** True when the server reported a {@code previous} link for this page. */
    public boolean hasPreviousPage() {
        return previousPageToken != null && !previousPageToken.isBlank();
    }

    /** The number of resources on this page. */
    public int size() {
        return resources.size();
    }

    @Override
    public String toString() {
        return resources.size() + " resource"
                + (resources.size() == 1 ? "" : "s")
                + (total == null ? "" : " of " + total)
                + (hasNextPage() ? " (more available)" : "");
    }
}
