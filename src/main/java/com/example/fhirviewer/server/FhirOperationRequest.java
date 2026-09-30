package com.example.fhirviewer.server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One invocation of a standard FHIR {@code $}-operation.
 *
 * <p>FHIR defines operations at three levels, and the level decides the URL and whether
 * an id is needed at all, so it is modelled explicitly rather than inferred from which
 * fields happen to be set:
 *
 * <pre>
 *   SERVER    GET|POST [base]/$operation
 *   TYPE      GET|POST [base]/[type]/$operation
 *   INSTANCE  GET|POST [base]/[type]/[id]/$operation
 * </pre>
 *
 * <p>Parameters are name/value pairs and are sent as a {@code Parameters} resource, the
 * form every R4 server accepts. Anything richer — a repeated parameter, a part, a
 * nested resource — would need a typed model, and guessing at one would send a request
 * the server cannot read; that is a later increment on this type, not a widening of the
 * string map.
 *
 * <p>The operation name is validated here rather than at request time. It goes straight
 * into a URL path, and a name carrying {@code /} or {@code ?} from a parameter or a
 * vendor descriptor would otherwise be able to redirect the request somewhere the user
 * did not intend.
 *
 * @param scope       which level the operation is invoked at
 * @param resourceType the resource type for {@link Scope#TYPE} and {@link Scope#INSTANCE}
 * @param resourceId   the resource id for {@link Scope#INSTANCE}
 * @param name         the operation name, with or without the leading {@code $}
 * @param parameters   the input parameters, in call order
 */
public record FhirOperationRequest(Scope scope, String resourceType, String resourceId,
        String name, Map<String, String> parameters) {

    /** Where an operation is invoked relative to the server's base URL. */
    public enum Scope {
        /** A whole-server operation, such as {@code $export}. */
        SERVER,
        /** A type-level operation, such as {@code Patient/$everything}. */
        TYPE,
        /** An instance-level operation, such as {@code Patient/1/$everything}. */
        INSTANCE
    }

    public FhirOperationRequest {
        Objects.requireNonNull(scope, "scope");
        name = validateName(name);
        resourceType = scope == Scope.SERVER ? null : requireToken(resourceType, "resourceType");
        resourceId = scope == Scope.INSTANCE ? requireToken(resourceId, "resourceId") : null;
        // An unmodifiable LinkedHashMap, not Map.copyOf: Map.copyOf discards iteration
        // order, and a Parameters resource the caller assembled in a deliberate order
        // would come out shuffled. Order is kept here so orderedParameters() is honest.
        parameters = parameters == null || parameters.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    /** A whole-server operation. */
    public static FhirOperationRequest onServer(String name) {
        return new FhirOperationRequest(Scope.SERVER, null, null, name, Map.of());
    }

    /** A type-level operation, with no parameters. */
    public static FhirOperationRequest onType(String resourceType, String name) {
        return new FhirOperationRequest(Scope.TYPE, resourceType, null, name, Map.of());
    }

    /** A type-level operation with parameters. */
    public static FhirOperationRequest onType(String resourceType, String name,
            Map<String, String> parameters) {
        return new FhirOperationRequest(Scope.TYPE, resourceType, null, name, parameters);
    }

    /** An instance-level operation, with no parameters. */
    public static FhirOperationRequest onInstance(String resourceType, String resourceId, String name) {
        return new FhirOperationRequest(Scope.INSTANCE, resourceType, resourceId, name, Map.of());
    }

    /**
     * The path below the base URL this operation is invoked at.
     *
     * <p>Built here, once, from already-validated parts, so no transport has to know
     * how FHIR lays out an operation URL.
     */
    public String path() {
        return switch (scope) {
            case SERVER -> "$" + name;
            case TYPE -> resourceType + "/$" + name;
            case INSTANCE -> resourceType + "/" + resourceId + "/$" + name;
        };
    }

    /** How this operation is named in a message or a log line. */
    public String describe() {
        return "$" + name + " (" + switch (scope) {
            case SERVER -> "server";
            case TYPE -> resourceType;
            case INSTANCE -> resourceType + "/" + resourceId;
        } + ")";
    }

    /**
     * The parameters, in the order they were supplied, for a {@code Parameters} body.
     *
     * <p>A fresh mutable copy, because the caller is building a FHIR resource from it
     * and may need to add to it; the request itself stays immutable.</p>
     */
    public Map<String, String> orderedParameters() {
        return new LinkedHashMap<>(parameters);
    }

    /**
     * Normalises the name to the bare word and rejects anything that is not one.
     *
     * <p>FHIR operation names are {@code [A-Za-z0-9_]}, optionally {@code -} separated
     * words, and always start with {@code $} on the wire. A name carrying a slash would
     * silently address a different resource.
     */
    private static String validateName(String name) {
        String bare = requireToken(name, "name");
        if (bare.startsWith("$")) {
            bare = bare.substring(1);
        }
        if (bare.isEmpty() || !bare.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("'" + name + "' is not a FHIR operation name.");
        }
        return bare;
    }

    /** A single URL path segment, free of anything that would change the path. */
    private static String requireToken(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A " + what + " is required.");
        }
        String trimmed = value.trim();
        if (trimmed.contains("/") || trimmed.contains("?") || trimmed.contains("#")) {
            throw new IllegalArgumentException("A " + what + " cannot contain '/', '?' or '#'.");
        }
        return trimmed;
    }

    /** Never prints parameter values: they routinely carry patient data. */
    @Override
    public String toString() {
        return describe() + " with " + parameters.size() + " parameters";
    }
}
