package com.example.fhirviewer.server;

import java.util.Objects;

/**
 * A configured FHIR server: a name, a base URL, the plugin that serves it and the
 * options to connect with.
 *
 * <p>This is the persistable part of a server definition. The plugin id and FHIR version
 * travel with it so a stored server can be matched back to its plugin; authentication
 * secrets never live here (see {@link ServerSession}). Instances are built with
 * {@link Builder} and are immutable afterwards.</p>
 */
public final class ServerDefinition implements FhirServerConfiguration {

    private final String id;
    private final String name;
    private final String baseUrl;
    private final String fhirVersion;
    private final String pluginId;
    private final int timeoutMillis;
    private final String administrationBaseUrl;

    private ServerDefinition(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.baseUrl = builder.baseUrl;
        this.fhirVersion = builder.fhirVersion;
        this.pluginId = builder.pluginId;
        this.timeoutMillis = builder.timeoutMillis;
        this.administrationBaseUrl = builder.administrationBaseUrl;
    }

    /**
     * A stable identifier for this server, assigned when it is first created.
     *
     * <p>Not shown to the user and not derived from anything they can edit. It exists so
     * that credentials can be filed against <em>this server</em> rather than against the
     * plugin that happens to serve it: several servers can share one plugin, and keying by
     * plugin meant the second server's password silently replaced the first's.</p>
     *
     * <p>Stable across renames and base-URL edits, which a name- or URL-derived key would
     * not be. Correcting a URL is exactly what a user does when a connection is failing, and
     * losing the password at that moment would turn a connection problem into an
     * authentication one.</p>
     */
    public String id() {
        return id;
    }

    /**
     * Where this server's credentials are filed, overriding the URL-derived default.
     *
     * <p>See {@link #id()} for why this is not derived from the name or the URL.</p>
     */
    @Override
    public String credentialKey() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Where this vendor's administration API is, when it is not under {@link #baseUrl()}.
     *
     * <p>{@code null} — the overwhelmingly common case — means the same as the base URL, so a
     * server with no vendor admin API needs no configuration at all. See
     * {@link FhirServerConfiguration#administrationBaseUrl()}.</p>
     */
    @Override
    public String administrationBaseUrl() {
        return administrationBaseUrl;
    }

    @Override
    public String fhirVersion() {
        return fhirVersion;
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public int timeoutMillis() {
        return timeoutMillis;
    }

    @Override
    public java.util.Map<String, String> extraHeaders() {
        return java.util.Map.of();
    }

    @Override
    public String toString() {
        return name + " (" + baseUrl + ")";
    }

        /** Starts a definition with the only two fields every server needs. */
    public static Builder named(String name, String baseUrl) {
        return new Builder(name, baseUrl);
    }

    /**
     * Starts a definition pre-configured with the Smile CDR plugin id,
     * so users can point FHIRViewer at Smile CDR without picking from a list.
     */
    public static Builder forSmileCdr(String name, String baseUrl) {
        return new Builder(name, baseUrl).pluginId(SmileCdrPlugin.PLUGIN_ID);
    }

    /**
     * Starts a definition pre-configured with the Firely Server plugin id,
     * so users can point FHIRViewer at Firely Server without picking from a list.
     *
     * <p>Firely Server is a separate product from Smile CDR, so this is a distinct
     * entry point from {@link #forSmileCdr(String, String)}.</p>
     */
    public static Builder forFirely(String name, String baseUrl) {
        return new Builder(name, baseUrl).pluginId(FirelyPlugin.PLUGIN_ID);
    }


    /** Builds immutable {@link ServerDefinition} instances with validation. */
    public static final class Builder {

        private String id;
        private String name;
        private String baseUrl;
        private String fhirVersion = "R4";
        private String pluginId = StandardFhirRestPlugin.PLUGIN_ID;
        private int timeoutMillis;
        private String administrationBaseUrl;

        private Builder(String name, String baseUrl) {
            // Generated here rather than in the constructor of ServerDefinition so that a
            // definition rebuilt from saved properties keeps the id it was saved with; the
            // setter below refuses to overwrite it with a different one.
            this.id = java.util.UUID.randomUUID().toString();
            name(name);
            baseUrl(baseUrl);
        }

        /**
         * Restores the identifier a saved server had.
         *
         * <p>Accepts a blank or absent value by keeping the generated one, so a settings
         * file written before ids existed still loads rather than being rejected. A
         * <em>different</em> non-blank value is accepted too, which is what restoring from
         * disk means; the generated value is only replaced when something was actually
         * supplied.</p>
         */
        public Builder id(String id) {
            if (id != null && !id.isBlank()) {
                this.id = id.trim();
            }
            return this;
        }

        /** A short human readable label shown in the server list. */
        public Builder name(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A server name is required.");
            }
            this.name = name.trim();
            return this;
        }

        /**
         * The FHIR base URL. Trailing slashes are removed and the URL must use the
         * http or https scheme; anything else is rejected here, before any network
         * call is attempted.
         */
        public Builder baseUrl(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("A FHIR base URL is required.");
            }
            String trimmed = baseUrl.trim().replaceAll("/+$", "");
            if (!trimmed.regionMatches(true, 0, "http://", 0, "http://".length())
                    && !trimmed.regionMatches(true, 0, "https://", 0, "https://".length())) {
                throw new IllegalArgumentException("A FHIR base URL must start with http:// or https://.");
            }
            this.baseUrl = trimmed;
            return this;
        }

        /**
         * Where this vendor's administration API is, when it is not under the base URL.
         *
         * <p>Optional, and blank means "not set": the field is left {@code null} and the
         * administration operations use the base URL like everything else. That default is
         * what keeps Firely working with no configuration, and what lets every existing
         * server definition stay valid without being edited.</p>
         *
         * <p>Validated exactly as {@link #baseUrl(String)} is, and for the same reason: a
         * malformed value here would otherwise surface as a failed request against the wrong
         * address, which is a far worse way to learn about a typo.</p>
         */
        public Builder administrationBaseUrl(String administrationBaseUrl) {
            if (administrationBaseUrl == null || administrationBaseUrl.isBlank()) {
                this.administrationBaseUrl = null;
                return this;
            }
            String trimmed = administrationBaseUrl.trim().replaceAll("/+$", "");
            if (!trimmed.regionMatches(true, 0, "http://", 0, "http://".length())
                    && !trimmed.regionMatches(true, 0, "https://", 0, "https://".length())) {
                throw new IllegalArgumentException(
                        "An administration base URL must start with http:// or https://.");
            }
            this.administrationBaseUrl = trimmed;
            return this;
        }

        /** The FHIR version, for example <code>R4</code>. */
        public Builder fhirVersion(String fhirVersion) {
            if (fhirVersion == null || fhirVersion.isBlank()) {
                throw new IllegalArgumentException("A FHIR version is required.");
            }
            this.fhirVersion = fhirVersion.trim();
            return this;
        }

        /** The id of the plugin that serves this server. */
        public Builder pluginId(String pluginId) {
            if (pluginId == null || pluginId.isBlank()) {
                throw new IllegalArgumentException("A plugin id is required.");
            }
            this.pluginId = pluginId.trim();
            return this;
        }

        /**
         * Connection/read timeout in milliseconds, or {@code 0} for the plugin default.
         */
        public Builder timeoutMillis(int timeoutMillis) {
            if (timeoutMillis < 0) {
                throw new IllegalArgumentException("The timeout must not be negative.");
            }
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public ServerDefinition build() {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(baseUrl, "baseUrl");
            return new ServerDefinition(this);
        }
    }
}
