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
 *
 * <p>All five Bundle relations are carried, not just {@code next}. A server that
 * volunteered a {@code previous} link is telling us it supports going backwards, and
 * ignoring that would make the viewer look broken on exactly the servers that work
 * best.</p>
 */
public final class SearchResultPage {

    private final List<IBaseResource> resources;
    private final Integer total;
    private final SearchPageLinks links;

    public SearchResultPage(List<IBaseResource> resources, Integer total, String nextPageToken) {
        this(resources, total, new SearchPageLinks(null, null, null, nextPageToken, null));
    }

    public SearchResultPage(List<IBaseResource> resources, Integer total, SearchPageLinks links) {
        this.resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        this.total = total;
        this.links = Objects.requireNonNull(links, "links");
    }

    /** The resources on this page, in server order. Never {@code null}. */
    public List<IBaseResource> resources() {
        return resources;
    }

    /** The server-reported total, or {@code null} when the server did not report one. */
    public Integer total() {
        return total;
    }

    /**
     * The paging links the server sent with this Bundle.
     *
     * <p>Every URL in here is the server's own and is used exactly as given, so
     * {@code previous}, {@code first} and {@code last} are reachable without this
     * application ever inventing a page token.
     */
    public SearchPageLinks links() {
        return links;
    }

    /** True when another page can be fetched. */
    public boolean hasNextPage() {
        return links.hasNext();
    }

    /**
     * The server-provided URL of the next page, or {@code null}.
     *
     * <p>Named "token" because that is what it always was, and because the UI already
     * passes it around under that name; it is a URL and is handed to
     * {@code FhirServerPlugin.pageAt} untouched.
     */
    public String nextPageToken() {
        return links.next();
    }

    /** The server-provided URL of the page before this one, or {@code null}. */
    public String previousPageToken() {
        return links.previous();
    }

    /** An empty page with no total and no paging links. */
    public static SearchResultPage empty() {
        return new SearchResultPage(List.of(), null, SearchPageLinks.none());
    }

    @Override
    public String toString() {
        return resources.size() + " resources"
                + (total == null ? "" : " of " + total)
                + (hasNextPage() ? " (more available)" : "");
    }
}
