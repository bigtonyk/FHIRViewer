package com.example.fhirviewer.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.ResourceInteractionComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.SystemInteractionComponent;

/**
 * Reads a FHIR R4 {@code CapabilityStatement} into the application's own
 * {@link ServerCapabilities}.
 *
 * <p>This is the one place that knows how a capability statement is shaped. Everything
 * downstream — the plugin, the service, the UI — works with the result, so a server
 * advertising an interaction this build does not model, or FHIR version R5B arriving,
 * is a change in this file rather than a change across the application.
 *
 * <p>Nothing here is defensive about a statement being wrong: a resource component with
 * no type, an interaction with no code, a search parameter with no name are all simply
 * not counted, because a capability statement that omits something is the server
 * declining it, and inventing a default would be a claim the server never made.
 */
public final class ServerCapabilityReader {

    private ServerCapabilityReader() {
    }

    /**
     * Reads a statement, or an empty capability set when the server sent none.
     *
     * @param statement the parsed {@code CapabilityStatement}, possibly {@code null}
     */
    public static ServerCapabilities of(CapabilityStatement statement) {
        if (statement == null) {
            return ServerCapabilities.empty();
        }
        List<String> resourceTypes = new ArrayList<>();
        Set<ServerInteraction> serverWide = new LinkedHashSet<>();
        Map<String, ServerResourceCapabilities> resources = new LinkedHashMap<>();

        for (CapabilityStatementRestComponent rest : statement.getRest()) {
            if (rest == null) {
                continue;
            }
            // System-level interactions apply to everything the server exposes, so
            // they are collected alongside the per-type ones rather than ignored.
            serverWide.addAll(systemInteractionsOf(rest.getInteraction()));
            for (CapabilityStatementRestResourceComponent resource : rest.getResource()) {
                readResource(resource, resourceTypes, resources);
            }
        }

        Set<ServerInteraction> everything = new LinkedHashSet<>(serverWide);
        resources.values().forEach(each -> everything.addAll(each.interactions()));

        String version = statement.getFhirVersion() == null ? "" : statement.getFhirVersion().toCode();
        // R4 declares no paging flag, so this answers the nearest question the
        // statement can: does search exist at all, here or on any type? A server that
        // advertises no search cannot return a searchset Bundle to page through.
        boolean paging = everything.contains(ServerInteraction.SEARCH);
        return new ServerCapabilities(version, resourceTypes, paging, everything, resources);
    }

    private static void readResource(CapabilityStatementRestResourceComponent resource,
            List<String> resourceTypes, Map<String, ServerResourceCapabilities> resources) {
        if (resource == null || resource.getType() == null || resource.getType().isBlank()) {
            return;
        }
        String type = resource.getType();
        if (resourceTypes.contains(type)) {
            return;
        }
        resourceTypes.add(type);
        resources.put(type, new ServerResourceCapabilities(type,
                interactionsOf(resource.getInteraction()),
                searchParametersOf(resource.getSearchParam())));
    }

    /**
     * The interactions a resource component advertised, skipping unknown codes.
     *
     * <p>An interaction this build does not model is dropped rather than guessed at: a
     * server offering something unrecognised is one this build cannot offer, and
     * claiming otherwise would put an operation in the UI that cannot be run.</p>
     */
    private static Set<ServerInteraction> interactionsOf(
            List<ResourceInteractionComponent> components) {
        Set<ServerInteraction> found = new LinkedHashSet<>();
        for (ResourceInteractionComponent component : components) {
            if (component != null && component.getCode() != null) {
                ServerInteraction.fromCode(component.getCode().toCode()).ifPresent(found::add);
            }
        }
        return found;
    }

    /**
     * The system-level interactions a server declared, mapped onto the same enum.
     *
     * <p>R4 spells these differently ({@code transaction}, {@code batch},
     * {@code search-system}, {@code history-system}, {@code system-level}) and this
     * build models none of them as a per-resource interaction, so the result is normally
     * empty. It is still collected rather than skipped, because a server that declares
     * nothing but a system-level search genuinely can be searched on.
     */
    private static Set<ServerInteraction> systemInteractionsOf(
            List<SystemInteractionComponent> components) {
        Set<ServerInteraction> found = new LinkedHashSet<>();
        for (SystemInteractionComponent component : components) {
            if (component != null && component.getCode() != null
                    && "search-system".equals(component.getCode().toCode())) {
                found.add(ServerInteraction.SEARCH);
            }
        }
        return found;
    }

    /** The search parameter names a resource advertised, in the order the server listed. */
    private static List<String> searchParametersOf(
            List<CapabilityStatementRestResourceSearchParamComponent> parameters) {
        List<String> names = new ArrayList<>();
        for (CapabilityStatementRestResourceSearchParamComponent parameter : parameters) {
            if (parameter != null && parameter.getName() != null && !parameter.getName().isBlank()
                    && !names.contains(parameter.getName())) {
                names.add(parameter.getName());
            }
        }
        return names;
    }
}
