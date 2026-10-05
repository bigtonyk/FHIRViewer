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

    /**
     * Where this server's saved credentials are filed.
     *
     * <p>Distinct from {@link #pluginId()} on purpose. Several servers can be served by one
     * plugin, and keying credentials by plugin meant the second server's password silently
     * replaced the first's — the user configured both correctly and one of them stopped
     * working with no message explaining why.</p>
     *
     * <p>The default keys on the base URL, which is enough to tell two servers apart and
     * needs nothing added to a plugin. {@link ServerDefinition} overrides it with a stable
     * generated id, because a URL-derived key breaks when a user edits a URL — and editing
     * a URL is exactly what someone does when a connection is failing, so the password
     * would be lost at the moment it was most likely to be needed.</p>
     *
     * <p>Never {@code null}; a configuration that cannot name itself falls back to its
     * plugin id, which is the older and less precise behaviour rather than a new failure
     * mode.</p>
     */
    default String credentialKey() {
        return baseUrl() == null || baseUrl().isBlank() ? pluginId() : baseUrl().trim();
    }

    /**
     * Where this vendor's administration API lives, when it is not under {@link #baseUrl()}.
     *
     * <p>Most servers serve everything from one root and leave this {@code null}. It exists
     * for the ones that do not: Smile CDR serves its FHIR endpoint on port 8000 and its JSON
     * Admin API on port 9000, so an operation declared for the admin API cannot be reached
     * through {@link #baseUrl()}. Firely's administration API is only a branch of the same
     * origin and needs nothing here.</p>
     *
     * <p>An <b>origin</b>, not a full path — {@code https://host:9000} — because the
     * operation's own path is appended to it. Never {@code null} for a server that has one,
     * and {@code null} meaning "same as {@link #baseUrl()}" is the normal case.</p>
     *
     * <p>A {@code default} method rather than a new abstract one, deliberately. Making it
     * abstract would break every configuration implementation, which is exactly what Phase
     * 29 exists to prevent: {@code LegacyPlugin} implements the pre-REST contract and must
     * keep compiling.</p>
     */
    default String administrationBaseUrl() {
        return null;
    }
}
