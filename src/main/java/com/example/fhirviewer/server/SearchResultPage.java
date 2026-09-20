package com.example.fhirviewer.server;

import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * One page of search results.
 *
 * <p>The core application deliberately assumes nothing about server paging: a page may
 * carry a total, a next-page token, both or neither, and the plugin advertises what it
 * found. Missing totals and paging links are normal on real servers, not errors.</p>
 */
public final class SearchResultPage {

    private final List<IBaseResource> resources;
    private final Integer total;
    private final String nextPageToken;

    public SearchResultPage(List<IBaseResource> resources, Integer total, String nextPageToken) {
        this.resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        this.total = total;
        this.nextPageToken = nextPageToken == null || nextPageToken.isBlank() ? null : nextPageToken;
    }

    /** The resources on this page, in server order. Never {@code null}. */
    public List<IBaseResource> resources() {
        return resources;
    }

    /** The server-reported total, or {@code null} when the server did not report one. */
    public Integer total() {
        return total;
    }

    /** True when another page can be fetched. */
    public boolean hasNextPage() {
        return nextPageToken != null;
    }

    /** The opaque token the plugin needs to fetch the next page, or {@code null}. */
    public String nextPageToken() {
        return nextPageToken;
    }

    /** An empty page with no total and no next page. */
    public static SearchResultPage empty() {
        return new SearchResultPage(List.of(), null, null);
    }

    @Override
    public String toString() {
        return resources.size() + " resources"
                + (total == null ? "" : " of " + total)
                + (hasNextPage() ? " (more available)" : "");
    }
}
