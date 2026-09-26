package com.example.fhirviewer.server;

/**
 * A FHIR server the application can connect to.
 *
 * <p>This is plain configuration data: it carries everything a plugin needs to open a
 * connection (base URL, FHIR version, plugin id, timeouts, non-secret options such as
 * extra headers) but never authentication secrets themselves. Secrets travel with the
 * request (see {@link ServerSession}) and are never written to disk.</p>
 */
public interface FhirServerConfiguration {

    /** A short human readable label, for example <code>My HAPI Server</code>. */
    String name();

    /** The FHIR base URL, for example <code>https://example.com/fhir</code>. */
    String baseUrl();

    /** The FHIR version, for example <code>R4</code>. */
    String fhirVersion();

    /** The id of the {@link FhirServerPlugin} that handles this server. */
    String pluginId();

    /**
     * How long to wait, in milliseconds, for a connection or a response. The HAPI
     * client applies its own transport defaults; a future plugin (or HAPI version
     * exposing client timeouts) can honour an explicit value from here. Kept on the
     * configuration now so server definitions already carry it, and so the dialog has
     * somewhere to show it, without blocking the first implementation on transport API
     * details.
     */
    int timeoutMillis();

    /**
     * Non-secret extra headers, for example an API key header name (never the value),
     * as name/value pairs. Never {@code null}; may be empty.
     */
    java.util.Map<String, String> extraHeaders();
}
