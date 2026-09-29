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

    /**
     * The default file the server list is kept in, under the application's settings
     * directory.
     *
     * <p>Named in one place, beside the plugin settings path in {@code MainWindow}, so
     * the code that loads the list and the code that saves it cannot disagree about where
     * the file is.</p>
     */
    public static Path defaultStoreFile() {
        return Path.of(System.getProperty("user.home", "."), ".fhirviewer",
                "server-definitions.properties");
    }

    /**
     * Adds every server saved at the given path, and restores the active selection.
     *
     * <p>Additive rather than a replacement: the caller decides when to load, and
     * loading on top of servers already added in this session should not discard them.
     * A saved name that is already configured is skipped, because a duplicate would make
     * "which server did that resource come from?" unanswerable - the origin records a
     * base URL, and two entries with one name cannot be told apart by the user either.</p>
     *
     * <p>A file that cannot be read is logged and treated as an empty list rather than
     * thrown. Failing to start because a settings file is damaged would be a far worse
     * outcome than starting with no servers, which the user can re-add in one dialog.</p>
     *
     * @return how many servers were added
     */
    public int load(Path storeFile) {
        Objects.requireNonNull(storeFile, "storeFile");
        ServerDefinitionStore store = new ServerDefinitionStore(storeFile);
        List<ServerDefinition> saved;
        String savedActive;
        try {
            saved = store.read();
            savedActive = store.readActiveName();
        } catch (IOException e) {
            log.info("saved FHIR servers could not be read from {}: {}", storeFile, e.toString());
            return 0;
        }
        int added = 0;
        for (ServerDefinition definition : saved) {
            if (add(definition)) {
                added++;
            }
        }
        restoreActive(savedActive);
        log.info("loaded {} FHIR server(s) from {}", added, storeFile);
        return added;
    }

    /**
     * Writes the current servers and the active selection to the given path.
     *
     * @throws IOException when the file cannot be written; the caller decides whether a
     *                     failed save is worth interrupting the user for, and a save
     *                     triggered by adding a server is not
     */
    public void save(Path storeFile) throws IOException {
        Objects.requireNonNull(storeFile, "storeFile");
        new ServerDefinitionStore(storeFile).write(definitions(), active);
    }

    /** The configured servers narrowed to the persistable ones, in configuration order. */
    private List<ServerDefinition> definitions() {
        List<ServerDefinition> persistable = new ArrayList<>(servers.size());
        for (FhirServerConfiguration server : servers) {
            if (server instanceof ServerDefinition definition) {
                persistable.add(definition);
            }
        }
        return persistable;
    }

    /**
     * Selects the previously active server by name.
     *
     * <p>Silently does nothing when the name is not among the loaded servers: the server
     * it named was removed or never loaded, and inventing a selection would point the
     * next search at a server the user did not choose.</p>
     */
    private void restoreActive(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        FhirServerConfiguration restored = find(name.trim());
        if (restored != null) {
            active = restored;
        }
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
