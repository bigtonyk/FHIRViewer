package com.example.fhirviewer.server;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a plugin reports about a server after a connection test or a metadata fetch.
 *
 * <p>This is the capability data the UI needs: the FHIR version the server speaks, the
 * resource types it supports and the search parameters it advertises. Raw HAPI
 * CapabilityStatement handling stays inside the plugins; callers use this instead.</p>
 */
public class ServerCapabilities {

    private final String fhirVersion;
    private final List<String> resourceTypes;
    private final boolean pagingSupported;
    private final Set<ServerInteraction> interactions;
    private final Map<String, ServerResourceCapabilities> resources;

    /** A capability set with no interactions and no per-type detail. */
    public ServerCapabilities(String fhirVersion, List<String> resourceTypes, boolean pagingSupported) {
        this(fhirVersion, resourceTypes, pagingSupported, Set.of(), Map.of());
    }

    public ServerCapabilities(String fhirVersion, List<String> resourceTypes, boolean pagingSupported,
            Set<ServerInteraction> interactions, Map<String, ServerResourceCapabilities> resources) {
        this.fhirVersion = Objects.requireNonNull(fhirVersion, "fhirVersion");
        this.resourceTypes = List.copyOf(Objects.requireNonNull(resourceTypes, "resourceTypes"));
        this.pagingSupported = pagingSupported;
        this.interactions = Set.copyOf(interactions == null ? Set.of() : interactions);
        this.resources = Map.copyOf(resources == null ? Map.of() : resources);
    }

    /** The FHIR version the server reported, for example <code>R4</code>. */
    public String fhirVersion() {
        return fhirVersion;
    }

    /** The resource types the server supports, in server order. Never {@code null}. */
    public List<String> resourceTypes() {
        return resourceTypes;
    }

    /**
     * True when the server can be asked to return a search Bundle at all.
     *
     * <p>R4 has no capability-statement flag for paging: a server that will send
     * {@code next} links is not distinguishable at metadata time from one that will
     * send none. So this answers the question the statement <em>can</em> answer, which
     * is whether search exists; the honest answer to "did this particular search page?"
     * is read from the Bundle's own links. That is why {@link SearchPageLinks} exists,
     * and why this was worth filling in at all: it used to be hard-coded {@code false},
     * which was never true of anything.
     */
    public boolean pagingSupported() {
        return pagingSupported;
    }

    /**
     * The interactions the server advertises across all of its resources.
     *
     * <p>Empty when the server declared no resource components at all, which is what a
     * minimal statement looks like. Ask {@link #supports(String, ServerInteraction)}
     * for a per-type question.
     */
    public Set<ServerInteraction> interactions() {
        return interactions;
    }

    /**
     * What the server said it can do with one resource type, when it said anything.
     *
     * <p>Empty for a type the statement did not describe in detail, which is not the
     * same as a type the server refused: {@link #supports(String, ServerInteraction)}
     * treats that as unrestricted.
     */
    public Optional<ServerResourceCapabilities> resource(String resourceType) {
        return resourceType == null ? Optional.empty()
                : Optional.ofNullable(resources.get(resourceType.trim()));
    }

    /**
     * True when the server advertises this interaction for this resource type.
     *
     * <p>A type the statement describes with no interaction list at all is treated as
     * unrestricted: a server omitting the list is relying on the base specification
     * rather than declining, and refusing a read on that basis would be worse than the
     * opposite mistake. A type that lists some interactions and not {@code PATCH} has
     * declined {@code PATCH}.
     *
     * <p>False for a type the server never described, so asking about a resource the
     * statement omitted is a refusal rather than a guess.
     */
    public boolean supports(String resourceType, ServerInteraction interaction) {
        if (interaction == null) {
            return false;
        }
        Optional<ServerResourceCapabilities> described = resource(resourceType);
        if (described.isPresent()) {
            return described.get().supportsOrUnrestricted(interaction);
        }
        // No per-type detail anywhere: fall back to what the server declared overall.
        return resources.isEmpty() && interactions.contains(interaction);
    }

    /**
     * Copies every field, so a subclass that only adds a vendor tag cannot accidentally
     * drop what this phase added.
     *
     * <p>{@code FirelyPlugin} and {@code SmileCdrPlugin} subclass this type to tag a
     * result as theirs. A three-field copy constructor would leave a vendor server
     * looking like it supports nothing beyond a version string, which is both wrong and
     * worse than the un-tagged result the wrapper is decorating.</p>
     */
    protected ServerCapabilities(ServerCapabilities other) {
        this(Objects.requireNonNull(other, "other").fhirVersion, other.resourceTypes,
                other.pagingSupported, other.interactions, other.resources);
    }

    /** An empty capability set, used when a server answers but reports nothing usable. */
    public static ServerCapabilities empty() {
        return new ServerCapabilities("", List.of(), false);
    }

    @Override
    public String toString() {
        return "FHIR " + fhirVersion + ", " + resourceTypes.size() + " resource types"
                + (pagingSupported ? ", paging" : "")
                + (interactions.isEmpty() ? "" : ", " + interactions.size() + " interactions");
    }
}
