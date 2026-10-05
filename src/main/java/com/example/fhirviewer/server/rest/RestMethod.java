package com.example.fhirviewer.server.rest;

/**
 * The HTTP verbs a {@link RestClient} can issue.
 *
 * <p>Modelled as an explicit enum rather than a string so a request cannot be built with a
 * typo, and so {@link #allowsRequestBody()} can state the rule once instead of each caller
 * remembering it. A {@code GET} or {@code DELETE} built through this API is rejected when a
 * body is supplied, which turns a silent server-side surprise into a local failure.
 */
public enum RestMethod {

    /** Read a resource, a search Bundle, metadata or a vendor endpoint. */
    GET,

    /** Create a resource, start a batch, or invoke an operation. */
    POST,

    /** Replace a resource, optionally under an {@code If-Match} precondition. */
    PUT,

    /** Apply a partial update in the server's patch format. */
    PATCH,

    /** Remove a resource or invoke a vendor delete endpoint. */
    DELETE;

    /**
     * True when this verb may carry a request body.
     *
     * <p>HTTP itself permits a body on any verb, but FHIR servers and the vendor endpoints
     * this project talks to ignore it on {@code GET} and {@code DELETE}, so sending one
     * would only risk leaking content into a request that does not need it.
     */
    public boolean allowsRequestBody() {
        return this == POST || this == PUT || this == PATCH;
    }
}
