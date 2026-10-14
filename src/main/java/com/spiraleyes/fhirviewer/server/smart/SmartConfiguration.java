package com.spiraleyes.fhirviewer.server.smart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What a server's {@code /.well-known/smart-configuration} document said, typed.
 *
 * <p>The fields follow SMART App Launch v2.2.0 §Conformance. Two are REQUIRED by the
 * specification and therefore required by {@link #isUsable()}: the authorization and token
 * endpoints. {@code capabilities} and {@code code_challenge_methods_supported} are also
 * required by the specification, but a server that omits them still launches — so
 * {@link #problems()} reports them while the launch remains possible, which is the
 * difference between a specification violation and a broken server.</p>
 *
 * <p><b>The issuer is recorded, not assumed.</b> The FHIR base URL a user typed and the
 * {@code issuer} the document claims are two different things: a server may be fronted by a
 * proxy, and an authorization server may publish a document for several FHIR endpoints.
 * A mismatch is reported rather than silently resolved, because which one is authoritative
 * is a question only the deployment can answer.</p>
 *
 * <p><b>Unknown fields are kept.</b> A future version of the specification — or a vendor —
 * may add fields this build has never heard of, and throwing them away would make the
 * question "did the server send something we ignored?" impossible to answer. They are
 * preserved in {@link #unknownFields()} as their JSON text.</p>
 *
 * <p>Built by {@link #parse(String)}, never by hand: the parsing rules and the validation
 * rules live together so a field cannot be read one way and checked another.</p>
 */
public final class SmartConfiguration {

    private final String issuer;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String jwksUri;
    private final String registrationEndpoint;
    private final List<String> capabilities;
    private final List<String> scopesSupported;
    private final List<String> codeChallengeMethodsSupported;
    private final List<String> grantTypesSupported;
    private final List<String> responseTypesSupported;
    private final List<String> tokenEndpointAuthMethodsSupported;
    private final String managementEndpoint;
    private final String introspectionEndpoint;
    private final String revocationEndpoint;
    private final Map<String, String> unknownFields;

    private SmartConfiguration(Builder builder) {
        this.issuer = builder.issuer;
        this.authorizationEndpoint = builder.authorizationEndpoint;
        this.tokenEndpoint = builder.tokenEndpoint;
        this.jwksUri = builder.jwksUri;
        this.registrationEndpoint = builder.registrationEndpoint;
        this.capabilities = List.copyOf(builder.capabilities);
        this.scopesSupported = List.copyOf(builder.scopesSupported);
        this.codeChallengeMethodsSupported = List.copyOf(builder.codeChallengeMethodsSupported);
        this.grantTypesSupported = List.copyOf(builder.grantTypesSupported);
        this.responseTypesSupported = List.copyOf(builder.responseTypesSupported);
        this.tokenEndpointAuthMethodsSupported =
                List.copyOf(builder.tokenEndpointAuthMethodsSupported);
        this.managementEndpoint = builder.managementEndpoint;
        this.introspectionEndpoint = builder.introspectionEndpoint;
        this.revocationEndpoint = builder.revocationEndpoint;
        this.unknownFields =
                Collections.unmodifiableMap(new LinkedHashMap<>(builder.unknownFields));
    }

    public String issuer() {
        return issuer;
    }

    /** Where an authorization request must be sent; required by the specification. */
    public String authorizationEndpoint() {
        return authorizationEndpoint;
    }

    /** Where the code and refresh tokens are exchanged; required by the specification. */
    public String tokenEndpoint() {
        return tokenEndpoint;
    }

    public String jwksUri() {
        return jwksUri;
    }

    public String registrationEndpoint() {
        return registrationEndpoint;
    }

    /** The SMART capabilities the server advertises, for example {@code launch-standalone}. */
    public List<String> capabilities() {
        return capabilities;
    }

    public List<String> scopesSupported() {
        return scopesSupported;
    }

    /** The PKCE code challenge methods; the specification requires {@code S256}. */
    public List<String> codeChallengeMethodsSupported() {
        return codeChallengeMethodsSupported;
    }

    public List<String> grantTypesSupported() {
        return grantTypesSupported;
    }

    public List<String> responseTypesSupported() {
        return responseTypesSupported;
    }

    public List<String> tokenEndpointAuthMethodsSupported() {
        return tokenEndpointAuthMethodsSupported;
    }

    public String managementEndpoint() {
        return managementEndpoint;
    }

    public String introspectionEndpoint() {
        return introspectionEndpoint;
    }

    public String revocationEndpoint() {
        return revocationEndpoint;
    }

    /**
     * Fields this build did not recognise, as their JSON text.
     *
     * <p>Kept so a diagnostic can say "the server sent a field this version ignores"
     * instead of silently dropping it.</p>
     */
    public Map<String, String> unknownFields() {
        return unknownFields;
    }

    /** True when the two endpoints a launch needs are present and pass URL validation. */
    public boolean isUsable() {
        return usableEndpoint(authorizationEndpoint) && usableEndpoint(tokenEndpoint);
    }
    /**
     * What is missing, malformed, or unsafe in this document, in a form a user can read.
     *
     * <p>Empty means the document is specification-conformant and safe to launch against.
     * A non-empty list is not necessarily a refusal: {@link #isUsable()} is what decides
     * whether a launch can proceed, so the workbench can show the metadata it did learn
     * alongside the reasons it will not use it.</p>
     *
     * @return one line per problem; empty when the document is clean
     */
    public List<String> problems() {
        List<String> found = new ArrayList<>();
        if (issuer == null || issuer.isBlank()) {
            found.add("The document does not declare an issuer.");
        }
        checkEndpoint(found, "authorization_endpoint", authorizationEndpoint, true);
        checkEndpoint(found, "token_endpoint", tokenEndpoint, true);
        // Optional endpoints are still checked when present: a malformed optional URL is a
        // document the server got wrong, and the user deserves to know which field.
        checkEndpoint(found, "jwks_uri", jwksUri, false);
        checkEndpoint(found, "registration_endpoint", registrationEndpoint, false);
        checkEndpoint(found, "management_endpoint", managementEndpoint, false);
        checkEndpoint(found, "introspection_endpoint", introspectionEndpoint, false);
        checkEndpoint(found, "revocation_endpoint", revocationEndpoint, false);

        if (capabilities.isEmpty()) {
            found.add("The document advertises no capabilities; a conformant SMART server "
                    + "lists at least one.");
        }
        if (codeChallengeMethodsSupported.isEmpty()) {
            found.add("The document lists no code_challenge_methods_supported; the "
                    + "specification requires at least S256.");
        } else {
            if (!codeChallengeMethodsSupported.contains("S256")) {
                found.add("The server does not advertise S256 for PKCE. This application "
                        + "only sends S256, so a launch would be refused.");
            }
            if (codeChallengeMethodsSupported.contains("plain")) {
                // Reported even though S256 may also be listed: plain PKCE is a broken
                // challenge, and a user comparing this against another client needs to see
                // it rather than discover it when the server picks the weaker method.
                found.add("The server advertises the plain PKCE method, which offers no "
                        + "protection; S256 should be used instead.");
            }
        }
        return found;
    }

    /**
     * Checks one endpoint field, adding a line for anything the launch could not use.
     *
     * @param required whether a missing value is itself a problem
     */
    private static void checkEndpoint(List<String> found, String field, String value,
            boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                found.add("The document has no " + field + ".");
            }
            return;
        }
        java.net.URI uri = SmartUrls.parseOrNull(value);
        if (uri == null) {
            found.add("The " + field + " is not an absolute http(s) URL: " + value);
        } else if ("http".equalsIgnoreCase(uri.getScheme())
                && !SmartUrls.isLoopback(uri.getHost())) {
            // The one rule that protects a credential in transit. Plain http is fine for a
            // server on this machine and nowhere else, and discovery never downgrades an
            // https document to reach one.
            found.add("The " + field + " is plain http on " + uri.getHost()
                    + ", which would send the launch unprotected; it must be https.");
        }
    }

    /** True when a required endpoint is present and its URL is one we would send a secret to. */
    private static boolean usableEndpoint(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        java.net.URI uri = SmartUrls.parseOrNull(value);
        return uri != null && ("https".equalsIgnoreCase(uri.getScheme())
                || SmartUrls.isLoopback(uri.getHost()));
    }
    /**
     * Parses a discovery document.
     *
     * <p>Malformed JSON and a document that is not a JSON object are
     * {@link SmartDiscoveryException.Kind#NOT_SMART} failures: a server that answers the
     * well-known path with an HTML error page or a FHIR resource is not a SMART server, and
     * that is a diagnosis, not a crash. Missing fields do not throw — they are what
     * {@link #problems()} is for, so the workbench can show the metadata that did arrive.</p>
     *
     * @param json the response body
     * @return the typed document
     * @throws SmartDiscoveryException when the body is not a JSON object
     */
    public static SmartConfiguration parse(String json) throws SmartDiscoveryException {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (java.io.IOException e) {
            throw new SmartDiscoveryException(SmartDiscoveryException.Kind.NOT_SMART,
                    "The server's answer is not JSON, so it is not a SMART configuration "
                            + "document.", e);
        }
        if (root == null || !root.isObject()) {
            throw new SmartDiscoveryException(SmartDiscoveryException.Kind.NOT_SMART,
                    "The server's answer is not a JSON object, so it is not a SMART "
                            + "configuration document.");
        }

        Builder builder = new Builder();
        builder.issuer = text(root, "issuer");
        builder.authorizationEndpoint = text(root, "authorization_endpoint");
        builder.tokenEndpoint = text(root, "token_endpoint");
        builder.jwksUri = text(root, "jwks_uri");
        builder.registrationEndpoint = text(root, "registration_endpoint");
        builder.capabilities = strings(root, "capabilities");
        builder.scopesSupported = strings(root, "scopes_supported");
        builder.codeChallengeMethodsSupported =
                strings(root, "code_challenge_methods_supported");
        builder.grantTypesSupported = strings(root, "grant_types_supported");
        builder.responseTypesSupported = strings(root, "response_types_supported");
        builder.tokenEndpointAuthMethodsSupported =
                strings(root, "token_endpoint_auth_methods_supported");
        builder.managementEndpoint = text(root, "management_endpoint");
        builder.introspectionEndpoint = text(root, "introspection_endpoint");
        builder.revocationEndpoint = text(root, "revocation_endpoint");

        // Anything not read above is kept, so the diagnostics can name a field this build
        // does not know rather than pretend the server sent nothing.
        root.fieldNames().forEachRemaining(name -> {
            if (!KNOWN_FIELDS.contains(name)) {
                builder.unknownFields.put(name, root.get(name).toString());
            }
        });
        return builder.build();
    }

    /** The fields this build reads; everything else lands in {@link #unknownFields()}. */
    private static final java.util.Set<String> KNOWN_FIELDS = java.util.Set.of(
            "issuer", "authorization_endpoint", "token_endpoint", "jwks_uri",
            "registration_endpoint", "capabilities", "scopes_supported",
            "code_challenge_methods_supported", "grant_types_supported",
            "response_types_supported", "token_endpoint_auth_methods_supported",
            "management_endpoint", "introspection_endpoint", "revocation_endpoint");

    /** A scalar field's text, or {@code null} when absent or not a string. */
    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || !value.isTextual() ? null : value.textValue();
    }

    /** A string-array field's values, empty when absent or not an array of strings. */
    private static List<String> strings(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        value.forEach(item -> {
            if (item.isTextual()) {
                String text = item.textValue().trim();
                if (!text.isEmpty()) {
                    found.add(text);
                }
            }
        });
        return found;
    }
    /** @return a builder, mostly for tests; production documents come from {@link #parse} */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds a document, for tests and for a server that published nothing at all. */
    public static final class Builder {
        private String issuer;
        private String authorizationEndpoint;
        private String tokenEndpoint;
        private String jwksUri;
        private String registrationEndpoint;
        private List<String> capabilities = List.of();
        private List<String> scopesSupported = List.of();
        private List<String> codeChallengeMethodsSupported = List.of();
        private List<String> grantTypesSupported = List.of();
        private List<String> responseTypesSupported = List.of();
        private List<String> tokenEndpointAuthMethodsSupported = List.of();
        private String managementEndpoint;
        private String introspectionEndpoint;
        private String revocationEndpoint;
        private final Map<String, String> unknownFields = new LinkedHashMap<>();

        public Builder issuer(String value) {
            this.issuer = value;
            return this;
        }

        public Builder authorizationEndpoint(String value) {
            this.authorizationEndpoint = value;
            return this;
        }

        public Builder tokenEndpoint(String value) {
            this.tokenEndpoint = value;
            return this;
        }

        public Builder jwksUri(String value) {
            this.jwksUri = value;
            return this;
        }

        public Builder registrationEndpoint(String value) {
            this.registrationEndpoint = value;
            return this;
        }

        public Builder capabilities(List<String> value) {
            this.capabilities = value == null ? List.of() : value;
            return this;
        }

        public Builder scopesSupported(List<String> value) {
            this.scopesSupported = value == null ? List.of() : value;
            return this;
        }

        public Builder codeChallengeMethodsSupported(List<String> value) {
            this.codeChallengeMethodsSupported = value == null ? List.of() : value;
            return this;
        }

        public Builder managementEndpoint(String value) {
            this.managementEndpoint = value;
            return this;
        }

        public Builder introspectionEndpoint(String value) {
            this.introspectionEndpoint = value;
            return this;
        }

        public Builder revocationEndpoint(String value) {
            this.revocationEndpoint = value;
            return this;
        }

        /** A document the server published nothing useful in; every problem is reported. */
        public static Builder empty() {
            return new Builder();
        }

        public SmartConfiguration build() {
            return new SmartConfiguration(this);
        }
    }

    @Override
    public String toString() {
        // No field could be a credential — the document is public by definition — so every
        // part of it is safe to log.
        return "SmartConfiguration[issuer=" + issuer
                + ", authorizationEndpoint=" + authorizationEndpoint
                + ", tokenEndpoint=" + tokenEndpoint
                + ", capabilities=" + capabilities.size()
                + ", unknownFields=" + unknownFields.keySet() + "]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SmartConfiguration that
                && java.util.Objects.equals(issuer, that.issuer)
                && java.util.Objects.equals(authorizationEndpoint, that.authorizationEndpoint)
                && java.util.Objects.equals(tokenEndpoint, that.tokenEndpoint)
                && capabilities.equals(that.capabilities)
                && codeChallengeMethodsSupported.equals(that.codeChallengeMethodsSupported);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(issuer, authorizationEndpoint, tokenEndpoint,
                capabilities, codeChallengeMethodsSupported);
    }
}