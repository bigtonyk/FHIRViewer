package com.example.fhirviewer.server;

/**
 * Firely-specific settings for a server definition.
 *
 * <p>Only Firely-specific options live here. Generic FHIR settings (base URL, FHIR version,
 * timeout, etc.) stay in {@link FhirServerConfiguration}. A server definition that uses the
 * Firely plugin can carry an optional instance of this interface, but it defaults to a minimal
 * anonymous configuration so a basic Firely connection works out of the box.</p>
 *
 * <p>Firely Server is a separate product from Smile CDR even though both are built by
 * Firely (formerly Smile). They expose different CapabilityStatement fingerprints, so each
 * keeps its own configuration type and plugin id.</p>
 */
public interface FirelyConfiguration extends FhirServerConfiguration {

    /**
     * The Firely-specific plugin id this configuration belongs to.
     * Must return {@value FirelyPlugin#PLUGIN_ID}.
     */
    @Override
    default String pluginId() {
        return FirelyPlugin.PLUGIN_ID;
    }

    /**
     * Whether this configuration requests Firely's {@code X-Firely-*} API key header.
     * False by default; the header value is a secret and therefore travels via
     * {@link ServerSession}, never through this configuration.
     */
    default boolean apiKeyAuthentication() {
        return false;
    }
}
