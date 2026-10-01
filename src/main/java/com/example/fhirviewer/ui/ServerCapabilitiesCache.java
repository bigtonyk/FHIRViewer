package com.example.fhirviewer.ui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.fhirviewer.server.ServerCapabilities;

/**
 * Remembers the resource types each server advertises, for the length of the session.
 *
 * <p>Exists so reopening a search screen does not re-read every server's CapabilityStatement.
 * A capability statement describes the software, not the data, so it does not change while
 * the viewer is running: re-reading it on every reopen was a round trip spent fetching a
 * list already held. The Load capabilities button still bypasses this, so a server that has
 * been upgraded can be re-read on demand.</p>
 *
 * <p>Keyed by base URL rather than by the server's name, because a rename must not orphan
 * what was already learned. Deliberately not persisted: a stale type list after a restart
 * would be worse than one extra request.</p>
 */
final class ServerCapabilitiesCache {

    private final Map<String, ServerCapabilities> byBaseUrl = new ConcurrentHashMap<>();

    /** What is known about the given server, or {@code null} if nothing has been read yet. */
    ServerCapabilities get(String baseUrl) {
        return baseUrl == null ? null : byBaseUrl.get(baseUrl);
    }

    /** Remembers what the given server advertised. */
    void put(String baseUrl, ServerCapabilities capabilities) {
        if (baseUrl != null && capabilities != null) {
            byBaseUrl.put(baseUrl, capabilities);
        }
    }

    /** True when nothing is known about the given server yet. */
    boolean isEmptyFor(String baseUrl) {
        return get(baseUrl) == null;
    }

    /** Forgets one server, so its next read goes to the network. */
    void forget(String baseUrl) {
        if (baseUrl != null) {
            byBaseUrl.remove(baseUrl);
        }
    }
}