package com.example.fhirviewer.server;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the FHIR servers the user has configured and which one is active.
 *
 * <p>In-memory for now: persistence (plan section 20, <code>servers.json</code> behind a
 * {@code FhirServerRepository}) can be added inside this class later without touching
 * the UI, which only ever asks this manager about servers. Servers are matched by name,
 * so a name acts as the user visible identity of a server.</p>
 */
public final class FhirServerManager {

    private final List<FhirServerConfiguration> servers = new CopyOnWriteArrayList<>();
    private volatile FhirServerConfiguration active;

    /**
     * Adds a server.
     *
     * @return {@code true} when it was added, {@code false} when a server with the same
     *         name is already configured
     */
    public synchronized boolean add(FhirServerConfiguration server) {
        Objects.requireNonNull(server, "server");
        if (find(server.name()) != null) {
            return false;
        }
        servers.add(server);
        if (active == null) {
            active = server;
        }
        return true;
    }

    /** Removes a server, clearing the active selection when it pointed at this server. */
    public synchronized boolean remove(FhirServerConfiguration server) {
        if (server == null) {
            return false;
        }
        boolean removed = servers.removeIf(existing -> existing.name().equals(server.name()));
        if (removed && active != null && active.name().equals(server.name())) {
            active = servers.isEmpty() ? null : servers.get(0);
        }
        return removed;
    }

    /** The configured servers, in configuration order. Never {@code null}. */
    public List<FhirServerConfiguration> servers() {
        return List.copyOf(servers);
    }

    /** Selects the active server; the server must already be configured here. */
    public void setActive(FhirServerConfiguration server) {
        if (server != null && find(server.name()) == null) {
            throw new IllegalArgumentException("The server is not configured: " + server.name());
        }
        this.active = server;
    }

    /** The active server, or empty when none is selected. */
    public Optional<FhirServerConfiguration> active() {
        return Optional.ofNullable(active);
    }

    /** True when the given server is the active one. */
    public boolean isActive(FhirServerConfiguration server) {
        return server != null && active != null && server.name().equals(active.name());
    }

    private FhirServerConfiguration find(String name) {
        for (FhirServerConfiguration existing : servers) {
            if (existing.name().equals(name)) {
                return existing;
            }
        }
        return null;
    }
}
