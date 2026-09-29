package com.example.fhirviewer.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;

/**
 * Reads a FHIR R4 search {@code Bundle} into the application's own
 * {@link SearchResultPage}.
 *
 * <p>The counterpart to {@link ServerCapabilityReader} for the other half of a search:
 * entries, the total, and the server's own paging links. Those links are copied exactly
 * as sent. FHIR lets a server encode a page however it likes — an opaque token, an
 * offset, a cursor over a snapshot — so anything this application built for itself
 * would be right on one server and wrong on the next.
 *
 * <p>Entries without a resource are skipped rather than represented as nulls. A search
 * Bundle may legitimately contain {@code outcome}-only entries or deleted rows, and a
 * null in the resource list would reach the UI as a blank row that cannot be opened.
 */
public final class ServerSearchBundleReader {

    private ServerSearchBundleReader() {
    }

    /**
     * Reads a Bundle, or an empty page when the server sent none.
     *
     * @param bundle the parsed search Bundle, possibly {@code null}
     */
    public static SearchResultPage of(Bundle bundle) {
        if (bundle == null) {
            return SearchResultPage.empty();
        }
        List<IBaseResource> resources = new ArrayList<>();
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry != null && entry.getResource() != null) {
                resources.add(entry.getResource());
            }
        }
        Integer total = bundle.hasTotal() ? bundle.getTotal() : null;
        return new SearchResultPage(resources, total, linksOf(bundle));
    }

    /**
     * The five relations FHIR defines, in the order the server listed them.
     *
     * <p>A relation outside those five is dropped: this is a paging model, and a
     * server-specific relation would be one more thing to store and never use.
     */
    private static SearchPageLinks linksOf(Bundle bundle) {
        Map<String, String> byRelation = new LinkedHashMap<>();
        for (Bundle.BundleLinkComponent link : bundle.getLink()) {
            if (link == null || link.getRelation() == null || link.getUrl() == null) {
                continue;
            }
            String relation = link.getRelation().trim().toLowerCase(Locale.ROOT);
            if (isPagingRelation(relation)) {
                // First occurrence wins: a server that repeats a relation has a bug, and
                // the later one would be the one that does not work.
                byRelation.putIfAbsent(relation, link.getUrl());
            }
        }
        return new SearchPageLinks(byRelation.get("self"), byRelation.get("first"),
                byRelation.get("previous"), byRelation.get("next"), byRelation.get("last"));
    }

    private static boolean isPagingRelation(String relation) {
        return switch (relation) {
            case "self", "first", "previous", "next", "last" -> true;
            default -> false;
        };
    }
}
