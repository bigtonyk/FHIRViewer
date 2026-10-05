package com.example.fhirviewer.server.rest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One HTTP request to a FHIR or vendor server, described without reference to any client.
 *
 * <p>A request is data, not a call: it is built, handed to a {@link RestClient} and then
 * discarded. Nothing here knows about sockets, JavaFX or a particular HTTP library, so a
 * plugin can build one in a test and a service can build one on a background thread
 * without either depending on the other's machinery.
 *
 * <p>The path is relative to the server's base URL (or an absolute URL, which
 * {@link RestUrls#join} passes through unchanged) and the query parameters are kept apart
 * from it, so joining a URL never has to parse a query string back out again.
 *
 * <p>{@link #toString()} deliberately prints neither the body, the parameter values nor the
 * header values. A search parameter can carry a patient name and a body can carry a whole
 * resource, and requests end up in log lines; only the shape is described.
 */
public final class RestRequest {

    /** The content type assumed when a body is supplied without one. */
    public static final String DEFAULT_CONTENT_TYPE = "application/fhir+json";

    /** The type asked for when the caller expresses no preference. */
    public static final String DEFAULT_ACCEPT = "application/fhir+json";

    private final RestMethod method;
    private final String path;
    private final Map<String, List<String>> queryParameters;
    private final RestHeaders headers;
    private final String body;
    /**
     * Whether this call goes to the vendor's administration API rather than the FHIR one.
     *
     * <p>A flag on the request rather than a second client or a convention about paths,
     * because "this is an administration call" is a fact about the call site and is already
     * known there — the plugin declared it. Inferring it from the path would be a guess,
     * and a wrong guess sends a request to an address the user did not choose.</p>
     */
    private final boolean administration;

    private RestRequest(RestMethod method, String path,
            Map<String, List<String>> queryParameters, RestHeaders headers, String body,
            boolean administration) {
        this.method = method;
        this.path = path;
        this.queryParameters = queryParameters;
        this.headers = headers;
        this.body = body;
        this.administration = administration;
    }

    /** True when this request should be sent to {@code administrationBaseUrl()}. */
    public boolean isAdministration() {
        return administration;
    }

    /** Starts a request for any verb. */
    public static Builder builder(RestMethod method, String path) {
        return new Builder(method, path);
    }

    /** A {@code GET}, the common case of reading metadata, a resource or a search. */
    public static RestRequest get(String path) {
        return builder(RestMethod.GET, path).build();
    }

    /** A {@code POST} with no body yet; add one with {@link Builder#body(String)}. */
    public static RestRequest post(String path) {
        return builder(RestMethod.POST, path).build();
    }

    /** A {@code PUT}, used for a conditional replace. */
    public static RestRequest put(String path) {
        return builder(RestMethod.PUT, path).build();
    }

    /** A {@code PATCH} in the server's own patch format. */
    public static RestRequest patch(String path) {
        return builder(RestMethod.PATCH, path).build();
    }

    /** A {@code DELETE}. */
    public static RestRequest delete(String path) {
        return builder(RestMethod.DELETE, path).build();
    }

    public RestMethod method() {
        return method;
    }

    /** The path, relative to the base URL or absolute. Never blank. */
    public String path() {
        return path;
    }

    /** The query parameters in the order they were added. Never {@code null}. */
    public Map<String, List<String>> queryParameters() {
        return queryParameters;
    }

    /**
     * The first value of one query parameter, or {@code null} when it was not set.
     *
     * <p>Parameter names are case-sensitive, because the FHIR search parameters they
     * usually carry are.
     */
    public String parameter(String name) {
        List<String> values = queryParameters.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** The headers, including {@code Content-Type} and {@code Accept}. Never {@code null}. */
    public RestHeaders headers() {
        return headers;
    }

    /** The request body, or {@code null} when there is none. */
    public String body() {
        return body;
    }

    /** True when a body was supplied. */
    public boolean hasBody() {
        return body != null;
    }

    /** The content type, or {@code null} when none was set and none was needed. */
    public String contentType() {
        return headers.first("Content-Type");
    }

    /** The type being asked for. Defaults to {@link #DEFAULT_ACCEPT}. */
    public String accept() {
        String declared = headers.first("Accept");
        return declared == null || declared.isBlank() ? DEFAULT_ACCEPT : declared;
    }

    /**
     * A description safe to log: the verb, the path, and how much else there is.
     *
     * <p>No body, no parameter values, no header values — all three can carry patient
     * data or credentials.
     */
    @Override
    public String toString() {
        return method + " " + path
                + " (" + queryParameters.size() + " parameter group(s), "
                + headers.size() + " header(s), "
                + (hasBody() ? body.length() + " character body" : "no body") + ")";
    }

    /** Assembles a {@link RestRequest}, filling in the content negotiation defaults. */
    public static final class Builder {

        private final RestMethod method;
        private final String path;
        private final Map<String, List<String>> queryParameters = new LinkedHashMap<>();
        private final RestHeaders.Builder headers = RestHeaders.builder();
        private String body;
        private boolean administration;

        private Builder(RestMethod method, String path) {
            this.method = Objects.requireNonNull(method, "method");
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("A request path is required.");
            }
            this.path = path.trim();
        }

        /** Adds a query parameter, keeping any value already set under the same name. */
        public Builder parameter(String name, String value) {
            if (name == null || name.isBlank() || value == null) {
                return this;
            }
            List<String> values =
                    queryParameters.computeIfAbsent(name, key -> new ArrayList<>());
            values.add(value);
            return this;
        }

        /** Adds every entry of the given parameters, in the map's order. */
        public Builder parameters(Map<String, List<String>> source) {
            if (source == null) {
                return this;
            }
            source.forEach((name, values) -> {
                if (values != null) {
                    values.forEach(value -> parameter(name, value));
                }
            });
            return this;
        }

        /** Sets the request body. Rejected at build time for a verb that cannot carry one. */
        public Builder body(String body) {
            this.body = body;
            return this;
        }

        /** Sets the {@code Content-Type}. */
        public Builder contentType(String contentType) {
            headers.contentType(contentType);
            return this;
        }

        /** Sets the {@code Accept} type. */
        public Builder accept(String accept) {
            headers.accept(accept);
            return this;
        }

        /** Negotiates FHIR JSON for both the content type and the accepted type. */
        public Builder fhirJson() {
            return contentType(DEFAULT_CONTENT_TYPE).accept(DEFAULT_ACCEPT);
        }

        /** Adds an arbitrary header, keeping any value already set under the same name. */
        public Builder header(String name, String value) {
            headers.add(name, value);
            return this;
        }

        /**
         * Builds the request, defaulting the content negotiation headers.
         *
         * @throws IllegalArgumentException when a body was given to a verb that cannot
         *         carry one, which the server would otherwise ignore in silence
         */
        public RestRequest build() {
            if (body != null && !method.allowsRequestBody()) {
                throw new IllegalArgumentException(method + " cannot carry a request body.");
            }
            RestHeaders soFar = headers.build();
            if (body != null && !soFar.contains("Content-Type")) {
                headers.contentType(DEFAULT_CONTENT_TYPE);
            }
            if (!soFar.contains("Accept")) {
                headers.accept(DEFAULT_ACCEPT);
            }
            // Frozen through an unmodifiable *view* of the LinkedHashMap, not through
            // Map.copyOf. Map.copyOf does not preserve iteration order, so a query string a
            // caller assembled deliberately would reach the server shuffled — which is
            // exactly the bug this was already fixed for in FhirOperationRequest, where a
            // Parameters body came out reordered. The same reasoning applies here, and a
            // repeatable parameter like _include is order-sensitive on some servers.
            Map<String, List<String>> frozen = new LinkedHashMap<>();
            queryParameters.forEach((name, values) -> frozen.put(name, List.copyOf(values)));
            return new RestRequest(method, path, Collections.unmodifiableMap(frozen),
                    headers.build(), body, administration);
        }

        /**
         * Marks this as a call to the vendor's administration API.
         *
         * <p>Only takes effect when the server actually declares a separate administration
         * URL. With none configured the request goes to the base URL like any other, which is
         * what keeps a Firely server working with nothing filled in — its administration
         * API is a branch of the same origin.</p>
         */
        public Builder administration() {
            this.administration = true;
            return this;
        }
    }

}
