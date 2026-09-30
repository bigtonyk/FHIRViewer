package com.example.fhirviewer.server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One call of a discovered {@link ServerOperation}: the values a caller supplied.
 *
 * <p>Kept separate from the operation on purpose. A descriptor says what an endpoint
 * needs; this says what this particular run has for it. Holding them apart is what lets a
 * UI discover a list of operations once and then invoke the same one repeatedly — paging
 * an export, re-running a validate with one more parameter — without rebuilding or
 * re-registering anything.</p>
 *
 * <p>Values are held in insertion order and are never sorted, because a query string
 * assembled deliberately by a plugin should reach the server in the order it was written.
 * Nothing here validates against the operation: that happens once, in
 * {@link PluginOperationClient}, where the descriptor is in hand.</p>
 *
 * <p><b>Never logged.</b> A path parameter is a resource id, a query parameter is a search,
 * and a header parameter can be a token, so {@link #toString()} prints only the shape.</p>
 */
public final class ServerOperationInvocation {

    private final String operationId;
    private final Map<String, String> pathParameters;
    private final Map<String, List<String>> queryParameters;
    private final Map<String, String> headerParameters;
    private final String body;

    private ServerOperationInvocation(Builder builder) {
        this.operationId = builder.operationId;
        this.pathParameters = orderedCopy(builder.pathParameters);
        this.queryParameters = orderedCopy(builder.queryParameters);
        this.headerParameters = orderedCopy(builder.headerParameters);
        this.body = builder.body;
    }

    /**
     * An unmodifiable copy that keeps insertion order.
     *
     * <p>{@code Map.copyOf} is not used, and the reason is the one already written down on
     * {@link FhirOperationRequest}: it discards iteration order. This class's own contract
     * says values "are held in insertion order and are never sorted, because a query string
     * assembled deliberately by a plugin should reach the server in the order it was
     * written" — and {@code Map.copyOf} silently broke exactly that, shuffling the query
     * parameters of every generated invocation. The generic form is what makes this path
     * common, so it is fixed here rather than left for a vendor to discover as a
     * mis-ordered query string.</p>
     */
    private static <V> Map<String, V> orderedCopy(Map<String, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /** Starts an invocation of the operation with this id. */
    public static Builder invocation(String operationId) {
        return new Builder(operationId);
    }

    /** An invocation with nothing but an operation id, for an operation that needs nothing. */
    public static ServerOperationInvocation of(String operationId) {
        return new Builder(operationId).build();
    }

    /** The id of the operation being run. Never {@code null}. */
    public String operationId() {
        return operationId;
    }

    /** The path parameter values, keyed by name. Never {@code null}. */
    public Map<String, String> pathParameters() {
        return pathParameters;
    }

    /** The query parameter values, keyed by name. Never {@code null}. */
    public Map<String, List<String>> queryParameters() {
        return queryParameters;
    }

    /** The header parameter values, keyed by name. Never {@code null}. */
    public Map<String, String> headerParameters() {
        return headerParameters;
    }

    /** The request body, or {@code null} when the caller supplied none. */
    public String body() {
        return body;
    }

    /** One path parameter value, or empty when none was supplied. */
    public Optional<String> pathParameter(String name) {
        return Optional.ofNullable(pathParameters.get(name));
    }

    /**
     * Every value supplied for one query parameter.
     *
     * <p>A list rather than a single value because a repeatable parameter — an
     * {@code _include}, a vendor's multi-valued filter — is the normal case for the
     * endpoints this type exists to reach.</p>
     */
    public List<String> queryParameter(String name) {
        List<String> values = queryParameters.get(name);
        return values == null ? List.of() : values;
    }

    /** One header parameter value, or empty when none was supplied. */
    public Optional<String> headerParameter(String name) {
        return Optional.ofNullable(headerParameters.get(name));
    }

    /** The shape of this call, safe to log: no values, no body. */
    @Override
    public String toString() {
        return operationId + " (" + pathParameters.size() + " path, "
                + queryParameters.size() + " query, " + headerParameters.size() + " header, "
                + (body == null ? "no body" : body.length() + " character body") + ")";
    }

    /** Assembles a {@link ServerOperationInvocation}. */
    public static final class Builder {

        private final String operationId;
        private final Map<String, String> pathParameters = new LinkedHashMap<>();
        private final Map<String, List<String>> queryParameters = new LinkedHashMap<>();
        private final Map<String, String> headerParameters = new LinkedHashMap<>();
        private String body;

        private Builder(String operationId) {
            if (operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("An operation id is required.");
            }
            this.operationId = operationId.trim();
        }

        /** Supplies a path parameter. A blank value is dropped, not sent as an empty segment. */
        public Builder pathParameter(String name, String value) {
            if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                pathParameters.put(name.trim(), value);
            }
            return this;
        }

        /** Appends one value to a query parameter, keeping any already added under the name. */
        public Builder queryParameter(String name, String value) {
            if (name == null || name.isBlank() || value == null) {
                return this;
            }
            queryParameters.computeIfAbsent(name.trim(), key -> new java.util.ArrayList<>()).add(value);
            return this;
        }

        /** Appends every value of a repeatable query parameter at once. */
        public Builder queryParameter(String name, List<String> values) {
            if (values != null) {
                values.forEach(value -> queryParameter(name, value));
            }
            return this;
        }

        /**
         * Supplies a header parameter.
         *
         * <p>The name is only recorded here. Whether it may actually be sent is decided by
         * {@link PluginOperationClient} once it can see the operation that declared it —
         * a value a caller could attach to any name would be the arbitrary header
         * manipulation this seam must not permit.</p>
         */
        public Builder headerParameter(String name, String value) {
            if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                headerParameters.put(name.trim(), value);
            }
            return this;
        }

        /** Sets the request body. */
        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public ServerOperationInvocation build() {
            return new ServerOperationInvocation(this);
        }
    }
}
