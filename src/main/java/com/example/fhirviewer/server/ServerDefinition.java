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

    private final String name;
    private final String baseUrl;
    private final String fhirVersion;
    private final String pluginId;
    private final int timeoutMillis;

    private ServerDefinition(Builder builder) {
        this.name = builder.name;
        this.baseUrl = builder.baseUrl;
        this.fhirVersion = builder.fhirVersion;
        this.pluginId = builder.pluginId;
        this.timeoutMillis = builder.timeoutMillis;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
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

        private String name;
        private String baseUrl;
        private String fhirVersion = "R4";
        private String pluginId = StandardFhirRestPlugin.PLUGIN_ID;
        private int timeoutMillis;

        private Builder(String name, String baseUrl) {
            name(name);
            baseUrl(baseUrl);
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
