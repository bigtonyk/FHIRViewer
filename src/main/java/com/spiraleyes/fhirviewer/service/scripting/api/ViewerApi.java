package com.spiraleyes.fhirviewer.service.scripting.api;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.spiraleyes.fhirviewer.service.FhirService;

/**
 * Display: show a resource or a page of search results so a person can see them.
 *
 * <p>Display goes through the scripting console output, never through JavaFX
 * controls, so a script never manipulates the UI directly. What a script shows
 * is deliberately limited to a short summary plus JSON; sensitive content is
 * only shown when the script itself asks for it.
 */
public class ViewerApi {

    private final FhirService fhirService;

    public ViewerApi(FhirService fhirService) {
        this.fhirService = fhirService == null ? new FhirService() : fhirService;
    }

    /** Show one resource: a one-line summary, then its JSON. */
    public void showResource(IBaseResource resource) {
        if (resource == null) {
            System.out.println("[viewer] No resource to show.");
            return;
        }
        System.out.println("[viewer] " + fhirService.summaryText(resource));
        System.out.println(fhirService.toJson(resource));
    }

    /** Show one page of search results: one line per resource. */
    public void showSearchResults(SearchResults results) {
        if (results == null) {
            System.out.println("[viewer] No results to show.");
            return;
        }
        System.out.println("[viewer] " + results);
        for (IBaseResource resource : results.resources()) {
            System.out.println("  - " + fhirService.summaryText(resource));
        }
    }

    /** Show arbitrary text, for example a headline a script wants visible. */
    public void showText(String text) {
        System.out.println(text == null ? "" : text);
    }
}
