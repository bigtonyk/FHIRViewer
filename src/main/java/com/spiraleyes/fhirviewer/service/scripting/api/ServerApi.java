package com.spiraleyes.fhirviewer.service.scripting.api;

import java.util.List;
import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.spiraleyes.fhirviewer.server.FhirServerConfiguration;
import com.spiraleyes.fhirviewer.server.FhirServerManager;
import com.spiraleyes.fhirviewer.server.FhirServerService;
import com.spiraleyes.fhirviewer.server.ServerCapabilities;
import com.spiraleyes.fhirviewer.server.ServerInteraction;
import com.spiraleyes.fhirviewer.server.ServerOperationException;

/**
 * The active server and what it can do. Read-only: scripts inspect, they do not
 * reconfigure, from here.
 */
public class ServerApi {

    private final FhirServerService serverService;
    private final FhirServerManager serverManager;

    public ServerApi(FhirServerService serverService, FhirServerManager serverManager) {
        this.serverService = Objects.requireNonNull(serverService, "serverService");
        this.serverManager = Objects.requireNonNull(serverManager, "serverManager");
    }

    /** The active server, or {@code null} when none is selected. */
    public ServerInfo activeServer() {
        return serverManager.active()
                .map(this::infoOf)
                .orElse(null);
    }

    /** Every configured server, in configuration order. */
    public List<ServerInfo> servers() {
        List<ServerInfo> out = new java.util.ArrayList<>();
        for (FhirServerConfiguration server : serverManager.servers()) {
            out.add(infoOf(server));
        }
        return List.copyOf(out);
    }

    /**
     * The active server's capabilities: version, resource types, paging and
     * advertised interactions. {@code null} when there is no active server.
     *
     * @throws ScriptApiException when the server cannot be reached or answers badly
     */
    public ServerCapabilities capabilities() throws ScriptApiException {
        FhirServerConfiguration server = activeServerConfiguration();
        if (server == null) {
            return null;
        }
        try {
            return serverService.capabilities(server);
        } catch (ServerOperationException e) {
            throw new ScriptApiException("Cannot read capabilities of " + server.name()
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * True when the active server advertises the interaction for the resource
     * type, for example {@code supports("Patient", ServerInteraction.READ)}.
     * False when the server cannot be reached or advertises nothing.
     */
    public boolean supports(String resourceType, ServerInteraction interaction) {
        if (activeServerConfiguration() == null) {
            return false;
        }
        try {
            return serverService.supports(activeServerConfiguration(), resourceType, interaction);
        } catch (IllegalArgumentException e) {
            // No active server: not supported.
            return false;
        }
    }

    /** The FHIR version the active server reports, or empty when none is active. */
    public String fhirVersion() {
        ServerInfo server = activeServer();
        return server == null ? "" : server.fhirVersion();
    }

    private ServerInfo infoOf(FhirServerConfiguration server) {
        return new ServerInfo(server.name(), server.baseUrl(), server.fhirVersion(), server.pluginId());
    }

    private FhirServerConfiguration activeServerConfiguration() {
        return serverManager.active().orElse(null);
    }
}
