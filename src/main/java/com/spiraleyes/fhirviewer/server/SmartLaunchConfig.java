package com.spiraleyes.fhirviewer.server;

import java.util.Objects;

/**
 * One SMART launch: the server, the client registration and the access being asked for.
 *
 * <p>This is a value type, not a session. It says what a user <em>intends</em> to launch and
 * nothing about whether that launch succeeded, which is what keeps "did the authorization
 * work?" answerable — a configuration that changed shape on success would make the second run
 * of a working setup behave differently from the first. The authorization state, granted
 * scopes and patient context belong to the session Phase 03 produces.</p>
 *
 * <p><b>No secret is stored.</b> SMART on FHIR from a desktop client is a
 * <em>public</em> client: it cannot keep a client secret, because anything shipped inside the
 * jar is readable. So this record deliberately has no such field, and the workbench must not
 * grow one. The only secret in the whole flow is the access token, which lives in memory for
 * the session and is never written to disk — see {@code ServerPassphrase} and
 * {@link PluginSettingsStore} for the two places a secret is allowed to exist.</p>
 *
 * <p>Built through {@link Builder} rather than a canonical constructor of eight arguments,
 * following {@link ServerDefinition}: a launch is described by naming the server, and
 * everything else is a refinement.</p>
 *
 * @param id              stable identifier used as the persistence key; never blank
 * @param name            the name shown in the workbench list; never blank
 * @param issuer          the FHIR server base URL, sent as the {@code iss} parameter; never blank
 * @param mode            standalone or EHR launch; never {@code null}
 * @param clientId        the registered OAuth client id; may be blank until registered
 * @param redirectUri     the registered redirect URI, where the flow uses one; may be blank
 * @param scopes          space separated SMART scopes to request; may be blank
 * @param launchParameter the {@code launch} parameter an EHR supplies; only used in EHR launch
 * @param audience        the {@code aud} request parameter; only used in standalone launch
 */
public final class SmartLaunchConfig {

    private final String id;
    private final String name;
    private final String issuer;
    private final SmartLaunchMode mode;
    private final String clientId;
    private final String redirectUri;
    private final String scopes;
    private final String launchParameter;
    private final String audience;

    private SmartLaunchConfig(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.issuer = builder.issuer;
        this.mode = builder.mode == null ? SmartLaunchMode.STANDALONE : builder.mode;
        this.clientId = emptyToNull(builder.clientId);
        this.redirectUri = emptyToNull(builder.redirectUri);
        this.scopes = emptyToNull(builder.scopes);
        this.launchParameter = emptyToNull(builder.launchParameter);
        this.audience = emptyToNull(builder.audience);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** The {@code iss} of an authorization request. */
    public String issuer() {
        return issuer;
    }

    public SmartLaunchMode mode() {
        return mode;
    }

    public String clientId() {
        return clientId;
    }

    public String redirectUri() {
        return redirectUri;
    }

    public String scopes() {
        return scopes;
    }

    /**
     * The EHR's {@code launch} parameter, or {@code null}.
     *
     * <p>Null rather than empty for a standalone launch, so "this launch has no context" and
     * "this launch has an empty context parameter" cannot be confused — the first is correct,
     * the second is a server that was sent a blank parameter and is entitled to refuse.</p>
     */
    public String launchParameter() {
        return launchParameter;
    }

    /** The {@code aud} parameter, or {@code null} when this launch does not send one. */
    public String audience() {
        return audience;
    }

    /**
     * A copy of this configuration with a new name and identity.
     *
     * <p>Used by the workbench's duplicate action, which has to produce a new identity rather
     * than a second row pointing at the same stored block. The caller supplies the id, because
     * choosing it is a persistence concern.</p>
     */
    public SmartLaunchConfig withName(String newName, String newId) {
        return SmartLaunchConfig.builder()
                .id(newId)
                .name(newName)
                .issuer(issuer)
                .mode(mode)
                .clientId(clientId)
                .redirectUri(redirectUri)
                .scopes(scopes)
                .launchParameter(launchParameter)
                .audience(audience)
                .build();
    }

    /**
     * Whether this configuration has everything an authorization request needs.
     *
     * <p>Returns a verdict rather than throwing, and lives on the model, because the workbench
     * has to enable and disable its authorize button from this and the store has to refuse an
     * unusable entry — so the rule belongs in one place rather than in both.</p>
     */
    public boolean isComplete() {
        return problems().isEmpty();
    }

    /**
     * What is missing, in a form a user can read.
     *
     * <p>Only the two things the specification makes non-optional are reported as missing: an
     * {@code iss} to send and a client id to identify the client. Scopes may be empty, since a
     * request for no scope is a legitimate way to find out what a server will grant. A launch
     * parameter without an EHR launch — or an EHR launch without one — is reported, because
     * both mean the wrong half of the form has been filled in.</p>
     *
     * @return one line per problem; empty when the configuration is ready
     */
    public java.util.List<String> problems() {
        java.util.List<String> found = new java.util.ArrayList<>();
        if (issuer == null || issuer.isBlank()) {
            found.add("A FHIR server base URL (iss) is required.");
        }
        if (clientId == null || clientId.isBlank()) {
            found.add("An OAuth client id is required.");
        }
        if (mode.needsLaunchParameter() && launchParameter == null) {
            found.add("An EHR launch needs the launch parameter the EHR supplies.");
        }
        if (!mode.needsLaunchParameter() && launchParameter != null) {
            found.add("A launch parameter only applies to an EHR launch.");
        }
        if (!mode.allowsAudience() && audience != null) {
            found.add("An audience only applies to a standalone launch; an EHR launch gets its "
                    + "resource server from the issuer.");
        }
        return found;
    }

    /**
     * One line for a list cell or a status bar.
     *
     * <p>Nothing on this record could be a credential — which is the point of not having a
     * client secret field — so this is safe to log and safe to show.</p>
     */
    @Override
    public String toString() {
        return name + " (" + mode.label() + ") -> " + (issuer == null ? "no server" : issuer);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof SmartLaunchConfig candidate && Objects.equals(id, candidate.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /** Blank becomes null and everything is trimmed, so a stray space cannot change a value. */
    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Describes a launch, then builds it.
     *
     * <p>Same shape as {@link ServerDefinition.Builder}: {@link #named(String)} for the field
     * that is always required, optional setters for the rest, and {@link #build()} for the
     * moment of validation. The build refuses an entry with no id or no name, because both are
     * persistence keys and an unusable one would silently overwrite another row.</p>
     */
    public static final class Builder {

        private String id;
        private String name;
        private String issuer;
        private SmartLaunchMode mode;
        private String clientId;
        private String redirectUri;
        private String scopes;
        private String launchParameter;
        private String audience;

        private Builder() {
        }

        /** Starts a configuration that only has a name; every other field is optional. */
        public static Builder named(String name) {
            Builder builder = new Builder();
            builder.name = name;
            return builder;
        }

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        /** @param value the FHIR server base URL, sent as {@code iss} */
        public Builder issuer(String value) {
            this.issuer = value;
            return this;
        }

        public Builder mode(SmartLaunchMode value) {
            this.mode = value;
            return this;
        }

        /** @param value the registered public client id */
        public Builder clientId(String value) {
            this.clientId = value;
            return this;
        }

        public Builder redirectUri(String value) {
            this.redirectUri = value;
            return this;
        }

        /** @param value space separated SMART scopes */
        public Builder scopes(String value) {
            this.scopes = value;
            return this;
        }

        public Builder launchParameter(String value) {
            this.launchParameter = value;
            return this;
        }

        public Builder audience(String value) {
            this.audience = value;
            return this;
        }

        /**
         * @throws IllegalArgumentException when the id or the name is missing, since both are
         *         needed to store the configuration and a half-stored launch is worse than none
         */
        public SmartLaunchConfig build() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("A launch configuration needs an id.");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A launch configuration needs a name.");
            }
            return new SmartLaunchConfig(this);
        }
    }

    /** @return a builder, to be given a name with {@link Builder#named(String)} */
    public static Builder builder() {
        return new Builder();
    }
}
