package com.example.fhirviewer.server;

/**
 * Smile-specific settings for a server definition.
 *
 * <p>Only Smile CDR-specific options live here. Generic FHIR settings (base URL, FHIR version,
 * timeout, etc.) stay in {@link FhirServerConfiguration}. A server definition that uses the Smile
 * plugin can carry an optional instance of this interface, but it defaults to a minimal
 * anonymous configuration so a basic Smile CDR connection works out of the box.
 */
public interface SmileCdrConfiguration extends FhirServerConfiguration {

    /**
     * The Smile-specific plugin id this configuration belongs to.
     * Must return {@value SmileCdrPlugin#PLUGIN_ID}.
     */
    @Override
    default String pluginId() {
        return SmileCdrPlugin.PLUGIN_ID;
    }

    /**
     * Whether this configuration requests Smile trusted-client mode.
     * False by default; trusted client mode requires explicit administrator opt-in.
     */
    default boolean trustedClientMode() {
        return false;
    }
}