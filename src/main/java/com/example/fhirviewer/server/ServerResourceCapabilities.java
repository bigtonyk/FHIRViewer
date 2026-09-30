package com.example.fhirviewer.server;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a server said it can do with one resource type.
 *
 * <p>Read out of a single {@code CapabilityStatement.rest.resource} component: the type
 * it names, the interactions it advertises and the search parameters it lists. This is
 * the per-type half of {@link ServerCapabilities}, kept as its own type because the
 * question callers actually ask is "can I patch <em>this</em> type", not "can I patch
 * something".
 *
 * <p>Everything here is what the server <em>advertised</em>, not what was tested. An
 * empty interaction set means the server made no claim, which is treated as "not
 * supported" rather than as "supported, the list is just missing" — the plan is
 * explicit that PATCH, history and conditional operations cannot be assumed.
 */
public final class ServerResourceCapabilities {

    private final String resourceType;
    private final Set<ServerInteraction> interactions;
    private final List<String> searchParameters;

    public ServerResourceCapabilities(String resourceType, Set<ServerInteraction> interactions,
            List<String> searchParameters) {
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.interactions = Set.copyOf(interactions == null ? Set.of() : interactions);
        this.searchParameters = List.copyOf(searchParameters == null ? List.of() : searchParameters);
    }

    /** The FHIR resource type, for example {@code Patient}. */
    public String resourceType() {
        return resourceType;
    }

    /** The interactions this type advertises. Never {@code null}; may be empty. */
    public Set<ServerInteraction> interactions() {
        return interactions;
    }

    /**
     * True when the server advertised this interaction for this type.
     *
     * <p>Deliberately false for an unknown interaction rather than an exception: a
     * server offering something this build does not model should not crash the read
     * that listed it.
     */
    public boolean supports(ServerInteraction interaction) {
        return interaction != null && interactions.contains(interaction);
    }

    /**
     * True when the server advertised this interaction, or advertised every interaction
     * and therefore said nothing specific.
     *
     * <p>Some servers declare a resource with no {@code interaction} list at all, on the
     * theory that the base specification already implies read/search/create. For those
     * the honest reading is "unrestricted", and refusing a read would be wrong; the
     * alternative — a server that lists only the interactions it wants highlighted
     * being treated as read-only — is the more damaging mistake.
     */
    public boolean supportsOrUnrestricted(ServerInteraction interaction) {
        return interactions.isEmpty() || supports(interaction);
    }

    /** The search parameter names this type advertises. Never {@code null}; may be empty. */
    public List<String> searchParameters() {
        return searchParameters;
    }

    /** True when the server listed this search parameter for this type. */
    public boolean supportsSearchParameter(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String wanted = name.trim();
        for (String parameter : searchParameters) {
            // A modifier or a chained name ("family:exact", "name.given") is still the
            // parameter it starts with, so both sides are compared on that head.
            String bare = parameter.indexOf(':') < 0 ? parameter : parameter.substring(0, parameter.indexOf(':'));
            if (bare.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** One line for a status bar; never prints anything the server did not advertise. */
    @Override
    public String toString() {
        return resourceType + " [" + String.join(", ", interactions.stream()
                .map(ServerInteraction::code).sorted().toList())
                + (interactions.isEmpty() ? "unrestricted" : "")
                + (searchParameters.isEmpty() ? "" : ", " + searchParameters.size() + " search parameters")
                + "]";
    }
}
