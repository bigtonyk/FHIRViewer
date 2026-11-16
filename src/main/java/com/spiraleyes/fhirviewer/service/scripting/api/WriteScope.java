package com.spiraleyes.fhirviewer.service.scripting.api;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The operations and resource types one write grant permits.
 *
 * <p>A scope is a second, narrower opt-in inside an enabled {@link WritePolicy}. The
 * console can approve "update and patch Patient only" without granting create or delete,
 * and the policy enforces that for every call, including calls through a {@code fhir}
 * reference a script kept from an earlier run.</p>
 *
 * <p>An empty resource-type set means every type; it does not mean none. That is the
 * default so an existing unbounded grant keeps its behavior.</p>
 */
public record WriteScope(Set<Operation> operations, Set<String> resourceTypes) {

    /** One write operation a scope can allow. */
    public enum Operation {
        CREATE,
        UPDATE,
        PATCH,
        DELETE;

        /** The operation name used by the scripting API, or {@code null} when unknown. */
        public static Operation named(String value) {
            if (value == null) {
                return null;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }

        /** The lowercase name scripts use. */
        public String apiName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public WriteScope {
        operations = operations == null ? Set.of() : Set.copyOf(operations);
        resourceTypes = resourceTypes == null ? Set.of() : resourceTypes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(type -> !type.isEmpty())
                .map(type -> type.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** A scope permitting every operation on every resource type. */
    public static WriteScope all() {
        return new WriteScope(Set.of(Operation.values()), Set.of());
    }

    /** A scope permitting the named operations on every resource type. */
    public static WriteScope operations(Operation... operations) {
        return new WriteScope(Set.of(operations), Set.of());
    }

    /** A scope permitting exactly the given operations and resource types. */
    public static WriteScope of(Collection<Operation> operations, Collection<String> resourceTypes) {
        return new WriteScope(new LinkedHashSet<>(operations), new LinkedHashSet<>(resourceTypes));
    }

    /** True when this scope has no operations, so no write can pass. */
    public boolean isEmpty() {
        return operations.isEmpty();
    }

    /** A scope permitting no write at all. */
    public static WriteScope none() {
        return new WriteScope(Set.of(), Set.of());
    }

    /** True when the operation and resource type are both inside this scope. */
    public boolean allows(String operation, String resourceType) {
        Operation named = Operation.named(operation);
        return named != null && operations.contains(named) && allowsType(resourceType);
    }

    private boolean allowsType(String resourceType) {
        if (resourceTypes.isEmpty()) {
            return true;
        }
        if (resourceType == null || resourceType.isBlank()) {
            // A write that cannot name its type cannot prove it is inside a type-limited
            // scope, so a limited scope refuses it.
            return false;
        }
        return resourceTypes.contains(resourceType.trim().toLowerCase(Locale.ROOT));
    }

    /** A short, user-facing description for the warning and the status line. */
    public String describe() {
        if (operations.isEmpty()) {
            return "no operations";
        }
        String operationText = operations.stream()
                .map(Operation::apiName)
                .sorted()
                .collect(Collectors.joining(", "));
        String typeText = resourceTypes.isEmpty()
                ? "all resource types"
                : resourceTypes.stream().sorted().collect(Collectors.joining(", "));
        return operationText + " on " + typeText;
    }
}
