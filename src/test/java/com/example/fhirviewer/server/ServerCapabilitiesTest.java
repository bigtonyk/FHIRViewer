package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.FhirContext;

/**
 * Capability awareness: what a real {@code CapabilityStatement} turns into, and what a
 * search {@code Bundle} turns into.
 *
 * <p>The point of these is the words "do not assume". The plan says PATCH, history,
 * batch, transactions and conditional operations cannot be assumed, so a server that
 * lists read, search and create but omits patch has refused a patch and
 * {@link ServerCapabilities#supports} has to say so. Before this phase the only
 * capability data kept was a list of type names and a {@code pagingSupported} flag
 * hard-coded to {@code false}, so nothing could be asked and the only safe assumption
 * was that nothing worked.
 */
public class ServerCapabilitiesTest {

    private static final String CAPABILITY_JSON = "{ \"resourceType\": \"CapabilityStatement\","
            + " \"status\": \"active\", \"date\": \"2024-01-01\", \"kind\": \"instance\","
            + " \"fhirVersion\": \"4.0.1\", \"format\": [ \"json\" ],"
            + " \"rest\": [ { \"mode\": \"server\", \"resource\": [ "
            + " { \"type\": \"Patient\", \"interaction\": ["
            // R4 spells search as "search-type" and splits history in two; HAPI's R4
            // parser rejects the shorter spellings outright, and the reader folds all
            // of them onto ServerInteraction.SEARCH and .HISTORY afterwards.
            + "   { \"code\": \"read\" }, { \"code\": \"search-type\" },"
            + "   { \"code\": \"create\" }, { \"code\": \"update\" },"
            + "   { \"code\": \"delete\" }, { \"code\": \"history-instance\" } ],"
            + "   \"searchParam\": [ { \"name\": \"family\" }, { \"name\": \"birthdate\" } ] },"
            + " { \"type\": \"Observation\", \"interaction\": ["
            + "   { \"code\": \"read\" }, { \"code\": \"search-type\" }, { \"code\": \"vread\" } ] },"
            // A type with no interaction list at all: the server is relying on the base
            // specification rather than declining anything.
            + " { \"type\": \"Practitioner\" }"
            + " ] } ] }";

    private static final String NO_PATCH_CAPABILITY_JSON =
            "{ \"resourceType\": \"CapabilityStatement\", \"status\": \"active\","
            + " \"date\": \"2024-01-01\", \"kind\": \"instance\", \"fhirVersion\": \"4.0.1\","
            + " \"format\": [ \"json\" ], \"rest\": [ { \"mode\": \"server\", \"resource\": [ "
            + " { \"type\": \"Patient\", \"interaction\": [ { \"code\": \"read\" } ] } ] } ] }";

    private static ServerCapabilities read(String json) {
        CapabilityStatement statement =
                (CapabilityStatement) FhirContext.forR4().newJsonParser().parseResource(json);
        return ServerCapabilityReader.of(statement);
    }


    @Test
    @DisplayName("A statement becomes interactions, search parameters and a real paging answer")
    void readsARealStatement() {
        ServerCapabilities capabilities = read(CAPABILITY_JSON);

        assertEquals("4.0.1", capabilities.fhirVersion());
        assertEquals(List.of("Patient", "Observation", "Practitioner"), capabilities.resourceTypes());
        assertTrue(capabilities.pagingSupported(),
                "a server that advertises search can return a searchset Bundle to page through");

        ServerResourceCapabilities patient = capabilities.resource("Patient").orElseThrow();
        assertEquals(Set.of(ServerInteraction.READ, ServerInteraction.SEARCH, ServerInteraction.CREATE,
                ServerInteraction.UPDATE, ServerInteraction.DELETE, ServerInteraction.HISTORY),
                patient.interactions());
        assertEquals(List.of("family", "birthdate"), patient.searchParameters());
        assertTrue(patient.supportsSearchParameter("Family"), "names compare case-insensitively");
        assertFalse(patient.supportsSearchParameter("identifier"));
    }

    @Test
    @DisplayName("A server that omits an interaction has declined it, not merely not mentioned it")
    void anOmittedInteractionIsARefusal() {
        ServerCapabilities capabilities = read(NO_PATCH_CAPABILITY_JSON);

        assertTrue(capabilities.supports("Patient", ServerInteraction.READ));
        assertFalse(capabilities.supports("Patient", ServerInteraction.PATCH),
                "Patient lists its interactions and patch is not among them");
        assertFalse(capabilities.supports("Patient", ServerInteraction.SEARCH));
        assertFalse(capabilities.pagingSupported(),
                "no search advertised anywhere, so there is no Bundle to page through");
    }

    @Test
    @DisplayName("A type with no interaction list is unrestricted rather than read-only")
    void anUndescribedTypeIsUnrestricted() {
        ServerCapabilities capabilities = read(CAPABILITY_JSON);

        assertTrue(capabilities.supports("Practitioner", ServerInteraction.PATCH));
        assertTrue(capabilities.supports("Practitioner", ServerInteraction.SEARCH));
    }

    @Test
    @DisplayName("A type the statement never mentions supports nothing")
    void anUnmentionedTypeSupportsNothing() {
        ServerCapabilities capabilities = read(CAPABILITY_JSON);

        assertFalse(capabilities.supports("Encounter", ServerInteraction.READ));
        assertTrue(capabilities.resource("Encounter").isEmpty());
        assertFalse(capabilities.supports(null, ServerInteraction.READ));
        assertFalse(capabilities.supports("Patient", null));
    }

    private static Bundle readBundle(String json) {
        return (Bundle) FhirContext.forR4().newJsonParser().parseResource(json);
    }

    @Test
    @DisplayName("An interaction code this build does not model is dropped, not guessed at")
    void unknownInteractionCodesAreDropped() {
        assertTrue(ServerInteraction.fromCode("patch").isPresent());
        assertTrue(ServerInteraction.fromCode(" PATCH ").isPresent(), "servers vary on spacing and case");
        assertTrue(ServerInteraction.fromCode("transaction").isEmpty(),
                "batch and transaction are system-level, not per-resource interactions here");
        assertTrue(ServerInteraction.fromCode("").isEmpty());
        assertTrue(ServerInteraction.fromCode(null).isEmpty());
    }

    @Test
    @DisplayName("The spellings servers disagree on all mean the same thing")
    void theAlternativeSpellingsAllMeanSearch() {
        // R4 declares search as "search-type"; HAPI's R4 enum only accepts that and the
        // two split history codes; plenty of servers and documentation write "search".
        // Treating them as three different capabilities would report working servers
        // as read-only.
        assertEquals(ServerInteraction.SEARCH, ServerInteraction.fromCode("search-type").orElseThrow());
        assertEquals(ServerInteraction.SEARCH, ServerInteraction.fromCode("search").orElseThrow());
        assertEquals(ServerInteraction.SEARCH, ServerInteraction.fromCode("vsearch").orElseThrow());
        assertEquals(ServerInteraction.HISTORY, ServerInteraction.fromCode("history").orElseThrow());
        assertEquals(ServerInteraction.HISTORY, ServerInteraction.fromCode("history-type").orElseThrow());
        assertEquals(ServerInteraction.HISTORY,
                ServerInteraction.fromCode("history-instance").orElseThrow());
    }

    @Test
    @DisplayName("A missing statement is an empty capability set, not a failure")
    void aMissingStatementIsEmpty() {
        ServerCapabilities capabilities = ServerCapabilityReader.of(null);

        assertEquals("", capabilities.fhirVersion());
        assertTrue(capabilities.resourceTypes().isEmpty());
        assertFalse(capabilities.pagingSupported());
        assertFalse(capabilities.supports("Patient", ServerInteraction.READ));
    }

    @Test
    @DisplayName("A search Bundle keeps all five of its links exactly as the server sent them")
    void readsEveryPagingLink() {
        String json = "{ \"resourceType\": \"Bundle\", \"type\": \"searchset\", \"total\": 2, \"link\": [ "
                + "{ \"relation\": \"self\", \"url\": \"http://x/Patient?page=2\" },"
                + "{ \"relation\": \"first\", \"url\": \"http://x/Patient?_page=1\" },"
                + "{ \"relation\": \"previous\", \"url\": \"http://x/Patient?_page=1\" },"
                + "{ \"relation\": \"next\", \"url\": \"http://x/Patient?_page=3\" },"
                + "{ \"relation\": \"last\", \"url\": \"http://x/Patient?_page=9\" },"
                // A repeated relation: the first one wins, because that is the one the
                // server used when it minted the rest.
                + "{ \"relation\": \"next\", \"url\": \"http://x/Patient?_page=broken\" }"
                + " ], \"entry\": [ { \"resource\": { \"resourceType\": \"Patient\", \"id\": \"a\" } },"
                + "{ \"search\": { \"mode\": \"outcome\" } } ] }";

        SearchResultPage page = ServerSearchBundleReader.of(readBundle(json));

        assertEquals(1, page.resources().size(), "an entry with no resource is not a blank row");
        assertEquals(Integer.valueOf(2), page.total());
        SearchPageLinks links = page.links();
        assertEquals("http://x/Patient?page=2", links.self());
        assertEquals("http://x/Patient?_page=1", links.first());
        assertEquals("http://x/Patient?_page=1", links.previous());
        assertEquals("http://x/Patient?_page=3", links.next());
        assertEquals("http://x/Patient?_page=9", links.last());
        assertEquals("http://x/Patient?_page=3", page.nextPageToken());
        assertEquals("http://x/Patient?_page=1", page.previousPageToken());
        assertTrue(page.hasNextPage());
    }

    @Test
    @DisplayName("A Bundle with no links is a single page, not an error")
    void aBundleWithoutLinksIsOnePage() {
        String json = "{ \"resourceType\": \"Bundle\", \"type\": \"searchset\", \"entry\": [ "
                + "{ \"resource\": { \"resourceType\": \"Patient\", \"id\": \"a\" } } ] }";

        SearchResultPage page = ServerSearchBundleReader.of(readBundle(json));

        assertFalse(page.hasNextPage());
        assertTrue(page.links().isEmpty());
        assertNull(page.nextPageToken());
        assertTrue(page.toString().contains("1 resources"));
    }

    @Test
    @DisplayName("A blank link URL is no link, so following one cannot re-fetch this page")
    void blankLinkUrlsAreDropped() {
        String json = "{ \"resourceType\": \"Bundle\", \"type\": \"searchset\", \"link\": [ "
                + "{ \"relation\": \"next\", \"url\": \"  \" } ] }";

        SearchResultPage page = ServerSearchBundleReader.of(readBundle(json));

        assertFalse(page.hasNextPage());
        assertNull(page.nextPageToken());
    }

    @Test
    @DisplayName("A missing Bundle is an empty page")
    void aMissingBundleIsEmpty() {
        assertTrue(ServerSearchBundleReader.of(null).resources().isEmpty());
        assertTrue(SearchResultPage.empty().links().isEmpty());
    }

    @Test
    @DisplayName("A page's links never print a URL, because a page token identifies a result set")
    void linksNeverPrintTheirUrls() {
        SearchPageLinks links = new SearchPageLinks(
                "http://x/Patient?token=secret", null, null, "http://x/Patient?page=3", null);

        assertEquals("SearchPageLinks[self, next]", links.toString());
        assertFalse(links.describe().contains("secret"));
        assertFalse(links.isEmpty());
        assertEquals("no paging links", SearchPageLinks.none().describe());
    }
}

