package com.example.fhirviewer.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the FHIR servers the user has configured and which one is active.
 *
 * <p>The list is persisted through {@link #save(Path)} and restored with
 * {@link #load(Path)}, so a server only has to be typed once. Storage itself lives in
 * {@link ServerDefinitionStore}: the manager owns the servers, and the store owns the
 * file, so a new format is a change in one class rather than in the one the UI asks
 * about servers. Credentials are not stored here - they belong to the plugin and live
 * encrypted in {@link PluginSettingsStore}.</p>
 *
 * <p>Servers are matched by name, so a name acts as the user visible identity of a
 * server.</p>
 */
public final class FhirServerManager {

    private static final Logger log = LoggerFactory.getLogger(FhirServerManager.class);

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
