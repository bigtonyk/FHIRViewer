package com.example.fhirviewer.server;

/**
 * How a request to a FHIR server is authenticated.
 *
 * <p>Authentication is its own interface so new mechanisms (OAuth 2.0, SMART on FHIR,
 * API keys, vendor-specific flows) can be added without touching the plugin or server
 * configuration types. Only anonymous access is needed for the first implementation;
 * the type exists so that future providers have a stable place to live.</p>
 */
public interface ServerAuthentication {

    /** A short id such as <code>anonymous</code>, <code>basic</code> or <code>bearer</code>. */
    String type();

    /** A human readable label shown in the server dialog. */
    String displayName();

    /** True when this provider carries no secret at all. */
    boolean isAnonymous();
}
